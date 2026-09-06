package com.noop.analytics.calorie

import com.noop.data.GravitySample
import com.noop.data.HrSample

/**
 * One activity day's inputs, reduced to per-minute means and window-scoped counts.
 *
 * No model runs here and no energy is estimated; this describes what a model would be handed.
 *
 * Parity debt: no Swift twin.
 */
data class CalorieDayTrace(
    /** The activity day's local midnight. */
    val start: Long,
    /** The first second after the window: the day's end, or the present second if it is earlier. */
    val end: Long,
    /**
     * The workout windows that overlap `[start, end)`, each keeping its own bounds.
     *
     * Reported because a zero workout term is otherwise ambiguous: no window reached the model, or a
     * window reached it and earned nothing. A session that began before the window began before it,
     * so the bounds are not cut back to the window's own.
     */
    val workouts: List<LongRange>,
    /** Mean bpm per minute, as (wall-clock second, bpm). A minute with no sample is absent. */
    val hrTrace: List<Pair<Long, Double>>,
    /**
     * How many heart-rate samples fell inside `[start, end)`.
     *
     * Reported because a zero energy term is otherwise ambiguous: the model scored those hours at
     * nothing, or no sample reached it at all.
     */
    val hrSampleCount: Int,
    /** How many gravity samples carrying a motion magnitude fell inside `[start, end)`. */
    val motionSampleCount: Int,
)

/** Reduces one day's raw streams to a [CalorieDayTrace], and folds a timeline into a running total. */
object CalorieDayTraceBuilder {

    /**
     * Describe the day whose local midnight is [localMidnightUtc].
     *
     * [nowUtc] clamps the window, so an in-progress day stops at the present second.
     */
    fun build(
        localMidnightUtc: Long,
        nowUtc: Long,
        gravity: List<GravitySample> = emptyList(),
        hr: List<HrSample> = emptyList(),
        workouts: List<LongRange> = emptyList(),
    ): CalorieDayTrace {
        val whole = ActivityDay.atLocalMidnight(localMidnightUtc).window()
        val start = whole.first
        val end = minOf(whole.exclusiveEnd, nowUtc)

        return CalorieDayTrace(
            start = start,
            end = end,
            workouts = workouts.filter { it.first < end && start < it.exclusiveEnd },
            hrTrace = perMinuteMeanBpm(hr, start, end),
            hrSampleCount = hr.count { it.ts >= start && it.ts < end },
            // A gravity row with no `dynAccel` carries no motion — a WHOOP 4.0 reports none at all —
            // so counting rows would report coverage the models never had.
            motionSampleCount = gravity.count { it.dynAccel != null && it.ts >= start && it.ts < end },
        )
    }

    /** A running total over [activeKcal], one point per minute of [tsIndex]. */
    fun cumulative(tsIndex: LongArray, activeKcal: DoubleArray): List<Pair<Long, Double>> {
        require(tsIndex.size == activeKcal.size) { "series of unequal length" }
        var running = 0.0
        return tsIndex.mapIndexed { i, ts -> running += activeKcal[i]; ts to running }
    }

    /** Mean bpm per minute across `[start, end)`. Minutes with no sample are omitted. */
    private fun perMinuteMeanBpm(hr: List<HrSample>, start: Long, end: Long): List<Pair<Long, Double>> {
        val count = minuteCount(start, end)
        if (count <= 0) return emptyList()
        val sum = DoubleArray(count)
        val n = IntArray(count)
        for (s in hr) {
            if (s.ts < start || s.ts >= end) continue
            val i = ((s.ts - start) / 60L).toInt()
            if (i < 0 || i >= count) continue
            sum[i] += s.bpm.toDouble()
            n[i] += 1
        }
        val out = ArrayList<Pair<Long, Double>>(count)
        for (i in 0 until count) if (n[i] > 0) out.add((start + i * 60L) to (sum[i] / n[i]))
        return out
    }
}
