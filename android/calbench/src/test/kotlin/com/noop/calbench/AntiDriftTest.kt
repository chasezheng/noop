package com.noop.calbench

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieModels
import com.noop.analytics.calorie.exclusiveEnd
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The harness and the app estimate the same day from the same streams and must agree to the last bit.
 *
 * A figure computed here is only worth reading if it is the one the app would have stored. The harness
 * compiles the shipped sources rather than porting them, so the risk is not the arithmetic but the
 * wiring: which port method feeds which parameter. This pins that wiring, so changing a resolution in
 * [BackupCalorieStreams] moves one side.
 *
 * The two sides are given identical inputs on purpose. `analyzeDay` is handed the very
 * [com.noop.analytics.calorie.CalorieInputs] the port resolved, with its sleep and workout-detection
 * streams left empty, so the only thing under comparison is the energy block. Anything else would be
 * testing the detector.
 */
class AntiDriftTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = "2026-03-10"

    /** [TestStore.MANIFEST]'s `exportedAt`, in seconds — what the run clamps a day in progress at. */
    private val nowUtc = 1_788_045_497L
    private val tzOffsetSeconds = zone.rules.getOffset(Instant.ofEpochSecond(nowUtc)).totalSeconds.toLong()
    private val windowStart = AnalyticsEngine.dayStartUtcSeconds(day) - tzOffsetSeconds

    @Test
    fun `every model agrees with analyzeDay to the bit`() = withTempDir { dir ->
        val backup = TestStore()
            .device("my-whoop")
            .restingHr("my-whoop-noop", day, 52)
            .also { seedStreams(it) }
            .writeNoopbak(File(dir, "one.noopbak"), settings = SETTINGS)
            .let { BackupSource.open(it) }

        backup.use { source ->
            assertEquals("the run clamps at the export instant", nowUtc, Runner.nowUtcFor(source))
            val streams = BackupCalorieStreams(source, zone, nowUtc)
            runBlocking {
                val whole = ActivityDay.atLocalMidnight(streams.localMidnightUtc(streams.activityDay(day))).window()
                val window = whole.first until minOf(whole.exclusiveEnd, nowUtc)
                assertEquals("the activity day opens at local midnight", windowStart, window.first)
                val inputs = streams.load(streams.activityDay(day), window)
                val vitals = streams.vitals(streams.activityDay(day), source.profile, source.maxHROverride)
                assertEquals("the port resolved the day's own computed resting HR", 52.0, vitals.restingHR)
                assertTrue("the day has streams to estimate from", inputs.hr.size > 100 && inputs.gravity.size > 100)
                assertTrue("the day has beat coverage to estimate from", inputs.rr.size > 100)

                val perModel = LinkedHashMap<String, Double>()
                for (model in CalorieModels.order) {
                    val profile = source.profile.copy(calorieModel = model)
                    val harness =
                        CalorieModels.timeline(model, profile, vitals, window.first, window.exclusiveEnd, inputs)

                    val shipped = AnalyticsEngine.analyzeDay(
                        day = day,
                        // Empty, so no sleep is staged and no workout is detected: the day's resting HR
                        // and its workout windows then come from where the harness got them, and the
                        // energy block is the only thing left to disagree about.
                        hr = emptyList(),
                        dayHr = emptyList(),
                        dayGravity = emptyList(),
                        calorieHr = inputs.hr,
                        calorieGravity = inputs.gravity,
                        calorieBeats = inputs.rr,
                        recordedWorkouts = inputs.workouts,
                        calorieRestingHR = vitals.restingHR,
                        profile = profile,
                        maxHROverride = source.maxHROverride,
                        tzOffsetSeconds = tzOffsetSeconds,
                        nowTs = nowUtc,
                    ).daily.activeKcalEst

                    assertNotNull("$model: analyzeDay scored the day", shipped)
                    val active = harness.dayActiveKcal
                    assertTrue("$model: the day earns above basal", active > 0.0)
                    assertEquals("$model: harness vs analyzeDay", shipped!!, active, 0.0)
                    perModel[model.id] = active
                }
                // Two models returning the same figure would let a wiring bug that starved one of
                // them pass every assertion above.
                assertEquals("all three models are distinguishable", 3, perModel.values.toSet().size)
            }
        }
    }

    /**
     * Three hours recorded a second at a time: still, then working, then still again, with a workout
     * inside the working hour.
     *
     * Every model must find something to estimate here and they must disagree with EACH OTHER, so a
     * run where two of them happened to return the same figure cannot pass by accident. One sample a
     * second, with beats, because the measured-basal model estimates nothing sparser.
     */
    private fun seedStreams(store: TestStore) {
        val start = windowStart
        for (second in 0 until 3 * 3_600) {
            val ts = start + second
            val working = second in 3_600 until 7_200
            store.hr("my-whoop", ts, if (working) 100 + (second % 45) else 55 + (second % 3))
            store.gravity("my-whoop", ts, if (working) 0.05 + (second % 7) * 0.01 else 0.0)
            store.beat("my-whoop", ts)
        }
        store.workout("my-whoop", start + 4_200L, start + 6_000L)
    }

    companion object {
        private const val SETTINGS = """
            {
              "profile.age": 34,
              "profile.sex": "male",
              "profile.weightKg": 78.5,
              "profile.heightCm": 181.0,
              "profile.hrMax": 0,
              "profile.vo2max": 0.0,
              "calorie.model": "hybrid",
              "calorie.dayActiveHRRFraction": 0.1,
              "calorie.activeAccrualMET": 1.5,
              "calorie.dynAccelMETGainPerG": 30.0
            }
        """
    }
}
