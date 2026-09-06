package com.noop.analytics

import com.noop.analytics.calorie.HeartRateGates
import com.noop.analytics.calorie.Calories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests `Calories.estimateDayEnergy` — the approximate whole-day heart-rate-only energy estimate
 * (Keytel active above a revised Harris–Benedict basal).
 *
 * A pure-function oracle rather than a shipped path: the stored figure is scored through
 * `CalorieDayScorer`, which dispatches on the wearer's selected model. Every figure here is an
 * approximation, not clinical parity. No database.
 */
class DayCaloriesTest {

    private fun hrDay(bpm: Int, n: Int): List<com.noop.data.HrSample> =
        (0 until n).map { com.noop.data.HrSample(deviceId = "test", ts = it.toLong(), bpm = bpm) }

    @Test
    fun dayCalories_emptyIsZero() {
        assertEquals(
            0.0,
            Calories.estimateDayEnergy(emptyList(), UserProfile(), hrmax = 190.0, restingHR = 55.0).dayTotalKcal,
            1e-12,
        )
    }

    @Test
    fun dayCalories_matchesBoutAtOneHz() {
        // At a steady 1 Hz stream the day and bout estimators agree exactly: every interval is
        // 1 s under both caps, and the day path's basal-plus-surplus split re-sums to the same
        // gross rate the bout path credits directly. (They DIVERGE on gappy streams — the day
        // path caps surplus at dayMaxGapS and keeps basal on the wall clock; see
        // dayGap_capsSurplusNotBasal — but not here.)
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val hr = hrDay(bpm = 130, n = 600) // 10 min above the active threshold, dense 1 Hz
        val day = Calories.estimateDayEnergy(hr, profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        val bout = Calories.estimateBoutCalories(hr, profile, hrmax = 185.0, restingHR = 55.0).first
        assertEquals(bout, day, 1e-9)
    }

    @Test
    fun sparseHr_tracksElapsedTimeNotSampleCount() {
        // A 10-minute effort at a steady active HR, sampled two ways over the SAME ~600 s span:
        // densely at 1 Hz, and sparsely at one sample / 10 s (the WHOOP 5/MG case). Energy must
        // track elapsed time, so the sparse estimate lands close to the dense one — NOT ~1/10th
        // of it, as the old one-second-per-sample count produced. (BOUT path only.)
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val dense = (0 until 600).map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 130) }
        val sparse = (0 until 600 step 10).map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 130) }
        val denseKcal = Calories.estimateBoutCalories(dense, profile, hrmax = 185.0, restingHR = 55.0).first
        val sparseKcal = Calories.estimateBoutCalories(sparse, profile, hrmax = 185.0, restingHR = 55.0).first
        assertEquals("sparse HR must be counted over elapsed time, not undercounted per sample",
            denseKcal, sparseKcal, denseKcal * 0.05)
        // Teeth: a per-sample count (60 samples) would be ~1/10th of the dense total.
        assertTrue(sparseKcal > denseKcal * 0.5)
    }

    @Test
    fun wearGap_isCappedNotCreditedInFull() {
        // Two active samples an hour apart must NOT credit a full hour of active burn — the
        // per-sample interval is capped at mergeGapS (150 s). The pre-gap sample contributes
        // 150 s and the tail 1 s, so the total equals a 151 s continuous equivalent, not 3600 s.
        // (BOUT path only.)
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val gapped = listOf(
            com.noop.data.HrSample(deviceId = "t", ts = 0L, bpm = 130),
            com.noop.data.HrSample(deviceId = "t", ts = 3600L, bpm = 130),
        )
        val cappedEquiv = (0..150).map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 130) }
        val gappedKcal = Calories.estimateBoutCalories(gapped, profile, hrmax = 185.0, restingHR = 55.0).first
        val equivKcal = Calories.estimateBoutCalories(cappedEquiv, profile, hrmax = 185.0, restingHR = 55.0).first
        assertEquals("an inter-sample gap must be capped at mergeGapS, not credited in full",
            equivKcal, gappedKcal, equivKcal * 0.001)
    }

    @Test
    fun sparseDay_tracksElapsedTimeNotSampleCount() {
        // THE headline fix. Basal accrues on the WALL CLOCK, so a fully-worn 24 h day totals the
        // subject's BMR at ANY sample cadence. The old model credited one second per sample, which
        // is only correct at exactly 1 Hz: a WHOOP 5/MG streams live HR ≈ every 30 s, so the same
        // fully-worn day counted 2 880 s of metabolism instead of 86 400 and collapsed to ~61 kcal.
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val sparse = (0 until 86_400 step 30)
            .map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 55) }
        assertEquals("one sample every 30 s over 24 h", 2_880, sparse.size)
        val total = Calories.estimateDayEnergy(
            sparse, profile, hrmax = 185.0, restingHR = 55.0, spanS = 86_400.0,
        ).dayTotalKcal
        assertEquals("a worn 24 h must total ≈ BMR regardless of sample cadence", 1825.25, total, 1.0)
        // One second per sample would produce 2 880 × restingRate, about 60.8 kcal.
        assertTrue("must not collapse toward the old per-sample undercount", total > 60.84 * 10)
        // The dense stream over the same span agrees, which is the cadence-independence claim.
        val dense = (0 until 86_400).map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 55) }
        val denseTotal = Calories.estimateDayEnergy(
            dense, profile, hrmax = 185.0, restingHR = 55.0, spanS = 86_400.0,
        ).dayTotalKcal
        assertEquals("sparse and dense coverage of the same day must agree", denseTotal, total, 1e-9)
    }

    @Test
    fun sparseDay_tracksElapsedTimeForTheActiveTermToo() {
        // The companion the cadence test above cannot make: at 55 bpm every sample sits below the
        // active gate, so it pins basal alone. Duration is credited per SAMPLE, so the active term is
        // where a cadence bug actually lands — and the day model now walks epochs rather than samples,
        // which is a second way for a sample's seconds to be lost.
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        fun activeAtCadence(step: Int) = Calories.estimateDayEnergy(
            (0 until 86_400 step step).map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 140) },
            profile, hrmax = 185.0, restingHR = 55.0, spanS = 86_400.0,
        ).dayActiveKcal
        val dense = activeAtCadence(1)
        assertTrue("the fixture must earn active energy above resting", dense > 0.0)
        // Within a tenth of a percent, not exact: the day's LAST sample has no successor to measure a
        // gap against and credits one second whatever the cadence, so a sparse stream is short by its
        // own interval. Bounded and known; a cadence bug is three orders of magnitude larger.
        assertEquals("one sample every 30 s must credit 30 s", dense, activeAtCadence(30), dense * 0.001)
        assertEquals("and every 5 s, 5 s", dense, activeAtCadence(5), dense * 0.001)
        // Teeth: crediting one second per sample would leave a 30 s stream with 1/30th of the energy.
        assertTrue("must not collapse toward a per-sample undercount", activeAtCadence(30) > dense / 2.0)
    }

    @Test
    fun unwornStretch_stillAccruesBasal() {
        // spanS is elapsed wall clock, not observed wear: an hour of HR at the start of the day
        // still credits a full day of basal, because resting metabolism happened whether or not the
        // strap was on the wrist. This is what makes the figure keep climbing all day.
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val wornOneHour = hrDay(55, 3_600)
        val fullDay = Calories.estimateDayEnergy(
            wornOneHour, profile, hrmax = 185.0, restingHR = 55.0, spanS = 86_400.0,
        ).dayTotalKcal
        assertEquals("basal spans the elapsed day even where the strap was off", 1825.25, fullDay, 1.0)
        // Half the day elapsed (e.g. queried at noon) → half the basal: the total ACCUMULATES.
        val halfDay = Calories.estimateDayEnergy(
            wornOneHour, profile, hrmax = 185.0, restingHR = 55.0, spanS = 43_200.0,
        ).dayTotalKcal
        assertEquals(1825.25 / 2.0, halfDay, 1.0)
        assertTrue("a partially-elapsed day must total less than a full one", halfDay < fullDay)
    }

    @Test
    fun dayGap_capsSurplusNotBasal() {
        // Elapsed-time weighting is only safe because the two terms are integrated separately:
        // basal on the wall clock, and only the SURPLUS above resting on measured wear, capped at
        // dayMaxGapS. Two active samples an hour apart must NOT credit an hour of exercise — the
        // first carries at most 120 s of surplus and the tail 1 s — while basal still covers the
        // whole 3601 s span.
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val gapped = listOf(
            com.noop.data.HrSample(deviceId = "t", ts = 0L, bpm = 130),
            com.noop.data.HrSample(deviceId = "t", ts = 3600L, bpm = 130),
        )
        val total = Calories.estimateDayEnergy(
            gapped, profile, hrmax = 185.0, restingHR = 55.0, spanS = 3_601.0,
        ).dayTotalKcal
        assertEquals("basal over 3601 s + surplus over (120 s + 1 s), not over the full hour",
            96.87, total, 0.5)
        // Teeth: crediting the gap in full would be ~695 kcal — 7x larger.
        assertTrue("an unbridged hour must not be credited as an hour of effort", total < 200.0)
        // And basal is never double-counted: the total always at least covers the elapsed span.
        assertTrue(total > 1825.247 / 86_400.0 * 3_601.0)
    }

    @Test
    fun span_nullFallsBackToObservedSpan() {
        // Pure-function callers and tests pass no span; the estimator then uses the span its own
        // samples cover (first..last inclusive), which keeps a dense 1 Hz day numerically identical
        // to the previous model and every existing vector deterministic.
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val dense = (0 until 600).map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 130) }
        val implicitSpan = Calories.estimateDayEnergy(dense, profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        val explicitSpan = Calories.estimateDayEnergy(
            dense, profile, hrmax = 185.0, restingHR = 55.0, spanS = 600.0,
        ).dayTotalKcal
        assertEquals(explicitSpan, implicitSpan, 1e-9)
    }

    @Test
    fun dayCalories_restingDayIsLowerThanActiveDay() {
        // A whole day at resting HR burns far less than the same length all-active day,
        // and the resting-day total is positive (BMR floor).
        val profile = UserProfile(weightKg = 70.0, heightCm = 170.0, age = 30.0, sex = "nonbinary")
        // Day activeThreshold = 55 + 0.10*(185-55) = 68 bpm; 60 < 68 (resting), 150 >= 68 (active).
        val restingDay = Calories.estimateDayEnergy(hrDay(60, 3600), profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        val activeDay = Calories.estimateDayEnergy(hrDay(150, 3600), profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        assertTrue("resting day must burn > 0 (BMR floor)", restingDay > 0.0)
        assertTrue("active day must exceed resting day", activeDay > restingDay)
    }

    @Test
    fun dayCalories_sedentaryFullDayApproximatesBMR() {
        // A full 24 h at resting HR (below the day active gate) must total ≈ the subject's BMR:
        // the day estimator floors every sub-threshold second at the resting metabolic rate, so
        // an all-rest day is BMR by construction. Standard male test subject's revised
        // Harris–Benedict BMR ≈ 1825 kcal. This is an APPROXIMATE estimate, not medical advice.
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        val sedentary = hrDay(55, 86_400) // 24 h, all at resting HR
        val total = Calories.estimateDayEnergy(sedentary, profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        assertEquals("a sedentary full day must total ≈ the subject's BMR (~1825 kcal)",
            1825.25, total, 1.0)
    }

    @Test
    fun dayGate_isTenPercentHRRAndCreditsSubExerciseHR() {
        // Pins the 10% day gate (dayActiveHRRFraction) and the trade-off it buys, so neither
        // direction can be changed silently.
        //
        // WHY it was lowered: the gate is a fraction of HR RESERVE, so a fit user with a low
        // resting HR was penalised hardest. At 42/180 the old 50% gate sat at 111 bpm — above
        // walking, easy cycling AND a recovery jog — so their whole ordinary day scored as pure
        // BMR and the daily figure barely moved. 10% puts that user's gate at ~56 bpm, between
        // sitting and walking HR (see lowRestingHRUser_getsCreditForOrdinaryActivity).
        //
        // WHAT it costs: for a subject with an ORDINARY resting HR the gate now falls BELOW
        // sedentary HR, and Keytel — regressed on genuine exercise — does not decay to BMR there.
        // This subject (RHR 55) gets a 68 bpm gate, so a light day (8 h sleep @55, 8 h sedentary
        // @70, 8 h light activity @100) is credited ≈ 5177 kcal against a true TDEE nearer ~2500.
        // That over-count is the known, accepted cost of the low gate; it is pinned here
        // deliberately rather than left to surprise someone.
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
        assertEquals(0.10, HeartRateGates().dayActiveHRRFraction, 1e-12)
        // Gate = 55 + 0.10 × (185 − 55) = 68 bpm.
        val below = Calories.estimateDayEnergy(hrDay(67, 3_600), profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        val above = Calories.estimateDayEnergy(hrDay(69, 3_600), profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        assertTrue("69 bpm is above the 68 bpm gate, 67 is below", above > below)
        assertEquals("sub-gate hour = basal only", 1825.247 / 24.0, below, 0.01)

        // The light day, with DISTINCT timestamps so the wall-clock span is a real 24 h.
        val lightDay =
            (0 until 8 * 3_600).map { com.noop.data.HrSample(deviceId = "t", ts = it.toLong(), bpm = 55) } +
            (0 until 8 * 3_600).map { com.noop.data.HrSample(deviceId = "t", ts = (8 * 3_600 + it).toLong(), bpm = 70) } +
            (0 until 8 * 3_600).map { com.noop.data.HrSample(deviceId = "t", ts = (16 * 3_600 + it).toLong(), bpm = 100) }
        val total = Calories.estimateDayEnergy(lightDay, profile, hrmax = 185.0, restingHR = 55.0).dayTotalKcal
        assertEquals("the 10% gate credits sedentary + light HR the Keytel rate — known over-count",
            5177.32, total, 2.0)
        assertTrue("the whole point of the low gate: a light day now exceeds bare BMR", total > 1825.25)
    }

    @Test
    fun lowRestingHRUser_getsCreditForOrdinaryActivity() {
        // The case the 10% gate exists for: RHR 42 / HRmax 180 (reserve 138 bpm). Walking ~65,
        // easy cycling ~95 and a recovery jog ~115 all sat BELOW the old 50% gate of 111 bpm (only
        // the jog cleared it), so a genuinely active day scored as bare BMR. At 10% the gate is
        // 55.8 bpm and each of those activities is credited above basal, in the right order of
        // effort.
        val profile = UserProfile(weightKg = 75.0, heightCm = 178.0, age = 35.0, sex = "male")
        fun hourAt(bpm: Int) =
            Calories.estimateDayEnergy(hrDay(bpm, 3_600), profile, hrmax = 180.0, restingHR = 42.0).dayTotalKcal
        val sitting = hourAt(50) // below the 55.8 bpm gate → basal only
        val walking = hourAt(65)
        val cycling = hourAt(95)
        val zone2 = hourAt(130)
        assertTrue("walking must clear the gate; sitting must not", sitting < walking)
        assertTrue(walking < cycling)
        assertTrue(cycling < zone2)
        // Teeth: under the OLD 50% gate (111 bpm) walking AND cycling were both basal-only, so they
        // were indistinguishable from sitting. Prove they no longer are.
        assertTrue("easy cycling must be worth clearly more than an hour of sitting",
            cycling > sitting * 2.0)
    }

    @Test
    fun analyzeDay_caloriesIgnoreAdjacentDayHr() {
        // analyzeDay must filter HR to the target UTC day before summing calories — the
        // IntelligenceEngine read window spans ~42h, so adjacent-day HR must NOT inflate the
        // day's activeKcalEst (the critical "full-window double-count" regression).
        val day = "2026-01-02"
        val noon = 1_767_355_200L // 2026-01-02T12:00:00Z
        fun hr(tsOffsetSec: Long, bpm: Int) =
            com.noop.data.HrSample(deviceId = "t", ts = noon + tsOffsetSec, bpm = bpm)
        val inDay = (0 until 600).map { hr(it.toLong(), 120) }
        // Same in-day HR plus 600 samples ~36h earlier (a different UTC day, inside the window).
        val withAdjacent = inDay + (0 until 600).map { hr(-36L * 3_600 - it, 120) }
        val a = AnalyticsEngine.analyzeDay(day = day, hr = inDay, profile = UserProfile()).daily.activeKcalEst
        val b = AnalyticsEngine.analyzeDay(day = day, hr = withAdjacent, profile = UserProfile()).daily.activeKcalEst
        assertNotNull(a)
        assertNotNull(b)
        assertEquals("adjacent-day HR must not change the day's calories", a!!, b!!, 1e-6)
    }

    @Test
    fun analyzeDay_dayHrCoversFullCalendarDay() {
        // Simulate the past-day clip: the night-window HR only reaches midday; the full calendar-day
        // HR also has the afternoon. activeKcalEst must use dayHr when supplied, so the full-day total
        // exceeds the clipped night-window total (the past-day undercount fix).
        val day = "2026-01-02"
        val noon = 1_767_355_200L // 2026-01-02T12:00:00Z
        fun hr(tsOffsetSec: Long, bpm: Int) =
            com.noop.data.HrSample(deviceId = "t", ts = noon + tsOffsetSec, bpm = bpm)
        val nightWindow = (0 until 600).map { hr(it.toLong(), 120) }
        val fullDay = nightWindow + (0 until 600).map { hr(3L * 3_600 + it, 120) }
        val clipped = AnalyticsEngine.analyzeDay(day = day, hr = nightWindow, profile = UserProfile()).daily.activeKcalEst
        val full = AnalyticsEngine.analyzeDay(
            day = day, hr = nightWindow, dayHr = fullDay, profile = UserProfile(),
        ).daily.activeKcalEst
        assertNotNull(clipped)
        assertNotNull(full)
        assertTrue("full calendar-day calories must exceed the clipped night-window total", full!! > clipped!!)
    }

    @Test
    fun analyzeDay_dayHrNullFallsBackToWindowHr() {
        // With no calendar-day stream, the total falls back to the window `hr` — identical to passing
        // that same window explicitly as dayHr (the (dayHr ?: hr) fallback).
        val day = "2026-01-02"
        val noon = 1_767_355_200L
        fun hr(tsOffsetSec: Long, bpm: Int) =
            com.noop.data.HrSample(deviceId = "t", ts = noon + tsOffsetSec, bpm = bpm)
        val window = (0 until 600).map { hr(it.toLong(), 120) }
        val fallback = AnalyticsEngine.analyzeDay(day = day, hr = window, profile = UserProfile()).daily.activeKcalEst
        val explicit = AnalyticsEngine.analyzeDay(day = day, hr = window, dayHr = window, profile = UserProfile()).daily.activeKcalEst
        assertNotNull(fallback)
        assertNotNull(explicit)
        assertEquals(fallback!!, explicit!!, 1e-9)
    }
}
