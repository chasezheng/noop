package com.noop.data

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.RegistryDayOwnerSource
import com.noop.analytics.UserProfile
import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieDayStreams
import com.noop.analytics.calorie.CalorieInputs
import com.noop.analytics.calorie.CalorieVitals
import com.noop.analytics.calorie.exclusiveEnd
import java.time.LocalDate
import java.util.TimeZone

/**
 * One day's calorie inputs, read from storage and resolved the way the analytics pass resolves them.
 *
 * Here rather than beside the models, so the analytics package keeps no storage dependency.
 *
 * A caller that recomputes a day rather than reading the stored row must resolve every input exactly
 * as the pass did. Each resolution below states which side it follows, because they do not all follow
 * the same one.
 */
internal class RepositoryCalorieStreams(
    private val repo: WhoopRepository,
    registry: DeviceRegistry,
    /** The owner used when the registry names none, matching what the pass falls back to. */
    private val fallbackDeviceId: String,
    private val nowUtc: Long,
) : CalorieDayStreams {

    private val ownerSource = RegistryDayOwnerSource(registry)
    private var candidatePriorities: List<Pair<String, Int>>? = null
    private val resolvedOwners = HashMap<ActivityDay, String>()

    override suspend fun localMidnightUtc(day: ActivityDay): Long = day.localMidnightUtc()

    /**
     * The single device that owns [day].
     *
     * Resolved rather than taken to be the active strap, which on a multi-device install names
     * whichever strap is paired now instead of the one this day was scored from.
     */
    override suspend fun owner(day: ActivityDay): String {
        resolvedOwners[day]?.let { return it }
        val candidates = candidatePriorities ?: ownerSource.candidatePriorities().also { candidatePriorities = it }
        val localMidnight = localMidnightUtc(day)
        val resolved = IntelligenceEngine.resolveDayOwner(
            repo = repo,
            ownerSource = ownerSource,
            candidatePriorities = candidates,
            day = day.key,
            // The same night window the pass probes over, so a day cannot resolve to one owner here
            // and another there.
            from = localMidnight - 30 * 3_600L,
            to = maxOf(localMidnight, minOf(localMidnight + 86_400L, nowUtc)),
            importedDeviceId = fallbackDeviceId,
        )
        resolvedOwners[day] = resolved
        return resolved
    }

    /**
     * Every stream inside [window], read under the union of the ids the owner banks through.
     *
     * The union rather than the single owner id: a re-added strap banks live data under a fresh id
     * while its import history stays under the canonical one, so a single-id read finds nothing on a
     * day the wearer can see data for. On a single-strap install the union is one id.
     *
     * Workouts are read back rather than re-detected. Reproducing every gate the detector applies is
     * fragile — a resting HR a few bpm out drops a bout — and the stored rows also carry the manual
     * and imported sessions detection never produces.
     *
     * Beats are read for every caller here, unlike the analytics pass, because a screen recomputes one
     * day on demand and every model it shows must be handed what it reads.
     */
    override suspend fun load(day: ActivityDay, window: LongRange): CalorieInputs {
        val owner = owner(day)
        val limit = IntelligenceEngine.STREAM_LIMIT
        // From local midnight rather than from the window: a workout that began before the activity
        // day opened still overlaps minutes inside it, and its row is keyed on its start.
        val readFrom = localMidnightUtc(day)
        val hr = repo.hrSamplesUnion(owner, readFrom, window.exclusiveEnd, limit)
        val gravity = repo.importedSourceIds(owner)
            .flatMap { repo.gravitySamplesForDevice(it, readFrom, window.exclusiveEnd, limit) }
            .distinctBy { it.ts }
            .sortedBy { it.ts }
        val workouts = (
            repo.detectedWorkoutsUnion(owner, readFrom, window.exclusiveEnd, limit) +
                repo.workoutsUnion(owner, readFrom, window.exclusiveEnd, limit)
            )
            .distinctBy { it.startTs to it.endTs }
            .sortedBy { it.startTs }
            .map { it.startTs until it.endTs }
        // There is no unioned beat read, so the ids are walked the way the motion read walks them.
        // The row range is inclusive of its end while a calorie window is half-open, hence the
        // second before the window shuts.
        val beats = LinkedHashMap<Long, Int>()
        for (id in repo.importedSourceIds(owner)) {
            for ((ts, count) in repo.rrIntervalsForDevice(id, readFrom, window.exclusiveEnd - 1, limit)
                .groupingBy { it.ts }.eachCount()
            ) {
                // First id wins a second outright rather than summing: a re-added strap banks the
                // same heartbeats under both of its ids, and summing would double-count them.
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
     * The anchors the pass scored [day] against: its own computed resting HR when it has one, else the
     * carry-forward across every vitals source.
     *
     * The computed row is read separately from the merged one: the merged view prefers an import, so
     * on a day an export covers it returns the export's resting HR even where NOOP staged its own,
     * which is a figure the pass never saw. Resting HR drives both the effort gate and the VO₂max, so
     * reading the wrong one changes every estimate here.
     *
     * There is no further fallback to the day's own lowest beats, which read far higher than a
     * sleep-derived resting HR. The pass stops here too, leaving the estimator's own default.
     */
    override suspend fun vitals(day: ActivityDay, profile: UserProfile, maxHROverride: Double?): CalorieVitals {
        val owner = owner(day)
        val computedIds = repo.computedSourceIds(owner)
        val ownRestingHr = computedIds
            .firstNotNullOfOrNull { repo.dailyMetrics(it, day.key, day.key).firstOrNull()?.restingHr }
            ?.toDouble()
        val sources = repo.vitalsSourceIds(owner)
        val restingHR = ownRestingHr ?: sources
            .mapNotNull { repo.latestRestingHrDay(it, day.key) }
            .maxOrNull()
            ?.let { latest ->
                AnalyticsEngine.calorieRestingHR(
                    rows = WhoopRepository.preferMeasuredRestingHr(
                        sources.flatMap { repo.dailyMetrics(it, latest, latest) },
                    ),
                    day = day.key,
                )
            }
        return CalorieVitals(
            hrmax = CalorieVitals.hrmaxFor(profile, maxHROverride),
            restingHR = restingHR,
        )
    }

    companion object {

        /** [date] as the day this port answers for, at the device's offset at [nowUtc]. */
        fun activityDay(date: LocalDate, nowUtc: Long): ActivityDay =
            ActivityDay.atOffset(date, TimeZone.getDefault().getOffset(nowUtc * 1_000L) / 1_000L)
    }
}
