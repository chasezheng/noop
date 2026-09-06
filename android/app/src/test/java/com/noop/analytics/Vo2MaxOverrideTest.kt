package com.noop.analytics

import com.noop.analytics.calorie.Calories
import com.noop.analytics.calorie.HybridModel
import com.noop.analytics.calorie.kcalFrom
import com.noop.data.HrSample
import com.noop.data.Vo2MaxEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A measured VO₂max replaces the heart-rate-ratio inference in every path that prices energy from
 * heart rate.
 *
 * The inference is Uth–Sørensen, `15.3 × HRmax / restingHR`. It swings hard on a resting HR the wearer
 * never measured directly, and it feeds Keytel's fitness-adjusted equation, so the whole day moves with
 * it. An override that reached only one of the three estimators would make a day disagree with the
 * workouts inside it.
 */
class Vo2MaxOverrideTest {

    private val subject = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
    private val hrmax = 184.0
    private val restingHR = 48.0

    private fun measured(vo2max: Double) = subject.copy(vo2maxOverride = vo2max)

    private fun hrRun(bpm: Int, seconds: Int, from: Long = 0L): List<HrSample> =
        (0 until seconds).map { HrSample(deviceId = "t", ts = from + it, bpm = bpm) }

    // ── The resolver ───────────────────────────────────────────────────────────────────────────

    @Test
    fun anUnsetOverride_fallsBackToTheHeartRateRatio() {
        assertEquals(
            Calories.vo2maxFor(hrmax, restingHR),
            Calories.vo2maxFor(subject, hrmax, restingHR),
        )
    }

    @Test
    fun aMeasuredValue_replacesTheHeartRateRatio() {
        assertEquals(42.0, Calories.vo2maxFor(measured(42.0), hrmax, restingHR)!!, 0.0)
    }

    @Test
    fun aMeasuredValue_appliesEvenWithNoRestingHeartRate() {
        // The inference needs a resting HR and returns null without one. A measured value does not.
        assertEquals(42.0, Calories.vo2maxFor(measured(42.0), hrmax, restingHR = null)!!, 0.0)
    }

    // ── Every heart-rate path honours it ───────────────────────────────────────────────────────

    @Test
    fun theHeartRateModel_scoresLowerWithALowerMeasuredVo2max() {
        val hr = hrRun(bpm = 130, seconds = 3_600)
        val inferred = Calories.estimateDayEnergy(hr, subject, hrmax, restingHR).dayActiveKcal
        val lower = Calories.estimateDayEnergy(hr, measured(45.0), hrmax, restingHR).dayActiveKcal
        // Uth infers 58.7 here, so a measured 45 must bring the day down.
        assertTrue(lower < inferred)
    }

    @Test
    fun theWorkoutEstimate_scoresLowerWithALowerMeasuredVo2max() {
        val hr = hrRun(bpm = 150, seconds = 3_600)
        val inferred = Calories.estimateBoutCalories(hr, subject, hrmax, restingHR).first
        val lower = Calories.estimateBoutCalories(hr, measured(45.0), hrmax, restingHR).first
        assertTrue(lower < inferred)
    }

    @Test
    fun theHybridsHeartRatePath_scoresLowerWithALowerMeasuredVo2max() {
        val from = 0L
        val hr = hrRun(bpm = 140, seconds = 3_600, from = from)
        val inferred = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + from, hr = hr,
            profile = subject, hrmax = hrmax, restingHR = restingHR,
        ).kcalFrom(HybridModel.Label.HR_FALLBACK)
        val lower = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + from, hr = hr,
            profile = measured(45.0), hrmax = hrmax, restingHR = restingHR,
        ).kcalFrom(HybridModel.Label.HR_FALLBACK)
        assertTrue("the fixture must earn fallback energy", inferred > 0.0)
        assertTrue(lower < inferred)
    }

    @Test
    fun theHybridsMotionPath_isUnaffected() {
        // Motion energy is MET-derived and carries no heart rate, so a VO₂max cannot move it. Worth
        // pinning: it is the term that dominates an ordinary day.
        val gravity = (0 until 3_600).map {
            com.noop.data.GravitySample(
                deviceId = "t", ts = 0L + it,
                x = 0.0, y = 0.0, z = 1.0, dynAccel = 0.10,
            )
        }
        val from = 0L
        val inferred = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + from, gravity = gravity,
            profile = subject, hrmax = hrmax, restingHR = restingHR,
        ).kcalFrom(HybridModel.Label.MOTION_ACTIVE)
        val lower = Calories.estimateDayEnergy(
            localMidnightUtc = 0L, nowUtc = 86_400L + from, gravity = gravity,
            profile = measured(45.0), hrmax = hrmax, restingHR = restingHR,
        ).kcalFrom(HybridModel.Label.MOTION_ACTIVE)
        assertTrue("the fixture must earn motion energy", inferred > 0.0)
        assertEquals(inferred, lower, 1e-12)
    }

    // ── The stored point names the method that produced it ─────────────────────────────────────

    @Test
    fun aMeasuredValue_isAttributedToTheWearerNotAnEstimator() {
        // The Health screen labels a VO₂max point by the method behind it. A measured value is not an
        // estimate, and must not be captioned as one whichever estimator the profile would have used.
        assertEquals(
            Vo2MaxEstimator.MEASURED,
            Vo2MaxEstimator.forProfile(waistCm = 0.0, vo2maxOverride = 42.0),
        )
        assertEquals(
            Vo2MaxEstimator.MEASURED,
            Vo2MaxEstimator.forProfile(waistCm = 88.0, vo2maxOverride = 42.0),
        )
    }

    @Test
    fun noMeasuredValue_keepsTheWaistRule() {
        // Without an override the estimator is chosen exactly as it was before the input existed.
        assertEquals(Vo2MaxEstimator.NES, Vo2MaxEstimator.forProfile(waistCm = 88.0, vo2maxOverride = 0.0))
        assertEquals(Vo2MaxEstimator.UTH, Vo2MaxEstimator.forProfile(waistCm = 0.0, vo2maxOverride = 0.0))
    }

}
