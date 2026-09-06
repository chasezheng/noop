package com.noop.analytics.calorie

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-second stages behind the measured-basal model, each held to a hand-computed vector.
 *
 * The whole-day figure is a sum over tens of thousands of seconds, so a stage that quietly stops
 * firing moves it by an amount no total-level assertion would name. Each test here fixes one stage's
 * output on an input small enough to check by eye.
 */
class DynamicHrrSignalsTest {

    private val oneSession = listOf(0..10)

    @Test
    fun hampel_replacesASpikeWithItsLocalMedian() {
        val values = doubleArrayOf(60.0, 62.0, 58.0, 61.0, 59.0, 200.0, 60.0, 62.0, 58.0, 61.0, 59.0)

        val filtered = DynamicHrrSignals.hampel(values, oneSession, radiusS = 5, sigmas = 3.0)

        assertEquals(60.0, filtered[5], 0.0)
    }

    @Test
    fun hampel_leavesAFlatRunAlone() {
        // A flat neighbourhood has a robust deviation of zero, where any threshold is exceeded by a
        // 1 bpm step. Without the non-zero requirement this run would be rewritten end to end.
        val values = DoubleArray(11) { 60.0 }
        values[5] = 61.0

        val filtered = DynamicHrrSignals.hampel(values, oneSession, radiusS = 5, sigmas = 3.0)

        assertEquals(61.0, filtered[5], 0.0)
    }

    @Test
    fun hampel_leavesAReadingInsideTheThresholdAlone() {
        val values = doubleArrayOf(60.0, 62.0, 58.0, 61.0, 59.0, 66.0, 60.0, 62.0, 58.0, 61.0, 59.0)

        val filtered = DynamicHrrSignals.hampel(values, oneSession, radiusS = 5, sigmas = 3.0)

        assertEquals(66.0, filtered[5], 0.0)
    }

    @Test
    fun hampel_leavesASecondThatCarriedNoReadingWithoutOne() {
        // A missing reading has no deviation from its neighbourhood, so nothing about the
        // neighbourhood can put a number there.
        val values = doubleArrayOf(60.0, 62.0, 58.0, 61.0, 59.0, Double.NaN, 60.0, 62.0, 58.0, 61.0, 59.0)

        val filtered = DynamicHrrSignals.hampel(values, oneSession, radiusS = 5, sigmas = 3.0)

        assertTrue(filtered[5].isNaN())
        assertEquals(59.0, filtered[4], 0.0)
    }

    @Test
    fun hampel_takesItsMedianFromInsideOneWearSessionOnly() {
        // The spike test above, with the strap off after second 4. Second 5's window is then the six
        // readings 5..10, whose median is the mean of the middle pair 60 and 61; across the whole
        // eleven it would be the single middle reading, 60.
        val values = doubleArrayOf(60.0, 62.0, 58.0, 61.0, 59.0, 200.0, 60.0, 62.0, 58.0, 61.0, 59.0)

        val filtered = DynamicHrrSignals.hampel(values, listOf(0..4, 5..10), radiusS = 5, sigmas = 3.0)

        assertEquals(60.5, filtered[5], 0.0)
    }

    @Test
    fun clipBlockPeaks_pullsTheBlockDownToItsOwnPercentile() {
        // Ten readings in one block. At the 90th percentile the ceiling is the 9th smallest, 80,
        // so only the 200 moves and it lands exactly on 80.
        val values = doubleArrayOf(60.0, 61.0, 62.0, 63.0, 64.0, 65.0, 66.0, 70.0, 80.0, 200.0)

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..9), windowStartUtc = 0L, blockS = 10, percentile = 0.90,
        )

        assertEquals(80.0, out[9], 0.0)
        assertEquals(80.0, out[8], 0.0)
        assertEquals(60.0, out[0], 0.0)
    }

    @Test
    fun clipBlockPeaks_pullsDownEveryReadingAboveTheCeiling() {
        // Three of ten sit above the 70th percentile, and all three land on it rather than only the
        // highest one.
        val values = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 150.0, 170.0, 200.0)

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..9), windowStartUtc = 0L, blockS = 10, percentile = 0.70,
        )

        assertEquals(50.0, out[7], 0.0)
        assertEquals(50.0, out[8], 0.0)
        assertEquals(50.0, out[9], 0.0)
    }

    @Test
    fun clipBlockPeaks_atOneLeavesEveryReadingAlone() {
        // Nothing can exceed the block's own maximum, so the whole stage is off.
        val values = doubleArrayOf(60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 200.0)

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..9), windowStartUtc = 0L, blockS = 10, percentile = 1.0,
        )

        assertEquals(200.0, out[9], 0.0)
    }

    @Test
    fun clipBlockPeaks_leavesASecondThatCarriedNoReadingWithoutOne() {
        // The clip stands in for a reading that was taken, not for one that was never made.
        // Nine readings, not ten: the rank is taken over what the block actually carried, so the
        // 80th percentile is the 8th smallest, 80.
        val values = doubleArrayOf(60.0, 61.0, 62.0, 63.0, 64.0, Double.NaN, 66.0, 70.0, 80.0, 200.0)

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..9), windowStartUtc = 0L, blockS = 10, percentile = 0.80,
        )

        assertTrue(out[5].isNaN())
        assertEquals(80.0, out[9], 0.0)
    }

    @Test
    fun clipBlockPeaks_neverReachesOutOfTheBlockItIsClipping() {
        // Two 10 s blocks: a flat 50 with a spike, then a genuine step up to 90. A ceiling taken
        // across both would pull the second block's readings down to the first block's level.
        val values = doubleArrayOf(
            50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 200.0, 50.0,
            90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0,
        )

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..19), windowStartUtc = 0L, blockS = 10, percentile = 0.90,
        )

        assertEquals(50.0, out[8], 0.0)
        assertEquals(90.0, out[10], 0.0)
        assertEquals(90.0, out[19], 0.0)
    }

    @Test
    fun clipBlockPeaks_cutsBlocksOnAbsoluteTimeRatherThanOnTheSessionStart() {
        // Seconds 0..9 here are unix seconds 5..14, so the first block holds only five of them and
        // the spike at index 3 is its own 5-reading block's maximum, which a 0.9 ceiling keeps.
        val values = doubleArrayOf(60.0, 60.0, 60.0, 200.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0)

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..9), windowStartUtc = 5L, blockS = 10, percentile = 0.90,
        )

        assertEquals(200.0, out[3], 0.0)
    }

    @Test
    fun clipBlockPeaks_leavesABlockOfOneReadingAlone() {
        // Nine-second blocks over ten seconds, so second 9 is a block on its own. Rank counts from
        // one and is clamped there, which makes a one-reading block's ceiling that reading. The
        // block before it shows the clip was running: five of nine at the 50th percentile is the
        // 5th smallest, 50, and everything above it lands there.
        val values = doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 200.0)

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..9), windowStartUtc = 0L, blockS = 9, percentile = 0.50,
        )

        assertEquals(200.0, out[9], 0.0)
        assertEquals(50.0, out[8], 0.0)
    }

    @Test
    fun clipBlockPeaks_leavesABlockThatCarriedNoReadingUntouched() {
        // Five-second blocks; the middle one holds nothing, and a silence this short does not split
        // the session. There is no percentile to take, so the block is skipped rather than written.
        // The last block still clips: three of five at the 50th percentile is 60.
        val values = doubleArrayOf(
            60.0, 60.0, 60.0, 60.0, 60.0,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            60.0, 60.0, 60.0, 60.0, 200.0,
        )

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..14), windowStartUtc = 0L, blockS = 5, percentile = 0.50,
        )

        assertTrue((5..9).all { out[it].isNaN() })
        assertEquals(60.0, out[14], 0.0)
    }

    @Test
    fun clipBlockPeaks_neverReadsAcrossTheSilenceBetweenTwoWearSessions() {
        // One ten-second block, split by the strap coming off after second 4. The second half is
        // then ranked on its own five readings — the 60th percentile is the 3rd smallest, 80 — where
        // one block over all ten would rank it against the first half's fives and give a ceiling
        // of 60.
        val values = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 50.0, 60.0, 70.0, 80.0, 90.0, 200.0)

        val out = DynamicHrrSignals.clipBlockPeaks(
            values, listOf(0..4, 5..9), windowStartUtc = 0L, blockS = 10, percentile = 0.60,
        )

        assertEquals(80.0, out[9], 0.0)
        assertEquals(70.0, out[6], 0.0)
    }

    // ── Trailing smoothers ────────────────────────────────────────────────────────────────────

    @Test
    fun trailingMean_averagesTheSecondsEndingAtEachOne() {
        val values = doubleArrayOf(2.0, 4.0, 6.0, 8.0)

        val mean = DynamicHrrSignals.trailingMean(values, listOf(0..3), widthS = 2, minSamples = 1)

        assertEquals(2.0, mean[0], 0.0)
        assertEquals(3.0, mean[1], 0.0)
        assertEquals(7.0, mean[3], 0.0)
    }

    @Test
    fun trailingMean_reportsNothingBelowTheSampleFloor() {
        // The floor is load-bearing: a mean that stood on one reading would let a single motion
        // sample decide a whole window's stillness.
        val values = doubleArrayOf(2.0, Double.NaN, Double.NaN, 8.0)

        val mean = DynamicHrrSignals.trailingMean(values, listOf(0..3), widthS = 4, minSamples = 2)

        assertTrue(mean[2].isNaN())
        assertEquals(5.0, mean[3], 0.0)
    }

    @Test
    fun trailingMean_neverReachesBackIntoAnEarlierSession() {
        val values = doubleArrayOf(2.0, 4.0, 100.0, 200.0)

        val mean = DynamicHrrSignals.trailingMean(values, listOf(0..1, 2..3), widthS = 4, minSamples = 1)

        assertEquals(100.0, mean[2], 0.0)
        assertEquals(150.0, mean[3], 0.0)
    }

    @Test
    fun trailingMedian_takesTheMiddleOfTheSecondsEndingAtEachOne() {
        val values = doubleArrayOf(10.0, 90.0, 20.0, 30.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..3), widthS = 3, minSamples = 1)

        assertEquals(20.0, median[2], 0.0)
        assertEquals(30.0, median[3], 0.0)
    }

    @Test
    fun trailingMedian_reportsNothingBelowTheSampleFloor() {
        val values = doubleArrayOf(10.0, Double.NaN, 20.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..2), widthS = 3, minSamples = 3)

        assertTrue(median.all { it.isNaN() })
    }

    @Test
    fun trailingMedian_averagesTheTwoMiddleReadingsOfAnEvenWindow() {
        // Four readings ending at second 3 sort to 10, 20, 30, 40, and an even count has no single
        // middle: the answer is the mean of the pair.
        val values = doubleArrayOf(10.0, 30.0, 40.0, 20.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..3), widthS = 4, minSamples = 4)

        assertEquals(25.0, median[3], 0.0)
    }

    @Test
    fun trailingMedian_neverReachesBackIntoAnEarlierSession() {
        // The window is carried from one second to the next, so a session boundary has to empty it.
        val values = doubleArrayOf(90.0, 92.0, 94.0, 10.0, 12.0, 14.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..2, 3..5), widthS = 5, minSamples = 3)

        assertEquals(92.0, median[2], 0.0)
        assertEquals(12.0, median[5], 0.0)
    }

    @Test
    fun trailingMedian_dropsASecondThatCarriedNoReadingAsItLeavesTheWindow() {
        // Second 1 carried nothing. At second 3 the window still spans it and reads two values; at
        // second 4 it has passed it and reads three.
        val values = doubleArrayOf(10.0, Double.NaN, 20.0, 30.0, 40.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..4), widthS = 3, minSamples = 1)

        assertEquals(25.0, median[3], 0.0)
        assertEquals(30.0, median[4], 0.0)
    }

    @Test
    fun trailingMedian_dropsOnlyOneOfTwoEqualReadings() {
        // Seconds 0 and 1 both read 20. The window at second 3 has passed the first of them and
        // must still hold the second: the middle of 20, 30, 40 rather than of 30 and 40.
        val values = doubleArrayOf(20.0, 20.0, 30.0, 40.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..3), widthS = 3, minSamples = 1)

        assertEquals(30.0, median[3], 0.0)
    }

    @Test
    fun trailingMedian_countsAfreshWhereTheReadingsRanOutAndReturned() {
        // A gap wider than the window empties it. The first second after the gap is one reading
        // short of the floor again, rather than resuming the count from before it.
        val values = doubleArrayOf(10.0, 20.0, Double.NaN, Double.NaN, Double.NaN, 40.0, 60.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..6), widthS = 3, minSamples = 2)

        assertEquals(15.0, median[1], 0.0)
        assertTrue(median[4].isNaN())
        assertTrue(median[5].isNaN())
        assertEquals(50.0, median[6], 0.0)
    }

    @Test
    fun trailingMedian_takesTheMiddleOfTheReadingsTheWindowActuallyCarried() {
        // Second 1 carried none, so the window ending at second 3 holds three readings rather than
        // four, and an odd count reports a reading rather than a mean.
        val values = doubleArrayOf(10.0, Double.NaN, 40.0, 20.0)

        val median = DynamicHrrSignals.trailingMedian(values, listOf(0..3), widthS = 4, minSamples = 3)

        assertEquals(20.0, median[3], 0.0)
    }

    // ── Quiet windows ─────────────────────────────────────────────────────────────────────────

    private fun quietWindows(
        hr: DoubleArray,
        still: BooleanArray,
        beat: BooleanArray,
        minWindowS: Int = 5,
        hrRangeBpm: Double = 10.0,
        stillFrac: Double = 0.8,
        beatFrac: Double = 0.5,
        repairGraceS: Int = 0,
    ): DoubleArray = DynamicHrrSignals.quietWindowMinHr(
        hr, listOf(0..hr.size - 1), still, beat, minWindowS, hrRangeBpm, stillFrac, beatFrac,
        repairGraceS,
    )

    @Test
    fun quietWindow_reportsTheLowestHeartRateOfAQualifyingStretch() {
        val hr = doubleArrayOf(52.0, 50.0, 54.0, 51.0, 53.0)

        val ends = quietWindows(hr, BooleanArray(5) { true }, BooleanArray(5) { true })

        assertEquals(50.0, ends[4], 0.0)
    }

    @Test
    fun quietWindow_keepsWideningWhileNothingInterruptsIt() {
        // The minimum length is a floor, not a width. Nothing here moves the left edge, so the window
        // ending at the last second spans the whole stretch and reports a dip 949 seconds behind it.
        val hr = DoubleArray(1_000) { 60.0 }
        hr[50] = 55.0

        val ends = quietWindows(hr, BooleanArray(1_000) { true }, BooleanArray(1_000) { true }, minWindowS = 300)

        assertEquals(55.0, ends[999], 0.0)
    }

    @Test
    fun quietWindow_isNotCutShortByAStretchThatCarriedNoReadings() {
        // Seconds that carried no reading cannot contradict the window, so a gap inside a session
        // does not move the left edge. The dip before the gap is still reported.
        val hr = DoubleArray(1_000) { if (it in 400..600) Double.NaN else 60.0 }
        hr[50] = 55.0

        val ends = quietWindows(hr, BooleanArray(1_000) { true }, BooleanArray(1_000) { true }, minWindowS = 300)

        assertEquals(55.0, ends[999], 0.0)
    }

    @Test
    fun quietWindow_measuresTheWholeStretchRatherThanThePartSeenSoFar() {
        // One value per stretch, at the last second it held. Second 299 is 300 seconds in and would
        // have measured 60, but the stretch is not over there, and by the time it is the dip at
        // second 400 is inside it.
        val hr = DoubleArray(1_000) { 60.0 }
        hr[400] = 55.0

        val ends = quietWindows(hr, BooleanArray(1_000) { true }, BooleanArray(1_000) { true }, minWindowS = 300)

        assertTrue(ends[299].isNaN())
        assertEquals(55.0, ends[999], 0.0)
        assertEquals(1, ends.count { !it.isNaN() })
    }

    @Test
    fun quietWindow_rejectsAStretchShorterThanTheMinimum() {
        val hr = doubleArrayOf(52.0, 50.0, 54.0, 51.0)

        val ends = quietWindows(hr, BooleanArray(4) { true }, BooleanArray(4) { true })

        assertTrue(ends.all { it.isNaN() })
    }

    @Test
    fun quietWindow_rejectsAStretchTheRateHasRisenAwayFrom() {
        // The last second reads 90, far above the 50s behind it, so what they measured is a rate the
        // wearer has left. Nothing wide enough is left to report.
        val hr = doubleArrayOf(50.0, 51.0, 52.0, 53.0, 90.0)

        val ends = quietWindows(hr, BooleanArray(5) { true }, BooleanArray(5) { true })

        assertTrue(ends.all { it.isNaN() })
    }

    @Test
    fun quietWindow_closesWhereTheRateHasRisenAwayFromTheLowestBehindIt() {
        // Second 5 reads 61, eleven above the 50s behind it. The stretch behind it closes and reports
        // the 50 it measured; the new one needs five seconds of its own before it can report 61.
        val hr = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 50.0, 61.0, 61.0, 61.0, 61.0, 61.0)

        val ends = quietWindows(hr, BooleanArray(10) { true }, BooleanArray(10) { true })

        assertEquals(50.0, ends[4], 0.0)
        assertTrue(ends[5].isNaN())
        assertTrue(ends[8].isNaN())
        assertEquals(61.0, ends[9], 0.0)
    }

    @Test
    fun quietWindow_keepsAHigherRateItHasAlreadyComeDownFrom() {
        // The cut is made against the rate at the second the window ends, not the widest gap inside
        // it. Second 0 reads 90, but every second after it reads 50, so nothing cuts the stretch back
        // and the 90 stays inside one that measures 50.
        val hr = doubleArrayOf(90.0, 50.0, 50.0, 50.0, 50.0, 50.0)

        val ends = quietWindows(hr, BooleanArray(6) { true }, BooleanArray(6) { true }, minWindowS = 6)

        assertEquals(50.0, ends[5], 0.0)
    }

    @Test
    fun quietWindow_rejectsAStretchThatIsNotStillEnough() {
        val hr = doubleArrayOf(52.0, 50.0, 54.0, 51.0, 53.0)
        val still = booleanArrayOf(true, true, true, false, false)

        val ends = quietWindows(hr, still, BooleanArray(5) { true })

        assertTrue(ends.all { it.isNaN() })
    }

    @Test
    fun quietWindow_rejectsAStretchWithoutEnoughBeatCoverage() {
        val hr = doubleArrayOf(52.0, 50.0, 54.0, 51.0, 53.0)
        val beat = booleanArrayOf(true, true, false, false, false)

        val ends = quietWindows(hr, BooleanArray(5) { true }, beat)

        assertTrue(ends.all { it.isNaN() })
    }

    @Test
    fun quietWindow_startsAfreshAfterAStretchTheShareNeverRepaired() {
        // Seconds 1 to 3 are not still and the grace is none, so each closes a stretch too short to
        // report. The still run after them is a stretch of its own and reports the 50 inside it.
        val hr = doubleArrayOf(60.0, 60.0, 60.0, 60.0, 52.0, 50.0, 54.0, 51.0, 53.0)
        val still = booleanArrayOf(true, false, false, false, true, true, true, true, true)

        val ends = quietWindows(hr, still, BooleanArray(9) { true }, minWindowS = 5, stillFrac = 0.9)

        assertEquals(50.0, ends[8], 0.0)
    }

    @Test
    fun quietWindow_repairsWhereTheLaterSecondsDiluteTheShareBackOverTheFloor() {
        // Seconds 0 to 4 are not still, so the stretch pauses at once and only climbs back over 0.9
        // at second 49, at 45 still of 50. That is inside the grace, so the paused seconds stayed in
        // the stretch and the dip among them is what it measures.
        val hr = DoubleArray(50) { 60.0 }
        hr[2] = 55.0
        val still = BooleanArray(50) { it >= 5 }

        val ends = quietWindows(
            hr, still, BooleanArray(50) { true }, minWindowS = 10, stillFrac = 0.9, repairGraceS = 60,
        )

        assertEquals(55.0, ends[49], 0.0)
    }

    @Test
    fun quietWindow_closesAPauseTheShareNeverRepairsInsideTheGrace() {
        // The same shape with a grace of ten. The share is still under the floor ten seconds after
        // the pause began, so that stretch is abandoned and a new one starts at second 11. The dip at
        // second 2 went with it, and what is measured is the 60 of the seconds after.
        val hr = DoubleArray(50) { 60.0 }
        hr[2] = 55.0
        val still = BooleanArray(50) { it >= 5 }

        val ends = quietWindows(
            hr, still, BooleanArray(50) { true }, minWindowS = 10, stillFrac = 0.9, repairGraceS = 10,
        )

        assertEquals(60.0, ends[49], 0.0)
    }

    @Test
    fun quietWindow_givesEachPauseItsOwnGrace() {
        // Second 20 is not still and repairs at once, and second 40 is not still too. The second
        // pause starts its grace afresh rather than inheriting what the first one spent, so the
        // stretch runs to the end and measures the dip.
        val hr = DoubleArray(60) { 60.0 }
        hr[50] = 55.0
        val still = BooleanArray(60) { it != 20 && it != 40 }

        val ends = quietWindows(
            hr, still, BooleanArray(60) { true }, minWindowS = 10, stillFrac = 0.99, repairGraceS = 3,
        )

        assertEquals(55.0, ends[59], 0.0)
    }

    @Test
    fun quietWindow_acceptsARateExactlyTheLimitAboveTheStretchesLowest() {
        // The window is cut back only where the rise EXCEEDS the limit, so a last second of 52 over
        // a lowest of 50 stands, as would 60 over 50 at a limit of 10.
        val hr = doubleArrayOf(50.0, 55.0, 60.0, 58.0, 52.0)

        val ends = quietWindows(hr, BooleanArray(5) { true }, BooleanArray(5) { true })

        assertEquals(50.0, ends[4], 0.0)
    }

    @Test
    fun quietWindow_acceptsAStretchThatIsExactlyStillEnough() {
        // The stretch pauses at second 0 and the four still seconds after it dilute the share back
        // to 0.80 against a 0.80 floor, where the test is `>=`. It repairs inside the grace, so the
        // paused second stays part of the stretch and its span is the full five.
        val hr = doubleArrayOf(52.0, 50.0, 54.0, 51.0, 53.0)
        val still = booleanArrayOf(false, true, true, true, true)

        val ends = quietWindows(hr, still, BooleanArray(5) { true }, repairGraceS = 5)

        assertEquals(50.0, ends[4], 0.0)
    }

    @Test
    fun quietWindow_acceptsAStretchWithExactlyTheBeatCoverageAsked() {
        // Two of five seconds carried an interval, which is 0.40 against a 0.40 floor.
        val hr = doubleArrayOf(52.0, 50.0, 54.0, 51.0, 53.0)
        val beat = booleanArrayOf(true, false, true, false, false)

        val ends = quietWindows(hr, BooleanArray(5) { true }, beat, beatFrac = 0.4)

        assertEquals(50.0, ends[4], 0.0)
    }

    @Test
    fun quietWindow_reportsTheLowestReadingOfItsOwnWearSessionOnly() {
        // The earlier session reads 45, within 10 bpm of the later one's rate, so a fold that
        // carried its left edge across the silence would report 45 at the last second instead of 50.
        val hr = doubleArrayOf(45.0, 45.0, 45.0, 45.0, 45.0, 52.0, 50.0, 54.0, 51.0, 53.0)

        val ends = DynamicHrrSignals.quietWindowMinHr(
            hr, listOf(0..4, 5..9), BooleanArray(10) { true }, BooleanArray(10) { true },
            minWindowS = 5, hrRangeBpm = 10.0, stillFrac = 0.8, beatFrac = 0.5,
            repairGraceS = 0,
        )

        assertEquals(45.0, ends[4], 0.0)
        assertEquals(50.0, ends[9], 0.0)
    }

    @Test
    fun quietWindow_measuresNothingFromAStretchThatCarriedNoReadings() {
        // Still and beat-covered throughout, but the optical channel dropped out. There is no
        // lowest heart rate to report, so the stretch measures nothing rather than qualifying on
        // stillness alone.
        val hr = DoubleArray(5) { Double.NaN }

        val ends = quietWindows(hr, BooleanArray(5) { true }, BooleanArray(5) { true })

        assertTrue(ends.all { it.isNaN() })
    }

    @Test
    fun quietWindow_excludesAReadingTheSpreadPushedOutOfTheWindow() {
        // Second 0 reads 45, 15 bpm below the rest, which drives the left edge to second 1. The
        // rate reported is the lowest of what is left, not the 45 the cut excluded.
        val hr = DoubleArray(10) { 60.0 }
        hr[0] = 45.0
        hr[5] = 50.0

        val ends = quietWindows(hr, BooleanArray(10) { true }, BooleanArray(10) { true })

        assertEquals(50.0, ends[9], 0.0)
    }

    @Test
    fun quietWindow_measuresNothingFromASessionOfNoReadingsAfterOneThatHadThem() {
        // The queue holding a window's lowest rate is emptied at each session, but the store behind
        // it is not. A session that carried no reading measures nothing, rather than reporting the
        // rate left behind by the session before it.
        val hr = doubleArrayOf(60.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)

        val ends = DynamicHrrSignals.quietWindowMinHr(
            hr, listOf(0..0, 1..5), BooleanArray(6) { true }, BooleanArray(6) { true },
            minWindowS = 5, hrRangeBpm = 10.0, stillFrac = 0.8, beatFrac = 0.5,
            repairGraceS = 0,
        )

        assertTrue(ends.all { it.isNaN() })
    }

    @Test
    fun quietWindow_neverSpansTheSilenceBetweenTwoWearSessions() {
        // The strap was off in between, so the two halves are not one quiet stretch however alike
        // they read. Each half alone is too short to qualify.
        val hr = doubleArrayOf(52.0, 50.0, 54.0, 51.0, 53.0, 52.0)

        val ends = DynamicHrrSignals.quietWindowMinHr(
            hr, listOf(0..2, 3..5), BooleanArray(6) { true }, BooleanArray(6) { true },
            minWindowS = 5, hrRangeBpm = 10.0, stillFrac = 0.8, beatFrac = 0.5,
            repairGraceS = 0,
        )

        assertTrue(ends.all { it.isNaN() })
    }

    // ── The basal heart rate through the day ──────────────────────────────────────────────────

    private val nothing = Double.NaN

    @Test
    fun basalHr_risesWhereAQuietStretchMeasuresAHigherRate() {
        val windows = doubleArrayOf(48.0, nothing, nothing, 61.0)
        val ratchet = doubleArrayOf(nothing, 70.0, 70.0, nothing)

        val basal = DynamicHrrSignals.trackBasalHr(windows, ratchet, seedBpm = nothing)

        assertEquals(48.0, basal[2], 0.0)
        assertEquals(61.0, basal[3], 0.0)
    }

    @Test
    fun basalHr_ratchetsDownToARateThatContradictsIt() {
        val windows = doubleArrayOf(60.0, nothing, nothing)
        val ratchet = doubleArrayOf(nothing, 52.0, 58.0)

        val basal = DynamicHrrSignals.trackBasalHr(windows, ratchet, seedBpm = nothing)

        assertEquals(52.0, basal[1], 0.0)
        assertEquals(52.0, basal[2], 0.0)
    }

    @Test
    fun basalHr_startsAtTheSeedAndIsOverwrittenByTheFirstQuietStretch() {
        val windows = doubleArrayOf(nothing, nothing, 64.0)
        val ratchet = DoubleArray(3) { nothing }

        val basal = DynamicHrrSignals.trackBasalHr(windows, ratchet, seedBpm = 55.0)

        assertEquals(55.0, basal[0], 0.0)
        assertEquals(64.0, basal[2], 0.0)
    }

    @Test
    fun basalHr_seedIsAStartingValueRatherThanAFloor() {
        val windows = DoubleArray(2) { nothing }
        val ratchet = doubleArrayOf(nothing, 49.0)

        val basal = DynamicHrrSignals.trackBasalHr(windows, ratchet, seedBpm = 55.0)

        assertEquals(49.0, basal[1], 0.0)
    }

    @Test
    fun basalHr_staysUnmeasuredWithNoSeedUntilAQuietStretchEnds() {
        val windows = doubleArrayOf(nothing, 47.0)
        val ratchet = DoubleArray(2) { nothing }

        val basal = DynamicHrrSignals.trackBasalHr(windows, ratchet, seedBpm = nothing)

        assertTrue(basal[0].isNaN())
        assertEquals(47.0, basal[1], 0.0)
    }

    @Test
    fun basalHr_cannotBeEstablishedByTheRatchetAlone() {
        // A trailing median over a stretch nothing qualified measures whatever the wearer was doing,
        // not a resting rate, so with no seed to lower it the fold stays unmeasured throughout.
        val windows = DoubleArray(3) { nothing }
        val ratchet = doubleArrayOf(50.0, 48.0, 47.0)

        val basal = DynamicHrrSignals.trackBasalHr(windows, ratchet, seedBpm = nothing)

        assertTrue(basal.all { it.isNaN() })
    }

    @Test
    fun basalHr_holdsItsValueThroughASecondNeitherStageMeasured() {
        val windows = doubleArrayOf(60.0, nothing, nothing)
        val ratchet = doubleArrayOf(nothing, nothing, 55.0)

        val basal = DynamicHrrSignals.trackBasalHr(windows, ratchet, seedBpm = nothing)

        assertEquals(60.0, basal[1], 0.0)
        assertEquals(55.0, basal[2], 0.0)
    }

    // ── Ramping a raise back over the stretch that produced it ────────────────────────────────

    private fun smoothed(basal: DoubleArray, windows: DoubleArray, hr: DoubleArray): DoubleArray =
        DynamicHrrSignals.smoothBasalRaises(basal, windows, hr)

    @Test
    fun basalRaise_ramps_inProportionToHowFarTheHeartRateRanAboveTheOldRate() {
        // Ten bpm above at second 1 and twenty at second 2, so second 1 takes a third of the raise
        // and second 2 the rest. The ramp reaches the measured rate at the second before the window
        // ends, leaving no step behind it.
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(50.0, 60.0, 70.0, 60.0))

        assertEquals(50.0 + 10.0 / 3.0, out[1], 1e-12)
        assertEquals(60.0, out[2], 1e-12)
        assertEquals(60.0, out[3], 0.0)
    }

    @Test
    fun basalRaise_ramps_noFurtherBackThanTheLastTimeTheRateChanged() {
        // The ratchet lowered the rate at second 1. Everything before that belongs to a rate the fold
        // has already left behind.
        val basal = doubleArrayOf(50.0, 40.0, 40.0, 40.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(50.0, 60.0, 60.0, 60.0, 60.0))

        assertEquals(40.0, out[1], 0.0)
        assertEquals(50.0, out[2], 1e-12)
        assertEquals(60.0, out[3], 1e-12)
    }

    @Test
    fun basalRaise_ramps_noFurtherBackThanAWindowThatMeasuredTheSameRate() {
        // Second 2 reported the rate the fold already held, so it changed nothing — but it measured
        // the rate there, and a ramp through it would raise it above what was measured.
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(nothing, nothing, 50.0, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(60.0, 60.0, 60.0, 60.0, 60.0))

        assertEquals(50.0, out[1], 0.0)
        assertEquals(50.0, out[2], 0.0)
        assertEquals(60.0, out[3], 1e-12)
    }

    @Test
    fun basalRaise_ramps_throughSecondsTheWindowItselfMeasuredTheNewRateAt() {
        // The stage is given the second a window ENDED, not the span it covered, so it cannot stop
        // at the span's first second. Seconds 2 to 4 already read the rate the window went on to
        // measure, and the ramp still climbs through them: each reads below 60 until the second
        // before the window ends.
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(50.0, 90.0, 60.0, 60.0, 60.0, 60.0))

        assertEquals(50.0 + 10.0 * 40.0 / 70.0, out[1], 1e-12)
        assertEquals(50.0 + 10.0 * 50.0 / 70.0, out[2], 1e-12)
        assertEquals(50.0 + 10.0 * 60.0 / 70.0, out[3], 1e-12)
        assertEquals(60.0, out[4], 1e-12)
    }

    /** The three stages that decide a raise, wired as the model wires them. */
    private fun foldBasal(hr: DoubleArray, seedBpm: Double): Pair<DoubleArray, DoubleArray> {
        val sessions = listOf(0..(hr.size - 1))
        val quiet = DynamicHrrSignals.quietWindowMinHr(
            hr, sessions, BooleanArray(hr.size) { true }, BooleanArray(hr.size) { true },
            minWindowS = 300, hrRangeBpm = 10.0, stillFrac = 0.98, beatFrac = 0.5,
            repairGraceS = 300,
        )
        val ratchet = DynamicHrrSignals.trailingMedian(hr, sessions, widthS = 30, minSamples = 20)
        val basal = DynamicHrrSignals.trackBasalHr(quiet, ratchet, seedBpm)
        return basal to DynamicHrrSignals.smoothBasalRaises(basal, quiet, hr)
    }

    @Test
    fun basalRaise_isNotRampedWhereTheWindowSpansTheRateFallingBack() {
        // A stretch that reads 50 and falls back to 45 before any window closes. The first window to
        // close spans the fall, so it measures 45 -- the rate the fold already held -- and there is
        // no raise to ramp.
        val (basal, out) = foldBasal(DoubleArray(400) { if (it < 250) 50.0 else 45.0 }, seedBpm = 45.0)

        assertArrayEquals(DoubleArray(400) { 45.0 }, basal, 0.0)
        assertArrayEquals(basal, out, 0.0)
    }

    @Test
    fun basalRaise_isNotRampedWhereTheRateFallsBackLaterInTheSameStretch() {
        // The same fall 550 s later, well past the point a 300 s window would once have closed on
        // nothing but 50s and raised the rate to it. A stretch measures the whole of itself, so
        // where inside it the fall lands makes no difference to what it reports.
        val (basal, out) = foldBasal(DoubleArray(1_200) { if (it < 800) 50.0 else 45.0 }, seedBpm = 45.0)

        assertArrayEquals(DoubleArray(1_200) { 45.0 }, basal, 0.0)
        assertArrayEquals(basal, out, 0.0)
    }

    @Test
    fun basalRaise_keepsTheStepWhereNothingRanAboveTheOldRate() {
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(50.0, 45.0, 48.0, 60.0))

        assertEquals(50.0, out[1], 0.0)
        assertEquals(50.0, out[2], 0.0)
    }

    @Test
    fun basalRaise_keepsTheStepWithNothingBehindIt() {
        // The window reporting at second 0 leaves the raise at second 1 no stretch to ramp over.
        val basal = doubleArrayOf(50.0, 60.0)

        val out = smoothed(basal, doubleArrayOf(50.0, 60.0), doubleArrayOf(50.0, 60.0))

        assertEquals(50.0, out[0], 0.0)
        assertEquals(60.0, out[1], 0.0)
    }

    @Test
    fun basalRaise_keepsTheStepWhereNoRateHadBeenMeasuredYet() {
        val basal = doubleArrayOf(nothing, nothing, 60.0)

        val out = smoothed(basal, doubleArrayOf(nothing, nothing, 60.0), doubleArrayOf(70.0, 70.0, 60.0))

        assertTrue(out[0].isNaN())
        assertTrue(out[1].isNaN())
        assertEquals(60.0, out[2], 0.0)
    }

    @Test
    fun basalRaise_fillsAGapInTheReadingsBeforeWeighingIt() {
        // Seconds 1 and 2 carried nothing. Filled from 50 to 90 they read 63.33 and 76.67, which is
        // 13.33 and 26.67 above the old rate against 40 at second 3: an eighth, then a half.
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(50.0, nothing, nothing, 90.0, 60.0))

        assertEquals(50.0 + 10.0 / 6.0, out[1], 1e-12)
        assertEquals(55.0, out[2], 1e-12)
        assertEquals(60.0, out[3], 1e-12)
    }

    @Test
    fun basalRaise_ramps_noFurtherBackThanTheFirstReading() {
        // Nothing was read before second 3, so seconds 1 and 2 are left where they stood. The ramp
        // weighs 20 at second 3 and 30 at second 4, a fifth then the whole of it.
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(nothing, nothing, nothing, 70.0, 80.0, 60.0))

        assertEquals(50.0, out[1], 0.0)
        assertEquals(50.0, out[2], 0.0)
        assertEquals(54.0, out[3], 1e-12)
        assertEquals(60.0, out[4], 1e-12)
    }

    @Test
    fun basalRaise_leavesTheSeriesAloneWhereNothingWasRead() {
        // No reading anywhere, so no stretch carries a weight and there is nothing to ramp along.
        val basal = doubleArrayOf(50.0, 50.0, 60.0)

        val out = smoothed(basal, doubleArrayOf(50.0, nothing, 60.0), doubleArrayOf(nothing, nothing, nothing))

        assertArrayEquals(basal, out, 0.0)
    }

    @Test
    fun basalRaise_neverCarriesTheRateAboveWhatTheStrapRead() {
        // The ramp reaches 60 at second 2, where the strap read 52. A basal rate above the reading it
        // is drawn from is not a rate the wearer was ever at.
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(50.0, 100.0, 52.0, 60.0))

        assertEquals(52.0, out[2], 1e-12)
    }

    @Test
    fun basalRaise_neverCarriesTheRateBelowWhereItAlreadyStood() {
        // Second 2 read below the old rate, so it earns no weight and the cap would drag the rate
        // down to it. This stage raises and does not lower; the ratchet owns the other direction.
        val basal = doubleArrayOf(50.0, 50.0, 50.0, 60.0)
        val windows = doubleArrayOf(50.0, nothing, nothing, 60.0)

        val out = smoothed(basal, windows, doubleArrayOf(50.0, 100.0, 45.0, 60.0))

        assertEquals(60.0, out[1], 1e-12)
        assertEquals(50.0, out[2], 0.0)
    }

    @Test
    fun basalRaise_leavesARatchetDownAlone() {
        val basal = doubleArrayOf(60.0, 52.0, 52.0)

        val out = smoothed(basal, doubleArrayOf(60.0, nothing, nothing), doubleArrayOf(60.0, 52.0, 52.0))

        assertArrayEquals(basal, out, 0.0)
    }

    @Test
    fun basalRaise_ramps_onlyWhereAWindowEnded() {
        // No window reported at second 2, so however far the rate moved there it is not a measurement
        // and nothing is ramped back to it.
        val basal = doubleArrayOf(50.0, 50.0, 70.0, 70.0)

        val out = smoothed(basal, doubleArrayOf(50.0, nothing, nothing, nothing), doubleArrayOf(50.0, 90.0, 70.0, 70.0))

        assertArrayEquals(basal, out, 0.0)
    }

    // The ramp over the beats above a resting rate of 50, in 10 bpm bands worth a quarter, a half
    // and three quarters of a beat.
    private val ramp = HrReserveRamp.of(restingHr = 50.0, bandBpm = 10.0)!!

    @Test
    fun ramp_chargesEachBandItsOwnWeight() {
        assertEquals(2.5, ramp.excess(50.0, 60.0), 1e-12)
        assertEquals(7.5, ramp.excess(50.0, 70.0), 1e-12)
        assertEquals(15.0, ramp.excess(50.0, 80.0), 1e-12)
    }

    @Test
    fun ramp_chargesABeatAboveTheLastBandInFull() {
        assertEquals(15.0 + 110.0, ramp.excess(50.0, 190.0), 1e-12)
    }

    @Test
    fun ramp_startingInsideABandChargesFromThere() {
        // Five beats at a half and five at three quarters: where the span starts decides the weight,
        // not how far it runs.
        assertEquals(6.25, ramp.excess(65.0, 75.0), 1e-12)
    }

    @Test
    fun ramp_chargesTheLowestWeightBelowTheRestingRate() {
        // A quiet window can measure a basal rate under the night's resting rate. The lowest band
        // continues downward so that case needs no separate handling.
        assertEquals(2.5, ramp.excess(40.0, 50.0), 1e-12)
    }

    @Test
    fun ramp_doesNotJumpAtABandEdge() {
        // The beat straddling the edge costs half at each weight, so two rates either side of it
        // differ by that beat and nothing more.
        assertEquals(0.375, ramp.excess(59.5, 60.5), 1e-12)
    }

    @Test
    fun ramp_splitsAcrossAnyMidpointItIsAskedFor() {
        val whole = ramp.excess(52.0, 140.0)

        assertEquals(whole, ramp.excess(52.0, 83.0) + ramp.excess(83.0, 140.0), 1e-12)
    }

    @Test
    fun ramp_readsNegativeWhenTheSpanRunsDownward() {
        assertEquals(-2.5, ramp.excess(60.0, 50.0), 1e-12)
    }

    @Test
    fun ramp_isAbsentWithoutARestingRateToPinItTo() {
        assertNull(HrReserveRamp.of(restingHr = null, bandBpm = 10.0))
        assertNull(HrReserveRamp.of(restingHr = 0.0, bandBpm = 10.0))
    }

    @Test
    fun ramp_isAbsentWhenTheBandHasNoWidth() {
        // How the wearer turns the discount off: no ramp at all, rather than one that charges in
        // full, so the model keeps the arithmetic it had.
        assertNull(HrReserveRamp.of(restingHr = 50.0, bandBpm = 0.0))
    }
}
