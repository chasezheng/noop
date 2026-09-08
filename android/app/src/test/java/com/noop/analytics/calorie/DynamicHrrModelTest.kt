package com.noop.analytics.calorie

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.UserProfile
import com.noop.data.GravitySample
import com.noop.data.HrSample
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measured-basal model over whole days.
 *
 * Standard subject: 80 kg / 180 cm / 35 y male, maximum 180 bpm, resting 50. Local midnight at UTC 0,
 * so the activity window is `[0, 86400)`. Peak suppression is off unless a test is about it, so a
 * blanked block cannot move a figure the test is not measuring.
 */
class DynamicHrrModelTest {

    private val setting = DynamicHrrModelSetting(peakClipEnabled = false)
    private val subject = UserProfile(
        weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male",
        dynamicHrrModelSetting = setting,
    )
    private val vitals = CalorieVitals(hrmax = 180.0, restingHR = 50.0)
    private val day = ActivityDay(LocalDate.of(1970, 1, 1), ZoneOffset.UTC)

    private fun hr(from: Long, seconds: Int, bpm: Int, cadenceS: Int = 1): List<HrSample> =
        (0 until seconds / cadenceS).map {
            HrSample(deviceId = "d", ts = from + it.toLong() * cadenceS, bpm = bpm)
        }

    private fun motion(from: Long, seconds: Int, g: Double?, cadenceS: Int = 1): List<GravitySample> =
        (0 until seconds / cadenceS).map {
            GravitySample(
                deviceId = "d", ts = from + it.toLong() * cadenceS,
                x = 0.0, y = 0.0, z = 1.0, dynAccel = g,
            )
        }

    private fun beats(from: Long, seconds: Int): List<Pair<Long, Int>> =
        (0 until seconds).map { (from + it.toLong()) to 1 }

    private fun score(
        inputs: CalorieInputs,
        profile: UserProfile = subject,
        anchors: CalorieVitals = vitals,
    ): CalorieTimeline = DynamicHrrModel(profile, anchors).timeline(0L, 86_400L, inputs)

    /** An hour still at 50 bpm, recorded a second at a time, with motion and beats throughout. */
    private val quietHour = CalorieInputs(
        hr = hr(0L, 3_600, 50),
        gravity = motion(0L, 3_600, 0.0),
        rr = beats(0L, 3_600),
    )

    /** The same hour at the live Bluetooth cadence of one reading every thirty seconds. */
    private val sparseHour = quietHour.copy(hr = hr(0L, 3_600, 50, cadenceS = 30))

    /** A quiet hour at 50 bpm, then an hour of effort at [bpm] the wearer is plainly not still for. */
    private fun quietThenEffortAt(bpm: Int) = CalorieInputs(
        hr = hr(0L, 3_600, 50) + hr(3_600L, 3_600, bpm),
        gravity = motion(0L, 3_600, 0.0) + motion(3_600L, 3_600, 0.5),
        rr = beats(0L, 7_200),
    )

    private fun active(timeline: CalorieTimeline): Double =
        timeline.dayActiveKcal

    private fun quietWindows(timeline: CalorieTimeline): Double =
        timeline.extras.getValue(DynamicHrrModel.Extra.QUIET_WINDOW_COUNT)

    private fun declined(timeline: CalorieTimeline): Boolean =
        timeline.extras[CalorieTimeline.DECLINED] == 1.0

    /** No stretch measured a rate, so the day was anchored on the seed rather than on a reading. */
    private fun measuredNothing(timeline: CalorieTimeline): Boolean =
        quietWindows(timeline) == 0.0 &&
            CalorieTimeline.MEASURED_BASAL_HR_BPM !in timeline.extras

    private fun coverage(timeline: CalorieTimeline): Double =
        timeline.extras.getValue(DynamicHrrModel.Extra.HR_COVERAGE_FRAC)

    private fun basalHr(timeline: CalorieTimeline): Double =
        timeline.extras.getValue(CalorieTimeline.MEASURED_BASAL_HR_BPM)

    // ── The basal heart rate the day settles on ───────────────────────────────────────────────

    @Test
    fun aQuietHour_measuresTheBasalHeartRateItWasHeldAt() {
        val timeline = score(quietHour)

        assertEquals(50.0, timeline.extras.getValue(CalorieTimeline.MEASURED_BASAL_HR_BPM), 0.0)
    }

    @Test
    fun aHeartRateAtOrBelowTheBasalRate_earnsNothingAboveResting() {
        // The property that makes the measured rate the basal rate. Asserted rather than trusted: the
        // reserve is a subtraction, and a sign error there would read as a plausible small total.
        assertEquals(0.0, active(score(quietHour)), 0.0)
    }

    @Test
    fun aLaterQuietStretch_raisesTheBasalHeartRate() {
        val timeline = score(
            CalorieInputs(
                hr = hr(0L, 1_800, 50) + hr(1_800L, 1_800, 130) + hr(3_600L, 1_800, 70),
                gravity = motion(0L, 1_800, 0.0) + motion(1_800L, 1_800, 0.5) + motion(3_600L, 1_800, 0.0),
                rr = beats(0L, 5_400),
            ),
        )

        assertEquals(70.0, timeline.extras.getValue(CalorieTimeline.MEASURED_BASAL_HR_BPM), 0.0)
    }

    @Test
    fun aStretchThatIsNotStill_leavesTheBasalHeartRateWhereItWas() {
        // The same shape as above with the closing stretch in motion. A rate the wearer was not still
        // for is no measurement of a basal rate, and only a measurement may raise it.
        val timeline = score(
            CalorieInputs(
                hr = hr(0L, 1_800, 50) + hr(1_800L, 1_800, 130) + hr(3_600L, 1_800, 70),
                gravity = motion(0L, 1_800, 0.0) + motion(1_800L, 3_600, 0.5),
                rr = beats(0L, 5_400),
            ),
        )

        assertEquals(50.0, timeline.extras.getValue(CalorieTimeline.MEASURED_BASAL_HR_BPM), 0.0)
    }

    @Test
    fun aStretchWithoutBeats_leavesTheBasalHeartRateWhereItWas() {
        val timeline = score(
            CalorieInputs(
                hr = hr(0L, 1_800, 50) + hr(1_800L, 1_800, 130) + hr(3_600L, 1_800, 70),
                gravity = motion(0L, 1_800, 0.0) + motion(1_800L, 1_800, 0.5) + motion(3_600L, 1_800, 0.0),
                rr = beats(0L, 3_600),
            ),
        )

        assertEquals(50.0, timeline.extras.getValue(CalorieTimeline.MEASURED_BASAL_HR_BPM), 0.0)
    }

    // ── Days the model will not estimate ──────────────────────────────────────────────────────

    @Test
    fun aDayRecordedEveryThirtySeconds_isFilledAndEstimated() {
        // Twenty-nine empty seconds between readings is a gap inside one stretch, not an absence of
        // one, so the day is scored second by second off the filled series.
        val timeline = score(sparseHour)

        assertFalse(declined(timeline))
        assertEquals(50.0, basalHr(timeline), 0.0)
    }

    @Test
    fun aDayWithNoMotionMagnitude_isDeclined() {
        // A WHOOP 4.0 reports none, and stillness cannot be established without it.
        val timeline = score(quietHour.copy(gravity = motion(0L, 3_600, null)))

        assertEquals(0.0, quietWindows(timeline), 0.0)
    }

    @Test
    fun aDayWithNoBeats_isDeclined() {
        val timeline = score(quietHour.copy(rr = emptyList()))

        assertEquals(0.0, quietWindows(timeline), 0.0)
    }

    @Test
    fun aWearerWithNoMaximalOxygenUptake_isDeclined() {
        // Nothing in the profile yields one without a resting heart rate, and there is then no oxygen
        // reserve to apportion. Reporting a day of pure resting energy would read as a measurement.
        val timeline = score(quietHour, anchors = CalorieVitals(hrmax = 180.0, restingHR = null))

        assertEquals(0.0, quietWindows(timeline), 0.0)
    }

    @Test
    fun aDeclinedDay_stillReportsTheCoverageThatDeclinedIt() {
        val timeline = score(sparseHour)

        // 120 samples over the 3571 seconds the session spans, first sample to last.
        assertEquals(120.0 / 3_571.0, timeline.extras.getValue(DynamicHrrModel.Extra.HR_COVERAGE_FRAC), 1e-12)
    }

    @Test
    fun aDeclinedDay_storesNothing() {
        val stored = CalorieDayScorer.score(
            day = day,
            nowUtc = null,
            hr = sparseHour.hr,
            gravity = sparseHour.gravity,
            workouts = emptyList(),
            beats = sparseHour.rr,
            profile = subject.copy(calorieModel = EnergyModel.DYNAMIC_HRR),
            hrmax = 180.0,
            restingHR = null,
        )

        assertNull(stored)
    }

    @Test
    fun anEstimatedDay_storesItsBasalHeartRateBesideItsEnergy() {
        val stored = CalorieDayScorer.score(
            day = day,
            nowUtc = null,
            hr = hr(0L, 1_800, 50) + hr(1_800L, 1_800, 130),
            gravity = motion(0L, 1_800, 0.0) + motion(1_800L, 1_800, 0.5),
            workouts = emptyList(),
            beats = beats(0L, 3_600),
            profile = subject.copy(calorieModel = EnergyModel.DYNAMIC_HRR),
            hrmax = 180.0,
            restingHR = 50.0,
        )

        assertNotNull(stored)
        assertEquals(50.0, stored!!.basalHrBpm!!, 0.0)
        assertTrue(stored.activeKcal > 0.0)
    }

    @Test
    fun theScoringPass_carriesTheBasalHeartRateOutBesideTheDaysEnergy() {
        // Stored under its own key rather than in the resting-heart-rate column, which holds a
        // different quantity read over a different window.
        val scored = AnalyticsEngine.analyzeDay(
            day = "1970-01-01",
            calorieHr = hr(0L, 1_800, 50) + hr(1_800L, 1_800, 130),
            calorieGravity = motion(0L, 1_800, 0.0) + motion(1_800L, 1_800, 0.5),
            calorieBeats = beats(0L, 3_600),
            calorieRestingHR = 50.0,
            profile = subject.copy(calorieModel = EnergyModel.DYNAMIC_HRR),
            maxHROverride = 180.0,
            tzOffsetSeconds = 0L,
        )

        assertEquals(50.0, scored.basalHrBpm!!, 0.0)
        assertNotNull(scored.daily.activeKcalEst)
    }

    @Test
    fun theScoringPass_carriesNoBasalHeartRateOutOfAModelThatMeasuresNone() {
        val scored = AnalyticsEngine.analyzeDay(
            day = "1970-01-01",
            calorieHr = hr(0L, 3_600, 120, cadenceS = 30),
            calorieRestingHR = 50.0,
            profile = subject.copy(calorieModel = EnergyModel.HEART_RATE),
            maxHROverride = 180.0,
            tzOffsetSeconds = 0L,
        )

        assertNull(scored.basalHrBpm)
        assertNotNull(scored.daily.activeKcalEst)
    }

    // ── The lead-in before any quiet stretch ──────────────────────────────────────────────────

    /** An hour of effort, then a quiet hour, then a second hour of the same effort. */
    private val effortThenQuietThenEffort = CalorieInputs(
        hr = hr(0L, 3_600, 120) + hr(3_600L, 3_600, 50) + hr(7_200L, 3_600, 120),
        gravity = motion(0L, 3_600, 0.5) + motion(3_600L, 3_600, 0.0) + motion(7_200L, 3_600, 0.5),
        rr = beats(0L, 10_800),
    )

    private fun kcalOverFirstHour(timeline: CalorieTimeline): Double {
        var total = 0.0
        for (i in 0 until 60) total += timeline.activeKcal[i]
        return total
    }

    @Test
    fun aWearerWithNoRestingHeartRateAnywhere_getsAnUnscoredLeadIn() {
        // No day in history carries one, so there is nothing to start the fold at. A default would be
        // an assumption at the one point this model exists to measure — and so would the trailing
        // median of an hour nothing qualified, which is why the lead-in carries no anchor at all.
        val timeline = score(
            effortThenQuietThenEffort,
            profile = subject.copy(vo2maxOverride = 55.0),
            anchors = CalorieVitals(hrmax = 180.0, restingHR = null),
        )

        val anchors = timeline.labeledSeries.getValue(DynamicHrrModel.Label.BASAL_HR)
        assertEquals(0.0, kcalOverFirstHour(timeline), 0.0)
        assertTrue("the lead-in must carry no anchor", anchors.take(60).all { it == null })
        assertTrue("and the day must still be estimated", quietWindows(timeline) > 0.0)
    }

    @Test
    fun aWearerWithARestingHeartRate_hasTheirLeadInScoredFromIt() {
        val timeline = score(effortThenQuietThenEffort)

        assertTrue(kcalOverFirstHour(timeline) > 0.0)
    }

    @Test
    fun aMeasuredRestingEnergy_replacesTheEstimateFromTheWearersBody() {
        // Zero means "estimate it", so the wearer's own figure has a sentinel between it and the
        // engine's. Nothing else in the suite moves that value off zero.
        val measured = subject.copy(
            dynamicHrrModelSetting = setting.copy(restingEnergyKcalPerDay = 1_577.0),
        )

        val timeline = score(quietHour, profile = measured)
        val estimated = score(quietHour)

        // A quiet hour earns nothing above resting, so the day's total IS its resting term.
        assertEquals(1_577.0, timeline.dayTotalKcal, 1e-9)
        assertTrue(estimated.dayTotalKcal != 1_577.0)
    }

    @Test
    fun theLeadIn_startsTheSeedOffsetAboveTheWearersRestingHeartRate() {
        // Before any quiet stretch has ended the anchor is exactly the seed. The ratchet is held off
        // by asking for more readings than its window can hold, so the only thing left to assert is
        // where the fold started; without this the offset could be any number and nothing would fail.
        val timeline = score(quietHour, profile = subject.copy(
            dynamicHrrModelSetting = setting.copy(
                basalSeedOffsetBpm = 9.0,
                basalLowerMinSamples = 600,
            ),
        ))

        assertEquals(
            59.0,
            timeline.labeledSeries.getValue(DynamicHrrModel.Label.BASAL_HR).first()!!,
            0.0,
        )
    }

    // ── Recomputing a day ─────────────────────────────────────────────────────────────────────

    @Test
    fun halfADay_givesTheSameBasalHeartRateSeriesAsTheWholeDayTruncated() {
        // The property that makes re-scoring a day over more data safe. Every step of the fold is
        // causal, so extending the input can only append. Two steps do look ahead — the spike filter
        // by its radius and peak suppression by up to one block — so the comparison stops that far
        // short of the cut rather than pretending they do not.
        val whole = CalorieInputs(
            hr = hr(0L, 1_200, 52) + hr(1_200L, 1_200, 110) + hr(2_400L, 1_200, 55) +
                hr(3_600L, 3_600, 90),
            gravity = motion(0L, 1_200, 0.0) + motion(1_200L, 1_200, 0.5) +
                motion(2_400L, 1_200, 0.0) + motion(3_600L, 3_600, 0.5),
            rr = beats(0L, 7_200),
        )
        val half = CalorieInputs(
            hr = whole.hr.filter { it.ts < 3_600L },
            gravity = whole.gravity.filter { it.ts < 3_600L },
            rr = whole.rr.filter { (ts, _) -> ts < 3_600L },
        )
        // Peak suppression is left on here: the lookahead it introduces is exactly what this property
        // has to survive.
        val settled = subject.copy(dynamicHrrModelSetting = DynamicHrrModelSetting())
        val fromWhole = DynamicHrrModel(settled, vitals).timeline(0L, 86_400L, whole)
        val fromHalf = DynamicHrrModel(settled, vitals).timeline(0L, 86_400L, half)

        val settledMinutes = (3_600 - DynamicHrrModelSetting().peakClipBlockS -
            DynamicHrrModelSetting().spikeWindowRadiusS) / 60
        val wholeSeries = fromWhole.labeledSeries.getValue(DynamicHrrModel.Label.BASAL_HR)
        val halfSeries = fromHalf.labeledSeries.getValue(DynamicHrrModel.Label.BASAL_HR)
        assertEquals(
            wholeSeries.subList(0, settledMinutes),
            halfSeries.subList(0, settledMinutes),
        )
    }

    // ── What the day's energy is made of ──────────────────────────────────────────────────────

    @Test
    fun aWearerWhoseRestingRateFloorsAtZero_getsNoFuelSplitForTheMinute() {
        // Harris-Benedict runs negative past a certain age and the resting rate floors at zero, so a
        // still minute costs nothing at all. There is then no energy to attribute to a fuel, and the
        // share is absent rather than a zero that would read as a measurement.
        val ancient = subject.copy(age = 2_000.0)

        val timeline = score(quietHour, profile = ancient)

        assertEquals(0.0, timeline.dayTotalKcal, 0.0)
        assertNull(timeline.labeledSeries.getValue(DynamicHrrModel.Label.FAT_PERCENTAGE).first())
    }

    @Test
    fun theFuelGrams_accountForTheWholeDaysEnergy() {
        val timeline = score(effortThenQuietThenEffort)
        val fat = timeline.extras.getValue(DynamicHrrModel.Extra.FAT_GRAMS)
        val carbohydrate = timeline.extras.getValue(DynamicHrrModel.Extra.CHO_GRAMS)

        assertEquals(
            timeline.dayTotalKcal,
            fat * FuelMix.KCAL_PER_G_FAT + carbohydrate * FuelMix.KCAL_PER_G_CHO,
            1e-6,
        )
    }

    @Test
    fun removingBlockPeaks_lowersTheDaysEnergyOnASpikyDay() {
        // Where the spikes ARE the peaks, removing them takes real effort with them. The setting
        // exists so a wearer can decide whether that trade is worth making.
        val spiky = CalorieInputs(
            hr = (0 until 3_600).map {
                HrSample("d", it.toLong(), if (it % 300 == 150) 170 else 100)
            } + hr(3_600L, 1_800, 50),
            gravity = motion(0L, 3_600, 0.5) + motion(3_600L, 1_800, 0.0),
            rr = beats(0L, 5_400),
        )
        val kept = score(spiky)
        val suppressed = score(spiky, profile = subject.copy(dynamicHrrModelSetting = DynamicHrrModelSetting()))

        assertTrue(active(suppressed) < active(kept))
    }

    // ── The window the day is scored over ─────────────────────────────────────────────────────

    @Test
    fun aWindowThatClosesBeforeItOpens_isDeclinedAndHoldsNoMinutes() {
        val timeline = DynamicHrrModel(subject, vitals).timeline(86_400L, 0L, quietHour)

        assertTrue(declined(timeline))
        assertEquals(0, timeline.tsIndex.size)
    }

    @Test
    fun aWindowLongerThanTwoDays_isDeclined() {
        // The per-second arrays are indexed by an Int and a caller-chosen span is not otherwise
        // bounded, so the guard is at two days. This is one second past it, and 172 801 s tiles into
        // 2 881 minutes.
        val timeline = DynamicHrrModel(subject, vitals).timeline(0L, 172_801L, quietHour)

        assertTrue(declined(timeline))
        assertEquals(0.0, coverage(timeline), 0.0)
        assertEquals(2_881, timeline.tsIndex.size)
    }

    @Test
    fun aWindowOfExactlyTwoDays_isStillEstimated() {
        // The guard rejects a span ABOVE two days, so two days themselves are scored.
        val timeline = DynamicHrrModel(subject, vitals).timeline(0L, 172_800L, quietHour)

        assertEquals(50.0, basalHr(timeline), 0.0)
    }

    @Test
    fun aWindowThatEndsMidMinute_booksOnlyTheSecondsThatElapsed() {
        // [0, 3630) is 61 minutes, the last of them 30 s long. Resting energy accrues per second and
        // a quiet hour earns nothing above it, so that minute costs exactly half of a whole one.
        val timeline = DynamicHrrModel(subject, vitals).timeline(0L, 3_630L, quietHour)

        assertEquals(61, timeline.totalKcal.size)
        assertEquals(timeline.totalKcal[0] / 2.0, timeline.totalKcal[60], 1e-12)
    }

    @Test
    fun aDeclinedWindowThatEndsMidMinute_booksOnlyTheSecondsThatElapsed() {
        // A measured 4 320 kcal a day is 0.05 kcal a second, so a whole minute is 3.0 kcal and the
        // 30 s closing [0, 90) are 1.5. Nothing was recorded, so the window is declined.
        val measured = subject.copy(
            dynamicHrrModelSetting = setting.copy(restingEnergyKcalPerDay = 4_320.0),
        )

        val timeline = DynamicHrrModel(measured, vitals).timeline(0L, 90L, CalorieInputs())

        assertTrue(declined(timeline))
        assertEquals(3.0, timeline.totalKcal[0], 1e-12)
        assertEquals(1.5, timeline.totalKcal[1], 1e-12)
    }

    @Test
    fun scoringADayAsOfAnInstantInsideIt_stopsAtThatInstant() {
        // The cut-off is the instant the day is scored as of rather than the wall clock, so
        // re-scoring the same day twice gives the same figure. Ninety minutes in, the window holds
        // 90 minutes and its last one opens at 01:29.
        val timeline = DynamicHrrModel(subject, vitals, nowUtc = 5_400L).timeline(day, quietHour)

        assertEquals(90, timeline.tsIndex.size)
        assertEquals(5_340L, timeline.tsIndex.last())
    }

    @Test
    fun scoringADayAsOfNoInstant_scoresTheWholeDay() {
        val timeline = DynamicHrrModel(subject, vitals).timeline(day, quietHour)

        assertEquals(1_440, timeline.tsIndex.size)
        assertEquals(50.0, basalHr(timeline), 0.0)
    }

    // ── What the model reads out of the streams ───────────────────────────────────────────────

    @Test
    fun twoHeartRatesInOneSecond_areOneMeasurementAndTheFirstStands() {
        // The quiet hour with a second reading of 150 bpm against every second of it. Taking the
        // later reading would put the whole hour at 150 and measure a basal rate there.
        val doubled = quietHour.copy(hr = quietHour.hr + hr(0L, 3_600, 150))

        val timeline = score(doubled)

        assertEquals(50.0, basalHr(timeline), 0.0)
        assertEquals(0.0, active(timeline), 0.0)
    }

    @Test
    fun twoMotionSamplesInOneSecond_areOneMeasurementAndTheFirstStands() {
        // The same rule for motion: taking the later 0.5 g reading would leave no second still and
        // no window able to measure the basal rate.
        val doubled = quietHour.copy(gravity = quietHour.gravity + motion(0L, 3_600, 0.5))

        assertEquals(50.0, basalHr(score(doubled)), 0.0)
    }

    @Test
    fun motionRecordedOutsideTheWindow_isNoMotionInsideIt() {
        // Motion is read a sample at a time rather than clipped with the heart rate, so the bounds
        // test is the model's own. Every sample here lands in the hour after the one scored.
        val timeline = DynamicHrrModel(subject, vitals).timeline(
            0L, 3_600L, quietHour.copy(gravity = motion(3_600L, 3_600, 0.0)),
        )

        assertTrue(measuredNothing(timeline))
    }

    @Test
    fun beatsRecordedOutsideTheWindow_areNoBeatCoverageInsideIt() {
        // Coverage of 1.0 says the day passed the density gate, so what left it unmeasured is the
        // beat coverage no stretch could find.
        val timeline = DynamicHrrModel(subject, vitals).timeline(
            0L, 3_600L, quietHour.copy(rr = beats(3_600L, 3_600)),
        )

        assertTrue(measuredNothing(timeline))
        assertEquals(1.0, coverage(timeline), 0.0)
    }

    @Test
    fun aSecondCarryingNoBeatIntervals_isNotBeatCoverage() {
        // A row of zero says the second was recorded and held no interval, which is the opposite of
        // evidence that the strap had a clean optical lock.
        val timeline = score(quietHour.copy(rr = (0 until 3_600).map { it.toLong() to 0 }))

        assertTrue(measuredNothing(timeline))
        assertEquals(1.0, coverage(timeline), 0.0)
    }

    // ── Wear sessions and the coverage they are measured over ─────────────────────────────────

    @Test
    fun theSilenceBetweenTwoWearSessions_isNotCountedAgainstCoverage() {
        // Two worn hours ten hours apart. Coverage is the share of the SESSIONS' seconds that
        // carried a reading — 7 200 of 7 200 — not of the day's 86 400.
        val timeline = score(
            CalorieInputs(
                hr = hr(0L, 3_600, 50) + hr(36_000L, 3_600, 50),
                gravity = motion(0L, 3_600, 0.0) + motion(36_000L, 3_600, 0.0),
                rr = beats(0L, 3_600) + beats(36_000L, 3_600),
            ),
        )

        assertEquals(1.0, coverage(timeline), 0.0)
    }

    @Test
    fun aSilenceShorterThanTheSessionGap_isCountedAgainstCoverage() {
        // An hour worn, ten minutes off, an hour worn. Ten minutes does not reach the 30-minute gap
        // that starts a new session, so this is one session of 7 800 s carrying 7 200 readings.
        val timeline = score(
            CalorieInputs(
                hr = hr(0L, 3_600, 50) + hr(4_200L, 3_600, 50),
                gravity = motion(0L, 3_600, 0.0) + motion(4_200L, 3_600, 0.0),
                rr = beats(0L, 3_600) + beats(4_200L, 3_600),
            ),
        )

        assertEquals(7_200.0 / 7_800.0, coverage(timeline), 0.0)
    }

    /** Two hours recorded every other second: 3 600 readings over the 7 199 s the session spans. */
    private val everyOtherSecondHours = CalorieInputs(
        hr = (0 until 3_600).map { HrSample(deviceId = "d", ts = it.toLong() * 2, bpm = 50) },
        gravity = motion(0L, 7_200, 0.0),
        rr = beats(0L, 7_200),
    )

    @Test
    fun halfTheSecondsRecorded_isStillEstimated() {
        // How much of a session was recorded is not a gate. Every other second is missing here, each
        // gap one second wide, so every one is filled and the day measures a basal rate. The reported
        // coverage is what the strap recorded, not what the fill left behind.
        val timeline = score(everyOtherSecondHours)

        assertEquals(3_600.0 / 7_199.0, coverage(timeline), 0.0)
        assertEquals(50.0, basalHr(timeline), 0.0)
    }

    @Test
    fun aDayWithNoHeartRateAtAll_isDeclinedAtZeroCoverage() {
        // No wear session, so there are no seconds to take a share of. The fraction is zero rather
        // than a division by zero.
        val timeline = score(CalorieInputs(gravity = motion(0L, 3_600, 0.0), rr = beats(0L, 3_600)))

        assertTrue(declined(timeline))
        assertEquals(0.0, coverage(timeline), 0.0)
    }

    // ── Stillness, and what a declined day publishes ──────────────────────────────────────────

    @Test
    fun motionExactlyAtTheStillnessThreshold_countsAsStill() {
        // The stage under test is a trailing MEAN, so the threshold has to be a value the mean lands
        // on exactly. Ten 0.02s average to 0.019999999999999997, which is below 0.02 and so is still
        // under `<` as well; 0.03125 is a power of two and survives the division unchanged.
        val exact = subject.copy(dynamicHrrModelSetting = setting.copy(stillMaxG = 0.03125))

        val timeline = score(quietHour.copy(gravity = motion(0L, 3_600, 0.03125)), profile = exact)

        assertEquals(50.0, basalHr(timeline), 0.0)
    }

    @Test
    fun motionJustAboveTheStillnessThreshold_isNotStill() {
        val timeline = score(quietHour.copy(gravity = motion(0L, 3_600, 0.021)))

        assertTrue(measuredNothing(timeline))
    }

    @Test
    fun motionTooSparseToSmooth_leavesNoSecondStill() {
        // The ten-second mean stands on three readings. One reading every five seconds gives a
        // window two, so no second is shown to be still and no stretch can measure a basal rate.
        val timeline = score(quietHour.copy(gravity = motion(0L, 3_600, 0.0, cadenceS = 5)))

        assertTrue(measuredNothing(timeline))
        assertEquals(1.0, coverage(timeline), 0.0)
    }

    @Test
    fun aDeclinedDay_publishesNoSeriesAndNoFuelSplit() {
        // The day still books resting energy, so zero grams beside a non-zero total would assert
        // that none of it came from anywhere. The keys are absent rather than zero.
        val timeline = score(sparseHour, anchors = CalorieVitals(hrmax = 180.0, restingHR = null))

        assertTrue(timeline.labeledSeries.values.all { series -> series.all { it == null } })
        assertNull(timeline.extras[DynamicHrrModel.Extra.FAT_GRAMS])
        assertNull(timeline.extras[DynamicHrrModel.Extra.CHO_GRAMS])
        assertNull(timeline.extras[CalorieTimeline.MEASURED_BASAL_HR_BPM])
        assertTrue(timeline.dayTotalKcal > 0.0)
    }

    // ── The reserve the energy is apportioned over ────────────────────────────────────────────

    @Test
    fun aMaximumHeartRateAtTheMeasuredBasalRate_earnsNothingAboveResting() {
        // There is no reserve between 50 bpm and a 50 bpm maximum to apportion, so the hour at
        // 120 bpm costs exactly what the quiet hour before it did. The same day against a 180 bpm
        // maximum is not free, which is what makes this the reserve and not the effort.
        val inputs = quietThenEffortAt(120)

        val noReserve = score(inputs, anchors = CalorieVitals(hrmax = 50.0, restingHR = 50.0))
        val withReserve = score(inputs, anchors = CalorieVitals(hrmax = 180.0, restingHR = 50.0))

        assertEquals(0.0, active(noReserve), 0.0)
        assertTrue(active(withReserve) > 0.0)
    }

    @Test
    fun aHeartRateAboveTheMaximum_isScoredAtTheMaximum() {
        // The reserve fraction is clipped to one, and fat supplies nothing above the anaerobic
        // threshold either way, so an hour at 250 bpm costs exactly what an hour at 180 does.
        val at180 = score(quietThenEffortAt(180))
        val at250 = score(quietThenEffortAt(250))

        assertEquals(active(at180), active(at250), 0.0)
        assertTrue(active(at180) > 0.0)
    }

    @Test
    fun aMaximalOxygenUptakeBelowTheRestingDraw_earnsNothingAboveResting() {
        // Active uptake is the reserve ABOVE the oxygen resting metabolism already takes, and a
        // 1 ml/kg/min maximum is below it. The term is held at zero rather than turned negative,
        // which would book the day's effort as energy the wearer did not spend.
        val inputs = quietThenEffortAt(120)

        val implausible = score(inputs, profile = subject.copy(vo2maxOverride = 1.0))

        assertEquals(0.0, active(implausible), 0.0)
        assertTrue(active(score(inputs)) > 0.0)
    }

    @Test
    fun aWearerWithNoMaximumHeartRate_isScoredAgainstTheDefaultOne() {
        // 220 is the last-resort constant rather than a Tanaka estimate off the wearer's age.
        val inputs = quietThenEffortAt(120)

        val withoutMax = score(inputs, anchors = CalorieVitals(hrmax = null, restingHR = 50.0))
        val at220 = score(inputs, anchors = CalorieVitals(hrmax = 220.0, restingHR = 50.0))

        assertEquals(active(at220), active(withoutMax), 0.0)
        assertTrue(active(withoutMax) > 0.0)
    }

    // ── What the filters took off each minute ─────────────────────────────────────────────────

    /** A flat hour at 100 bpm, still and beat-covered, with second 150 reading 170. */
    private val oneSpikeInAFlatHour = CalorieInputs(
        hr = (0 until 3_600).map {
            HrSample(deviceId = "d", ts = it.toLong(), bpm = if (it == 150) 170 else 100)
        },
        gravity = motion(0L, 3_600, 0.0),
        rr = beats(0L, 3_600),
    )

    private fun deltaHr(timeline: CalorieTimeline): List<Double?> =
        timeline.labeledSeries.getValue(DynamicHrrModel.Label.DELTA_HR)

    @Test
    fun theHeartRateDelta_isZeroOnAMinuteScoredExactlyAsRecorded() {
        assertEquals(0.0, deltaHr(score(quietHour))[0]!!, 0.0)
    }

    @Test
    fun theHeartRateDelta_isNullOnAMinuteThatCarriedNoReading() {
        // One hour of the day was worn; there is nothing in minute 60 to compare against.
        assertNull(deltaHr(score(quietHour))[60])
    }

    @Test
    fun theHeartRateDelta_isHowFarTheFiltersPulledTheMinuteDown() {
        // The 300 s block opening the hour holds 299 readings of 100 and one of 170. At the 0.97
        // percentile its ceiling is the 291st smallest, 100, so second 150 is pulled down 70 bpm and
        // the minute it falls in averages that over its sixty seconds.
        val clipping = subject.copy(dynamicHrrModelSetting = DynamicHrrModelSetting())

        val deltas = deltaHr(score(oneSpikeInAFlatHour, profile = clipping))

        assertEquals(-70.0 / 60.0, deltas[2]!!, 1e-12)
        assertEquals(0.0, deltas[1]!!, 0.0)
    }

    @Test
    fun theHeartRateDelta_isZeroOnTheSameMinuteWithPeakSuppressionOff() {
        // The spike filter leaves a lone reading in a flat neighbourhood alone — a flat run has a
        // robust deviation of zero — so with the clipper off nothing comes off the minute at all.
        assertEquals(0.0, deltaHr(score(oneSpikeInAFlatHour))[2]!!, 0.0)
    }

    @Test
    fun theBasalHeartRateSeries_reportsTheAnchorTheMinuteClosedOn() {
        // A quiet hour at 50 then a quiet hour at 70. The rise past the 10 bpm limit closes the first
        // stretch at second 3 599, which reports the 50 it measured; the second reports 70 at the
        // last second of the day. The raise is ramped back over the 3 599 seconds between those two
        // reports, every one of them 20 bpm above the old rate and so weighted alike.
        val timeline = score(
            CalorieInputs(
                hr = hr(0L, 3_600, 50) + hr(3_600L, 3_600, 70),
                gravity = motion(0L, 7_200, 0.0),
                rr = beats(0L, 7_200),
            ),
        )

        val anchors = timeline.labeledSeries.getValue(DynamicHrrModel.Label.BASAL_HR)
        assertEquals(50.0 + 20.0 * 240.0 / 3_599.0, anchors[63]!!, 1e-9)
        assertEquals(50.0 + 20.0 * 300.0 / 3_599.0, anchors[64]!!, 1e-9)
    }

    // ── The settings the wearer can move ──────────────────────────────────────────────────────

    @Test
    fun everyShippedDefault_sitsInsideTheRangePersistenceClampsItTo() {
        // The ranges are enforced where a setting is stored rather than in the model, so a default
        // outside its own range would be rewritten the first time the wearer opened the screen.
        val d = DynamicHrrModelSetting()
        val r = DynamicHrrModelSettingRanges
        val outside = mapOf(
            "wearSessionMaxSilenceS" to (d.wearSessionMaxSilenceS !in r.WEAR_SESSION_MAX_SILENCE_S),
            "minHrCoverageFrac" to (d.minHrCoverageFrac !in r.MIN_HR_COVERAGE_FRAC),
            "spikeWindowRadiusS" to (d.spikeWindowRadiusS !in r.SPIKE_WINDOW_RADIUS_S),
            "spikeThresholdSigmas" to (d.spikeThresholdSigmas !in r.SPIKE_THRESHOLD_SIGMAS),
            "peakClipBlockS" to (d.peakClipBlockS !in r.PEAK_CLIP_BLOCK_S),
            "peakClipKeptFrac" to (d.peakClipKeptFrac !in r.PEAK_CLIP_KEPT_FRAC),
            "stillMaxG" to (d.stillMaxG !in r.STILL_MAX_G),
            "stillSmoothingS" to (d.stillSmoothingS !in r.STILL_SMOOTHING_S),
            "quietStretchMinLengthS" to (d.quietStretchMinLengthS !in r.QUIET_STRETCH_MIN_LENGTH_S),
            "quietStretchMaxRiseBpm" to (d.quietStretchMaxRiseBpm !in r.QUIET_STRETCH_MAX_RISE_BPM),
            "quietStretchMinStillFrac" to (d.quietStretchMinStillFrac !in r.QUIET_STRETCH_MIN_STILL_FRAC),
            "quietStretchMinBeatFrac" to (d.quietStretchMinBeatFrac !in r.QUIET_STRETCH_MIN_BEAT_FRAC),
            "basalLowerWindowS" to (d.basalLowerWindowS !in r.BASAL_LOWER_WINDOW_S),
            "basalLowerMinSamples" to (d.basalLowerMinSamples !in r.BASAL_LOWER_MIN_SAMPLES),
            "basalSeedOffsetBpm" to (d.basalSeedOffsetBpm !in r.BASAL_SEED_OFFSET_BPM),
            "reserveRampBandBpm" to (d.reserveRampBandBpm !in r.RESERVE_RAMP_BAND_BPM),
            "restingEnergyKcalPerDay" to (d.restingEnergyKcalPerDay !in r.RESTING_ENERGY_KCAL_PER_DAY),
            "restingFatNightFrac" to (d.restingFatNightFrac !in r.RESTING_FAT_NIGHT_FRAC),
            "restingFatDayFrac" to (d.restingFatDayFrac !in r.RESTING_FAT_DAY_FRAC),
            "restingFatDayStartHour" to (d.restingFatDayStartHour !in r.RESTING_FAT_DAY_START_HOUR),
            "restingFatDayEndHour" to (d.restingFatDayEndHour !in r.RESTING_FAT_DAY_END_HOUR),
            "activeFatZone1Frac" to (d.activeFatZone1Frac !in r.ACTIVE_FAT_ZONE1_FRAC),
            "activeFatZone2TopFrac" to (d.activeFatZone2TopFrac !in r.ACTIVE_FAT_ZONE2_TOP_FRAC),
        ).filterValues { it }.keys

        assertEquals(emptySet<String>(), outside)
    }

    /** The same wearer with every beat above rest counted in full. */
    private val straightReserve =
        subject.copy(dynamicHrrModelSetting = setting.copy(reserveRampBandBpm = 0.0))

    @Test
    fun anHourTenBeatsAboveRest_booksUnderAThirdOfWhatTheStraightReserveWould() {
        // Ten beats over a 50 bpm rest fall entirely in the quarter-weight band, and the reserve they
        // are measured against shrinks by less, so the hour books 2.5/115 rather than 10/130.
        val ramped = active(score(quietThenEffortAt(60)))

        assertEquals(0.2826, ramped / active(score(quietThenEffortAt(60), profile = straightReserve)), 1e-4)
    }

    @Test
    fun anHourAtTheirMaximum_booksWhatTheStraightReserveWould() {
        // The reserve is weighted on both sides of the ratio, so a maximal effort still reads as the
        // whole of it and the ramp moves energy toward the higher rates rather than removing it.
        val ramped = active(score(quietThenEffortAt(180)))

        assertEquals(active(score(quietThenEffortAt(180), profile = straightReserve)), ramped, 1e-9)
    }

    @Test
    fun theDiscountShrinksAsTheEffortRises() {
        val shares = listOf(60, 90, 120).map { bpm ->
            active(score(quietThenEffortAt(bpm))) / active(score(quietThenEffortAt(bpm), profile = straightReserve))
        }

        assertEquals(shares.sorted(), shares)
        assertTrue(shares.toString(), shares.first() < 0.3 && shares.last() > 0.8)
    }

    @Test
    fun aWearerWithNoRestingRate_isScoredOnTheStraightReserve() {
        // A maximal oxygen uptake entered by hand estimates the day without a resting rate, and there
        // is then nothing to pin the bands to. The alternative would be bands anchored on a number
        // this wearer never had.
        val measuredVo2max = subject.copy(vo2maxOverride = 45.0)
        val noRestingRate = CalorieVitals(hrmax = 180.0, restingHR = null)

        val ramped = active(score(quietThenEffortAt(120), measuredVo2max, noRestingRate))

        val straight = measuredVo2max.copy(dynamicHrrModelSetting = setting.copy(reserveRampBandBpm = 0.0))
        assertEquals(active(score(quietThenEffortAt(120), straight, noRestingRate)), ramped, 0.0)
    }

    /** A quiet hour holding a nine-second run at 200 bpm: too long to be a spike, thin enough to clip. */
    private val quietHourWithAClippableRun = CalorieInputs(
        hr = (0 until 3_600).map {
            HrSample(deviceId = "d", ts = it.toLong(), bpm = if (it in 600..608) 200 else 50)
        },
        gravity = motion(0L, 3_600, 0.0),
        rr = beats(0L, 3_600),
    )

    @Test
    fun peakClippingDoesNotDecideWhichStretchesAreQuiet() {
        // The quiet-window search reads the spike-filtered heart rate, not the clipped one. Clipping
        // only lowers the high readings, so a stretch broken by a run of them would otherwise be
        // handed them already pulled down, and would certify a basal rate off a stretch that was
        // not quiet.
        val noFilter = setting.copy(spikeThresholdSigmas = 100.0)

        val windows = listOf(true, false).map { suppress ->
            val profile = subject.copy(dynamicHrrModelSetting = noFilter.copy(peakClipEnabled = suppress))
            score(quietHourWithAClippableRun, profile = profile)
                .extras.getValue(DynamicHrrModel.Extra.QUIET_WINDOW_COUNT)
        }

        assertEquals(windows[1], windows[0], 0.0)
    }

    @Test
    fun peakClippingStillDecidesWhatTheMinutesCost() {
        // The other half of the same ordering: the run is removed from the energy the fold reads,
        // which is what clipping is for, so the two stages are not simply independent.
        val noFilter = setting.copy(spikeThresholdSigmas = 100.0)
        val clipped = subject.copy(dynamicHrrModelSetting = noFilter.copy(peakClipEnabled = true))
        val kept = subject.copy(dynamicHrrModelSetting = noFilter.copy(peakClipEnabled = false))

        assertEquals(0.0, active(score(quietHourWithAClippableRun, profile = clipped)), 0.0)
        assertTrue(active(score(quietHourWithAClippableRun, profile = kept)) > 3.0)
    }

    @Test
    fun aSilenceOfExactlyTheSessionGap_isStillOneSession() {
        // The split is `>` the gap, so a silence measuring exactly it does not reach the bound. Read
        // through coverage, which is what the split changes: one session of 8 999 s carrying 7 200
        // readings, rather than two full sessions reading 1.0.
        val second = 3_599L + DynamicHrrModelSetting().wearSessionMaxSilenceS

        val timeline = score(
            CalorieInputs(
                hr = hr(0L, 3_600, 50) + hr(second, 3_600, 50),
                gravity = motion(0L, 3_600, 0.0) + motion(second, 3_600, 0.0),
                rr = beats(0L, 3_600) + beats(second, 3_600),
            ),
        )

        assertEquals(7_200.0 / 8_999.0, coverage(timeline), 1e-12)
    }

    @Test
    fun aBasalRaise_isWeighedOnTheSpikeFilteredSeriesNotTheClippedOne() {
        // The twin of peakClippingDoesNotDecideWhichStretchesAreQuiet, for the ramp. The ramp's speed
        // comes from how far the rate ran above the old one, and it reads the same series the quiet
        // windows do, so clipping cannot move the anchors. It still moves the energy, which is the
        // other test below.
        val quietThenEffortThenQuiet = CalorieInputs(
            hr = (0 until 10_800).map {
                val bpm = when {
                    it < 3_600 -> 50
                    it in 3_600..3_608 -> 200
                    it < 7_200 -> 100
                    else -> 70
                }
                HrSample(deviceId = "d", ts = it.toLong(), bpm = bpm)
            },
            gravity = motion(0L, 3_600, 0.0) + motion(3_600L, 3_600, 0.5) + motion(7_200L, 3_600, 0.0),
            rr = beats(0L, 10_800),
        )
        val noFilter = setting.copy(spikeThresholdSigmas = 100.0)

        val series = listOf(true, false).map { suppress ->
            score(
                quietThenEffortThenQuiet,
                profile = subject.copy(dynamicHrrModelSetting = noFilter.copy(peakClipEnabled = suppress)),
            ).labeledSeries.getValue(DynamicHrrModel.Label.BASAL_HR)
        }

        assertEquals(series[1], series[0])
    }
}
