package com.noop.calbench

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieDayScorer
import com.noop.analytics.calorie.CalorieModels
import com.noop.analytics.calorie.EnergyModel
import com.noop.analytics.calorie.exclusiveEnd
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where a run's progress and skipped-backup warnings go. Never mixed with the rows. */
fun interface Warn {
    fun warn(line: String)
}

/**
 * Score every requested day of one backup with every requested model.
 *
 * Each model is given the same window and the same inputs, and does all of the arithmetic itself.
 */
class Runner(
    private val zone: ZoneId,
    private val models: List<EnergyModel>,
    private val out: RowWriter,
    private val warn: Warn,
) {

    var daysScored: Int = 0
        private set

    /** Days inside the range whose activity window held no heart rate, and so were not scored. */
    var daysUnobserved: Int = 0
        private set

    suspend fun run(source: BackupSource, from: LocalDate?, to: LocalDate?) {
        val nowUtc = nowUtcFor(source)
        val tzOffsetSeconds = zone.rules.getOffset(Instant.ofEpochSecond(nowUtc)).totalSeconds.toLong()
        val streams = BackupCalorieStreams(source, zone, nowUtc)
        val days = daysIn(source, tzOffsetSeconds, from, to)
        if (days.isEmpty()) {
            warn.warn("${source.label}: no heart rate in the requested range; nothing to score")
            return
        }
        warn.warn("${source.label}: ${days.size} day(s) ${days.first()}..${days.last()}, tz=$zone, now=$nowUtc")
        for (day in days) {
            val activityDay = streams.activityDay(day)
            // The window is the same activity day for every model, so it and the streams inside it
            // are resolved once.
            val whole = ActivityDay.atLocalMidnight(streams.localMidnightUtc(activityDay)).window()
            val window = whole.first until minOf(whole.exclusiveEnd, nowUtc)
            val inputs = streams.load(activityDay, window)
            // The whole window rather than the clamped end, matching the app: a day the strap never
            // observed gets no rows rather than a full day of basal.
            if (inputs.hr.none { it.ts in whole }) {
                daysUnobserved++
                continue
            }
            val owner = streams.owner(activityDay)
            val vitals = streams.vitals(activityDay, source.profile, source.maxHROverride)
            val stored = source.db.storedActiveKcal("$owner-noop", day)
            for (model in models) {
                val timeline = CalorieModels.timeline(
                    model, source.profile, vitals, window.first, window.exclusiveEnd, inputs,
                )
                val dayTotalKcal = timeline.dayTotalKcal
                val dayActiveKcal = timeline.dayActiveKcal
                for (i in timeline.tsIndex.indices) {
                    out.row(
                        EpochRow(
                            backup = source.label,
                            exportedAt = source.manifest.exportedAtMs,
                            appVersion = source.manifest.appVersion,
                            appBuild = source.manifest.appBuild,
                            schemaVersion = source.manifest.schemaVersion,
                            day = day,
                            deviceId = owner,
                            model = model.id,
                            tz = zone.id,
                            windowStart = window.first,
                            windowEnd = window.exclusiveEnd,
                            epochStart = timeline.tsIndex[i],
                            activeKcal = timeline.activeKcal[i],
                            series = seriesAt(timeline.labeledSeries, i),
                            dayTotalKcal = dayTotalKcal,
                            dayBasalKcal = dayTotalKcal - dayActiveKcal,
                            dayActiveKcal = dayActiveKcal,
                            storedActiveKcal = stored,
                            // Day-scoped, so they ride every row rather than needing a second file.
                            extras = timeline.extras,
                        ),
                    )
                }
            }
            daysScored++
        }
    }

    companion object {

        /**
         * One minute of every series a model reported, keyed as the model keyed it.
         *
         * A key whose value is null for this minute is left out: the model is saying it has nothing
         * to report there, and an absent key says that where a zero would not. A model that declined
         * the day can report a series as empty rather than as nulls, hence the bounds check.
         */
        fun seriesAt(series: Map<String, List<Double?>>, i: Int): Map<String, Double> {
            val at = LinkedHashMap<String, Double>(series.size)
            for ((label, values) in series) {
                val value = values.getOrNull(i) ?: continue
                at[label] = value
            }
            return at
        }

        /**
         * The instant a day still in progress is clamped at: when the backup was written.
         *
         * A wall clock keeps moving, so the same file scored twice would give two figures for its last
         * day, over hours whose samples the file cannot contain. A backup with no manifest has nothing
         * better than the file's own modification time.
         */
        fun nowUtcFor(source: BackupSource): Long =
            if (source.manifest.exportedAtMs > 0) {
                source.manifest.exportedAtMs / 1_000L
            } else {
                File(source.label).lastModified() / 1_000L
            }

        /**
         * The local days the backup has heart rate for, narrowed to `[from, to]`.
         *
         * Bounded by what the file holds rather than by the calendar, so a range starting before the
         * wearer owned the strap costs nothing.
         */
        fun daysIn(source: BackupSource, tzOffsetSeconds: Long, from: LocalDate?, to: LocalDate?): List<String> {
            val extent = source.db.hrDayRange() ?: return emptyList()
            var first = LocalDate.parse(AnalyticsEngine.dayString(extent.first, tzOffsetSeconds))
            var last = LocalDate.parse(AnalyticsEngine.dayString(extent.second, tzOffsetSeconds))
            if (from != null && from.isAfter(first)) first = from
            if (to != null && to.isBefore(last)) last = to
            if (first.isAfter(last)) return emptyList()
            return generateSequence(first) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .map { it.toString() }
                .toList()
        }
    }
}
