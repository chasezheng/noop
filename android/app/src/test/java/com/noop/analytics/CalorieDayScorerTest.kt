package com.noop.analytics

import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieDayScorer
import com.noop.analytics.calorie.EnergyModel
import com.noop.analytics.calorie.HybridModelSetting
import com.noop.data.GravitySample
import com.noop.data.HrSample
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CalorieDayScorer.score] is the only call site that produces `DailyMetric.activeKcalEst`, and until
 * this file existed nothing tested it: every pinned kcal literal in the suite calls the models
 * directly, so a wrong call HERE passed the whole suite.
 *
 * That matters because the estimators it dispatches to take defaulted parameters. Dropping `gravity`
 * reduces the hybrid to its heart-rate fallback, dropping `workouts` zeroes the workout term, and
 * dropping `nowUtc` books a full 24 h of basal on a day that is half over — each of which compiles.
 * The relational tests below are the guard: they fail if an argument stops arriving, without
 * depending on what the arithmetic downstream happens to produce.
 *
 * Standard subject: 80 kg / 180 cm / 35 y male, HRmax 185, resting 55.
 * Local midnight at UTC 0, so the activity window is [0, 86400).
 */
class CalorieDayScorerTest {

    private val subject = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
    private val hybrid = subject.copy(calorieModel = EnergyModel.HYBRID)
    private val heartRate = subject.copy(calorieModel = EnergyModel.HEART_RATE)

    private val hrmax = 185.0
    private val restingHR = 55.0
    private val day = ActivityDay(LocalDate.of(1970, 1, 1), ZoneOffset.UTC)
    private val winStart = 0L

    /** Noon of the activity day: half the window has elapsed. */
    private val midday = winStart + 43_200L

    /** Six hours of heart rate at 30 s cadence, the 5/MG live rate. */
    private fun hr(bpm: Int, hours: Int = 6): List<HrSample> =
        (0 until hours * 120).map { HrSample(deviceId = "d", ts = winStart + it * 30L, bpm = bpm) }

    /** Six hours of motion at one sample a minute. 0.05 g is 2.5 MET, above the 1.5 accrual floor. */
    private fun motion(g: Double = 0.05, hours: Int = 6): List<GravitySample> =
        (0 until hours * 60).map {
            GravitySample(deviceId = "d", ts = winStart + it * 60L, x = 0.0, y = 0.0, z = 1.0, dynAccel = g)
        }

    private val workout = listOf(winStart + 3_600L until winStart + 5_400L)

    private fun score(
        profile: UserProfile,
        nowUtc: Long? = midday,
        hr: List<HrSample> = hr(100),
        gravity: List<GravitySample> = motion(),
        workouts: List<LongRange> = workout,
    ): Double? = CalorieDayScorer.score(
        day = day, nowUtc = nowUtc, hr = hr, gravity = gravity, workouts = workouts,
        beats = emptyList(), profile = profile, hrmax = hrmax, restingHR = restingHR,
    )?.activeKcal

    // ---- The stored figure, both branches ----

    @Test
    fun hybrid_scoresTheDayFromMotionWorkoutsAndHeartRate() {
        assertEquals(832.9406252136566, score(hybrid)!!, 1e-9)
    }

    @Test
    fun heartRate_scoresTheDayFromHeartRateAlone() {
        assertEquals(2072.5012369354226, score(heartRate)!!, 1e-9)
    }

    @Test
    fun theTwoModelsDisagreeWhenMotionIsPresent() {
        // If they ever match on this fixture the hybrid has stopped seeing its motion input.
        assertTrue(kotlin.math.abs(score(hybrid)!! - score(heartRate)!!) > 1.0)
    }

    // ---- Every argument must actually reach the estimator ----

    @Test
    fun gravityReachesTheHybrid() {
        // Withhold motion and the hybrid falls to heart rate, which estimates the same minutes higher.
        assertTrue(score(hybrid, gravity = emptyList())!! > score(hybrid)!!)
    }

    @Test
    fun workoutsReachTheHybrid() {
        // The window's minutes are priced by heart rate instead of motion, so the total moves.
        assertNotEquals(score(hybrid, workouts = emptyList())!!, score(hybrid)!!)
    }

    @Test
    fun nowUtcClampsTheWindowTheHybridScores() {
        // Eighteen hours of heart rate, so a third of it falls after midday. `nowUtc` clamps the
        // window end, so those hours must not be priced — a day in progress cannot book hours that
        // have not happened. The default six-hour fixture would not catch a dropped clock, and
        // neither would twelve: both end at or before midday, so every sample precedes the clamp.
        val spanning = hr(bpm = 100, hours = 18)
        val inProgress = score(hybrid, nowUtc = midday, hr = spanning)!!
        val complete = score(hybrid, nowUtc = null, hr = spanning)!!
        assertTrue("clamping at midday must score less than the whole day", inProgress < complete)
    }

    @Test
    fun profileReachesTheEstimator() {
        val heavier = hybrid.copy(weightKg = 120.0)
        assertTrue(score(heavier)!! > score(hybrid)!!)
    }

    @Test
    fun tuningReachesTheEstimator() {
        // Raising the accrual floor above the fixture's 2.5 MET must strip the motion term.
        val strict = hybrid.copy(hybridModelSetting = HybridModelSetting(activeAccrualMET = 3.0))
        assertTrue(score(strict)!! < score(hybrid)!!)
    }

    // ---- The no-data contract ----

    @Test
    fun aDayWithNoHeartRateInTheWindow_scoresNull() {
        assertNull(score(hybrid, hr = emptyList()))
        assertNull(score(heartRate, hr = emptyList()))
    }

    @Test
    fun heartRateOutsideTheWindow_doesNotCount() {
        val before = listOf(HrSample(deviceId = "d", ts = winStart - 60L, bpm = 150))
        assertNull(score(hybrid, hr = before))
    }

    @Test
    fun aDayWithHeartRateButNoMotion_stillScores() {
        assertNotNull(score(hybrid, gravity = emptyList(), workouts = emptyList()))
    }

    private fun assertNotEquals(a: Double, b: Double) =
        assertTrue("expected the two figures to differ; both were $a", kotlin.math.abs(a - b) > 1e-6)
}
