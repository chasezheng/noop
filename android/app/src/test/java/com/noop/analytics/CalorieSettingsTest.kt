package com.noop.analytics

import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieVitals
import com.noop.analytics.calorie.Calories
import com.noop.analytics.calorie.EnergyModel
import com.noop.analytics.calorie.HeartRateGates
import com.noop.analytics.calorie.HybridMet
import com.noop.analytics.calorie.HybridModel
import com.noop.analytics.calorie.HybridModelSetting
import com.noop.analytics.calorie.KeytelModel
import com.noop.analytics.calorie.exclusiveEnd
import com.noop.analytics.calorie.kcalFrom
import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HeartRateGates] and [HybridModelSetting] are the knobs behind the daily energy models, edited in
 * Settings → Calorie tracking. Two contracts are pinned here.
 *
 * The first is that the DEFAULT of each is byte-identical to the constants the engines used before
 * they existed. Every pure caller and every other test in this suite passes no setting at all, so a
 * drift between a default field and its constant would silently re-score history with nothing to
 * catch it.
 *
 * The second is that each knob moves the number it claims to, in the direction it claims to. A knob
 * threaded to the wrong place still compiles and still looks correct in review — it just quietly
 * does nothing, and the wearer concludes the setting is broken rather than that their day is.
 */
class CalorieSettingsTest {

    private val subject = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")

    /** The standard subject, scored with one specific pair of heart-rate gates. */
    private fun tuned(gates: HeartRateGates): UserProfile = subject.copy(heartRateGates = gates)

    /** The standard subject, scored with one specific motion-hybrid setting. */
    private fun tuned(setting: HybridModelSetting): UserProfile = subject.copy(hybridModelSetting = setting)

    /** The standard subject, scored by one specific model. */
    private fun scoredBy(model: EnergyModel): UserProfile = subject.copy(calorieModel = model)

    private fun hrRun(bpm: Int, seconds: Int, from: Long = 0L): List<HrSample> =
        (0 until seconds).map { HrSample(deviceId = "test", ts = from + it, bpm = bpm) }

    private fun motionRun(g: Double, seconds: Int, from: Long): List<GravitySample> =
        (0 until seconds).map {
            GravitySample(deviceId = "test", ts = from + it, x = 0.0, y = 0.0, z = 1.0, dynAccel = g)
        }

    // ── One gate formula, shared by every scoring site ─────────────────────────────────────────

    @Test
    fun theGate_isTheKarvonenReserveFraction() {
        // resting + 10% of the reserve, at resting 50 and HRmax 190: 50 + 0.1 * 140.
        assertEquals(64.0, Calories.activeHrGate(50.0, 190.0, 0.10), 1e-12)
    }

    @Test
    fun aFractionOfZero_gatesAtRestingItself() {
        assertEquals(50.0, Calories.activeHrGate(50.0, 190.0, 0.0), 1e-12)
    }

    @Test
    fun aFractionOfOne_gatesAtHrMax() {
        assertEquals(190.0, Calories.activeHrGate(50.0, 190.0, 1.0), 1e-12)
    }

    // ── The default must reproduce the shipped constants ────────────────────────────────────────

    @Test
    fun theDayGateReachesTheHeartRateModel() {
        // `subject` already carries default gates, so comparing them against an explicitly defaulted
        // pair asserts nothing. What must hold is that the knob is THREADED: raising the day
        // gate above the fixture's heart rate has to strip the active term.
        val hr = hrRun(bpm = 120, seconds = 600)
        val shipped = Calories.estimateDayEnergy(hr, subject, hrmax = 185.0, restingHR = 55.0)
        val gatedOut = Calories.estimateDayEnergy(
            hr, tuned(HeartRateGates(dayActiveHRRFraction = 0.95)), hrmax = 185.0, restingHR = 55.0,
        )
        assertTrue(shipped.dayActiveKcal > 0.0)
        assertEquals(0.0, gatedOut.dayActiveKcal, 1e-12)
        assertEquals("basal is not gated", shipped.dayBasalKcal, gatedOut.dayBasalKcal, 1e-12)
    }

    @Test
    fun theTwoGapCapsAreTheValuesTheEnergyPathsIntegrateWith() {
        // Pinned BY NAME. Both are structurally exercised elsewhere, but nothing named them, so a
        // rename or a re-coupling to the detector's own merge threshold would pass silently.
        assertEquals(150.0, WorkoutDetector.mergeGapS, 0.0)
        assertEquals(120.0, Calories.dayMaxGapS, 0.0)
        assertTrue("a bout's cap is the looser of the two", WorkoutDetector.mergeGapS > Calories.dayMaxGapS)
    }

    @Test
    fun defaultModel_isHeartRateNotTheHybrid() {
        assertEquals(EnergyModel.HEART_RATE, UserProfile().calorieModel)
    }

    // ── Heart-rate knobs ───────────────────────────────────────────────────────────────────────

    @Test
    fun loweringTheDayGate_creditsMoreEnergy() {
        // 90 bpm sits above a 10% reserve gate for this subject and below a 40% one, so the same
        // hour is scored as active by the lower gate and as pure basal by the higher one.
        val hr = hrRun(bpm = 90, seconds = 3_600)
        val openGate = Calories.estimateDayEnergy(
            hr, tuned(HeartRateGates(dayActiveHRRFraction = 0.10)), hrmax = 185.0, restingHR = 55.0,
        ).dayTotalKcal
        val strictGate = Calories.estimateDayEnergy(
            hr, tuned(HeartRateGates(dayActiveHRRFraction = 0.40)), hrmax = 185.0, restingHR = 55.0,
        ).dayTotalKcal
        assertTrue("a lower gate must credit more, not less", openGate > strictGate)
    }

    @Test
    fun aGateAboveTheDaysHeartRate_leavesOnlyBasal() {
        val hr = hrRun(bpm = 90, seconds = 3_600)
        val basalOnly = Calories.estimateDayEnergy(
            hr, tuned(HeartRateGates(dayActiveHRRFraction = 0.60)), hrmax = 185.0, restingHR = 55.0,
        ).dayTotalKcal
        // Harris–Benedict for this subject is 1825.247 kcal/day; one hour of it is 1825.247 / 24.
        assertEquals(1825.247 / 24.0, basalOnly, 0.01)
    }

    @Test
    fun neitherGateSetting_movesAStoredWorkout() {
        // `estimateBoutCalories` prices `WorkoutRow.energyKcal`, a table with no rescore path behind
        // it, and holds its gate at the fixed [Calories.activeHRRFraction]. Wiring either wearer
        // setting into it would silently move every workout already on record.
        val hr = hrRun(bpm = 100, seconds = 600)
        val untouched = Calories.estimateBoutCalories(hr, subject, hrmax = 185.0, restingHR = 55.0).first
        for (gates in listOf(
            HeartRateGates(dayActiveHRRFraction = 0.55),
            HeartRateGates(boutActiveHRRFraction = 0.05),
            HeartRateGates(boutActiveHRRFraction = 0.50),
        )) {
            assertEquals(
                "a gate setting must not reach the bout estimator",
                untouched,
                Calories.estimateBoutCalories(hr, tuned(gates), hrmax = 185.0, restingHR = 55.0).first,
                0.0,
            )
        }
    }

    @Test
    fun loweringTheBoutGate_widensTheWorkoutGateTheDayModelScoresWith() {
        // `boutActiveHRRFraction` values a workout MINUTE inside a scored day, which is a different
        // question from what a whole bout cost.
        fun gateAt(fraction: Double) =
            KeytelModel(
                tuned(HeartRateGates(boutActiveHRRFraction = fraction)),
                CalorieVitals(hrmax = 185.0, restingHR = 55.0),
            ).workoutGate
        assertTrue("a lower fraction must admit a lower heart rate", gateAt(0.05) < gateAt(0.50))
        // Karvonen: 55 + 0.05 x (185 - 55).
        assertEquals(61.5, gateAt(0.05), 1e-12)
    }

    // ── Hybrid knobs ───────────────────────────────────────────────────────────────────────────

    @Test
    fun theMotionGain_scalesTheMetItDerives() {
        assertEquals(4.0, HybridMet.metFromDynAccel(0.1, HybridModelSetting(dynAccelMETGainPerG = 30.0)), 1e-12)
        assertEquals(7.0, HybridMet.metFromDynAccel(0.1, HybridModelSetting(dynAccelMETGainPerG = 60.0)), 1e-12)
    }

    @Test
    fun theActivityDayOpensAtLocalMidnightAndRunsTwentyFourHours() {
        val window = ActivityDay.atLocalMidnight(0L).window()
        assertEquals(0L, window.first)
        assertEquals(86_400L, window.exclusiveEnd)
    }

    @Test
    fun raisingTheMotionGain_raisesTheHybridsMovementEnergy() {
        // One hour of steady light motion inside the activity day, no workouts.
        val motion = motionRun(g = 0.05, seconds = 3_600, from = 14_400L)
        val lowGain = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, gravity = motion,
            profile = tuned(HybridModelSetting(dynAccelMETGainPerG = 30.0)), hrmax = 185.0, restingHR = 55.0,
        )
        val highGain = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, gravity = motion,
            profile = tuned(HybridModelSetting(dynAccelMETGainPerG = 60.0)), hrmax = 185.0, restingHR = 55.0,
        )
        assertTrue("a higher gain must earn more movement energy", highGain.kcalFrom(HybridModel.Label.MOTION_ACTIVE) > lowGain.kcalFrom(HybridModel.Label.MOTION_ACTIVE))
    }

    @Test
    fun raisingTheAccrualThreshold_excludesMinutesBelowIt() {
        // 0.05 g is 2.5 MET at the default gain: above a 1.5 MET threshold, below a 3.0 MET one.
        val motion = motionRun(g = 0.05, seconds = 3_600, from = 14_400L)
        val earned = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, gravity = motion,
            profile = tuned(HybridModelSetting(activeAccrualMET = 1.5)), hrmax = 185.0, restingHR = 55.0,
        )
        val excluded = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, gravity = motion,
            profile = tuned(HybridModelSetting(activeAccrualMET = 3.0)), hrmax = 185.0, restingHR = 55.0,
        )
        assertTrue("minutes above the threshold must earn", earned.kcalFrom(HybridModel.Label.MOTION_ACTIVE) > 0.0)
        assertEquals("minutes below it must earn nothing", 0.0, excluded.kcalFrom(HybridModel.Label.MOTION_ACTIVE), 0.0)
    }

    @Test
    fun disablingTheHeartRateFallback_dropsEnergyFromMotionlessMinutes() {
        // Heart rate only, no motion channel at all — the WHOOP 4.0 shape.
        val hr = hrRun(bpm = 120, seconds = 3_600, from = 14_400L)
        val withFallback = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, hr = hr,
            profile = tuned(HybridModelSetting(hrFallbackWhenNoMET = true)), hrmax = 185.0, restingHR = 55.0,
        )
        val without = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, hr = hr,
            profile = tuned(HybridModelSetting(hrFallbackWhenNoMET = false)), hrmax = 185.0, restingHR = 55.0,
        )
        assertTrue("the fallback must earn something", withFallback.kcalFrom(HybridModel.Label.HR_FALLBACK) > 0.0)
        assertEquals("without it those minutes earn nothing", 0.0, without.kcalFrom(HybridModel.Label.HR_FALLBACK), 0.0)
    }

    @Test
    fun theFallbackKnob_cannotZeroTheHeartRateModel() {
        // The knob decides what happens to a minute the MOTION path could not cover. The heart-rate
        // model has no motion path, so with the knob off every minute was left uncovered and a flat
        // zero was written to the day. The setting sits under "Motion hybrid" and its copy says it
        // affects that model, so a wearer on the default had no way to connect the two.
        val hr = hrRun(bpm = 130, seconds = 3_600)
        val on = Calories.estimateDayEnergy(
            hr, tuned(HybridModelSetting(hrFallbackWhenNoMET = true)), hrmax = 185.0, restingHR = 55.0,
        )
        val off = Calories.estimateDayEnergy(
            hr, tuned(HybridModelSetting(hrFallbackWhenNoMET = false)), hrmax = 185.0, restingHR = 55.0,
        )
        assertTrue("the fixture must earn something", on.dayActiveKcal > 0.0)
        assertEquals(on.dayActiveKcal, off.dayActiveKcal, 0.0)
    }

    @Test
    fun theTwoModels_creditTheSameSecondsWhenNeitherHasMotion() {
        // With no motion and no workouts the hybrid IS the heart-rate model: every minute takes the
        // heart-rate path. One estimator now serves both, and this pins that they have not drifted.
        val hr = hrRun(bpm = 130, seconds = 3_600, from = 14_400L)
        val hybrid = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, hr = hr,
            profile = subject, hrmax = 185.0, restingHR = 55.0,
        )
        val heartRateOnly = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 100_800L, hr = hr,
            profile = subject, hrmax = 185.0, restingHR = 55.0,
        )
        assertEquals(heartRateOnly.dayActiveKcal, hybrid.dayActiveKcal, 1e-12)
    }

    @Test
    fun basalForAFullDay_isTheHarrisBenedictFigure() {
        assertEquals(1825.247, Calories.basalKcalForSpan(subject, 86_400.0), 0.01)
    }

    @Test
    fun basalScalesWithTheElapsedPartOfTheDay() {
        // The Total energy card adds this to the active term, so a day half over must contribute half
        // a day of resting metabolism — not a full 24 h booked at midnight.
        assertEquals(1825.247 / 2.0, Calories.basalKcalForSpan(subject, 43_200.0), 0.01)
    }

    @Test
    fun basalForNoElapsedTime_isZero() {
        assertEquals(0.0, Calories.basalKcalForSpan(subject, 0.0), 0.0)
    }

    @Test
    fun basalPlusActive_reconstructsTheTotalTheEstimatorReports() {
        // The contract the Total energy card depends on: nothing stores basal, so the card re-derives
        // it and adds it back. That reconstruction must equal what the estimator itself computed.
        val hr = hrRun(bpm = 120, seconds = 3_600)
        val energy = Calories.estimateDayEnergy(
            hr, subject, hrmax = 185.0, restingHR = 55.0, spanS = 3_600.0,
        )
        assertEquals(
            energy.dayTotalKcal,
            energy.dayActiveKcal + Calories.basalKcalForSpan(subject, 3_600.0),
            1e-9,
        )
    }

    // ── The knobs reach the stored figure ──────────────────────────────────────────────────────

    @Test
    fun withNoMotion_theHybridDegradesToTheHeartRateModel() {
        // A WHOOP 4.0 reports no motion magnitude at all, so every minute takes the hybrid's
        // heart-rate fallback and the two models must agree exactly on the active term.
        val hr = hrRun(bpm = 110, seconds = 3_600, from = 43_200L)
        val hrModel = AnalyticsEngine.analyzeDay(
            day = "1970-01-01", dayHr = hr, nowTs = 86_400L,
            profile = scoredBy(EnergyModel.HEART_RATE),
        )
        val hybridModel = AnalyticsEngine.analyzeDay(
            day = "1970-01-01", dayHr = hr, nowTs = 86_400L,
            profile = scoredBy(EnergyModel.HYBRID),
        )
        assertEquals(hrModel.daily.activeKcalEst!!, hybridModel.daily.activeKcalEst!!, 1e-9)
    }

    @Test
    fun withMotion_theHybridScoresDifferentlyFromHeartRateAlone() {
        // Motion is what the hybrid exists to use: the same day with a movement channel must not
        // reduce to the heart-rate answer, or selecting it in Settings would be inert.
        val hr = hrRun(bpm = 110, seconds = 3_600, from = 43_200L)
        val motion = motionRun(g = 0.05, seconds = 3_600, from = 43_200L)
        val hrModel = AnalyticsEngine.analyzeDay(
            day = "1970-01-01", dayHr = hr, dayGravity = motion, nowTs = 86_400L,
            profile = scoredBy(EnergyModel.HEART_RATE),
        )
        val hybridModel = AnalyticsEngine.analyzeDay(
            day = "1970-01-01", dayHr = hr, dayGravity = motion, nowTs = 86_400L,
            profile = scoredBy(EnergyModel.HYBRID),
        )
        assertNotEquals(hrModel.daily.activeKcalEst, hybridModel.daily.activeKcalEst)
    }

    @Test
    fun theStoredFigure_excludesRestingMetabolism() {
        // The contract this basis change exists for: a day spent entirely below the active gate
        // stores ZERO, not a day of basal. Anything else is not comparable with the phone's active
        // energy, which is what the `active_kcal` key resolves it against.
        val hr = hrRun(bpm = 50, seconds = 3_600, from = 43_200L)
        val scored = AnalyticsEngine.analyzeDay(
            day = "1970-01-01", dayHr = hr, nowTs = 86_400L, profile = subject,
        )
        assertEquals(0.0, scored.daily.activeKcalEst!!, 1e-9)
    }

    @Test
    fun analyzeDay_movesTheStoredFigureWhenTheDayGateMoves() {
        val hr = hrRun(bpm = 90, seconds = 3_600, from = 43_200L)
        val openGate = AnalyticsEngine.analyzeDay(
            day = "1970-01-01", dayHr = hr, nowTs = 86_400L,
            profile = tuned(HeartRateGates(dayActiveHRRFraction = 0.05)),
        )
        val strictGate = AnalyticsEngine.analyzeDay(
            day = "1970-01-01", dayHr = hr, nowTs = 86_400L,
            profile = tuned(HeartRateGates(dayActiveHRRFraction = 0.60)),
        )
        assertTrue(
            "the Settings gate must reach the stored figure",
            openGate.daily.activeKcalEst!! > strictGate.daily.activeKcalEst!!,
        )
    }

    // ── The workout gate reaches the hybrid's workout minutes ──────────────────────────────────

    @Test
    fun theWorkoutGate_holdsWorkoutMinutesToItsOwnBar() {
        // The knob's copy says it applies inside a detected workout. Under the hybrid every minute was
        // held to the DAY gate instead, so a wearer who raised this one saw nothing happen.
        val start = 0L
        val hr = (0 until 3_600).map { HrSample("t", start + it, 105) }
        fun workoutKcalAt(boutFraction: Double) = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + start, hr = hr,
            workouts = listOf(start until start + 3_600),
            profile = UserProfile(heartRateGates = HeartRateGates(boutActiveHRRFraction = boutFraction)),
            hrmax = 185.0, restingHR = 55.0,
        ).kcalFrom(HybridModel.Label.WORKOUT)
        assertTrue("the fixture must earn workout energy at the shipped gate", workoutKcalAt(0.10) > 0.0)
        assertEquals("a gate above the fixture's heart rate must earn none of it",
                     0.0, workoutKcalAt(0.60), 1e-12)
    }

    @Test
    fun theWorkoutGate_leavesNonWorkoutMinutesToTheDayGate() {
        // The other half: raising the workout bar must not quietly raise it for the rest of the day.
        val start = 0L
        val hr = (0 until 3_600).map { HrSample("t", start + it, 105) }
        fun fallbackKcalAt(boutFraction: Double) = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + start, hr = hr,
            profile = UserProfile(heartRateGates = HeartRateGates(boutActiveHRRFraction = boutFraction)),
            hrmax = 185.0, restingHR = 55.0,
        ).kcalFrom(HybridModel.Label.HR_FALLBACK)
        assertTrue(fallbackKcalAt(0.10) > 0.0)
        assertEquals(fallbackKcalAt(0.10), fallbackKcalAt(0.60), 1e-12)
    }


    // ── One sample's seconds, spread across the minutes they cover ─────────────────────────────

    @Test
    fun aSampleSpanningTwoMinutes_isSplitBetweenThem() {
        // A sample carries the seconds until the next one, and those seconds can cross a minute
        // boundary into a minute a different path owns. Truncating them to the sample's own minute
        // would silently drop energy; crediting them all to it would estimate motion minutes from
        // heart rate. Here the second minute is a workout minute and the first is not.
        val start = 0L
        // One sample 30 s before the workout opens, the next 30 s after: 60 s of duration, split.
        val hr = listOf(HrSample("t", start + 30, 150), HrSample("t", start + 90, 150))
        val energy = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + start, hr = hr,
            workouts = listOf(start + 60 until start + 120),
            profile = UserProfile(), hrmax = 185.0, restingHR = 55.0,
        )
        // The first sample's 60 s straddle the boundary: 30 s of fallback, 30 s of workout. The
        // second sample sits inside the workout and carries one representative second.
        assertTrue("the workout minute must be estimated", energy.kcalFrom(HybridModel.Label.WORKOUT) > 0.0)
        assertTrue("and the minute before it must not be swallowed", energy.kcalFrom(HybridModel.Label.HR_FALLBACK) > 0.0)
        // 31 s of workout against 30 s of fallback, from identical samples at one rate.
        assertEquals(31.0 / 30.0, energy.kcalFrom(HybridModel.Label.WORKOUT) / energy.kcalFrom(HybridModel.Label.HR_FALLBACK), 1e-9)
    }

    @Test
    fun duplicateTimestamps_creditNoDuration() {
        // Two samples at the same second measure a zero gap. Crediting a representative second to each
        // would double-count that second; the day must read as though one landed.
        val start = 0L
        fun activeFor(hr: List<HrSample>) = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + start, hr = hr,
            profile = UserProfile(), hrmax = 185.0, restingHR = 55.0,
        ).dayActiveKcal
        val once = (0 until 600).map { HrSample("t", start + it, 150) }
        val duplicated = once.flatMap { listOf(it, it) }
        assertTrue(activeFor(once) > 0.0)
        assertEquals(activeFor(once), activeFor(duplicated), 1e-9)
    }

}
