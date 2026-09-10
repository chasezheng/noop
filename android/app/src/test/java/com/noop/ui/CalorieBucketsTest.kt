package com.noop.ui

import com.noop.analytics.calorie.RunningPaceEstimator
import com.noop.analytics.calorie.WalkingPaceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The half-hour table under the calorie plots.
 *
 * The rows are the only new arithmetic on that screen, and no test can reach a composable, so the
 * fold is pinned here.
 */
class CalorieBucketsTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** 2026-01-01 00:00:00 UTC. */
    private val midnight = 1_767_225_600L

    private fun minutes(from: Long, count: Int, value: (Int) -> Double): List<CaloriePoint> =
        (0 until count).map { (from + it * 60L) to value(it) }

    @Test fun aWakeTimeRollsBackToThePreviousHalfHourMark() {
        assertEquals(midnight + 7 * 3_600L, halfHourStart(midnight + 7 * 3_600L + 12 * 60L, utc))
        assertEquals(midnight + 7 * 3_600L + 1_800L, halfHourStart(midnight + 7 * 3_600L + 44 * 60L, utc))
        // Already on a mark: unchanged, rather than rolled back a further half hour.
        assertEquals(midnight + 7 * 3_600L, halfHourStart(midnight + 7 * 3_600L, utc))
        assertEquals(midnight + 7 * 3_600L + 1_800L, halfHourStart(midnight + 7 * 3_600L + 1_800L, utc))
    }

    @Test fun rowsRunFromTheStartToTheLastMinuteWithEnergy() {
        // Ninety minutes from 07:00, one kcal a minute.
        val kcal = minutes(midnight + 7 * 3_600L, 90) { 1.0 }
        val rows = calorieBuckets(
            kcal = kcal,
            series = emptyList(),
            coverage = kcal,
            fromTs = midnight + 7 * 3_600L,
        )

        assertEquals(3, rows.size)
        assertEquals(midnight + 7 * 3_600L, rows[0].startTs)
        assertEquals(midnight + 7 * 3_600L + 1_800L, rows[1].startTs)
        assertEquals(midnight + 7 * 3_600L + 3_600L, rows[2].startTs)
        rows.forEach { assertEquals(30.0, it.kcal, 1e-9) }
    }

    @Test fun aStartBeforeTheDataSkipsTheRowsThatHoldNone() {
        val kcal = minutes(midnight + 8 * 3_600L, 30) { 1.0 }
        val rows = calorieBuckets(kcal = kcal, series = emptyList(), coverage = kcal, fromTs = midnight)

        assertEquals(1, rows.size)
        assertEquals(midnight + 8 * 3_600L, rows.first().startTs)
    }

    @Test fun aPartialFirstRowReportsOnlyTheMinutesItHolds() {
        // Woke at 07:12, so the row opens at 07:00 and carries eighteen minutes.
        val kcal = minutes(midnight + 7 * 3_600L + 12 * 60L, 18) { 1.0 }
        val rows = calorieBuckets(
            kcal = kcal,
            series = emptyList(),
            coverage = kcal,
            fromTs = halfHourStart(midnight + 7 * 3_600L + 12 * 60L, utc),
        )

        assertEquals(1, rows.size)
        assertEquals(midnight + 7 * 3_600L, rows.first().startTs)
        assertEquals(18.0, rows.first().kcal, 1e-9)
    }

    @Test fun eachSeriesReportsItsMeanAndItsExtremes() {
        val kcal = minutes(midnight, 30) { 1.0 }
        // 60, 61, … 89 bpm across the half hour.
        val bpm = minutes(midnight, 30) { 60.0 + it }
        val rows = calorieBuckets(kcal = kcal, series = listOf(bpm), coverage = bpm, fromTs = midnight)

        val stats = rows.single().series.single()!!
        assertEquals(74.5, stats.mean, 1e-9)
        assertEquals(60.0, stats.min, 1e-9)
        assertEquals(89.0, stats.max, 1e-9)
    }

    @Test fun aSeriesSilentAcrossARowReportsNothingRatherThanZero() {
        val kcal = minutes(midnight, 60) { 1.0 }
        // Only the second half hour carries the series.
        val partial = minutes(midnight + 1_800L, 30) { 5.0 }
        val rows = calorieBuckets(kcal = kcal, series = listOf(partial), coverage = kcal, fromTs = midnight)

        assertEquals(2, rows.size)
        assertNull(rows[0].series.single())
        assertEquals(5.0, rows[1].series.single()!!.mean, 1e-9)
    }

    @Test fun aHalfHourWithNoHeartRateGetsNoRow() {
        // Resting energy is booked all day, so only the heart rate says the strap was worn.
        val kcal = minutes(midnight, 120) { 1.0 }
        val bpm = minutes(midnight, 30) { 60.0 } + minutes(midnight + 5_400L, 30) { 62.0 }
        val rows = calorieBuckets(kcal = kcal, series = listOf(bpm), coverage = bpm, fromTs = midnight)

        assertEquals(2, rows.size)
        assertEquals(midnight, rows[0].startTs)
        assertEquals(midnight + 5_400L, rows[1].startTs)
    }

    @Test fun aDayWithNoEnergyHasNoRows() {
        assertTrue(
            calorieBuckets(
                kcal = emptyList(),
                series = emptyList(),
                coverage = emptyList(),
                fromTs = midnight,
            ).isEmpty(),
        )
    }

    @Test fun theCursorReadsTheNearestPointAndTheSpanAroundIt() {
        val bpm = minutes(midnight, 60) { 60.0 + it }

        assertEquals(0, nearestPointIndex(bpm, midnight - 500L))
        assertEquals(10, nearestPointIndex(bpm, midnight + 10 * 60L + 20L))
        assertEquals(59, nearestPointIndex(bpm, midnight + 100 * 60L))
        assertNull(nearestPointIndex(emptyList(), midnight))

        // Ten minutes centred on 00:30 is 00:25 through 00:35 inclusive: eleven readings.
        val at = midnight + 30 * 60L
        val stats = statsIn(bpm, (at - 300L)..(at + 300L))!!
        assertEquals(90.0, stats.mean, 1e-9)
        assertEquals(85.0, stats.min, 1e-9)
        assertEquals(95.0, stats.max, 1e-9)
        assertEquals(11 * 90.0, sumIn(bpm, (at - 300L)..(at + 300L)), 1e-9)
    }

    // MARK: - The pace that costs the same

    /** 70 kg, with resting energy accruing at 0.02 kcal/s, which is 36 kcal over a half hour. */
    private val walking = WalkingPaceEstimator(70.0, 0.02)

    private val running = RunningPaceEstimator(70.0, 0.02)

    private fun pace(activeKcal: Double, spanS: Double = 1_800.0): Double? =
        equivalentPaceSecPerKm(walking, running, activeKcal, spanS)

    /**
     * Expected values from the Abe 2015 level gross curves, bisected as the code bisects.
     *
     * The pace is not proportional to the energy: the walking curve is a quadratic in the speed and the
     * two gaits are read from different curves.
     */
    @Test fun equivalentPace_readsBackThePaceThatCostsTheEnergy() {
        assertEquals(1_163.988883, pace(87.5)!!, 1e-6)
        assertEquals(661.714778, pace(175.0)!!, 1e-6)
        assertEquals(480.638541, pace(247.0)!!, 1e-6)
        assertEquals(451.300763, pace(300.0)!!, 1e-6)
        assertEquals(399.744035, pace(450.0)!!, 1e-6)
    }

    /** The cost is per kilogram carried, so the same energy moves a heavier wearer more slowly. */
    @Test fun equivalentPace_slowsAsTheWearerGetsHeavier() {
        val heavy = equivalentPaceSecPerKm(
            WalkingPaceEstimator(140.0, 0.02),
            RunningPaceEstimator(140.0, 0.02),
            175.0,
            1_800.0,
        )
        assertEquals(1_370.352211, heavy!!, 1e-6)
    }

    /**
     * At 70 kg over a half hour the gait changes at 246.9965 active kcal, where the walking speed
     * reaches 7.49 km/h.
     */
    @Test fun equivalentPace_switchesGaitAtTheCrossover() {
        assertEquals(3_600.0 / running.kmPerHourFor(246.0, 1_800.0)!!, pace(246.0)!!, 1e-9)
        assertEquals(3_600.0 / walking.kmPerHourFor(248.0, 1_800.0)!!, pace(248.0)!!, 1e-9)
        // The published crossover is not exactly where the two curves meet, so the pace steps there.
        // The step is smaller than the second the table displays; a wrong crossover would widen it.
        assertTrue(Math.abs(pace(246.997)!! - pace(246.996)!!) < 0.02)
    }

    /**
     * A continuous pace is what keeps the gait change invisible to the reader.
     *
     * This is the property a wrong crossover breaks. The gait each row was read from is not.
     */
    @Test fun equivalentPace_quickensAsTheEnergyRises() {
        var previous = Double.MAX_VALUE
        var activeKcal = 61.0
        while (activeKcal <= 600.0) {
            val secPerKm = pace(activeKcal)!!
            assertTrue("$activeKcal kcal: $secPerKm after $previous", secPerKm < previous)
            previous = secPerKm
            activeKcal += 1.0
        }
    }

    /**
     * A half hour that earned 40 kcal was not a slow walk, so it is given no pace at all.
     *
     * The walking curve is fitted from 2.4 km/h, which at 70 kg over a half hour is 60.6 active kcal.
     * Below that speed the curve has the wrong shape, and a pace slower than 25:00/km asserts that
     * travel is what happened.
     */
    @Test fun equivalentPace_isAbsentBelowTheSlowestMeasuredWalk() {
        assertEquals(1_499.165920, pace(60.7)!!, 1e-6)
        assertNull(pace(60.5))
        assertNull(pace(40.0))
        assertNull(pace(87.5, 3_600.0))
        assertNull(pace(0.0))
        assertNull(pace(-5.0))
    }

    /** A span of zero has nothing to divide by. */
    @Test fun equivalentPace_isAbsentWithoutASpan() {
        assertNull(pace(87.5, 0.0))
    }

    /** A weight is coerced positive before it reaches a profile, so zero is a caller's error. */
    @Test fun paceEstimators_rejectANonPositiveWeight() {
        assertThrows(IllegalArgumentException::class.java) { WalkingPaceEstimator(0.0, 0.02) }
        assertThrows(IllegalArgumentException::class.java) { RunningPaceEstimator(-70.0, 0.02) }
        assertThrows(IllegalArgumentException::class.java) { WalkingPaceEstimator(70.0, -0.01) }
    }
}
