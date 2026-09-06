package com.noop.analytics

import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieTimeline
import com.noop.analytics.calorie.Calories
import com.noop.analytics.calorie.HybridMet
import com.noop.analytics.calorie.HybridModelSetting
import com.noop.analytics.calorie.HybridModel.Extra
import com.noop.analytics.calorie.HybridModel.Label
import com.noop.analytics.calorie.exclusiveEnd
import com.noop.analytics.calorie.kcalFrom
import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HybridMetTest — the Oura-style hybrid day engine.
 *
 * Mirror of Swift `MetCaloriesTests`. Every pinned number here must hold on BOTH platforms.
 *
 * Standard subject throughout: 80 kg / 180 cm / 35 y male.
 *   Harris–Benedict BMR = 88.362 + 13.397×80 + 479.9×1.80 − 5.677×35 = 1825.247 kcal/day
 *   restingRate         = 1825.247 / 86 400                          = 0.021125544 kcal/s
 */
class HybridMetTest {

    private val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")

    /** The same subject with the heart-rate fallback off, isolating the motion path. */
    private val noFallbackProfile =
        profile.copy(hybridModelSetting = HybridModelSetting(hrFallbackWhenNoMET = false))

    private val bmrPerDay = 1825.247
    private val hrmax = 185.0
    private val restingHR = 55.0

    /** Local midnight at UTC 0, so the activity window is [0, 86400). */
    private val localMidnight = 0L
    private val winStart = 0L
    private val winEnd = 86_400L

    private fun gravity(ts: Long, dyn: Double?) =
        GravitySample(deviceId = "d", ts = ts, x = 0.0, y = 0.0, z = 1.0, dynAccel = dyn)

    private fun hr(ts: Long, bpm: Int) = HrSample(deviceId = "d", ts = ts, bpm = bpm)

    /** A whole-day MET or minute-band figure, by the [Extra] key the hybrid states it under. */
    private fun CalorieTimeline.stat(key: String): Double = extras.getValue(key)

    // ---- Day window: local midnight ----

    @Test
    fun dayWindow_opensAtLocalMidnightAndRunsTwentyFourHours() {
        val window = ActivityDay.atLocalMidnight(localMidnight).window()
        assertEquals(0L, window.first)
        assertEquals(86_400L, window.exclusiveEnd)
        assertEquals(86_400L, window.exclusiveEnd - window.first)
    }

    @Test
    fun dayWindow_shiftsWithTheLocalMidnightItIsGiven() {
        // A caller in UTC+2 passes localMidnightUtc = -7200; the window opens there.
        val window = ActivityDay.atLocalMidnight(-7_200L).window()
        assertEquals(-7_200L, window.first)
        assertEquals(79_200L, window.exclusiveEnd)
    }

    // ---- Transfer function ----

    @Test
    fun metFromDynAccel_anchorsAtRestAndBriskWalk() {
        assertEquals(1.0, HybridMet.metFromDynAccel(0.0, HybridModelSetting()), 1e-9)   // still -> resting
        assertEquals(4.0, HybridMet.metFromDynAccel(0.1, HybridModelSetting()), 1e-9)   // brisk walk anchor
        assertEquals(1.0, HybridMet.metFromDynAccel(-5.0, HybridModelSetting()), 1e-9)  // negative clamps to rest
    }

    @Test
    fun metFromDynAccel_clampsAtMaxMET() {
        assertEquals(HybridMet.maxMET, HybridMet.metFromDynAccel(1.0, HybridModelSetting()), 1e-9)
        assertEquals(HybridMet.maxMET, HybridMet.metFromDynAccel(99.0, HybridModelSetting()), 1e-9)
    }

    @Test
    fun band_matchesTheDocumentedOuraEdges() {
        assertEquals(1, HybridMet.bandForMET(0.9))    // rest, < 1.05
        assertEquals(2, HybridMet.bandForMET(1.5))    // inactive, 1.05..2.0
        assertEquals(3, HybridMet.bandForMET(3.0))    // low, 2.0..4.0
        assertEquals(4, HybridMet.bandForMET(5.0))    // medium, 4.0..6.0
        assertEquals(5, HybridMet.bandForMET(9.0))    // high, >= 6.0
    }

    // ---- Basal: credited on the wall clock, never gated on wear ----

    @Test
    fun emptyDay_stillAccruesAFullDayOfBasal() {
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(bmrPerDay, rEnergy.dayBasalKcal, 0.01)
        assertEquals(bmrPerDay, rEnergy.dayTotalKcal, 0.01)
        assertEquals(0.0, rEnergy.dayActiveKcal, 1e-9)
        // Nothing was worn, so the whole window is non-wear and nothing is covered.
        assertEquals(1440.0, rEnergy.stat(Extra.NON_WEAR_MINUTES), 1e-9)
        assertEquals(0.0, rEnergy.stat(Extra.COVERAGE_MINUTES), 1e-9)
    }

    @Test
    fun nowUtc_clampsBasalToTheElapsedPartOfTheDay() {
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, nowUtc = winStart + 43_200L,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(bmrPerDay / 2.0, rEnergy.dayBasalKcal, 0.01)
        assertEquals(720.0, rEnergy.stat(Extra.NON_WEAR_MINUTES), 1e-9)
    }

    @Test
    fun nowUtc_beforeTheWindowYieldsAnEmptyDay() {
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, nowUtc = winStart - 1L,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(0.0, rEnergy.dayTotalKcal, 1e-9)
        assertEquals(0.0, rEnergy.dayBasalKcal, 1e-9)
    }

    // ---- The 1.5 MET accrual gate ----

    @Test
    fun motionBelowAccrualGate_earnsNoActiveEnergy() {
        // 0.01 g -> 1.3 MET: above rest, below the 1.5 accrual gate. Oura calls this sedentary.
        val g = (0 until 1440).map { gravity(winStart + it * 60L, 0.01) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g,
            profile = noFallbackProfile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(0.0, rEnergy.kcalFrom(Label.MOTION_ACTIVE), 1e-9)
        assertEquals(0.0, rEnergy.stat(Extra.ACTIVE_MINUTES), 1e-9)
        // ...but it IS covered, and bands as "inactive" (1.05 <= 1.3 < 2.0).
        assertEquals(1440.0, rEnergy.stat(Extra.COVERAGE_MINUTES), 1e-9)
        assertEquals(1440.0, rEnergy.stat(Extra.INACTIVE_MINUTES), 1e-9)
        assertEquals(bmrPerDay, rEnergy.dayTotalKcal, 0.01)
    }

    @Test
    fun motionAboveAccrualGate_earnsAboveOneMetExcess() {
        // 60 minutes at 0.1 g = 4.0 MET. Per minute: (4-1) x 80 kg x (1/60) h = 4.0 kcal.
        val g = (0 until 60).map { gravity(winStart + it * 60L, 0.1) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g,
            profile = noFallbackProfile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(240.0, rEnergy.kcalFrom(Label.MOTION_ACTIVE), 1e-6)
        assertEquals(240.0, rEnergy.dayActiveKcal, 1e-6)
        assertEquals(bmrPerDay + 240.0, rEnergy.dayTotalKcal, 0.01)
        assertEquals(60.0, rEnergy.stat(Extra.ACTIVE_MINUTES), 1e-9)
        assertEquals(60.0, rEnergy.stat(Extra.MEDIUM_MINUTES), 1e-9)   // 4.0 MET bands as medium
        assertEquals(4.0, rEnergy.stat(Extra.MAX_MET), 1e-9)
        assertEquals(4.0, rEnergy.stat(Extra.MEAN_MET), 1e-9)
        assertEquals(240.0, rEnergy.stat(Extra.MET_MINUTES), 1e-9)     // 4 MET x 60 min
    }

    @Test
    fun accrualGateSitsBelowTheDisplayBandEdge() {
        // The whole point of Oura's two thresholds: 1.6 MET earns energy but still bands as
        // "inactive", because accrual starts at 1.5 while the inactive->low edge is 2.0.
        val g = (0 until 60).map { gravity(winStart + it * 60L, 0.02) }  // 1.6 MET
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g,
            profile = noFallbackProfile, hrmax = hrmax, restingHR = restingHR,
        )
        assertTrue("1.6 MET is past the 1.5 accrual gate", rEnergy.kcalFrom(Label.MOTION_ACTIVE) > 0.0)
        assertEquals(60.0, rEnergy.stat(Extra.ACTIVE_MINUTES), 1e-9)
        assertEquals(60.0, rEnergy.stat(Extra.INACTIVE_MINUTES), 1e-9)  // ...yet still bands as inactive
        assertEquals(0.0, rEnergy.stat(Extra.LOW_MINUTES), 1e-9)
    }

    // ---- Epoch averaging + ring MET precedence ----

    @Test
    fun withinAMinute_metValuesAverage() {
        // Half the minute still, half at 0.1 g -> mean 2.5 MET.
        val g = (0 until 30).map { gravity(winStart + it, 0.0) } +
            (30 until 60).map { gravity(winStart + it, 0.1) }
        val eps = HybridMet.epochs(winStart, winStart + 60, g, emptyList(), HybridModelSetting())
        assertEquals(1, eps.size)
        assertEquals(2.5, eps[0].met!!, 1e-9)
    }

    @Test
    fun ringMET_winsOverDerivedMotion() {
        // Ring says resting; motion would have said 4 MET. The ring emits MET directly, so no
        // transfer function (and none of its uncertainty) applies.
        val g = (0 until 60).map { gravity(winStart + it * 60L, 0.1) }
        val ring = (0 until 60).map { (winStart + it * 60L to 1.0) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g, ringMET = ring,
            profile = noFallbackProfile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(0.0, rEnergy.kcalFrom(Label.MOTION_ACTIVE), 1e-9)
        assertEquals(60.0, rEnergy.stat(Extra.REST_MINUTES), 1e-9)
        assertEquals(1.0, rEnergy.stat(Extra.MEAN_MET), 1e-9)
    }

    @Test
    fun nullDynAccel_isSkippedNotTreatedAsZero() {
        // Every WHOOP 4.0 record reads back null here. Those minutes are non-wear, not "still".
        val g = (0 until 60).map { gravity(winStart + it * 60L, null) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g,
            profile = noFallbackProfile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(0.0, rEnergy.stat(Extra.COVERAGE_MINUTES), 1e-9)
        assertEquals(1440.0, rEnergy.stat(Extra.NON_WEAR_MINUTES), 1e-9)
        assertEquals(0.0, rEnergy.stat(Extra.REST_MINUTES), 1e-9)
    }

    // ---- Hybrid arbitration: one path per epoch, never both ----

    @Test
    fun workoutEpochs_areScoredByHeartRateNotMotion() {
        // 30 minutes of hard motion AND hard HR, entirely inside a declared workout window.
        val g = (0 until 30).map { gravity(winStart + it * 60L, 0.15) }
        val h = (0 until 30 * 2).map { hr(winStart + it * 30L, 150) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g, hr = h,
            workouts = listOf(winStart until winStart + 1_800L),
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        // Motion contributed NOTHING there — heart rate owns the epoch.
        assertEquals(0.0, rEnergy.kcalFrom(Label.MOTION_ACTIVE), 1e-9)
        assertTrue("HR should have scored the workout", rEnergy.kcalFrom(Label.WORKOUT) > 0.0)
        assertEquals(rEnergy.kcalFrom(Label.WORKOUT), rEnergy.dayActiveKcal, 1e-9)
        // The MET series is still reported for those minutes; only the ENERGY path changed.
        assertEquals(30.0, rEnergy.stat(Extra.COVERAGE_MINUTES), 1e-9)
        assertEquals(5.5, rEnergy.stat(Extra.MAX_MET), 1e-9)
    }

    @Test
    fun sameMinutesOutsideAWorkout_scoreByMotionInstead() {
        val g = (0 until 30).map { gravity(winStart + it * 60L, 0.15) }
        val h = (0 until 30 * 2).map { hr(winStart + it * 30L, 150) }
        val withWorkoutEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g, hr = h,
            workouts = listOf(winStart until winStart + 1_800L),
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        val withoutEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g, hr = h,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        // 30 min at 5.5 MET: (5.5-1) x 80 x (1/60) = 6.0 kcal/min -> 180 kcal.
        assertEquals(180.0, withoutEnergy.kcalFrom(Label.MOTION_ACTIVE), 1e-6)
        assertEquals(0.0, withoutEnergy.kcalFrom(Label.WORKOUT), 1e-9)
        // Both paths are non-zero and neither day double-counts: active == exactly one path's total.
        assertEquals(withoutEnergy.kcalFrom(Label.MOTION_ACTIVE), withoutEnergy.dayActiveKcal, 1e-9)
        assertEquals(withWorkoutEnergy.kcalFrom(Label.WORKOUT), withWorkoutEnergy.dayActiveKcal, 1e-9)
    }

    @Test
    fun basalIsNeverDoubleCountedByEitherPath() {
        // Identity: total == basal + active, for a day that exercises both scoring paths.
        val g = (0 until 120).map { gravity(winStart + it * 60L, 0.12) }
        val h = (0 until 240).map { hr(winStart + it * 30L, 140) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g, hr = h,
            workouts = listOf(winStart until winStart + 1_800L),
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(rEnergy.dayBasalKcal + rEnergy.dayActiveKcal, rEnergy.dayTotalKcal, 1e-9)
        assertEquals(rEnergy.kcalFrom(Label.WORKOUT) + rEnergy.kcalFrom(Label.MOTION_ACTIVE) + rEnergy.kcalFrom(Label.HR_FALLBACK), rEnergy.dayActiveKcal, 1e-9)
        assertEquals(bmrPerDay, rEnergy.dayBasalKcal, 0.01)
        assertTrue(rEnergy.kcalFrom(Label.WORKOUT) > 0.0)
        assertTrue(rEnergy.kcalFrom(Label.MOTION_ACTIVE) > 0.0)
    }

    @Test
    fun provenanceOfEveryKcalIsReportedSeparately() {
        // A day with all three sources: a workout (HR), covered motion outside it (MET), and a
        // later stretch with HR but no motion at all (fallback).
        val g = (0 until 120).map { gravity(winStart + it * 60L, 0.12) }
        val h = (0 until 720).map { hr(winStart + it * 30L, 140) }   // 6 h of HR
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g, hr = h,
            workouts = listOf(winStart until winStart + 1_800L),
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        assertTrue("workout window scored by HR", rEnergy.kcalFrom(Label.WORKOUT) > 0.0)
        assertTrue("covered non-workout minutes scored by MET", rEnergy.kcalFrom(Label.MOTION_ACTIVE) > 0.0)
        assertTrue("uncovered minutes with HR scored by fallback", rEnergy.kcalFrom(Label.HR_FALLBACK) > 0.0)
        assertEquals(rEnergy.kcalFrom(Label.WORKOUT) + rEnergy.kcalFrom(Label.MOTION_ACTIVE) + rEnergy.kcalFrom(Label.HR_FALLBACK), rEnergy.dayActiveKcal, 1e-9)
    }

    // ---- Degradation when there is no motion channel at all (WHOOP 4.0) ----

    @Test
    fun noMotionChannel_fallsBackToHeartRateByDefault() {
        // A WHOOP 4.0 day: HR all day, dynAccel null everywhere.
        val h = (0 until 2880).map { hr(winStart + it * 30L, 120) }
        val fallbackEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, hr = h,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        val basalOnlyEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, hr = h,
            profile = noFallbackProfile, hrmax = hrmax, restingHR = restingHR,
        )
        assertTrue("HR fallback must keep a 4.0 strap scoring", fallbackEnergy.dayActiveKcal > 0.0)
        // ...and every kcal of it is reported as fallback, not as motion-derived.
        assertEquals(fallbackEnergy.dayActiveKcal, fallbackEnergy.kcalFrom(Label.HR_FALLBACK), 1e-9)
        assertEquals(0.0, fallbackEnergy.kcalFrom(Label.MOTION_ACTIVE), 1e-9)
        assertEquals(0.0, basalOnlyEnergy.dayActiveKcal, 1e-9)
        assertEquals(bmrPerDay, basalOnlyEnergy.dayTotalKcal, 0.01)
    }

    @Test
    fun hrFallback_respectsTheDayGateAndDoesNotFireAtRestingHr() {
        // Resting HR 55 with hrmax 185 -> the 10% HRR gate sits at 68 bpm. A day spent at 60 bpm
        // is entirely below it, so the fallback adds nothing and the day is pure basal.
        val h = (0 until 2880).map { hr(winStart + it * 30L, 60) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, hr = h,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(0.0, rEnergy.dayActiveKcal, 1e-9)
        assertEquals(bmrPerDay, rEnergy.dayTotalKcal, 0.01)
    }

    @Test
    fun hrFallback_isCappedByTheGapLimitNotTheWholeDay() {
        // Two isolated elevated samples an hour apart must not each bill an hour of effort.
        val h = listOf(hr(winStart + 60L, 150), hr(winStart + 3_660L, 150))
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, hr = h,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        // Each sample is clipped to its own 60 s epoch, so at most ~2 minutes of surplus is credited.
        assertTrue("surplus must be gap-capped", rEnergy.dayActiveKcal < 30.0)
        assertTrue(rEnergy.dayActiveKcal > 0.0)
    }

    // ---- Coverage bookkeeping ----

    @Test
    fun coverageAndNonWearAlwaysSumToTheWindow() {
        val g = (0 until 300).map { gravity(winStart + it * 60L, 0.05) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        assertEquals(300.0, rEnergy.stat(Extra.COVERAGE_MINUTES), 1e-9)
        assertEquals(1140.0, rEnergy.stat(Extra.NON_WEAR_MINUTES), 1e-9)
        assertEquals(1440.0, rEnergy.stat(Extra.COVERAGE_MINUTES) + rEnergy.stat(Extra.NON_WEAR_MINUTES), 1e-9)
    }

    @Test
    fun bandMinutesSumToCoverage() {
        val g = (0 until 200).map { gravity(winStart + it * 60L, 0.0) } +
            (200 until 400).map { gravity(winStart + it * 60L, 0.1) } +
            (400 until 500).map { gravity(winStart + it * 60L, 0.25) }
        val rEnergy = Calories.estimateDayEnergy(
            localMidnightUtc = localMidnight, gravity = g,
            profile = profile, hrmax = hrmax, restingHR = restingHR,
        )
        val banded = rEnergy.stat(Extra.REST_MINUTES) + rEnergy.stat(Extra.INACTIVE_MINUTES) + rEnergy.stat(Extra.LOW_MINUTES) +
            rEnergy.stat(Extra.MEDIUM_MINUTES) + rEnergy.stat(Extra.HIGH_MINUTES)
        assertEquals(rEnergy.stat(Extra.COVERAGE_MINUTES), banded, 1e-9)
        assertEquals(500.0, banded, 1e-9)
        assertEquals(200.0, rEnergy.stat(Extra.REST_MINUTES), 1e-9)     // 1.0 MET
        assertEquals(200.0, rEnergy.stat(Extra.MEDIUM_MINUTES), 1e-9)   // 4.0 MET
        assertEquals(100.0, rEnergy.stat(Extra.HIGH_MINUTES), 1e-9)     // 8.5 MET
    }
}
