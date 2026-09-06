package com.noop.analytics.calorie

import com.noop.analytics.UserProfile
import com.noop.data.GravitySample
import com.noop.data.HrSample
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry contract: which models exist, in what order, and what each one reads.
 *
 * The numbers themselves are pinned by [CalorieCorpusTest]. What this file guards is the wiring
 * around them, which a golden cannot see: a model that stopped discarding an input, a variant that
 * went missing from the enumeration, an iteration order that flipped under the comparison card.
 */
class CalorieModelsTest {

    private val subject = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")
    private val vitals = CalorieVitals(hrmax = 185.0, restingHR = 55.0)

    /** Local midnight at UTC 0, so the activity window is [0, 86400). */
    private val start = 0L
    private val end = start + 86_400L
    private val utcDay = ActivityDay(LocalDate.of(1970, 1, 1), ZoneOffset.UTC)

    private fun hr(bpm: Int, seconds: Int, from: Long = start): List<HrSample> =
        (0 until seconds step 30).map { HrSample("t", from + it, bpm) }

    private fun motion(g: Double, seconds: Int, from: Long = start): List<GravitySample> =
        (0 until seconds step 60).map { GravitySample("t", from + it, 0.0, 0.0, 1.0, dynAccel = g) }

    private fun inputs() = CalorieInputs(
        hr = hr(bpm = 120, seconds = 6 * 3_600),
        gravity = motion(g = 0.05, seconds = 6 * 3_600),
        workouts = listOf(start + 3_600L until start + 5_400L),
    )

    private fun profileWith(setting: HybridModelSetting = HybridModelSetting()) =
        subject.copy(hybridModelSetting = setting)

    private fun timelineOf(model: EnergyModel, inputs: CalorieInputs, profile: UserProfile = subject) =
        CalorieModels.timeline(model, profile, vitals, start, end, inputs)

    // ── The enumeration ───────────────────────────────────────────────────────────────────────

    @Test
    fun everyModelBuildsItsOwnVariant() {
        // No model is asked what it is, so the guard is that no two cases of the `when` build the
        // same class, which is the shape a copy-pasted branch takes.
        val built = EnergyModel.values().map { CalorieModels.create(it, subject, vitals)::class }
        assertEquals(EnergyModel.values().size, built.toSet().size)
        assertTrue(CalorieModels.create(EnergyModel.HEART_RATE, subject, vitals) is KeytelModel)
        assertTrue(CalorieModels.create(EnergyModel.HYBRID, subject, vitals) is HybridModel)
    }

    @Test
    fun theRegistryEnumeratesEveryModelExactlyOnce() {
        assertEquals(EnergyModel.values().toSet(), CalorieModels.order.toSet())
        assertEquals(EnergyModel.values().size, CalorieModels.order.size)
        // The second dispatch, which no caller of the first would notice going wrong.
        for (model in EnergyModel.values()) {
            assertTrue("$model must score a span", timelineOf(model, inputs()).tsIndex.isNotEmpty())
        }
    }

    @Test
    fun theIterationOrderIsTheOneTheComparisonCardDraws() {
        // User-visible: the legend reads in this order and the curves take their tints from it.
        assertEquals(
            listOf(EnergyModel.HYBRID, EnergyModel.HEART_RATE, EnergyModel.DYNAMIC_HRR),
            CalorieModels.order,
        )
    }

    // ── What each variant reads ───────────────────────────────────────────────────────────────

    @Test
    fun theHeartRateModelDiscardsMotionAndWorkouts() {
        val withEverything = timelineOf(EnergyModel.HEART_RATE, inputs())
        val heartRateAlone = timelineOf(EnergyModel.HEART_RATE, CalorieInputs(hr = inputs().hr))
        assertEquals(heartRateAlone.dayActiveKcal, withEverything.dayActiveKcal, 0.0)
        // One series, and it is the one this model computes: a motion or workout term would be a
        // label for a path it never took.
        assertEquals(setOf(KeytelModel.Label.HEART_RATE), withEverything.labeledKcal.keys)
        assertTrue(
            "every minute must be estimated from heart rate",
            withEverything.kcalFrom(KeytelModel.Label.HEART_RATE) > 0.0,
        )
    }

    @Test
    fun theHeartRateModelPublishesNoMetOrAttribution() {
        // It computes neither, so it states neither: a MET series of nulls or a flat label would be a
        // reading no minute of this model ever produced.
        assertTrue(timelineOf(EnergyModel.HEART_RATE, inputs()).labeledSeries.isEmpty())
    }

    @Test
    fun theHybridReadsMotionAndWorkouts() {
        val scored = timelineOf(EnergyModel.HYBRID, inputs())
        assertTrue("motion must earn something", scored.kcalFrom(HybridModel.Label.MOTION_ACTIVE) > 0.0)
        assertTrue(
            "the workout window must be estimated from heart rate",
            scored.kcalFrom(HybridModel.Label.WORKOUT) > 0.0,
        )
    }

    @Test
    fun theHeartRateModelIgnoresHrFallbackWhenNoMET() {
        // The knob is a HYBRID one: it decides what becomes of a minute the motion path could not
        // cover. Route it into this model and every minute is labelled NO_DATA and the day stores a
        // flat zero. The hybrid beside it is the control — there, turning the knob off is the setting
        // working.
        val knobOff = HybridModelSetting(hrFallbackWhenNoMET = false)
        val onlyHeartRate = CalorieInputs(hr = inputs().hr)

        val heartRate = timelineOf(EnergyModel.HEART_RATE, onlyHeartRate, profileWith(knobOff))
        assertTrue("a knobs-off wearer's heart-rate day collapsed to basal", heartRate.dayActiveKcal > 0.0)

        val hybrid = timelineOf(EnergyModel.HYBRID, onlyHeartRate, profileWith(knobOff))
        assertEquals("with no motion at all the hybrid must honour it", 0.0, hybrid.dayActiveKcal, 0.0)
        assertTrue(
            "and it must say so: every minute is uncovered",
            hybrid.labeledSeries.getValue(HybridModel.Label.SOURCE)
                .all { it == HybridModel.Source.NO_DATA.ordinal.toDouble() },
        )
    }

    @Test
    fun aSampleStampedAfterTheSpan_neitherEarnsNorLendsItsGap() {
        // A deliberate decision rather than a side effect of the clip. A row timestamped past the end
        // of the span cannot describe time that has elapsed, so it earns nothing — and, because the
        // model sizes each sample's weight from the NEXT one's timestamp, it must not lend the last
        // real sample a two-minute gap either. Only a clock-skewed strap writes one.
        val midday = start + 43_200L
        val real = hr(bpm = 120, seconds = 6 * 3_600)
        val skewed = real + HrSample("t", midday + 30L, 120)
        val model = HybridModel(subject, vitals)
        assertEquals(
            model.timeline(start, midday, CalorieInputs(hr = real)).dayActiveKcal,
            model.timeline(start, midday, CalorieInputs(hr = skewed)).dayActiveKcal,
            0.0,
        )
    }

    // ── The two entry points ──────────────────────────────────────────────────────────────────

    @Test
    fun theMinuteSeriesIsTheSamePassAsTheTotals() {
        val model = HybridModel(subject, vitals)
        val span = model.timeline(start, end, inputs())
        val day = model.timeline(utcDay, inputs())
        assertArrayEquals(span.tsIndex, day.tsIndex)
        assertEquals(span.activeKcal.toList(), day.activeKcal.toList())
        // What [CalorieDayScorer] stores is the sum of those minutes, so the day figure has to be it.
        assertEquals(day.dayActiveKcal, day.activeKcal.sum(), 0.0)
    }

    @Test
    fun theMinuteSeriesCreditsBasalOverTheWholeWindow() {
        // Per minute, resting is the total less the active term; over the window it is the day's own
        // Harris-Benedict figure. The two agree to the reassociation of 1 440 additions and no more.
        val model = CalorieModels.create(EnergyModel.HYBRID, subject, vitals)
        val day = model.timeline(utcDay, inputs())
        val basal = day.tsIndex.indices.sumOf { day.totalKcal[it] - day.activeKcal[it] }
        assertEquals(Calories.basalKcalForSpan(subject, (end - start).toDouble()), basal, 1e-6)
    }

    @Test
    fun theDatabaseEntryPointScoresWhatThePortHandsIt() {
        // Same day, same streams, reached through the port rather than passed in: the two entry
        // points must not be able to answer differently.
        val port = PreloadedCalorieStreams(localMidnightUtc = 0L, owner = "t", inputs = inputs(), vitals = vitals)
        val model = HybridModel(subject, vitals, port)
        val throughThePort = runBlocking { model.timeline(utcDay) }
        assertEquals(model.timeline(start, end, inputs()).dayActiveKcal, throughThePort.dayActiveKcal, 0.0)
    }

    @Test
    fun theDatabaseEntryPointClampsTheWindowAtTheClock() {
        // Eighteen hours of heart rate, so a third of it falls after midday. Six would not catch a
        // dropped clock: every sample would precede the clamp and only basal would move.
        val spanning = CalorieInputs(hr = hr(bpm = 120, seconds = 18 * 3_600))
        val port = PreloadedCalorieStreams(localMidnightUtc = 0L, inputs = spanning, vitals = vitals)
        val midday = start + 43_200L
        val whole = HybridModel(subject, vitals, port)
        val inProgress = HybridModel(subject, vitals, port, midday)
        // The window is not public, so the clamp is asserted where it shows: how many minutes the
        // model answers for.
        assertEquals(1_440, whole.timeline(utcDay, spanning).tsIndex.size)
        assertEquals(720, inProgress.timeline(utcDay, spanning).tsIndex.size)
        val wholeKcal = runBlocking { whole.timeline(utcDay) }.dayActiveKcal
        val inProgressKcal = runBlocking { inProgress.timeline(utcDay) }.dayActiveKcal
        assertTrue("a day in progress cannot score hours that have not happened", inProgressKcal < wholeKcal)
    }

    @Test
    fun theWindowIsTheCalendarDay() {
        val port = PreloadedCalorieStreams(localMidnightUtc = 0L, inputs = inputs(), vitals = vitals)
        val model = HybridModel(subject, vitals, port)
        val minutes = model.timeline(utcDay, inputs()).tsIndex
        assertEquals("the day opens at local midnight", 0L, minutes.first())
        assertEquals(1_440, minutes.size)
        assertEquals(0L until 86_400L, utcDay.window())
    }
}
