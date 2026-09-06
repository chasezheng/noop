package com.noop.ui

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

// MARK: - The walk that costs the same

/**
 * Net oxygen cost of level walking, in ml·kg⁻¹·min⁻¹ per metre per minute.
 *
 * The speed term of the ACSM walking equation. Its 3.5 ml·kg⁻¹·min⁻¹ resting term is left out: the
 * energy converted below is already energy above resting.
 */
private const val WALK_VO2_PER_M_PER_MIN = 0.1

/** kcal released per litre of oxygen, the constant the ACSM equation is stated against. */
private const val WALK_KCAL_PER_LITRE_O2 = 5.0

/** The slowest pace reported, 2 km/h in metres per minute. */
private const val WALK_SLOWEST_M_PER_MIN = 100.0 / 3.0

/**
 * The level walking speed that costs [kcal] above resting over [spanS], in seconds per kilometre.
 *
 * Null below [WALK_SLOWEST_M_PER_MIN], which is slower than anyone walks: a span that earned that
 * little was not walking slowly, and naming a pace for it would say that it was.
 *
 * The equation this comes from is validated from about 3 to 6.4 km/h. Outside that band it is the
 * same straight line extended rather than a measured cost.
 */
internal fun walkEquivalentSecPerKm(kcal: Double, spanS: Double, weightKg: Double): Double? {
    if (kcal <= 0.0 || spanS <= 0.0 || weightKg <= 0.0) return null
    val litresO2PerMin = kcal / WALK_KCAL_PER_LITRE_O2 / (spanS / 60.0)
    val metresPerMin = litresO2PerMin * 1_000.0 / weightKg / WALK_VO2_PER_M_PER_MIN
    return if (metresPerMin < WALK_SLOWEST_M_PER_MIN) null else 60_000.0 / metresPerMin
}
