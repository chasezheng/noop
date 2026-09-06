package com.noop.analytics

import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieDayTrace
import com.noop.analytics.calorie.CalorieDayTraceBuilder
import com.noop.analytics.calorie.CalorieInputs
import com.noop.analytics.calorie.CalorieModels
import com.noop.analytics.calorie.CalorieTimeline
import com.noop.analytics.calorie.CalorieVitals
import com.noop.analytics.calorie.Calories
import com.noop.analytics.calorie.EnergyModel
import com.noop.analytics.calorie.HybridModel
import com.noop.analytics.calorie.HybridModelSetting
import com.noop.analytics.calorie.exclusiveEnd
import com.noop.analytics.calorie.kcalFrom
import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the calorie screen draws: the window and shapes [CalorieDayTraceBuilder] produces, and the
 * model output plotted over them.
 *
 * The builder estimates nothing. What it must get right is the window, the per-minute heart-rate
 * mean, the input counts and which workouts overlap the day — each of which the screen presents as
 * fact about the day.
 *
 * Attribution is pinned separately, because no sum can catch a mislabelled minute and the labels are
 * what the breakdown names its terms after.
 */
class CalorieDayTraceTest {

    private val subject = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")

    /** Local midnight of an arbitrary day, aligned to a minute so epochs land on round timestamps. */
    private val midnight = 1_700_000_000L / 60 * 60
    private val hrmax = 185.0
    private val restingHR = 55.0

    /** The moment the whole activity window has elapsed. */
    private val dayEnd = midnight + 86_400L

    private fun hrRun(bpm: Int, from: Long, seconds: Int): List<HrSample> =
        (0 until seconds).map { HrSample(deviceId = "test", ts = from + it, bpm = bpm) }

    private fun motionRun(g: Double?, from: Long, seconds: Int): List<GravitySample> =
        (0 until seconds).map {
            GravitySample(deviceId = "test", ts = from + it, x = 0.0, y = 0.0, z = 1.0, dynAccel = g)
        }

    private fun build(
        gravity: List<GravitySample> = emptyList(),
        hr: List<HrSample> = emptyList(),
        workouts: List<LongRange> = emptyList(),
        nowUtc: Long = dayEnd,
    ): CalorieDayTrace = CalorieDayTraceBuilder.build(
        localMidnightUtc = midnight, nowUtc = nowUtc, gravity = gravity, hr = hr, workouts = workouts,
    )

    /** The hybrid over the same window, which the screen draws when the wearer has selected it. */
    private fun hybrid(
        gravity: List<GravitySample> = emptyList(),
        hr: List<HrSample> = emptyList(),
        workouts: List<LongRange> = emptyList(),
        profile: UserProfile = subject,
    ): CalorieTimeline = CalorieModels.timeline(
        EnergyModel.HYBRID, profile, CalorieVitals(hrmax = hrmax, restingHR = restingHR),
        midnight, dayEnd, CalorieInputs(hr = hr, gravity = gravity, workouts = workouts),
    )

    /** The heart-rate model over the same window. */
    private fun heartRateModel(
        hr: List<HrSample> = emptyList(),
        profile: UserProfile = subject,
    ): CalorieTimeline = CalorieModels.timeline(
        EnergyModel.HEART_RATE, profile, CalorieVitals(hrmax = hrmax, restingHR = restingHR),
        midnight, dayEnd, CalorieInputs(hr = hr),
    )

    /** The minutes of `[from, from + seconds)`, as indices into the timeline's aligned series. */
    private fun minutesBetween(t: CalorieTimeline, from: Long, seconds: Int): List<Int> =
        t.tsIndex.indices.filter { t.tsIndex[it] >= from && t.tsIndex[it] < from + seconds }

    private fun sourcesBetween(t: CalorieTimeline, from: Long, seconds: Int): Set<HybridModel.Source> {
        val series = t.labeledSeries.getValue(HybridModel.Label.SOURCE)
        return minutesBetween(t, from, seconds)
            .map { HybridModel.Source.values()[series[it]!!.toInt()] }
            .toSet()
    }

    private fun neverDecreases(curve: List<Pair<Long, Double>>): Boolean =
        curve.zipWithNext().all { (a, b) -> b.second >= a.second }

    // ── The window ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theWindow_startsAtLocalMidnight() {
        assertEquals(midnight, build().start)
    }

    @Test
    fun anInProgressDay_endsAtThePresentSecond() {
        assertEquals(midnight + 15 * 3_600L, build(nowUtc = midnight + 15 * 3_600L).end)
    }

    @Test
    fun aCompletedDay_endsAtTheDaysOwnEnd() {
        // The clamp takes the earlier of the two, so a clock past the day must not extend it.
        assertEquals(dayEnd, build(nowUtc = dayEnd + 7_200L).end)
    }

    @Test
    fun theModelsCoverTheSameMinutesAsEachOther() {
        assertArrayEquals(hybrid().tsIndex, heartRateModel().tsIndex)
    }

    @Test
    fun theModelsCreditTheSameRestingEnergy() {
        // The screen shows resting for whichever model is selected, so two spans would give one day
        // two different resting figures.
        val hr = hrRun(bpm = 120, from = midnight + 8 * 3_600L, seconds = 3_600)
        val gravity = motionRun(g = 0.10, from = midnight + 8 * 3_600L, seconds = 3_600)
        assertEquals(
            hybrid(gravity = gravity, hr = hr).dayBasalKcal,
            heartRateModel(hr = hr).dayBasalKcal,
            1e-9,
        )
    }

    // ── The shapes the builder produces ────────────────────────────────────────────────────────

    @Test
    fun theHeartRateTrace_carriesOnePointPerMinuteThatHadASample() {
        val from = midnight + 9 * 3_600L
        val trace = build(hr = hrRun(bpm = 120, from = from, seconds = 180)).hrTrace
        assertEquals(listOf(from, from + 60L, from + 120L), trace.map { it.first })
    }

    @Test
    fun theHeartRateTrace_reportsTheMinutesMean() {
        val from = midnight + 9 * 3_600L
        val mixed = listOf(
            HrSample(deviceId = "test", ts = from, bpm = 100),
            HrSample(deviceId = "test", ts = from + 30L, bpm = 140),
        )
        assertEquals(120.0, build(hr = mixed).hrTrace.single().second, 1e-12)
    }

    @Test
    fun aMinuteWithNoSample_isAbsentFromTheTraceRatherThanZeroFilled() {
        // A zero-filled minute would draw the heart rate falling to nothing and climbing back.
        val from = midnight + 9 * 3_600L
        val split = hrRun(bpm = 120, from = from, seconds = 60) +
            hrRun(bpm = 120, from = from + 120L, seconds = 60)
        assertEquals(listOf(from, from + 120L), build(hr = split).hrTrace.map { it.first })
    }

    @Test
    fun aSampleOutsideTheWindow_reachesNeitherTheTraceNorTheCount() {
        val trace = build(
            hr = hrRun(bpm = 120, from = midnight - 600L, seconds = 600),
            nowUtc = midnight + 3_600L,
        )
        assertTrue(trace.hrTrace.isEmpty())
        assertEquals(0, trace.hrSampleCount)
    }

    @Test
    fun theSampleCounts_reportTheSamplesInsideTheWindow() {
        val from = midnight + 9 * 3_600L
        assertEquals(600, build(hr = hrRun(bpm = 120, from = from, seconds = 600)).hrSampleCount)
    }

    @Test
    fun aGravityRowWithNoMotionMagnitude_isNotCountedAsMotion() {
        // A WHOOP 4.0 reports rows with no `dynAccel` at all; counting them would report coverage no
        // model ever had.
        val from = midnight + 9 * 3_600L
        assertEquals(0, build(gravity = motionRun(g = null, from = from, seconds = 600)).motionSampleCount)
        assertEquals(600, build(gravity = motionRun(g = 0.10, from = from, seconds = 600)).motionSampleCount)
    }

    @Test
    fun aWorkoutOutsideTheWindow_isDropped() {
        val inside = midnight + 9 * 3_600L until midnight + 9 * 3_600L + 600L
        val before = midnight - 1_200L until midnight - 600L
        val after = dayEnd + 600L until dayEnd + 1_200L
        assertEquals(listOf(inside), build(workouts = listOf(before, inside, after)).workouts)
    }

    @Test
    fun aWorkoutStraddlingMidnight_keepsItsOwnBounds() {
        // Kept whole rather than cut at the window edge: the screen names the session's real hours,
        // and a session that began before midnight began before midnight.
        val straddling = midnight - 600L until midnight + 600L
        assertEquals(listOf(straddling), build(workouts = listOf(straddling)).workouts)
    }

    // ── Attribution ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMotionOnlyDay_earnsMotionEnergy() {
        val t = hybrid(gravity = motionRun(g = 0.10, from = midnight + 8 * 3_600L, seconds = 7_200))
        assertTrue(t.kcalFrom(HybridModel.Label.MOTION_ACTIVE) > 0.0)
    }

    @Test
    fun aWorkoutDay_earnsWorkoutEnergy() {
        val start = midnight + 9 * 3_600L
        val t = hybrid(
            gravity = motionRun(g = 0.05, from = midnight + 8 * 3_600L, seconds = 10_800),
            hr = hrRun(bpm = 145, from = start, seconds = 3_600),
            workouts = listOf(start until start + 3_600),
        )
        assertTrue(t.kcalFrom(HybridModel.Label.WORKOUT) > 0.0)
    }

    @Test
    fun aDayWithNoMotion_earnsFallbackEnergy() {
        // A WHOOP 4.0 shape: heart rate everywhere, no motion magnitude anywhere.
        val t = hybrid(hr = hrRun(bpm = 130, from = midnight + 10 * 3_600L, seconds = 3_600))
        assertTrue(t.kcalFrom(HybridModel.Label.HR_FALLBACK) > 0.0)
    }

    @Test
    fun aWorkoutMinute_isLabelledWorkoutEvenWhenMotionCoversIt() {
        // Motion data exists for the minute, so a naive labeller calls it motion — but the hybrid
        // scored it by heart rate, and the breakdown would name the wrong term.
        val start = midnight + 9 * 3_600L
        val t = hybrid(
            gravity = motionRun(g = 0.20, from = start, seconds = 600),
            hr = hrRun(bpm = 150, from = start, seconds = 600),
            workouts = listOf(start until start + 600),
        )
        assertEquals(setOf(HybridModel.Source.WORKOUT), sourcesBetween(t, start, 600))
    }

    @Test
    fun aWorkoutMinutesMotion_doesNotReachTheMotionTerm() {
        val start = midnight + 9 * 3_600L
        val t = hybrid(
            gravity = motionRun(g = 0.20, from = start, seconds = 600),
            hr = hrRun(bpm = 150, from = start, seconds = 600),
            workouts = listOf(start until start + 600),
        )
        assertEquals(0.0, t.kcalFrom(HybridModel.Label.MOTION_ACTIVE), 1e-12)
    }

    @Test
    fun motionBelowTheAccrualFloor_isLabelledIdle() {
        // 0.01 g reaches 1.3 MET, under the 1.5 floor.
        val from = midnight + 8 * 3_600L
        val t = hybrid(gravity = motionRun(g = 0.01, from = from, seconds = 600))
        assertEquals(setOf(HybridModel.Source.MOTION_IDLE), sourcesBetween(t, from, 600))
    }

    @Test
    fun motionBelowTheAccrualFloor_earnsNothing() {
        val from = midnight + 8 * 3_600L
        val t = hybrid(gravity = motionRun(g = 0.01, from = from, seconds = 600))
        assertEquals(0.0, minutesBetween(t, from, 600).sumOf { t.activeKcal[it] }, 1e-12)
    }

    @Test
    fun aMinuteWithNoMotion_isLabelledFallbackWhenTheFallbackIsAllowed() {
        val from = midnight + 10 * 3_600L
        val t = hybrid(hr = hrRun(bpm = 130, from = from, seconds = 600))
        assertEquals(setOf(HybridModel.Source.HR_FALLBACK), sourcesBetween(t, from, 600))
    }

    @Test
    fun aMinuteWithNoMotion_isLabelledNoDataWhenTheFallbackIsOff() {
        val from = midnight + 10 * 3_600L
        val t = hybrid(
            hr = hrRun(bpm = 130, from = from, seconds = 600),
            profile = subject.copy(hybridModelSetting = HybridModelSetting(hrFallbackWhenNoMET = false)),
        )
        assertEquals(setOf(HybridModel.Source.NO_DATA), sourcesBetween(t, from, 600))
    }

    @Test
    fun aMinuteWithNoMotion_earnsNothingWhenTheFallbackIsOff() {
        val t = hybrid(
            hr = hrRun(bpm = 130, from = midnight + 10 * 3_600L, seconds = 600),
            profile = subject.copy(hybridModelSetting = HybridModelSetting(hrFallbackWhenNoMET = false)),
        )
        assertEquals(0.0, t.dayActiveKcal, 1e-12)
    }

    @Test
    fun theHeartRateModel_stillScoresWhenTheMotionFallbackKnobIsOff() {
        // hrFallbackWhenNoMET is a HYBRID knob: it governs minutes the motion path could not cover.
        // The heart-rate model has no motion path at all, so honouring it labelled every minute
        // NO_DATA and drew a flat zero beside a Today tile showing the real figure.
        val off = subject.copy(hybridModelSetting = HybridModelSetting(hrFallbackWhenNoMET = false))
        val heartRate = heartRateModel(
            hr = hrRun(bpm = 120, from = midnight + 9 * 3_600L, seconds = 3_600), profile = off,
        )
        assertTrue("the heart-rate model must estimate its own minutes", heartRate.dayActiveKcal > 0.0)
    }

    @Test
    fun theSampleOverload_matchesTheRegistryOverTheSameWindow() {
        // Two ways into the same model: from a run of samples and a span, and from the registry with
        // an explicit window. Handed the same window they must agree, or a caller's choice of entry
        // point would decide the wearer's figure.
        val hr = hrRun(bpm = 120, from = midnight, seconds = 7_200)
        val window = ActivityDay.atLocalMidnight(midnight).window()
        val direct = Calories.estimateDayEnergy(
            hrSamples = hr, profile = subject, hrmax = hrmax, restingHR = restingHR,
            spanS = (minOf(dayEnd, window.exclusiveEnd) - window.first).toDouble(),
        )
        val heartRate = heartRateModel(hr = hr)
        assertEquals(direct.dayActiveKcal, heartRate.dayActiveKcal, 1e-9)
        assertEquals(direct.dayBasalKcal, heartRate.dayBasalKcal, 1e-9)
    }

    // ── The cumulative curve ───────────────────────────────────────────────────────────────────

    @Test
    fun cumulative_ofADayWithNoMinutes_isEmpty() {
        assertTrue(CalorieDayTraceBuilder.cumulative(LongArray(0), DoubleArray(0)).isEmpty())
    }

    @Test
    fun cumulative_hasOnePointPerMinute() {
        val t = hybrid(gravity = motionRun(g = 0.12, from = midnight + 8 * 3_600L, seconds = 3_600))
        assertEquals(t.tsIndex.size, cumulative(t).size)
    }

    @Test
    fun cumulative_climbsThroughTheActiveHourAndHoldsAfterIt() {
        // Monotonicity alone is free — the per-minute figures are never negative. What the curve has to
        // get right is WHERE it climbs: flat before the hour that moved, flat after it, and ending on
        // the same total the estimator reports.
        val from = midnight + 8 * 3_600L
        val t = hybrid(
            gravity = motionRun(g = 0.12, from = from, seconds = 3_600),
            hr = hrRun(bpm = 120, from = from, seconds = 3_600),
        )
        val curve = cumulative(t)
        assertTrue(neverDecreases(curve))
        fun valueAt(ts: Long) = curve.last { it.first <= ts }.second
        assertEquals("nothing before the day's first movement", 0.0, valueAt(from - 60), 1e-9)
        assertTrue("the active hour must move the curve", valueAt(from + 3_600) > 0.0)
        assertEquals("and nothing after it", valueAt(from + 3_600), valueAt(dayEnd - 60), 1e-9)
    }

    private fun cumulative(t: CalorieTimeline): List<Pair<Long, Double>> =
        CalorieDayTraceBuilder.cumulative(t.tsIndex, t.activeKcal)
}
