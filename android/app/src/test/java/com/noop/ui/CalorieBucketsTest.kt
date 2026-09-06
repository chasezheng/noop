package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // MARK: - The walk that costs the same

    /**
     * A 70 kg wearer walking 5 km/h for half an hour earns 87.5 kcal above resting, so 87.5 kcal over
     * half an hour reads back as 5 km/h — 720 s/km.
     */
    @Test fun walkEquivalent_readsBackThePaceThatCostsTheEnergy() {
        assertEquals(720.0, walkEquivalentSecPerKm(87.5, 1_800.0, 70.0)!!, 1e-9)
        // The cost is linear in the speed, so twice the energy over the same span is twice the speed.
        assertEquals(360.0, walkEquivalentSecPerKm(175.0, 1_800.0, 70.0)!!, 1e-9)
        // And the same energy over twice the span is half the speed.
        assertEquals(1_440.0, walkEquivalentSecPerKm(87.5, 3_600.0, 70.0)!!, 1e-9)
    }

    /** The cost is per kilogram carried, so the same energy moves a heavier wearer more slowly. */
    @Test fun walkEquivalent_slowsAsTheWearerGetsHeavier() {
        assertEquals(1_440.0, walkEquivalentSecPerKm(87.5, 1_800.0, 140.0)!!, 1e-9)
    }

    /**
     * A half hour that earned 2 kcal was not a slow walk, so it is given no pace at all.
     *
     * The equation would answer about 526 min/km, a number no reader could act on and which asserts
     * that walking is what happened.
     */
    @Test fun walkEquivalent_isAbsentWhereNoWalkCouldHaveCostSoLittle() {
        assertNull(walkEquivalentSecPerKm(2.0, 1_800.0, 70.0))
        assertNull(walkEquivalentSecPerKm(0.0, 1_800.0, 70.0))
        assertNull(walkEquivalentSecPerKm(-5.0, 1_800.0, 70.0))
    }

    /** A span or a weight of zero has nothing to divide by. */
    @Test fun walkEquivalent_isAbsentWithoutASpanOrAWeight() {
        assertNull(walkEquivalentSecPerKm(87.5, 0.0, 70.0))
        assertNull(walkEquivalentSecPerKm(87.5, 1_800.0, 0.0))
    }
}
