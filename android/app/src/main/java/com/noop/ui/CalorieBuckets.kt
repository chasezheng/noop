package com.noop.ui

import com.noop.analytics.calorie.PaceConstants
import com.noop.analytics.calorie.RunningPaceEstimator
import com.noop.analytics.calorie.WalkingPaceEstimator
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// MARK: - The day at half-hour resolution
//
// Pure folds over the series a plot draws, so the table under the plots and the reading taken at the
// cursor are the same arithmetic over different spans.

/** How a series read over one span. */
internal data class BucketStats(val mean: Double, val min: Double, val max: Double)

/** One row of the half-hourly table. */
internal data class CalorieBucket(
    val startTs: Long,
    /** Energy over the half hour, in kcal. */
    val kcal: Double,
    /** One entry per series asked for, null where that series carried no value in the row. */
    val series: List<BucketStats?>,
)

/** Half an hour, in seconds. */
internal const val HALF_HOUR_S: Long = 1_800L

/** [ts] rolled back to the previous whole or half hour on the wearer's own clock. */
internal fun halfHourStart(ts: Long, zone: ZoneId): Long {
    val zoned = Instant.ofEpochSecond(ts).atZone(zone)
    return zoned.truncatedTo(ChronoUnit.HOURS)
        .plusMinutes(if (zoned.minute >= 30) 30L else 0L)
        .toEpochSecond()
}

/** Σ of the values in [points] timestamped inside [range]. */
internal fun sumIn(points: List<CaloriePoint>, range: LongRange): Double {
    var total = 0.0
    for ((ts, v) in points) if (ts in range) total += v
    return total
}

/** How [points] read inside [range], or null where it carried no value there. */
internal fun statsIn(points: List<CaloriePoint>, range: LongRange): BucketStats? {
    var total = 0.0
    var count = 0
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    for ((ts, v) in points) {
        if (ts !in range) continue
        total += v
        count++
        if (v < min) min = v
        if (v > max) max = v
    }
    return if (count == 0) null else BucketStats(total / count, min, max)
}

/**
 * [kcal] and [series] folded into half-hour rows, from [fromTs] to the last minute [kcal] covers.
 *
 * A row appears only where [coverage] carried a reading. Resting energy is booked for every minute
 * of the day, so without that test a strap left in a drawer still fills a table with rows nothing was
 * measured over. Every list must ascend by timestamp.
 */
internal fun calorieBuckets(
    kcal: List<CaloriePoint>,
    series: List<List<CaloriePoint>>,
    coverage: List<CaloriePoint>,
    fromTs: Long,
    bucketS: Long = HALF_HOUR_S,
): List<CalorieBucket> {
    if (kcal.isEmpty() || bucketS <= 0L) return emptyList()
    val lastTs = kcal.last().first
    val out = ArrayList<CalorieBucket>()
    var start = fromTs
    while (start <= lastTs) {
        val range = start until (start + bucketS)
        if (coverage.any { it.first in range }) {
            out.add(
                CalorieBucket(
                    startTs = start,
                    kcal = sumIn(kcal, range),
                    series = series.map { statsIn(it, range) },
                ),
            )
        }
        start += bucketS
    }
    return out
}

// MARK: - The pace that costs the same

/**
 * The pace to display for a span, in seconds per kilometre, or null if no pace is displayed.
 *
 * The gait is walking at and above [PaceConstants.CROSSOVER_KM_PER_HOUR] and running below it. That is
 * the slower of the two speeds on each side of that speed, so the value never claims the faster gait.
 * It is not the gait a person would choose: above the crossover running costs less per kilometre, so a
 * person runs.
 *
 * The walking speed selects the gait. Both speeds reach the crossover at almost the same energy,
 * because both costs are almost equal there, so the running speed would select the same gait; the
 * displayed pace steps by 0.02 s/km at the switch.
 *
 * Null below [PaceConstants.SLOWEST_KM_PER_HOUR]: a span that earned that little was not travelling
 * slowly, and naming a pace for it would say that it was.
 */
internal fun equivalentPaceSecPerKm(
    walking: WalkingPaceEstimator,
    running: RunningPaceEstimator,
    activeKcal: Double,
    spanS: Double,
): Double? {
    val walkingKmPerHour = walking.kmPerHourFor(activeKcal, spanS) ?: return null
    val kmPerHour =
        if (walkingKmPerHour >= PaceConstants.CROSSOVER_KM_PER_HOUR) walkingKmPerHour
        else running.kmPerHourFor(activeKcal, spanS) ?: return null
    return if (kmPerHour < PaceConstants.SLOWEST_KM_PER_HOUR) null else 3_600.0 / kmPerHour
}
