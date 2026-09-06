package com.noop.calbench

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.DayOwnerResolver
import com.noop.analytics.UserProfile
import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieDayStreams
import com.noop.analytics.calorie.CalorieInputs
import com.noop.analytics.calorie.CalorieVitals
import com.noop.analytics.calorie.exclusiveEnd
import com.noop.data.DailyMetric
import com.noop.data.HrSample
import java.time.Instant
import java.time.ZoneId

/**
 * One day's calorie inputs, read from a backup file rather than from the app's own storage.
 *
 * Every resolution below is the app's, restated against raw SQL because the generated data-access
 * code is Android and cannot be imported here. The models themselves are imported rather than
 * reimplemented, so this file decides only which rows they are handed.
 */
class BackupCalorieStreams(
    private val source: BackupSource,
    zone: ZoneId,
    /**
     * The instant a day in progress is clamped at. The backup's `exportedAt`, so a re-run answers
     * with what the app could have known when it wrote the file rather than booking the hours since.
     */
    private val nowUtc: Long,
) : CalorieDayStreams {

    private val db = source.db

    /**
     * Seconds east of UTC at [nowUtc], with the daylight-saving state at that instant folded in.
     *
     * A fixed offset rather than a region zone: the two differ by an hour on a past day in the other
     * half of the year, and the stored column was written with this one.
     */
    private val tzOffsetSeconds: Long = zone.rules.getOffset(Instant.ofEpochSecond(nowUtc)).totalSeconds.toLong()

    /** The registry's active strap, and the id every unresolved day falls back to. */
    private val fallbackDeviceId: String = db.activeDeviceId() ?: WHOOP_SOURCE

    private val candidatePriorities: List<Pair<String, Int>> by lazy {
        db.pairedDevices()
            .filter { it.status != "archived" }
            .map { d ->
                val priority = when {
                    d.id == db.activeDeviceId() -> 0
                    d.sourceKind == "activityFile" -> 3
                    d.sourceKind == "cloudImport" || d.sourceKind == "fileImport" -> 2
                    else -> 1
                }
                d.id to priority
            }
    }

    private val resolvedOwners = HashMap<ActivityDay, String>()

    /** [dayKey] as the day this port answers for, at [zone]'s offset at [nowUtc]. */
    fun activityDay(dayKey: String): ActivityDay = ActivityDay.forKey(dayKey, tzOffsetSeconds)

    override suspend fun localMidnightUtc(day: ActivityDay): Long = day.localMidnightUtc()

    /**
     * The single device that owns [day], by the app's own rule.
     *
     * Every input that rule reads is in the backup, so this resolves the same owner the app did
     * rather than a rule of the harness's own.
     */
    override suspend fun owner(day: ActivityDay): String {
        resolvedOwners[day]?.let { return it }
        val localMidnight = localMidnightUtc(day)
        val from = localMidnight - 30 * 3_600L
        val to = maxOf(localMidnight, minOf(localMidnight + 86_400L, nowUtc))
        val resolved = db.dayOwner(day.key)
            ?: when {
                candidatePriorities.isEmpty() -> fallbackDeviceId
                candidatePriorities.size == 1 && candidatePriorities.first().first == fallbackDeviceId ->
                    fallbackDeviceId
                else -> {
                    val candidates = candidatePriorities.map { (id, priority) ->
                        DayOwnerResolver.Candidate(
                            deviceId = id,
                            priority = priority,
                            hasData = db.hrSamples(id, from, to, 1).isNotEmpty(),
                        )
                    }
                    DayOwnerResolver.resolve(day.key, lockedOwner = null, candidates = candidates) ?: fallbackDeviceId
                }
            }
        resolvedOwners[day] = resolved
        return resolved
    }

    override suspend fun load(day: ActivityDay, window: LongRange): CalorieInputs {
        val owner = owner(day)
        // From local midnight, not from the window: a workout that began in the hours before the
        // activity day opened still overlaps minutes inside it, and the row is keyed on its start.
        val readFrom = localMidnightUtc(day)
        val ids = importedSourceIds(owner)
        val readTo = window.exclusiveEnd
        val hr = mergeHrByTs(ids.map { db.hrSamples(it, readFrom, readTo, STREAM_LIMIT) })
        val gravity = ids
            .flatMap { db.gravitySamples(it, readFrom, readTo, STREAM_LIMIT) }
            .distinctBy { it.ts }
            .sortedBy { it.ts }
        val detected = computedSourceIds(owner).flatMap { db.workouts(it, readFrom, readTo, STREAM_LIMIT) }
        val recorded = ids.flatMap { db.workouts(it, readFrom, readTo, STREAM_LIMIT) }
        val workouts = (dedupWorkoutsByKey(detected) + dedupWorkoutsByKey(recorded))
            .distinctBy { it.startTs to it.endTs }
            .sortedBy { it.startTs }
            .map { it.startTs until it.endTs }
        // The row range is inclusive of its end while a calorie window is half-open, so the read stops
        // one second short of it. The first id to carry a second wins it outright: a re-added strap
        // banks the same heartbeats under both of its ids.
        val beats = LinkedHashMap<Long, Int>()
        for (id in ids) {
            for ((ts, count) in db.rrBeatsPerSecond(id, readFrom, readTo - 1, STREAM_LIMIT)) {
                beats.putIfAbsent(ts, count)
            }
        }
        return CalorieInputs(
            hr = hr,
            gravity = gravity,
            workouts = workouts,
            rr = beats.toList().sortedBy { it.first },
        )
    }

    /**
     * The anchors the app scored [day] against: its own computed resting HR when it has one, else the
     * carry-forward across every vitals source.
     *
     * The computed row is read separately from the merged one: the merged view prefers an import, so
     * on a day an export covers it would return the export's resting HR even where the app staged its
     * own, which is a figure the pass never saw.
     */
    override suspend fun vitals(day: ActivityDay, profile: UserProfile, maxHROverride: Double?): CalorieVitals {
        val owner = owner(day)
        val ownRestingHr = computedSourceIds(owner)
            .firstNotNullOfOrNull { db.restingHrRows(it, day.key, day.key).firstOrNull()?.restingHr }
            ?.toDouble()
        val sources = vitalsSourceIds(owner)
        val restingHR = ownRestingHr ?: sources
            .mapNotNull { db.latestRestingHrDay(it, day.key) }
            .maxOrNull()
            ?.let { latest ->
                AnalyticsEngine.calorieRestingHR(
                    rows = preferMeasuredRestingHr(sources.flatMap { db.restingHrRows(it, latest, latest) }),
                    day = day.key,
                )
            }
        return CalorieVitals(
            hrmax = CalorieVitals.hrmaxFor(profile, maxHROverride),
            restingHR = restingHR,
        )
    }

    /**
     * The app's read-side source-id rules, restated because the class holding them cannot be imported
     * here.
     *
     * The one copy in this harness, and nothing checks it against the original. Each rule is one line,
     * but any of them changes which rows a day is read from, so a change to the app's must be mirrored
     * here by hand.
     */
    private fun importedSourceIds(activeDeviceId: String): List<String> =
        if (activeDeviceId == WHOOP_SOURCE) listOf(WHOOP_SOURCE) else listOf(activeDeviceId, WHOOP_SOURCE)

    private fun computedSourceIds(activeDeviceId: String): List<String> =
        importedSourceIds(activeDeviceId).map { "$it-noop" }

    private fun vitalsSourceIds(activeDeviceId: String): List<String> =
        importedSourceIds(activeDeviceId) + computedSourceIds(activeDeviceId) + HEALTH_CONNECT_SOURCE

    private fun preferMeasuredRestingHr(rows: List<DailyMetric>): List<DailyMetric> {
        val measured = rows
            .filter { it.deviceId != HEALTH_CONNECT_SOURCE && it.restingHr != null }
            .mapTo(HashSet()) { it.day }
        if (measured.isEmpty()) return rows
        return rows.filter { it.deviceId != HEALTH_CONNECT_SOURCE || it.day !in measured }
    }

    private fun mergeHrByTs(lists: List<List<HrSample>>): List<HrSample> {
        if (lists.size == 1) return lists[0]
        val byTs = LinkedHashMap<Long, HrSample>()
        for (list in lists) for (s in list) byTs.putIfAbsent(s.ts, s)
        return byTs.values.sortedBy { it.ts }
    }

    private fun dedupWorkoutsByKey(rows: List<WorkoutRowLite>): List<WorkoutRowLite> {
        val seen = HashSet<Pair<Long, String>>()
        return rows.filter { seen.add(it.startTs to it.sport) }
    }

    companion object {
        const val WHOOP_SOURCE = "my-whoop"
        const val HEALTH_CONNECT_SOURCE = "health-connect"

        /**
         * The app's own row cap, which is far higher than its data-access default and is what a
         * whole-day window needs.
         */
        const val STREAM_LIMIT: Int = 200_000
    }
}
