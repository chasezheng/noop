package com.noop.analytics.calorie

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The characterization gate over `DailyMetric.activeKcalEst`.
 *
 * That column is persisted and has no undo migration: it is only ever recomputed, so a refactor that
 * moves it rewrites the wearer's history on the next pass. [CalorieCorpus] scores sixteen day shapes
 * against five wearers under three clocks and this file pins the answer, so any change to the models
 * has to declare which of the 255 spans it moved and by how much.
 *
 * The golden is one string compare rather than several hundred assertions, and it is deliberately
 * unrounded: a tolerance is a decision about how much drift is acceptable, and the answer for a
 * stored column is none.
 *
 * Below it are the relational invariants the golden cannot express. A golden agrees with itself after
 * a wrong edit to the literal; a relation does not, so the two guard each other.
 */
class CalorieCorpusTest {

    @Test
    fun theCorpusReproducesTheRecordedFigures() {
        val expected = listOf(RECORDED_0, RECORDED_1, RECORDED_2, RECORDED_3, RECORDED_4)
            .joinToString("\n") { it.trim() }.lines()
        val actual = CalorieCorpus.records()
        val moved = expected.zip(actual).filter { (e, a) -> e != a }
        if (expected.size != actual.size || moved.isNotEmpty()) {
            val report = moved.take(12).joinToString("\n") { (e, a) -> "  recorded $e\n  now      $a" }
            fail(
                "the corpus moved on ${moved.size} of ${expected.size} lines " +
                    "(${actual.size} produced)\n$report",
            )
        }
    }

    // ── Invariants the golden cannot encode ───────────────────────────────────────────────────

    @Test
    fun everyModelsLabelledTermsSumToItsActiveFigure() {
        // Whatever paths a model publishes, they account for the whole of what it stores: a minute
        // valued but attributed to nothing would be invisible to every other assertion here.
        forEachSpan { case, who, now ->
            for (model in EnergyModel.values()) {
                val t = CalorieCorpus.timelineFor(model, case, who, now)
                val labelled = t.labeledKcal.keys.sumOf { t.kcalFrom(it) }
                assertEquals("${case.name}/${who.name}/$model", t.dayActiveKcal, labelled, 1e-9)
            }
        }
    }

    @Test
    fun theStoredFigureIsTheSelectedModelsActiveTerm() {
        // Pins what the scorer contributes on top of the model: the window it derives, the clock it
        // clamps at, and that motion and workouts reach the model that reads them. Dropping any one
        // of the three still compiles.
        //
        // `ringMETPresent` is the one shape it cannot cover: the scorer takes no ring MET, so that
        // stream reaches the models and never the stored column.
        forEachSpan { case, who, now ->
            if (case.name == "ringMETPresent") return@forEachSpan
            val stored = CalorieCorpus.storedFigure(case, who, now) ?: return@forEachSpan
            val model = CalorieCorpus.timelineFor(who.profile.calorieModel, case, who, now)
            assertEquals(
                "${case.name}/${who.name}", stored.toString(), model.dayActiveKcal.toString(),
            )
        }
    }

    @Test
    fun everyModelAnswersForTheSameMinutes() {
        // The comparison card plots the series against one another, so they must share a grid.
        forEachSpan { case, who, now ->
            val grid = CalorieCorpus.timelineFor(EnergyModel.HEART_RATE, case, who, now).tsIndex
            for (model in EnergyModel.values()) {
                assertArrayEquals(
                    "${case.name}/${who.name}/$model",
                    grid,
                    CalorieCorpus.timelineFor(model, case, who, now).tsIndex,
                )
            }
        }
    }

    @Test
    fun theHeartRateModelIgnoresHrFallbackWhenNoMET() {
        // The knob is a HYBRID one: it decides what becomes of a minute the motion path could not
        // cover. Routing it into the heart-rate model labels every minute NO_DATA and stores a flat
        // zero for any wearer who turned it off. Nothing else in the suite covers this.
        val who = CalorieCorpus.profiles.single { it.name == "knobsOffDefault" }
        assertTrue("the fixture must actually have the knob off", !who.profile.hybridModelSetting.hrFallbackWhenNoMET)
        val case = CalorieCorpus.cases.single { it.name == "dense1Hz18h" }
        val now = CalorieCorpus.clocks.single { it.name == "elapsed" }.nowUtcFor(case.localMidnightUtc)
        val active = CalorieCorpus.timelineFor(EnergyModel.HEART_RATE, case, who, now).dayActiveKcal
        assertTrue("a knobs-off wearer's heart-rate day collapsed to zero", active > 1.0)
    }

    @Test
    fun aDuplicatedStreamIsNotEstimatedTwice() {
        // A repeated timestamp measures no time, so the model credits it nothing and lets the next
        // sample carry the real gap. The golden pins the duplicated day's figure but agrees with
        // itself after a wrong edit; this says what the figure has to BE.
        val who = CalorieCorpus.profiles.single { it.name == "defaultHr" }
        val duplicated = CalorieCorpus.cases.single { it.name == "duplicateTimestamps" }
        val once = duplicated.copy(hr = duplicated.hr.distinctBy { it.ts })
        val now = CalorieCorpus.clocks.single { it.name == "elapsed" }.nowUtcFor(duplicated.localMidnightUtc)
        assertTrue("the fixture must actually repeat timestamps", duplicated.hr.size > once.hr.size)
        assertEquals(
            CalorieCorpus.timelineFor(EnergyModel.HEART_RATE, once, who, now).dayActiveKcal,
            CalorieCorpus.timelineFor(EnergyModel.HEART_RATE, duplicated, who, now).dayActiveKcal,
            0.0,
        )
    }

    @Test
    fun motionMakesTheTwoModelsDisagree() {
        val who = CalorieCorpus.profiles.single { it.name == "defaultHybrid" }
        val case = CalorieCorpus.cases.single { it.name == "motionAndHr" }
        val now = CalorieCorpus.clocks.single { it.name == "elapsed" }.nowUtcFor(case.localMidnightUtc)
        val hr = CalorieCorpus.timelineFor(EnergyModel.HEART_RATE, case, who, now).dayActiveKcal
        val hybrid = CalorieCorpus.timelineFor(EnergyModel.HYBRID, case, who, now).dayActiveKcal
        assertTrue("the hybrid stopped reading its motion input", kotlin.math.abs(hr - hybrid) > 1.0)
    }

    @Test
    fun aHeavierWearerEarnsMoreAndAHigherFloorEarnsLess() {
        val who = CalorieCorpus.profiles.single { it.name == "defaultHybrid" }
        val case = CalorieCorpus.cases.single { it.name == "motionAndHr" }
        val now = CalorieCorpus.clocks.single { it.name == "elapsed" }.nowUtcFor(case.localMidnightUtc)
        val base = CalorieCorpus.timelineFor(EnergyModel.HYBRID, case, who, now).dayActiveKcal
        val heavier = who.copy(profile = who.profile.copy(weightKg = 120.0))
        assertTrue(CalorieCorpus.timelineFor(EnergyModel.HYBRID, case, heavier, now).dayActiveKcal > base)
        val clamped = who.copy(
            profile = who.profile.copy(
                hybridModelSetting = who.profile.hybridModelSetting.copy(activeAccrualMET = 4.5),
            ),
        )
        assertTrue(CalorieCorpus.timelineFor(EnergyModel.HYBRID, case, clamped, now).dayActiveKcal < base)
    }

    @Test
    fun everyDayShapeThatCarriesHeartRateInTheWindowScores() {
        // Guards the corpus itself: a shape whose samples all fell outside the window would record
        // nulls forever and pin nothing.
        val scoring = CalorieCorpus.cases.count { case ->
            val who = CalorieCorpus.profiles.first()
            val now = CalorieCorpus.clocks.first().nowUtcFor(case.localMidnightUtc)
            CalorieCorpus.storedFigure(case, who, now) != null
        }
        assertEquals("expected every shape but empty, preWindowOnly and motionOnly to score", 14, scoring)
    }

    private fun forEachSpan(body: (CalorieDayCase, CalorieProfileCase, Long) -> Unit) {
        for ((case, who, now) in CalorieCorpus.spans()) body(case, who, now)
    }

    // ── The recorded answer ───────────────────────────────────────────────────────────────────
    //
    // Split only because a Kotlin string constant cannot exceed the class file's 64 KB UTF-8 limit.

    private val RECORDED_0 = """
empty|defaultHr|elapsed|stored|null
empty|defaultHr|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
empty|defaultHr|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
empty|defaultHr|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
empty|defaultHr|midday|stored|null
empty|defaultHr|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
empty|defaultHr|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
empty|defaultHr|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
empty|defaultHr|earlyMorning|stored|null
empty|defaultHr|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
empty|defaultHr|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
empty|defaultHr|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
empty|defaultHybrid|elapsed|stored|null
empty|defaultHybrid|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
empty|defaultHybrid|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
empty|defaultHybrid|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
empty|defaultHybrid|midday|stored|null
empty|defaultHybrid|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
empty|defaultHybrid|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
empty|defaultHybrid|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
empty|defaultHybrid|earlyMorning|stored|null
empty|defaultHybrid|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
empty|defaultHybrid|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
empty|defaultHybrid|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
empty|knobsOffDefault|elapsed|stored|null
empty|knobsOffDefault|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
empty|knobsOffDefault|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|d51e5a62be94e2eb
empty|knobsOffDefault|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
empty|knobsOffDefault|midday|stored|null
empty|knobsOffDefault|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
empty|knobsOffDefault|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|d223a05eb2fee76b
empty|knobsOffDefault|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
empty|knobsOffDefault|earlyMorning|stored|null
empty|knobsOffDefault|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
empty|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|b5b409904d6fa25b
empty|knobsOffDefault|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
empty|defaultMeasuredBasal|elapsed|stored|null
empty|defaultMeasuredBasal|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
empty|defaultMeasuredBasal|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
empty|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
empty|defaultMeasuredBasal|midday|stored|null
empty|defaultMeasuredBasal|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
empty|defaultMeasuredBasal|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
empty|defaultMeasuredBasal|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
empty|defaultMeasuredBasal|earlyMorning|stored|null
empty|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
empty|defaultMeasuredBasal|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
empty|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
empty|noVitalsFemale|elapsed|stored|null
empty|noVitalsFemale|elapsed|HEART_RATE|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
empty|noVitalsFemale|elapsed|HYBRID|0.0|1284.1510000000178|1284.1510000000178|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
empty|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
empty|noVitalsFemale|midday|stored|null
empty|noVitalsFemale|midday|HEART_RATE|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d
empty|noVitalsFemale|midday|HYBRID|0.0|642.0755000000079|642.0755000000079|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
empty|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
empty|noVitalsFemale|earlyMorning|stored|null
empty|noVitalsFemale|earlyMorning|HEART_RATE|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa
empty|noVitalsFemale|earlyMorning|HYBRID|0.0|80.25943749999998|80.25943749999998|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
empty|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
preWindowOnly|defaultHr|elapsed|stored|null
preWindowOnly|defaultHr|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
preWindowOnly|defaultHr|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
preWindowOnly|defaultHr|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
preWindowOnly|defaultHr|midday|stored|null
preWindowOnly|defaultHr|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
preWindowOnly|defaultHr|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
preWindowOnly|defaultHr|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
preWindowOnly|defaultHr|earlyMorning|stored|null
preWindowOnly|defaultHr|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
preWindowOnly|defaultHr|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
preWindowOnly|defaultHr|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
preWindowOnly|defaultHybrid|elapsed|stored|null
preWindowOnly|defaultHybrid|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
preWindowOnly|defaultHybrid|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
preWindowOnly|defaultHybrid|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
preWindowOnly|defaultHybrid|midday|stored|null
preWindowOnly|defaultHybrid|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
preWindowOnly|defaultHybrid|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
preWindowOnly|defaultHybrid|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
preWindowOnly|defaultHybrid|earlyMorning|stored|null
preWindowOnly|defaultHybrid|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
preWindowOnly|defaultHybrid|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
preWindowOnly|defaultHybrid|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
preWindowOnly|knobsOffDefault|elapsed|stored|null
preWindowOnly|knobsOffDefault|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
preWindowOnly|knobsOffDefault|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|d51e5a62be94e2eb
preWindowOnly|knobsOffDefault|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
preWindowOnly|knobsOffDefault|midday|stored|null
preWindowOnly|knobsOffDefault|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
preWindowOnly|knobsOffDefault|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|d223a05eb2fee76b
preWindowOnly|knobsOffDefault|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
preWindowOnly|knobsOffDefault|earlyMorning|stored|null
preWindowOnly|knobsOffDefault|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
preWindowOnly|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|b5b409904d6fa25b
preWindowOnly|knobsOffDefault|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
preWindowOnly|defaultMeasuredBasal|elapsed|stored|null
preWindowOnly|defaultMeasuredBasal|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
preWindowOnly|defaultMeasuredBasal|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
preWindowOnly|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
preWindowOnly|defaultMeasuredBasal|midday|stored|null
preWindowOnly|defaultMeasuredBasal|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
preWindowOnly|defaultMeasuredBasal|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
preWindowOnly|defaultMeasuredBasal|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
preWindowOnly|defaultMeasuredBasal|earlyMorning|stored|null
preWindowOnly|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
preWindowOnly|defaultMeasuredBasal|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
preWindowOnly|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
preWindowOnly|noVitalsFemale|elapsed|stored|null
preWindowOnly|noVitalsFemale|elapsed|HEART_RATE|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
preWindowOnly|noVitalsFemale|elapsed|HYBRID|0.0|1284.1510000000178|1284.1510000000178|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|99c5ce0eae06776b
preWindowOnly|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
preWindowOnly|noVitalsFemale|midday|stored|null
preWindowOnly|noVitalsFemale|midday|HEART_RATE|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d
preWindowOnly|noVitalsFemale|midday|HYBRID|0.0|642.0755000000079|642.0755000000079|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
preWindowOnly|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
preWindowOnly|noVitalsFemale|earlyMorning|stored|null
preWindowOnly|noVitalsFemale|earlyMorning|HEART_RATE|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa
preWindowOnly|noVitalsFemale|earlyMorning|HYBRID|0.0|80.25943749999998|80.25943749999998|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
preWindowOnly|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
sparse30s|defaultHr|elapsed|stored|2916.125552568586
sparse30s|defaultHr|elapsed|HEART_RATE|2916.125552568586|1825.2470000000453|4741.372552568631|2916.125552568586|1440|1767312000|1767398340|ae003111f2d3691e
sparse30s|defaultHr|elapsed|HYBRID|2916.125552568586|1825.2470000000453|4741.372552568631|0.0|0.0|2916.125552568586|1440|1767312000|1767398340|ae003111f2d3691e|422bdff66635984f
sparse30s|defaultHr|elapsed|DYNAMIC_HRR|2428.599626859878|1825.247000000038|4253.846626859916|2428.599626859878|1440|1767312000|1767398340|b93876244831c7bd|0.0|null
sparse30s|defaultHr|midday|stored|2185.879131031877
sparse30s|defaultHr|midday|HEART_RATE|2185.879131031877|912.6235000000052|3098.502631031882|2185.879131031877|720|1767312000|1767355140|de5f43a8933ec08b
sparse30s|defaultHr|midday|HYBRID|2185.879131031877|912.6235000000052|3098.502631031882|0.0|0.0|2185.879131031877|720|1767312000|1767355140|de5f43a8933ec08b|3ced3425ffbeea93
sparse30s|defaultHr|midday|DYNAMIC_HRR|1818.5495118787658|912.6235000000029|2731.1730118787686|1818.5495118787658|720|1767312000|1767355140|deea6c479d038642|0.0|null
sparse30s|defaultHr|earlyMorning|stored|null
sparse30s|defaultHr|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
sparse30s|defaultHr|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
sparse30s|defaultHr|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
sparse30s|defaultHybrid|elapsed|stored|2916.125552568586
sparse30s|defaultHybrid|elapsed|HEART_RATE|2916.125552568586|1825.2470000000453|4741.372552568631|2916.125552568586|1440|1767312000|1767398340|ae003111f2d3691e
sparse30s|defaultHybrid|elapsed|HYBRID|2916.125552568586|1825.2470000000453|4741.372552568631|0.0|0.0|2916.125552568586|1440|1767312000|1767398340|ae003111f2d3691e|422bdff66635984f
sparse30s|defaultHybrid|elapsed|DYNAMIC_HRR|2428.599626859878|1825.247000000038|4253.846626859916|2428.599626859878|1440|1767312000|1767398340|b93876244831c7bd|0.0|null
sparse30s|defaultHybrid|midday|stored|2185.879131031877
sparse30s|defaultHybrid|midday|HEART_RATE|2185.879131031877|912.6235000000052|3098.502631031882|2185.879131031877|720|1767312000|1767355140|de5f43a8933ec08b
sparse30s|defaultHybrid|midday|HYBRID|2185.879131031877|912.6235000000052|3098.502631031882|0.0|0.0|2185.879131031877|720|1767312000|1767355140|de5f43a8933ec08b|3ced3425ffbeea93
sparse30s|defaultHybrid|midday|DYNAMIC_HRR|1818.5495118787658|912.6235000000029|2731.1730118787686|1818.5495118787658|720|1767312000|1767355140|deea6c479d038642|0.0|null
sparse30s|defaultHybrid|earlyMorning|stored|null
sparse30s|defaultHybrid|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
sparse30s|defaultHybrid|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
sparse30s|defaultHybrid|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
sparse30s|knobsOffDefault|elapsed|stored|0.0
sparse30s|knobsOffDefault|elapsed|HEART_RATE|2545.9850153629363|1825.2470000000453|4371.232015362982|2545.9850153629363|1440|1767312000|1767398340|a609e4275ad70c1b
sparse30s|knobsOffDefault|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|d51e5a62be94e2eb
sparse30s|knobsOffDefault|elapsed|DYNAMIC_HRR|2428.599626859878|1825.247000000038|4253.846626859916|2428.599626859878|1440|1767312000|1767398340|b93876244831c7bd|0.0|null
sparse30s|knobsOffDefault|midday|stored|0.0
sparse30s|knobsOffDefault|midday|HEART_RATE|1908.4639603375417|912.6235000000047|2821.0874603375464|1908.4639603375417|720|1767312000|1767355140|ccdd7cc8ee743758
sparse30s|knobsOffDefault|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|d223a05eb2fee76b
sparse30s|knobsOffDefault|midday|DYNAMIC_HRR|1818.5495118787658|912.6235000000029|2731.1730118787686|1818.5495118787658|720|1767312000|1767355140|deea6c479d038642|0.0|null
sparse30s|knobsOffDefault|earlyMorning|stored|null
sparse30s|knobsOffDefault|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
sparse30s|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|b5b409904d6fa25b
sparse30s|knobsOffDefault|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
sparse30s|defaultMeasuredBasal|elapsed|stored|2428.599626859878
sparse30s|defaultMeasuredBasal|elapsed|HEART_RATE|2916.125552568586|1825.2470000000453|4741.372552568631|2916.125552568586|1440|1767312000|1767398340|ae003111f2d3691e
sparse30s|defaultMeasuredBasal|elapsed|HYBRID|2916.125552568586|1825.2470000000453|4741.372552568631|0.0|0.0|2916.125552568586|1440|1767312000|1767398340|ae003111f2d3691e|422bdff66635984f
sparse30s|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|2428.599626859878|1825.247000000038|4253.846626859916|2428.599626859878|1440|1767312000|1767398340|b93876244831c7bd|0.0|null
sparse30s|defaultMeasuredBasal|midday|stored|1818.5495118787658
sparse30s|defaultMeasuredBasal|midday|HEART_RATE|2185.879131031877|912.6235000000052|3098.502631031882|2185.879131031877|720|1767312000|1767355140|de5f43a8933ec08b
sparse30s|defaultMeasuredBasal|midday|HYBRID|2185.879131031877|912.6235000000052|3098.502631031882|0.0|0.0|2185.879131031877|720|1767312000|1767355140|de5f43a8933ec08b|3ced3425ffbeea93
sparse30s|defaultMeasuredBasal|midday|DYNAMIC_HRR|1818.5495118787658|912.6235000000029|2731.1730118787686|1818.5495118787658|720|1767312000|1767355140|deea6c479d038642|0.0|null
sparse30s|defaultMeasuredBasal|earlyMorning|stored|null
sparse30s|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
sparse30s|defaultMeasuredBasal|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
sparse30s|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
sparse30s|noVitalsFemale|elapsed|stored|1997.835228854343
sparse30s|noVitalsFemale|elapsed|HEART_RATE|1997.835228854343|1284.1509999998773|3281.9862288542204|1997.835228854343|1440|1767312000|1767398340|1fd3cc6a771dea2a
sparse30s|noVitalsFemale|elapsed|HYBRID|1997.835228854343|1284.1509999998773|3281.9862288542204|0.0|0.0|1997.835228854343|1440|1767312000|1767398340|1fd3cc6a771dea2a|940df3b37920b5b9
sparse30s|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
sparse30s|noVitalsFemale|midday|stored|1497.9773947843637
sparse30s|noVitalsFemale|midday|HEART_RATE|1497.9773947843637|642.0755000000045|2140.052894784368|1497.9773947843637|720|1767312000|1767355140|17a782420f03b792
sparse30s|noVitalsFemale|midday|HYBRID|1497.9773947843637|642.0755000000045|2140.052894784368|0.0|0.0|1497.9773947843637|720|1767312000|1767355140|17a782420f03b792|b1145643c2eb0cd4
sparse30s|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
sparse30s|noVitalsFemale|earlyMorning|stored|null
sparse30s|noVitalsFemale|earlyMorning|HEART_RATE|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa
sparse30s|noVitalsFemale|earlyMorning|HYBRID|0.0|80.25943749999998|80.25943749999998|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
sparse30s|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
dense1Hz18h|defaultHr|elapsed|stored|6574.494347102745
dense1Hz18h|defaultHr|elapsed|HEART_RATE|6574.494347102745|1825.2470000000358|8399.741347102781|6574.494347102745|1440|1767312000|1767398340|66098c9ba5943ac
dense1Hz18h|defaultHr|elapsed|HYBRID|6574.494347102745|1825.2470000000358|8399.741347102781|0.0|0.0|6574.494347102745|1440|1767312000|1767398340|66098c9ba5943ac|e43d94764c1f020d
dense1Hz18h|defaultHr|elapsed|DYNAMIC_HRR|5740.206809680235|1825.2470000000221|7565.453809680257|5740.206809680235|1440|1767312000|1767398340|196a1e1727c01814|0.0|null
dense1Hz18h|defaultHr|midday|stored|4383.051792268587
dense1Hz18h|defaultHr|midday|HEART_RATE|4383.051792268587|912.6235000000261|5295.675292268613|4383.051792268587|720|1767312000|1767355140|7c419e5275df1294
dense1Hz18h|defaultHr|midday|HYBRID|4383.051792268587|912.6235000000261|5295.675292268613|0.0|0.0|4383.051792268587|720|1767312000|1767355140|7c419e5275df1294|9841c03c9d8f019c
dense1Hz18h|defaultHr|midday|DYNAMIC_HRR|3824.9410082750023|912.6235000000038|4737.564508275006|3824.9410082750023|720|1767312000|1767355140|5d57c4114379265d|0.0|null
dense1Hz18h|defaultHr|earlyMorning|stored|547.92819567156
dense1Hz18h|defaultHr|earlyMorning|HEART_RATE|547.92819567156|114.07793750000019|662.0061331715602|547.92819567156|90|1767312000|1767317340|d40fbe7b3bc3d70a
dense1Hz18h|defaultHr|earlyMorning|HYBRID|547.92819567156|114.07793750000019|662.0061331715602|0.0|0.0|547.92819567156|90|1767312000|1767317340|d40fbe7b3bc3d70a|545a5eed4c964cfb
dense1Hz18h|defaultHr|earlyMorning|DYNAMIC_HRR|478.15616819761124|114.07793750000019|592.2341056976114|478.15616819761124|90|1767312000|1767317340|3b0122f964f14a34|0.0|null
dense1Hz18h|defaultHybrid|elapsed|stored|6574.494347102745
dense1Hz18h|defaultHybrid|elapsed|HEART_RATE|6574.494347102745|1825.2470000000358|8399.741347102781|6574.494347102745|1440|1767312000|1767398340|66098c9ba5943ac
dense1Hz18h|defaultHybrid|elapsed|HYBRID|6574.494347102745|1825.2470000000358|8399.741347102781|0.0|0.0|6574.494347102745|1440|1767312000|1767398340|66098c9ba5943ac|e43d94764c1f020d
dense1Hz18h|defaultHybrid|elapsed|DYNAMIC_HRR|5740.206809680235|1825.2470000000221|7565.453809680257|5740.206809680235|1440|1767312000|1767398340|196a1e1727c01814|0.0|null
dense1Hz18h|defaultHybrid|midday|stored|4383.051792268587
dense1Hz18h|defaultHybrid|midday|HEART_RATE|4383.051792268587|912.6235000000261|5295.675292268613|4383.051792268587|720|1767312000|1767355140|7c419e5275df1294
dense1Hz18h|defaultHybrid|midday|HYBRID|4383.051792268587|912.6235000000261|5295.675292268613|0.0|0.0|4383.051792268587|720|1767312000|1767355140|7c419e5275df1294|9841c03c9d8f019c
dense1Hz18h|defaultHybrid|midday|DYNAMIC_HRR|3824.9410082750023|912.6235000000038|4737.564508275006|3824.9410082750023|720|1767312000|1767355140|5d57c4114379265d|0.0|null
dense1Hz18h|defaultHybrid|earlyMorning|stored|547.92819567156
dense1Hz18h|defaultHybrid|earlyMorning|HEART_RATE|547.92819567156|114.07793750000019|662.0061331715602|547.92819567156|90|1767312000|1767317340|d40fbe7b3bc3d70a
dense1Hz18h|defaultHybrid|earlyMorning|HYBRID|547.92819567156|114.07793750000019|662.0061331715602|0.0|0.0|547.92819567156|90|1767312000|1767317340|d40fbe7b3bc3d70a|545a5eed4c964cfb
dense1Hz18h|defaultHybrid|earlyMorning|DYNAMIC_HRR|478.15616819761124|114.07793750000019|592.2341056976114|478.15616819761124|90|1767312000|1767317340|3b0122f964f14a34|0.0|null
dense1Hz18h|knobsOffDefault|elapsed|stored|0.0
dense1Hz18h|knobsOffDefault|elapsed|HEART_RATE|5743.9252162233115|1825.247000000013|7569.1722162233245|5743.9252162233115|1440|1767312000|1767398340|75644d29084a17f
dense1Hz18h|knobsOffDefault|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|d51e5a62be94e2eb
dense1Hz18h|knobsOffDefault|elapsed|DYNAMIC_HRR|5740.206809680235|1825.2470000000221|7565.453809680257|5740.206809680235|1440|1767312000|1767398340|196a1e1727c01814|0.0|null
dense1Hz18h|knobsOffDefault|midday|stored|0.0
dense1Hz18h|knobsOffDefault|midday|HEART_RATE|3829.3575950896598|912.623500000002|4741.981095089662|3829.3575950896598|720|1767312000|1767355140|d104033a13d5de43
dense1Hz18h|knobsOffDefault|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|d223a05eb2fee76b
dense1Hz18h|knobsOffDefault|midday|DYNAMIC_HRR|3824.9410082750023|912.6235000000038|4737.564508275006|3824.9410082750023|720|1767312000|1767355140|5d57c4114379265d|0.0|null
dense1Hz18h|knobsOffDefault|earlyMorning|stored|0.0
dense1Hz18h|knobsOffDefault|earlyMorning|HEART_RATE|478.6892582948816|114.07793749999996|592.7671957948816|478.6892582948816|90|1767312000|1767317340|1d360ce9d104fb60
dense1Hz18h|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|b5b409904d6fa25b
dense1Hz18h|knobsOffDefault|earlyMorning|DYNAMIC_HRR|478.15616819761124|114.07793750000019|592.2341056976114|478.15616819761124|90|1767312000|1767317340|3b0122f964f14a34|0.0|null
dense1Hz18h|defaultMeasuredBasal|elapsed|stored|5740.206809680235
dense1Hz18h|defaultMeasuredBasal|elapsed|HEART_RATE|6574.494347102745|1825.2470000000358|8399.741347102781|6574.494347102745|1440|1767312000|1767398340|66098c9ba5943ac
dense1Hz18h|defaultMeasuredBasal|elapsed|HYBRID|6574.494347102745|1825.2470000000358|8399.741347102781|0.0|0.0|6574.494347102745|1440|1767312000|1767398340|66098c9ba5943ac|e43d94764c1f020d
dense1Hz18h|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|5740.206809680235|1825.2470000000221|7565.453809680257|5740.206809680235|1440|1767312000|1767398340|196a1e1727c01814|0.0|null
dense1Hz18h|defaultMeasuredBasal|midday|stored|3824.9410082750023
dense1Hz18h|defaultMeasuredBasal|midday|HEART_RATE|4383.051792268587|912.6235000000261|5295.675292268613|4383.051792268587|720|1767312000|1767355140|7c419e5275df1294
dense1Hz18h|defaultMeasuredBasal|midday|HYBRID|4383.051792268587|912.6235000000261|5295.675292268613|0.0|0.0|4383.051792268587|720|1767312000|1767355140|7c419e5275df1294|9841c03c9d8f019c
dense1Hz18h|defaultMeasuredBasal|midday|DYNAMIC_HRR|3824.9410082750023|912.6235000000038|4737.564508275006|3824.9410082750023|720|1767312000|1767355140|5d57c4114379265d|0.0|null
dense1Hz18h|defaultMeasuredBasal|earlyMorning|stored|478.15616819761124
dense1Hz18h|defaultMeasuredBasal|earlyMorning|HEART_RATE|547.92819567156|114.07793750000019|662.0061331715602|547.92819567156|90|1767312000|1767317340|d40fbe7b3bc3d70a
dense1Hz18h|defaultMeasuredBasal|earlyMorning|HYBRID|547.92819567156|114.07793750000019|662.0061331715602|0.0|0.0|547.92819567156|90|1767312000|1767317340|d40fbe7b3bc3d70a|545a5eed4c964cfb
dense1Hz18h|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|478.15616819761124|114.07793750000019|592.2341056976114|478.15616819761124|90|1767312000|1767317340|3b0122f964f14a34|0.0|null
dense1Hz18h|noVitalsFemale|elapsed|stored|4504.474001173455
dense1Hz18h|noVitalsFemale|elapsed|HEART_RATE|4504.474001173455|1284.1509999999162|5788.625001173371|4504.474001173455|1440|1767312000|1767398340|6d6f262133e2473e
dense1Hz18h|noVitalsFemale|elapsed|HYBRID|4504.474001173455|1284.1509999999162|5788.625001173371|0.0|0.0|4504.474001173455|1440|1767312000|1767398340|6d6f262133e2473e|64a8c4d199644a59
dense1Hz18h|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
dense1Hz18h|noVitalsFemale|midday|stored|3003.0248269985723
dense1Hz18h|noVitalsFemale|midday|HEART_RATE|3003.0248269985723|642.0755000000095|3645.1003269985817|3003.0248269985723|720|1767312000|1767355140|565a215a86189f
dense1Hz18h|noVitalsFemale|midday|HYBRID|3003.0248269985723|642.0755000000095|3645.1003269985817|0.0|0.0|3003.0248269985723|720|1767312000|1767355140|565a215a86189f|c8efd3405c713183
dense1Hz18h|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
dense1Hz18h|noVitalsFemale|earlyMorning|stored|375.4038904725673
dense1Hz18h|noVitalsFemale|earlyMorning|HEART_RATE|375.4038904725673|80.25943750000016|455.66332797256746|375.4038904725673|90|1767312000|1767317340|723896e2070d0ec2
dense1Hz18h|noVitalsFemale|earlyMorning|HYBRID|375.4038904725673|80.25943750000016|455.66332797256746|0.0|0.0|375.4038904725673|90|1767312000|1767317340|723896e2070d0ec2|de3d38a3720f06f3
dense1Hz18h|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
    """

    private val RECORDED_1 = """
wearGap40min|defaultHr|elapsed|stored|1469.4702662958043
wearGap40min|defaultHr|elapsed|HEART_RATE|1469.4702662958043|1825.247000000068|3294.7172662958724|1469.4702662958043|1440|1767312000|1767398340|e39ad3c06766e526
wearGap40min|defaultHr|elapsed|HYBRID|1469.4702662958043|1825.247000000068|3294.7172662958724|0.0|0.0|1469.4702662958043|1440|1767312000|1767398340|e39ad3c06766e526|65a69f986ffcc085
wearGap40min|defaultHr|elapsed|DYNAMIC_HRR|1200.3668221945745|1825.2470000000671|3025.6138221946417|1200.3668221945745|1440|1767312000|1767398340|2316ae73de7f7bd|0.0|null
wearGap40min|defaultHr|midday|stored|1469.4702662958043
wearGap40min|defaultHr|midday|HEART_RATE|1469.4702662958043|912.6235000000229|2382.093766295827|1469.4702662958043|720|1767312000|1767355140|cafe2065eb59677
wearGap40min|defaultHr|midday|HYBRID|1469.4702662958043|912.6235000000229|2382.093766295827|0.0|0.0|1469.4702662958043|720|1767312000|1767355140|cafe2065eb59677|27a44fb100add6c5
wearGap40min|defaultHr|midday|DYNAMIC_HRR|1200.3668221945745|912.623500000022|2112.9903221945965|1200.3668221945745|720|1767312000|1767355140|725ab524f74a0fac|0.0|null
wearGap40min|defaultHr|earlyMorning|stored|181.57781871840228
wearGap40min|defaultHr|earlyMorning|HEART_RATE|181.57781871840228|114.07793750000002|295.6557562184023|181.57781871840228|90|1767312000|1767317340|3023e83b6c8b5b1e
wearGap40min|defaultHr|earlyMorning|HYBRID|181.57781871840228|114.07793750000002|295.6557562184023|0.0|0.0|181.57781871840228|90|1767312000|1767317340|3023e83b6c8b5b1e|3878872045c0cadd
wearGap40min|defaultHr|earlyMorning|DYNAMIC_HRR|149.74211942603193|114.07793750000002|263.82005692603195|149.74211942603193|90|1767312000|1767317340|2331ff933bf04ffb|0.0|null
wearGap40min|defaultHybrid|elapsed|stored|1469.4702662958043
wearGap40min|defaultHybrid|elapsed|HEART_RATE|1469.4702662958043|1825.247000000068|3294.7172662958724|1469.4702662958043|1440|1767312000|1767398340|e39ad3c06766e526
wearGap40min|defaultHybrid|elapsed|HYBRID|1469.4702662958043|1825.247000000068|3294.7172662958724|0.0|0.0|1469.4702662958043|1440|1767312000|1767398340|e39ad3c06766e526|65a69f986ffcc085
wearGap40min|defaultHybrid|elapsed|DYNAMIC_HRR|1200.3668221945745|1825.2470000000671|3025.6138221946417|1200.3668221945745|1440|1767312000|1767398340|2316ae73de7f7bd|0.0|null
wearGap40min|defaultHybrid|midday|stored|1469.4702662958043
wearGap40min|defaultHybrid|midday|HEART_RATE|1469.4702662958043|912.6235000000229|2382.093766295827|1469.4702662958043|720|1767312000|1767355140|cafe2065eb59677
wearGap40min|defaultHybrid|midday|HYBRID|1469.4702662958043|912.6235000000229|2382.093766295827|0.0|0.0|1469.4702662958043|720|1767312000|1767355140|cafe2065eb59677|27a44fb100add6c5
wearGap40min|defaultHybrid|midday|DYNAMIC_HRR|1200.3668221945745|912.623500000022|2112.9903221945965|1200.3668221945745|720|1767312000|1767355140|725ab524f74a0fac|0.0|null
wearGap40min|defaultHybrid|earlyMorning|stored|181.57781871840228
wearGap40min|defaultHybrid|earlyMorning|HEART_RATE|181.57781871840228|114.07793750000002|295.6557562184023|181.57781871840228|90|1767312000|1767317340|3023e83b6c8b5b1e
wearGap40min|defaultHybrid|earlyMorning|HYBRID|181.57781871840228|114.07793750000002|295.6557562184023|0.0|0.0|181.57781871840228|90|1767312000|1767317340|3023e83b6c8b5b1e|3878872045c0cadd
wearGap40min|defaultHybrid|earlyMorning|DYNAMIC_HRR|149.74211942603193|114.07793750000002|263.82005692603195|149.74211942603193|90|1767312000|1767317340|2331ff933bf04ffb|0.0|null
wearGap40min|knobsOffDefault|elapsed|stored|0.0
wearGap40min|knobsOffDefault|elapsed|HEART_RATE|1284.8046235002182|1825.247000000066|3110.051623500284|1284.8046235002182|1440|1767312000|1767398340|d1630136cbae311e
wearGap40min|knobsOffDefault|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|d51e5a62be94e2eb
wearGap40min|knobsOffDefault|elapsed|DYNAMIC_HRR|1200.3668221945745|1825.2470000000671|3025.6138221946417|1200.3668221945745|1440|1767312000|1767398340|2316ae73de7f7bd|0.0|null
wearGap40min|knobsOffDefault|midday|stored|0.0
wearGap40min|knobsOffDefault|midday|HEART_RATE|1284.8046235002182|912.6235000000208|2197.428123500239|1284.8046235002182|720|1767312000|1767355140|a2b13a2e445b58bf
wearGap40min|knobsOffDefault|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|d223a05eb2fee76b
wearGap40min|knobsOffDefault|midday|DYNAMIC_HRR|1200.3668221945745|912.623500000022|2112.9903221945965|1200.3668221945745|720|1767312000|1767355140|725ab524f74a0fac|0.0|null
wearGap40min|knobsOffDefault|earlyMorning|stored|0.0
wearGap40min|knobsOffDefault|earlyMorning|HEART_RATE|158.58012217760796|114.07793749999996|272.6580596776079|158.58012217760796|90|1767312000|1767317340|4006bb7eb814b074
wearGap40min|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|b5b409904d6fa25b
wearGap40min|knobsOffDefault|earlyMorning|DYNAMIC_HRR|149.74211942603193|114.07793750000002|263.82005692603195|149.74211942603193|90|1767312000|1767317340|2331ff933bf04ffb|0.0|null
wearGap40min|defaultMeasuredBasal|elapsed|stored|1200.3668221945745
wearGap40min|defaultMeasuredBasal|elapsed|HEART_RATE|1469.4702662958043|1825.247000000068|3294.7172662958724|1469.4702662958043|1440|1767312000|1767398340|e39ad3c06766e526
wearGap40min|defaultMeasuredBasal|elapsed|HYBRID|1469.4702662958043|1825.247000000068|3294.7172662958724|0.0|0.0|1469.4702662958043|1440|1767312000|1767398340|e39ad3c06766e526|65a69f986ffcc085
wearGap40min|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|1200.3668221945745|1825.2470000000671|3025.6138221946417|1200.3668221945745|1440|1767312000|1767398340|2316ae73de7f7bd|0.0|null
wearGap40min|defaultMeasuredBasal|midday|stored|1200.3668221945745
wearGap40min|defaultMeasuredBasal|midday|HEART_RATE|1469.4702662958043|912.6235000000229|2382.093766295827|1469.4702662958043|720|1767312000|1767355140|cafe2065eb59677
wearGap40min|defaultMeasuredBasal|midday|HYBRID|1469.4702662958043|912.6235000000229|2382.093766295827|0.0|0.0|1469.4702662958043|720|1767312000|1767355140|cafe2065eb59677|27a44fb100add6c5
wearGap40min|defaultMeasuredBasal|midday|DYNAMIC_HRR|1200.3668221945745|912.623500000022|2112.9903221945965|1200.3668221945745|720|1767312000|1767355140|725ab524f74a0fac|0.0|null
wearGap40min|defaultMeasuredBasal|earlyMorning|stored|149.74211942603193
wearGap40min|defaultMeasuredBasal|earlyMorning|HEART_RATE|181.57781871840228|114.07793750000002|295.6557562184023|181.57781871840228|90|1767312000|1767317340|3023e83b6c8b5b1e
wearGap40min|defaultMeasuredBasal|earlyMorning|HYBRID|181.57781871840228|114.07793750000002|295.6557562184023|0.0|0.0|181.57781871840228|90|1767312000|1767317340|3023e83b6c8b5b1e|3878872045c0cadd
wearGap40min|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|149.74211942603193|114.07793750000002|263.82005692603195|149.74211942603193|90|1767312000|1767317340|2331ff933bf04ffb|0.0|null
wearGap40min|noVitalsFemale|elapsed|stored|1006.9318442095769
wearGap40min|noVitalsFemale|elapsed|HEART_RATE|1006.9318442095769|1284.150999999954|2291.082844209531|1006.9318442095769|1440|1767312000|1767398340|8de6f223c1880e81
wearGap40min|noVitalsFemale|elapsed|HYBRID|1006.9318442095769|1284.150999999954|2291.082844209531|0.0|0.0|1006.9318442095769|1440|1767312000|1767398340|8de6f223c1880e81|4c1ce2e18f53b580
wearGap40min|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
wearGap40min|noVitalsFemale|midday|stored|1006.9318442095769
wearGap40min|noVitalsFemale|midday|HEART_RATE|1006.9318442095769|642.0755000000061|1649.007344209583|1006.9318442095769|720|1767312000|1767355140|bbc6cd2936afdab0
wearGap40min|noVitalsFemale|midday|HYBRID|1006.9318442095769|642.0755000000061|1649.007344209583|0.0|0.0|1006.9318442095769|720|1767312000|1767355140|bbc6cd2936afdab0|e78627da0a2da340
wearGap40min|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
wearGap40min|noVitalsFemale|earlyMorning|stored|124.43529233260745
wearGap40min|noVitalsFemale|earlyMorning|HEART_RATE|124.43529233260745|80.25943750000005|204.6947298326075|124.43529233260745|90|1767312000|1767317340|908adb92d860b465
wearGap40min|noVitalsFemale|earlyMorning|HYBRID|124.43529233260745|80.25943750000005|204.6947298326075|0.0|0.0|124.43529233260745|90|1767312000|1767317340|908adb92d860b465|826e77ccc761173c
wearGap40min|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
duplicateTimestamps|defaultHr|elapsed|stored|362.9762180114947
duplicateTimestamps|defaultHr|elapsed|HEART_RATE|362.9762180114947|1825.2470000000405|2188.223218011535|362.9762180114947|1440|1767312000|1767398340|2fa776a8f5a32114
duplicateTimestamps|defaultHr|elapsed|HYBRID|362.9762180114947|1825.2470000000405|2188.223218011535|0.0|0.0|362.9762180114947|1440|1767312000|1767398340|2fa776a8f5a32114|8d4b60e6ac593dd5
duplicateTimestamps|defaultHr|elapsed|DYNAMIC_HRR|302.1816025958841|1825.2470000000349|2127.428602595919|302.1816025958841|1440|1767312000|1767398340|54b21b8242a76d81|0.0|null
duplicateTimestamps|defaultHr|midday|stored|362.9762180114947
duplicateTimestamps|defaultHr|midday|HEART_RATE|362.9762180114947|912.6234999999954|1275.59971801149|362.9762180114947|720|1767312000|1767355140|574a91ab2bb0bbb5
duplicateTimestamps|defaultHr|midday|HYBRID|362.9762180114947|912.6234999999954|1275.59971801149|0.0|0.0|362.9762180114947|720|1767312000|1767355140|574a91ab2bb0bbb5|aaa0b106bc6c0d15
duplicateTimestamps|defaultHr|midday|DYNAMIC_HRR|302.1816025958841|912.6234999999898|1214.805102595874|302.1816025958841|720|1767312000|1767355140|ac9220665d7f9b0|0.0|null
duplicateTimestamps|defaultHr|earlyMorning|stored|null
duplicateTimestamps|defaultHr|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
duplicateTimestamps|defaultHr|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
duplicateTimestamps|defaultHr|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
duplicateTimestamps|defaultHybrid|elapsed|stored|362.9762180114947
duplicateTimestamps|defaultHybrid|elapsed|HEART_RATE|362.9762180114947|1825.2470000000405|2188.223218011535|362.9762180114947|1440|1767312000|1767398340|2fa776a8f5a32114
duplicateTimestamps|defaultHybrid|elapsed|HYBRID|362.9762180114947|1825.2470000000405|2188.223218011535|0.0|0.0|362.9762180114947|1440|1767312000|1767398340|2fa776a8f5a32114|8d4b60e6ac593dd5
duplicateTimestamps|defaultHybrid|elapsed|DYNAMIC_HRR|302.1816025958841|1825.2470000000349|2127.428602595919|302.1816025958841|1440|1767312000|1767398340|54b21b8242a76d81|0.0|null
duplicateTimestamps|defaultHybrid|midday|stored|362.9762180114947
duplicateTimestamps|defaultHybrid|midday|HEART_RATE|362.9762180114947|912.6234999999954|1275.59971801149|362.9762180114947|720|1767312000|1767355140|574a91ab2bb0bbb5
duplicateTimestamps|defaultHybrid|midday|HYBRID|362.9762180114947|912.6234999999954|1275.59971801149|0.0|0.0|362.9762180114947|720|1767312000|1767355140|574a91ab2bb0bbb5|aaa0b106bc6c0d15
duplicateTimestamps|defaultHybrid|midday|DYNAMIC_HRR|302.1816025958841|912.6234999999898|1214.805102595874|302.1816025958841|720|1767312000|1767355140|ac9220665d7f9b0|0.0|null
duplicateTimestamps|defaultHybrid|earlyMorning|stored|null
duplicateTimestamps|defaultHybrid|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
duplicateTimestamps|defaultHybrid|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
duplicateTimestamps|defaultHybrid|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
duplicateTimestamps|knobsOffDefault|elapsed|stored|0.0
duplicateTimestamps|knobsOffDefault|elapsed|HEART_RATE|316.98082492990596|1825.2470000000365|2142.2278249299425|316.98082492990596|1440|1767312000|1767398340|ab9be07b3a8df6ca
duplicateTimestamps|knobsOffDefault|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|d51e5a62be94e2eb
duplicateTimestamps|knobsOffDefault|elapsed|DYNAMIC_HRR|302.1816025958841|1825.2470000000349|2127.428602595919|302.1816025958841|1440|1767312000|1767398340|54b21b8242a76d81|0.0|null
duplicateTimestamps|knobsOffDefault|midday|stored|0.0
duplicateTimestamps|knobsOffDefault|midday|HEART_RATE|316.98082492990596|912.6234999999913|1229.6043249298973|316.98082492990596|720|1767312000|1767355140|773ab84988d0522b
duplicateTimestamps|knobsOffDefault|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|d223a05eb2fee76b
duplicateTimestamps|knobsOffDefault|midday|DYNAMIC_HRR|302.1816025958841|912.6234999999898|1214.805102595874|302.1816025958841|720|1767312000|1767355140|ac9220665d7f9b0|0.0|null
duplicateTimestamps|knobsOffDefault|earlyMorning|stored|null
duplicateTimestamps|knobsOffDefault|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
duplicateTimestamps|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|b5b409904d6fa25b
duplicateTimestamps|knobsOffDefault|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
duplicateTimestamps|defaultMeasuredBasal|elapsed|stored|302.1816025958841
duplicateTimestamps|defaultMeasuredBasal|elapsed|HEART_RATE|362.9762180114947|1825.2470000000405|2188.223218011535|362.9762180114947|1440|1767312000|1767398340|2fa776a8f5a32114
duplicateTimestamps|defaultMeasuredBasal|elapsed|HYBRID|362.9762180114947|1825.2470000000405|2188.223218011535|0.0|0.0|362.9762180114947|1440|1767312000|1767398340|2fa776a8f5a32114|8d4b60e6ac593dd5
duplicateTimestamps|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|302.1816025958841|1825.2470000000349|2127.428602595919|302.1816025958841|1440|1767312000|1767398340|54b21b8242a76d81|0.0|null
duplicateTimestamps|defaultMeasuredBasal|midday|stored|302.1816025958841
duplicateTimestamps|defaultMeasuredBasal|midday|HEART_RATE|362.9762180114947|912.6234999999954|1275.59971801149|362.9762180114947|720|1767312000|1767355140|574a91ab2bb0bbb5
duplicateTimestamps|defaultMeasuredBasal|midday|HYBRID|362.9762180114947|912.6234999999954|1275.59971801149|0.0|0.0|362.9762180114947|720|1767312000|1767355140|574a91ab2bb0bbb5|aaa0b106bc6c0d15
duplicateTimestamps|defaultMeasuredBasal|midday|DYNAMIC_HRR|302.1816025958841|912.6234999999898|1214.805102595874|302.1816025958841|720|1767312000|1767355140|ac9220665d7f9b0|0.0|null
duplicateTimestamps|defaultMeasuredBasal|earlyMorning|stored|null
duplicateTimestamps|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
duplicateTimestamps|defaultMeasuredBasal|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
duplicateTimestamps|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
duplicateTimestamps|noVitalsFemale|elapsed|stored|248.7448245157257
duplicateTimestamps|noVitalsFemale|elapsed|HEART_RATE|248.7448245157257|1284.1510000000171|1532.8958245157428|248.7448245157257|1440|1767312000|1767398340|25df48b459b27441
duplicateTimestamps|noVitalsFemale|elapsed|HYBRID|248.7448245157257|1284.1510000000171|1532.8958245157428|0.0|0.0|248.7448245157257|1440|1767312000|1767398340|25df48b459b27441|db69f6270f5c78ac
duplicateTimestamps|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
duplicateTimestamps|noVitalsFemale|midday|stored|248.7448245157257
duplicateTimestamps|noVitalsFemale|midday|HEART_RATE|248.7448245157257|642.0755000000073|890.820324515733|248.7448245157257|720|1767312000|1767355140|c6ceecd48691f070
duplicateTimestamps|noVitalsFemale|midday|HYBRID|248.7448245157257|642.0755000000073|890.820324515733|0.0|0.0|248.7448245157257|720|1767312000|1767355140|c6ceecd48691f070|c4aeb9a3ed03236c
duplicateTimestamps|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
duplicateTimestamps|noVitalsFemale|earlyMorning|stored|null
duplicateTimestamps|noVitalsFemale|earlyMorning|HEART_RATE|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa
duplicateTimestamps|noVitalsFemale|earlyMorning|HYBRID|0.0|80.25943749999998|80.25943749999998|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
duplicateTimestamps|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
workoutHeavy|defaultHr|elapsed|stored|4381.378056238455
workoutHeavy|defaultHr|elapsed|HEART_RATE|4381.378056238455|1825.2470000000476|6206.625056238503|4381.378056238455|1440|1767312000|1767398340|ef38508d233fdd11
workoutHeavy|defaultHr|elapsed|HYBRID|3380.1558456452303|1825.2470000000485|5205.402845645279|2922.31584564523|457.8400000000001|0.0|1440|1767312000|1767398340|ad1a46abaf807229|6b6759dfc5b93362
workoutHeavy|defaultHr|elapsed|DYNAMIC_HRR|3642.0583969780077|1825.2470000000426|5467.30539697805|3642.0583969780077|1440|1767312000|1767398340|cde7fcbb637e1d8a|0.0|null
workoutHeavy|defaultHr|midday|stored|4381.378056238455
workoutHeavy|defaultHr|midday|HEART_RATE|4381.378056238455|912.6235000000024|5294.001556238458|4381.378056238455|720|1767312000|1767355140|841e45e68bd87c00
workoutHeavy|defaultHr|midday|HYBRID|3380.1558456452303|912.6235000000033|4292.779345645234|2922.31584564523|457.8400000000001|0.0|720|1767312000|1767355140|244d178470e9a688|e0d6e0d308531022
workoutHeavy|defaultHr|midday|DYNAMIC_HRR|3642.0583969780077|912.6234999999974|4554.681896978005|3642.0583969780077|720|1767312000|1767355140|292887270d9768eb|0.0|null
workoutHeavy|defaultHr|earlyMorning|stored|546.0178286969826
workoutHeavy|defaultHr|earlyMorning|HEART_RATE|546.0178286969826|114.07793750000008|660.0957661969827|546.0178286969826|90|1767312000|1767317340|68c6fc902d13fa80
workoutHeavy|defaultHr|earlyMorning|HYBRID|546.0178286969826|114.07793750000008|660.0957661969827|546.0178286969826|0.0|0.0|90|1767312000|1767317340|68c6fc902d13fa80|a651ac073d5cf13
workoutHeavy|defaultHr|earlyMorning|DYNAMIC_HRR|454.68125229280895|114.07793750000008|568.759189792809|454.68125229280895|90|1767312000|1767317340|e2b42c9fc7895f7d|0.0|null
workoutHeavy|defaultHybrid|elapsed|stored|3380.1558456452303
workoutHeavy|defaultHybrid|elapsed|HEART_RATE|4381.378056238455|1825.2470000000476|6206.625056238503|4381.378056238455|1440|1767312000|1767398340|ef38508d233fdd11
workoutHeavy|defaultHybrid|elapsed|HYBRID|3380.1558456452303|1825.2470000000485|5205.402845645279|2922.31584564523|457.8400000000001|0.0|1440|1767312000|1767398340|ad1a46abaf807229|6b6759dfc5b93362
workoutHeavy|defaultHybrid|elapsed|DYNAMIC_HRR|3642.0583969780077|1825.2470000000426|5467.30539697805|3642.0583969780077|1440|1767312000|1767398340|cde7fcbb637e1d8a|0.0|null
workoutHeavy|defaultHybrid|midday|stored|3380.1558456452303
workoutHeavy|defaultHybrid|midday|HEART_RATE|4381.378056238455|912.6235000000024|5294.001556238458|4381.378056238455|720|1767312000|1767355140|841e45e68bd87c00
workoutHeavy|defaultHybrid|midday|HYBRID|3380.1558456452303|912.6235000000033|4292.779345645234|2922.31584564523|457.8400000000001|0.0|720|1767312000|1767355140|244d178470e9a688|e0d6e0d308531022
workoutHeavy|defaultHybrid|midday|DYNAMIC_HRR|3642.0583969780077|912.6234999999974|4554.681896978005|3642.0583969780077|720|1767312000|1767355140|292887270d9768eb|0.0|null
workoutHeavy|defaultHybrid|earlyMorning|stored|546.0178286969826
workoutHeavy|defaultHybrid|earlyMorning|HEART_RATE|546.0178286969826|114.07793750000008|660.0957661969827|546.0178286969826|90|1767312000|1767317340|68c6fc902d13fa80
workoutHeavy|defaultHybrid|earlyMorning|HYBRID|546.0178286969826|114.07793750000008|660.0957661969827|546.0178286969826|0.0|0.0|90|1767312000|1767317340|68c6fc902d13fa80|a651ac073d5cf13
workoutHeavy|defaultHybrid|earlyMorning|DYNAMIC_HRR|454.68125229280895|114.07793750000008|568.759189792809|454.68125229280895|90|1767312000|1767317340|e2b42c9fc7895f7d|0.0|null
workoutHeavy|knobsOffDefault|elapsed|stored|2177.421629026022
workoutHeavy|knobsOffDefault|elapsed|HEART_RATE|3827.3406105933655|1825.247000000054|5652.587610593419|3827.3406105933655|1440|1767312000|1767398340|83d5efbfb8c90748
workoutHeavy|knobsOffDefault|elapsed|HYBRID|2177.421629026022|1825.2470000000517|4002.6686290260736|1919.1816290260206|258.23999999999995|0.0|1440|1767312000|1767398340|72a2a692744912bf|26cebffc074a096e
workoutHeavy|knobsOffDefault|elapsed|DYNAMIC_HRR|3642.0583969780077|1825.2470000000426|5467.30539697805|3642.0583969780077|1440|1767312000|1767398340|cde7fcbb637e1d8a|0.0|null
workoutHeavy|knobsOffDefault|midday|stored|2177.421629026022
workoutHeavy|knobsOffDefault|midday|HEART_RATE|3827.3406105933655|912.6235000000088|4739.964110593374|3827.3406105933655|720|1767312000|1767355140|f8e8dd38be563019
workoutHeavy|knobsOffDefault|midday|HYBRID|2177.421629026022|912.6235000000065|3090.0451290260285|1919.1816290260206|258.23999999999995|0.0|720|1767312000|1767355140|e0ea4c54502a2d36|1565bbafaeec04ee
workoutHeavy|knobsOffDefault|midday|DYNAMIC_HRR|3642.0583969780077|912.6234999999974|4554.681896978005|3642.0583969780077|720|1767312000|1767355140|292887270d9768eb|0.0|null
workoutHeavy|knobsOffDefault|earlyMorning|stored|358.2397411301694
workoutHeavy|knobsOffDefault|earlyMorning|HEART_RATE|476.7079587794354|114.07793750000013|590.7858962794355|476.7079587794354|90|1767312000|1767317340|d8bf66c4a2c6fb8a
workoutHeavy|knobsOffDefault|earlyMorning|HYBRID|358.2397411301694|114.07793750000008|472.3176786301695|358.2397411301694|0.0|0.0|90|1767312000|1767317340|281f9444113ef5bd|8260ace43edfa632
workoutHeavy|knobsOffDefault|earlyMorning|DYNAMIC_HRR|454.68125229280895|114.07793750000008|568.759189792809|454.68125229280895|90|1767312000|1767317340|e2b42c9fc7895f7d|0.0|null
workoutHeavy|defaultMeasuredBasal|elapsed|stored|3642.0583969780077
workoutHeavy|defaultMeasuredBasal|elapsed|HEART_RATE|4381.378056238455|1825.2470000000476|6206.625056238503|4381.378056238455|1440|1767312000|1767398340|ef38508d233fdd11
workoutHeavy|defaultMeasuredBasal|elapsed|HYBRID|3380.1558456452303|1825.2470000000485|5205.402845645279|2922.31584564523|457.8400000000001|0.0|1440|1767312000|1767398340|ad1a46abaf807229|6b6759dfc5b93362
workoutHeavy|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|3642.0583969780077|1825.2470000000426|5467.30539697805|3642.0583969780077|1440|1767312000|1767398340|cde7fcbb637e1d8a|0.0|null
workoutHeavy|defaultMeasuredBasal|midday|stored|3642.0583969780077
workoutHeavy|defaultMeasuredBasal|midday|HEART_RATE|4381.378056238455|912.6235000000024|5294.001556238458|4381.378056238455|720|1767312000|1767355140|841e45e68bd87c00
workoutHeavy|defaultMeasuredBasal|midday|HYBRID|3380.1558456452303|912.6235000000033|4292.779345645234|2922.31584564523|457.8400000000001|0.0|720|1767312000|1767355140|244d178470e9a688|e0d6e0d308531022
workoutHeavy|defaultMeasuredBasal|midday|DYNAMIC_HRR|3642.0583969780077|912.6234999999974|4554.681896978005|3642.0583969780077|720|1767312000|1767355140|292887270d9768eb|0.0|null
workoutHeavy|defaultMeasuredBasal|earlyMorning|stored|454.68125229280895
workoutHeavy|defaultMeasuredBasal|earlyMorning|HEART_RATE|546.0178286969826|114.07793750000008|660.0957661969827|546.0178286969826|90|1767312000|1767317340|68c6fc902d13fa80
workoutHeavy|defaultMeasuredBasal|earlyMorning|HYBRID|546.0178286969826|114.07793750000008|660.0957661969827|546.0178286969826|0.0|0.0|90|1767312000|1767317340|68c6fc902d13fa80|a651ac073d5cf13
workoutHeavy|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|454.68125229280895|114.07793750000008|568.759189792809|454.68125229280895|90|1767312000|1767317340|e2b42c9fc7895f7d|0.0|null
workoutHeavy|noVitalsFemale|elapsed|stored|2334.0966450767014
workoutHeavy|noVitalsFemale|elapsed|HEART_RATE|3001.9471458351036|1284.150999999847|4286.098145834951|3001.9471458351036|1440|1767312000|1767398340|e95253667bbe1f73
workoutHeavy|noVitalsFemale|elapsed|HYBRID|2334.0966450767014|1284.150999999848|3618.2476450765494|2002.1626450767028|331.9339999999999|0.0|1440|1767312000|1767398340|cc4a260a11a9dfd8|c4358493b23bec51
workoutHeavy|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
workoutHeavy|noVitalsFemale|midday|stored|2334.0966450767014
workoutHeavy|noVitalsFemale|midday|HEART_RATE|3001.9471458351036|642.0755000000004|3644.022645835104|3001.9471458351036|720|1767312000|1767355140|7bc703a57c42895a
workoutHeavy|noVitalsFemale|midday|HYBRID|2334.0966450767014|642.0755000000017|2976.172145076703|2002.1626450767028|331.9339999999999|0.0|720|1767312000|1767355140|5b2d278850843169|f0b475baf6ab4191
workoutHeavy|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
workoutHeavy|noVitalsFemale|earlyMorning|stored|374.0216214360926
workoutHeavy|noVitalsFemale|earlyMorning|HEART_RATE|374.0216214360926|80.25943749999993|454.2810589360925|374.0216214360926|90|1767312000|1767317340|865b3bfdf2621e38
workoutHeavy|noVitalsFemale|earlyMorning|HYBRID|374.0216214360926|80.25943749999993|454.2810589360925|374.0216214360926|0.0|0.0|90|1767312000|1767317340|865b3bfdf2621e38|cb7ea5802e068f79
workoutHeavy|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
motionOnly|defaultHr|elapsed|stored|null
motionOnly|defaultHr|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
motionOnly|defaultHr|elapsed|HYBRID|1155.36|1825.2470000000505|2980.6070000000504|0.0|1155.36|0.0|1440|1767312000|1767398340|934702c46d4ee320|facb90a980a491a5
motionOnly|defaultHr|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
motionOnly|defaultHr|midday|stored|null
motionOnly|defaultHr|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
motionOnly|defaultHr|midday|HYBRID|1155.36|912.6235000000054|2067.9835000000053|0.0|1155.36|0.0|720|1767312000|1767355140|5c56d5c8a8d8e1c1|6d93b1d0412f1fe5
motionOnly|defaultHr|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
motionOnly|defaultHr|earlyMorning|stored|null
motionOnly|defaultHr|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
motionOnly|defaultHr|earlyMorning|HYBRID|54.99999999999999|114.07793750000005|169.07793750000005|0.0|54.99999999999999|0.0|90|1767312000|1767317340|d7c426beeaeeb827|f38facf7bb59cd84
motionOnly|defaultHr|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
motionOnly|defaultHybrid|elapsed|stored|null
motionOnly|defaultHybrid|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
motionOnly|defaultHybrid|elapsed|HYBRID|1155.36|1825.2470000000505|2980.6070000000504|0.0|1155.36|0.0|1440|1767312000|1767398340|934702c46d4ee320|facb90a980a491a5
motionOnly|defaultHybrid|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
motionOnly|defaultHybrid|midday|stored|null
motionOnly|defaultHybrid|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
motionOnly|defaultHybrid|midday|HYBRID|1155.36|912.6235000000054|2067.9835000000053|0.0|1155.36|0.0|720|1767312000|1767355140|5c56d5c8a8d8e1c1|6d93b1d0412f1fe5
motionOnly|defaultHybrid|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
motionOnly|defaultHybrid|earlyMorning|stored|null
motionOnly|defaultHybrid|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
motionOnly|defaultHybrid|earlyMorning|HYBRID|54.99999999999999|114.07793750000005|169.07793750000005|0.0|54.99999999999999|0.0|90|1767312000|1767317340|d7c426beeaeeb827|f38facf7bb59cd84
motionOnly|defaultHybrid|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
motionOnly|knobsOffDefault|elapsed|stored|null
motionOnly|knobsOffDefault|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
motionOnly|knobsOffDefault|elapsed|HYBRID|657.3599999999998|1825.2470000000503|2482.60700000005|0.0|657.3599999999998|0.0|1440|1767312000|1767398340|f562610768febc1a|d24b5ab07b0495a1
motionOnly|knobsOffDefault|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
motionOnly|knobsOffDefault|midday|stored|null
motionOnly|knobsOffDefault|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
motionOnly|knobsOffDefault|midday|HYBRID|657.3599999999998|912.6235000000053|1569.983500000005|0.0|657.3599999999998|0.0|720|1767312000|1767355140|a48dc4440560a07b|53ac5fcd4f6151a1
motionOnly|knobsOffDefault|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
motionOnly|knobsOffDefault|earlyMorning|stored|null
motionOnly|knobsOffDefault|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
motionOnly|knobsOffDefault|earlyMorning|HYBRID|32.199999999999996|114.07793750000013|146.27793750000012|0.0|32.199999999999996|0.0|90|1767312000|1767317340|352dbbdbd99d9fb0|e98d2dadc1cf915
motionOnly|knobsOffDefault|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
motionOnly|defaultMeasuredBasal|elapsed|stored|null
motionOnly|defaultMeasuredBasal|elapsed|HEART_RATE|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
motionOnly|defaultMeasuredBasal|elapsed|HYBRID|1155.36|1825.2470000000505|2980.6070000000504|0.0|1155.36|0.0|1440|1767312000|1767398340|934702c46d4ee320|facb90a980a491a5
motionOnly|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|0.0|1825.2470000000212|1825.2470000000212|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
motionOnly|defaultMeasuredBasal|midday|stored|null
motionOnly|defaultMeasuredBasal|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
motionOnly|defaultMeasuredBasal|midday|HYBRID|1155.36|912.6235000000054|2067.9835000000053|0.0|1155.36|0.0|720|1767312000|1767355140|5c56d5c8a8d8e1c1|6d93b1d0412f1fe5
motionOnly|defaultMeasuredBasal|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
motionOnly|defaultMeasuredBasal|earlyMorning|stored|null
motionOnly|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
motionOnly|defaultMeasuredBasal|earlyMorning|HYBRID|54.99999999999999|114.07793750000005|169.07793750000005|0.0|54.99999999999999|0.0|90|1767312000|1767317340|d7c426beeaeeb827|f38facf7bb59cd84
motionOnly|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
motionOnly|noVitalsFemale|elapsed|stored|null
motionOnly|noVitalsFemale|elapsed|HEART_RATE|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c
motionOnly|noVitalsFemale|elapsed|HYBRID|837.6360000000003|1284.1509999999917|2121.786999999992|0.0|837.6360000000003|0.0|1440|1767312000|1767398340|13c6b2d8b5904ec0|93214cba9d7a043d
motionOnly|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
motionOnly|noVitalsFemale|midday|stored|null
motionOnly|noVitalsFemale|midday|HEART_RATE|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d
motionOnly|noVitalsFemale|midday|HYBRID|837.6360000000003|642.0755000000007|1479.711500000001|0.0|837.6360000000003|0.0|720|1767312000|1767355140|20b9e65fe9d19c61|e1ff7be55e18517d
motionOnly|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
motionOnly|noVitalsFemale|earlyMorning|stored|null
motionOnly|noVitalsFemale|earlyMorning|HEART_RATE|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa
motionOnly|noVitalsFemale|earlyMorning|HYBRID|39.875|80.25943749999996|120.13443749999996|0.0|39.875|0.0|90|1767312000|1767317340|7e638a8f0dca0179|b24a54cbfc04e43e
motionOnly|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
    """

    private val RECORDED_2 = """
motionAndHr|defaultHr|elapsed|stored|3649.984076253816
motionAndHr|defaultHr|elapsed|HEART_RATE|3649.984076253816|1825.2470000000467|5475.231076253863|3649.984076253816|1440|1767312000|1767398340|4135f1edc65ae835
motionAndHr|defaultHr|elapsed|HYBRID|1155.36|1825.2470000000505|2980.6070000000504|0.0|1155.36|0.0|1440|1767312000|1767398340|934702c46d4ee320|facb90a980a491a5
motionAndHr|defaultHr|elapsed|DYNAMIC_HRR|3033.267392755837|1825.2470000000476|4858.514392755885|3033.267392755837|1440|1767312000|1767398340|9e80560f0a027489|0.0|null
motionAndHr|defaultHr|midday|stored|3649.984076253816
motionAndHr|defaultHr|midday|HEART_RATE|3649.984076253816|912.6235000000015|4562.607576253818|3649.984076253816|720|1767312000|1767355140|7d7a119f4b79ffd4
motionAndHr|defaultHr|midday|HYBRID|1155.36|912.6235000000054|2067.9835000000053|0.0|1155.36|0.0|720|1767312000|1767355140|5c56d5c8a8d8e1c1|6d93b1d0412f1fe5
motionAndHr|defaultHr|midday|DYNAMIC_HRR|3033.267392755837|912.6235000000029|3945.89089275584|3033.267392755837|720|1767312000|1767355140|2dea20da97f32e8|0.0|null
motionAndHr|defaultHr|earlyMorning|stored|180.0608820232662
motionAndHr|defaultHr|earlyMorning|HEART_RATE|180.0608820232662|114.07793749999993|294.13881952326614|180.0608820232662|90|1767312000|1767317340|231f11c57bb92c37
motionAndHr|defaultHr|earlyMorning|HYBRID|54.99999999999999|114.07793750000005|169.07793750000005|0.0|54.99999999999999|0.0|90|1767312000|1767317340|d7c426beeaeeb827|f38facf7bb59cd84
motionAndHr|defaultHr|earlyMorning|DYNAMIC_HRR|149.8762679484448|114.0779375000001|263.9542054484449|149.8762679484448|90|1767312000|1767317340|ddc387b83f88f6ba|0.0|null
motionAndHr|defaultHybrid|elapsed|stored|1155.36
motionAndHr|defaultHybrid|elapsed|HEART_RATE|3649.984076253816|1825.2470000000467|5475.231076253863|3649.984076253816|1440|1767312000|1767398340|4135f1edc65ae835
motionAndHr|defaultHybrid|elapsed|HYBRID|1155.36|1825.2470000000505|2980.6070000000504|0.0|1155.36|0.0|1440|1767312000|1767398340|934702c46d4ee320|facb90a980a491a5
motionAndHr|defaultHybrid|elapsed|DYNAMIC_HRR|3033.267392755837|1825.2470000000476|4858.514392755885|3033.267392755837|1440|1767312000|1767398340|9e80560f0a027489|0.0|null
motionAndHr|defaultHybrid|midday|stored|1155.36
motionAndHr|defaultHybrid|midday|HEART_RATE|3649.984076253816|912.6235000000015|4562.607576253818|3649.984076253816|720|1767312000|1767355140|7d7a119f4b79ffd4
motionAndHr|defaultHybrid|midday|HYBRID|1155.36|912.6235000000054|2067.9835000000053|0.0|1155.36|0.0|720|1767312000|1767355140|5c56d5c8a8d8e1c1|6d93b1d0412f1fe5
motionAndHr|defaultHybrid|midday|DYNAMIC_HRR|3033.267392755837|912.6235000000029|3945.89089275584|3033.267392755837|720|1767312000|1767355140|2dea20da97f32e8|0.0|null
motionAndHr|defaultHybrid|earlyMorning|stored|54.99999999999999
motionAndHr|defaultHybrid|earlyMorning|HEART_RATE|180.0608820232662|114.07793749999993|294.13881952326614|180.0608820232662|90|1767312000|1767317340|231f11c57bb92c37
motionAndHr|defaultHybrid|earlyMorning|HYBRID|54.99999999999999|114.07793750000005|169.07793750000005|0.0|54.99999999999999|0.0|90|1767312000|1767317340|d7c426beeaeeb827|f38facf7bb59cd84
motionAndHr|defaultHybrid|earlyMorning|DYNAMIC_HRR|149.8762679484448|114.0779375000001|263.9542054484449|149.8762679484448|90|1767312000|1767317340|ddc387b83f88f6ba|0.0|null
motionAndHr|knobsOffDefault|elapsed|stored|657.3599999999998
motionAndHr|knobsOffDefault|elapsed|HEART_RATE|3188.001100896549|1825.2470000000435|5013.248100896592|3188.001100896549|1440|1767312000|1767398340|ee25ab6324a0a823
motionAndHr|knobsOffDefault|elapsed|HYBRID|657.3599999999998|1825.2470000000503|2482.60700000005|0.0|657.3599999999998|0.0|1440|1767312000|1767398340|f562610768febc1a|d24b5ab07b0495a1
motionAndHr|knobsOffDefault|elapsed|DYNAMIC_HRR|3033.267392755837|1825.2470000000476|4858.514392755885|3033.267392755837|1440|1767312000|1767398340|9e80560f0a027489|0.0|null
motionAndHr|knobsOffDefault|midday|stored|657.3599999999998
motionAndHr|knobsOffDefault|midday|HEART_RATE|3188.001100896549|912.6234999999983|4100.624600896547|3188.001100896549|720|1767312000|1767355140|8335842f73d0a64a
motionAndHr|knobsOffDefault|midday|HYBRID|657.3599999999998|912.6235000000053|1569.983500000005|0.0|657.3599999999998|0.0|720|1767312000|1767355140|a48dc4440560a07b|53ac5fcd4f6151a1
motionAndHr|knobsOffDefault|midday|DYNAMIC_HRR|3033.267392755837|912.6235000000029|3945.89089275584|3033.267392755837|720|1767312000|1767355140|2dea20da97f32e8|0.0|null
motionAndHr|knobsOffDefault|earlyMorning|stored|32.199999999999996
motionAndHr|knobsOffDefault|earlyMorning|HEART_RATE|156.41600290129435|114.07793749999996|270.4939404012943|156.41600290129435|90|1767312000|1767317340|c49af29afde067d3
motionAndHr|knobsOffDefault|earlyMorning|HYBRID|32.199999999999996|114.07793750000013|146.27793750000012|0.0|32.199999999999996|0.0|90|1767312000|1767317340|352dbbdbd99d9fb0|e98d2dadc1cf915
motionAndHr|knobsOffDefault|earlyMorning|DYNAMIC_HRR|149.8762679484448|114.0779375000001|263.9542054484449|149.8762679484448|90|1767312000|1767317340|ddc387b83f88f6ba|0.0|null
motionAndHr|defaultMeasuredBasal|elapsed|stored|3033.267392755837
motionAndHr|defaultMeasuredBasal|elapsed|HEART_RATE|3649.984076253816|1825.2470000000467|5475.231076253863|3649.984076253816|1440|1767312000|1767398340|4135f1edc65ae835
motionAndHr|defaultMeasuredBasal|elapsed|HYBRID|1155.36|1825.2470000000505|2980.6070000000504|0.0|1155.36|0.0|1440|1767312000|1767398340|934702c46d4ee320|facb90a980a491a5
motionAndHr|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|3033.267392755837|1825.2470000000476|4858.514392755885|3033.267392755837|1440|1767312000|1767398340|9e80560f0a027489|0.0|null
motionAndHr|defaultMeasuredBasal|midday|stored|3033.267392755837
motionAndHr|defaultMeasuredBasal|midday|HEART_RATE|3649.984076253816|912.6235000000015|4562.607576253818|3649.984076253816|720|1767312000|1767355140|7d7a119f4b79ffd4
motionAndHr|defaultMeasuredBasal|midday|HYBRID|1155.36|912.6235000000054|2067.9835000000053|0.0|1155.36|0.0|720|1767312000|1767355140|5c56d5c8a8d8e1c1|6d93b1d0412f1fe5
motionAndHr|defaultMeasuredBasal|midday|DYNAMIC_HRR|3033.267392755837|912.6235000000029|3945.89089275584|3033.267392755837|720|1767312000|1767355140|2dea20da97f32e8|0.0|null
motionAndHr|defaultMeasuredBasal|earlyMorning|stored|149.8762679484448
motionAndHr|defaultMeasuredBasal|earlyMorning|HEART_RATE|180.0608820232662|114.07793749999993|294.13881952326614|180.0608820232662|90|1767312000|1767317340|231f11c57bb92c37
motionAndHr|defaultMeasuredBasal|earlyMorning|HYBRID|54.99999999999999|114.07793750000005|169.07793750000005|0.0|54.99999999999999|0.0|90|1767312000|1767317340|d7c426beeaeeb827|f38facf7bb59cd84
motionAndHr|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|149.8762679484448|114.0779375000001|263.9542054484449|149.8762679484448|90|1767312000|1767317340|ddc387b83f88f6ba|0.0|null
motionAndHr|noVitalsFemale|elapsed|stored|837.6360000000003
motionAndHr|noVitalsFemale|elapsed|HEART_RATE|2500.757414612255|1284.1509999998357|3784.908414612091|2500.757414612255|1440|1767312000|1767398340|abc67c9aa83fcf4c
motionAndHr|noVitalsFemale|elapsed|HYBRID|837.6360000000003|1284.1509999999917|2121.786999999992|0.0|837.6360000000003|0.0|1440|1767312000|1767398340|13c6b2d8b5904ec0|93214cba9d7a043d
motionAndHr|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
motionAndHr|noVitalsFemale|midday|stored|837.6360000000003
motionAndHr|noVitalsFemale|midday|HEART_RATE|2500.757414612255|642.0754999999895|3142.8329146122446|2500.757414612255|720|1767312000|1767355140|30b48caadc0ea87d
motionAndHr|noVitalsFemale|midday|HYBRID|837.6360000000003|642.0755000000007|1479.711500000001|0.0|837.6360000000003|0.0|720|1767312000|1767355140|20b9e65fe9d19c61|e1ff7be55e18517d
motionAndHr|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
motionAndHr|noVitalsFemale|earlyMorning|stored|39.875
motionAndHr|noVitalsFemale|earlyMorning|HEART_RATE|123.35942227637467|80.2594375|203.61885977637468|123.35942227637467|90|1767312000|1767317340|f9baf227662a3170
motionAndHr|noVitalsFemale|earlyMorning|HYBRID|39.875|80.25943749999996|120.13443749999996|0.0|39.875|0.0|90|1767312000|1767317340|7e638a8f0dca0179|b24a54cbfc04e43e
motionAndHr|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
ringMETPresent|defaultHr|elapsed|stored|2185.879131031877
ringMETPresent|defaultHr|elapsed|HEART_RATE|2185.879131031877|1825.2470000000658|4011.126131031943|2185.879131031877|1440|1767312000|1767398340|6f05f822cb5d4c10
ringMETPresent|defaultHr|elapsed|HYBRID|1644.0000000000002|1825.2470000000646|3469.247000000065|0.0|1644.0000000000002|0.0|1440|1767312000|1767398340|3d5ddad939477d1d|2dca859947359afc
ringMETPresent|defaultHr|elapsed|DYNAMIC_HRR|1817.090520355618|1825.2470000000658|3642.337520355684|1817.090520355618|1440|1767312000|1767398340|fed574a3ef0f1f5b|0.0|null
ringMETPresent|defaultHr|midday|stored|2185.879131031877
ringMETPresent|defaultHr|midday|HEART_RATE|2185.879131031877|912.6235000000206|3098.5026310318976|2185.879131031877|720|1767312000|1767355140|afee3e283e827c31
ringMETPresent|defaultHr|midday|HYBRID|1644.0000000000002|912.6235000000195|2556.6235000000197|0.0|1644.0000000000002|0.0|720|1767312000|1767355140|c97359ecb96ed48c|5c49cbbb8bc455bc
ringMETPresent|defaultHr|midday|DYNAMIC_HRR|1817.090520355618|912.6235000000206|2729.7140203556387|1817.090520355618|720|1767312000|1767355140|b1d82073ecbbc1f2|0.0|null
ringMETPresent|defaultHr|earlyMorning|stored|174.4131223997243
ringMETPresent|defaultHr|earlyMorning|HEART_RATE|174.4131223997243|114.07793749999993|288.49105989972423|174.4131223997243|90|1767312000|1767317340|63694dd02e2e7648
ringMETPresent|defaultHr|earlyMorning|HYBRID|128.13333333333333|114.07793750000005|242.21127083333337|0.0|128.13333333333333|0.0|90|1767312000|1767317340|8774d14793e6fbf1|33bc9f4a1a0bd74a
ringMETPresent|defaultHr|earlyMorning|DYNAMIC_HRR|147.3264550472327|114.07793750000005|261.40439254723276|147.3264550472327|90|1767312000|1767317340|c23f28432bf62bb4|0.0|null
ringMETPresent|defaultHybrid|elapsed|stored|690.2399999999999
ringMETPresent|defaultHybrid|elapsed|HEART_RATE|2185.879131031877|1825.2470000000658|4011.126131031943|2185.879131031877|1440|1767312000|1767398340|6f05f822cb5d4c10
ringMETPresent|defaultHybrid|elapsed|HYBRID|1644.0000000000002|1825.2470000000646|3469.247000000065|0.0|1644.0000000000002|0.0|1440|1767312000|1767398340|3d5ddad939477d1d|2dca859947359afc
ringMETPresent|defaultHybrid|elapsed|DYNAMIC_HRR|1817.090520355618|1825.2470000000658|3642.337520355684|1817.090520355618|1440|1767312000|1767398340|fed574a3ef0f1f5b|0.0|null
ringMETPresent|defaultHybrid|midday|stored|690.2399999999999
ringMETPresent|defaultHybrid|midday|HEART_RATE|2185.879131031877|912.6235000000206|3098.5026310318976|2185.879131031877|720|1767312000|1767355140|afee3e283e827c31
ringMETPresent|defaultHybrid|midday|HYBRID|1644.0000000000002|912.6235000000195|2556.6235000000197|0.0|1644.0000000000002|0.0|720|1767312000|1767355140|c97359ecb96ed48c|5c49cbbb8bc455bc
ringMETPresent|defaultHybrid|midday|DYNAMIC_HRR|1817.090520355618|912.6235000000206|2729.7140203556387|1817.090520355618|720|1767312000|1767355140|b1d82073ecbbc1f2|0.0|null
ringMETPresent|defaultHybrid|earlyMorning|stored|54.99999999999999
ringMETPresent|defaultHybrid|earlyMorning|HEART_RATE|174.4131223997243|114.07793749999993|288.49105989972423|174.4131223997243|90|1767312000|1767317340|63694dd02e2e7648
ringMETPresent|defaultHybrid|earlyMorning|HYBRID|128.13333333333333|114.07793750000005|242.21127083333337|0.0|128.13333333333333|0.0|90|1767312000|1767317340|8774d14793e6fbf1|33bc9f4a1a0bd74a
ringMETPresent|defaultHybrid|earlyMorning|DYNAMIC_HRR|147.3264550472327|114.07793750000005|261.40439254723276|147.3264550472327|90|1767312000|1767317340|c23f28432bf62bb4|0.0|null
ringMETPresent|knobsOffDefault|elapsed|stored|394.3599999999999
ringMETPresent|knobsOffDefault|elapsed|HEART_RATE|1908.4639603375417|1825.2470000000644|3733.710960337606|1908.4639603375417|1440|1767312000|1767398340|a264f0620746d467
ringMETPresent|knobsOffDefault|elapsed|HYBRID|1521.2000000000005|1825.2470000000656|3346.447000000066|0.0|1521.2000000000005|0.0|1440|1767312000|1767398340|10d8d5cc9452daab|851ed8300d81c98a
ringMETPresent|knobsOffDefault|elapsed|DYNAMIC_HRR|1817.090520355618|1825.2470000000658|3642.337520355684|1817.090520355618|1440|1767312000|1767398340|fed574a3ef0f1f5b|0.0|null
ringMETPresent|knobsOffDefault|midday|stored|394.3599999999999
ringMETPresent|knobsOffDefault|midday|HEART_RATE|1908.4639603375417|912.6235000000192|2821.087460337561|1908.4639603375417|720|1767312000|1767355140|8019dabc4533664e
ringMETPresent|knobsOffDefault|midday|HYBRID|1521.2000000000005|912.6235000000204|2433.823500000021|0.0|1521.2000000000005|0.0|720|1767312000|1767355140|f46b2f3e7ac4e502|daab44695f7d5d0a
ringMETPresent|knobsOffDefault|midday|DYNAMIC_HRR|1817.090520355618|912.6235000000206|2729.7140203556387|1817.090520355618|720|1767312000|1767355140|b1d82073ecbbc1f2|0.0|null
ringMETPresent|knobsOffDefault|earlyMorning|stored|32.199999999999996
ringMETPresent|knobsOffDefault|earlyMorning|HEART_RATE|151.7827160329978|114.07793749999999|265.8606535329978|151.7827160329978|90|1767312000|1767317340|3000455858f12ce0
ringMETPresent|knobsOffDefault|earlyMorning|HYBRID|116.53333333333335|114.07793750000006|230.6112708333334|0.0|116.53333333333335|0.0|90|1767312000|1767317340|1aacbd2fa2fc4cd7|2b43a6c269da6d8c
ringMETPresent|knobsOffDefault|earlyMorning|DYNAMIC_HRR|147.3264550472327|114.07793750000005|261.40439254723276|147.3264550472327|90|1767312000|1767317340|c23f28432bf62bb4|0.0|null
ringMETPresent|defaultMeasuredBasal|elapsed|stored|1817.090520355618
ringMETPresent|defaultMeasuredBasal|elapsed|HEART_RATE|2185.879131031877|1825.2470000000658|4011.126131031943|2185.879131031877|1440|1767312000|1767398340|6f05f822cb5d4c10
ringMETPresent|defaultMeasuredBasal|elapsed|HYBRID|1644.0000000000002|1825.2470000000646|3469.247000000065|0.0|1644.0000000000002|0.0|1440|1767312000|1767398340|3d5ddad939477d1d|2dca859947359afc
ringMETPresent|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|1817.090520355618|1825.2470000000658|3642.337520355684|1817.090520355618|1440|1767312000|1767398340|fed574a3ef0f1f5b|0.0|null
ringMETPresent|defaultMeasuredBasal|midday|stored|1817.090520355618
ringMETPresent|defaultMeasuredBasal|midday|HEART_RATE|2185.879131031877|912.6235000000206|3098.5026310318976|2185.879131031877|720|1767312000|1767355140|afee3e283e827c31
ringMETPresent|defaultMeasuredBasal|midday|HYBRID|1644.0000000000002|912.6235000000195|2556.6235000000197|0.0|1644.0000000000002|0.0|720|1767312000|1767355140|c97359ecb96ed48c|5c49cbbb8bc455bc
ringMETPresent|defaultMeasuredBasal|midday|DYNAMIC_HRR|1817.090520355618|912.6235000000206|2729.7140203556387|1817.090520355618|720|1767312000|1767355140|b1d82073ecbbc1f2|0.0|null
ringMETPresent|defaultMeasuredBasal|earlyMorning|stored|147.3264550472327
ringMETPresent|defaultMeasuredBasal|earlyMorning|HEART_RATE|174.4131223997243|114.07793749999993|288.49105989972423|174.4131223997243|90|1767312000|1767317340|63694dd02e2e7648
ringMETPresent|defaultMeasuredBasal|earlyMorning|HYBRID|128.13333333333333|114.07793750000005|242.21127083333337|0.0|128.13333333333333|0.0|90|1767312000|1767317340|8774d14793e6fbf1|33bc9f4a1a0bd74a
ringMETPresent|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|147.3264550472327|114.07793750000005|261.40439254723276|147.3264550472327|90|1767312000|1767317340|c23f28432bf62bb4|0.0|null
ringMETPresent|noVitalsFemale|elapsed|stored|500.42399999999964
ringMETPresent|noVitalsFemale|elapsed|HEART_RATE|1497.9773947843637|1284.1509999998266|2782.1283947841903|1497.9773947843637|1440|1767312000|1767398340|205856124c6709d7
ringMETPresent|noVitalsFemale|elapsed|HYBRID|1191.900000000002|1284.1509999999034|2476.0509999999053|0.0|1191.900000000002|0.0|1440|1767312000|1767398340|2974cc4754762759|b2a41a631e45e8a2
ringMETPresent|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
ringMETPresent|noVitalsFemale|midday|stored|500.42399999999964
ringMETPresent|noVitalsFemale|midday|HEART_RATE|1497.9773947843637|642.0754999999804|2140.052894784344|1497.9773947843637|720|1767312000|1767355140|152113a660cf983e
ringMETPresent|noVitalsFemale|midday|HYBRID|1191.900000000002|642.0755000000029|1833.9755000000048|0.0|1191.900000000002|0.0|720|1767312000|1767355140|ae41acaebe245f38|5723d7155e662062
ringMETPresent|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
ringMETPresent|noVitalsFemale|earlyMorning|stored|39.875
ringMETPresent|noVitalsFemale|earlyMorning|HEART_RATE|119.3728510612961|80.25943749999999|199.6322885612961|119.3728510612961|90|1767312000|1767317340|a61efe1f940e6eb0
ringMETPresent|noVitalsFemale|earlyMorning|HYBRID|92.89666666666668|80.25943749999999|173.15610416666667|0.0|92.89666666666668|0.0|90|1767312000|1767317340|e20a5077ebec269|871137be1394eee6
ringMETPresent|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
inProgress|defaultHr|elapsed|stored|2916.125552568586
inProgress|defaultHr|elapsed|HEART_RATE|2916.125552568586|1825.2470000000626|4741.372552568649|2916.125552568586|1440|1767312000|1767398340|59938bb896ed4b22
inProgress|defaultHr|elapsed|HYBRID|919.1599999999997|1825.2470000000603|2744.40700000006|0.0|919.1599999999997|0.0|1440|1767312000|1767398340|ca010dbb5a6cdd83|d26193bbce203e08
inProgress|defaultHr|elapsed|DYNAMIC_HRR|2426.070847551026|1825.2470000000608|4251.317847551087|2426.070847551026|1440|1767312000|1767398340|706022a737009d26|0.0|null
inProgress|defaultHr|midday|stored|2916.125552568586
inProgress|defaultHr|midday|HEART_RATE|2916.125552568586|912.6235000000179|3828.749052568604|2916.125552568586|720|1767312000|1767355140|e6bf0780d12d0cd3
inProgress|defaultHr|midday|HYBRID|919.1599999999997|912.6235000000155|1831.7835000000152|0.0|919.1599999999997|0.0|720|1767312000|1767355140|676b4659eba450aa|aa4b7c7ed6b946c8
inProgress|defaultHr|midday|DYNAMIC_HRR|2426.070847551026|912.6235000000161|3338.694347551042|2426.070847551026|720|1767312000|1767355140|884585121b854e77|0.0|null
inProgress|defaultHr|earlyMorning|stored|546.2451231520496
inProgress|defaultHr|earlyMorning|HEART_RATE|546.2451231520496|114.07793749999973|660.3230606520493|546.2451231520496|90|1767312000|1767317340|af886d66dc7642e7
inProgress|defaultHr|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767312000|1767317340|3118814b138d970f|3ff1afc1afea9422
inProgress|defaultHr|earlyMorning|DYNAMIC_HRR|456.9895825504624|114.07793749999973|571.0675200504621|456.9895825504624|90|1767312000|1767317340|49e443436f475257|0.0|null
inProgress|defaultHybrid|elapsed|stored|919.1599999999997
inProgress|defaultHybrid|elapsed|HEART_RATE|2916.125552568586|1825.2470000000626|4741.372552568649|2916.125552568586|1440|1767312000|1767398340|59938bb896ed4b22
inProgress|defaultHybrid|elapsed|HYBRID|919.1599999999997|1825.2470000000603|2744.40700000006|0.0|919.1599999999997|0.0|1440|1767312000|1767398340|ca010dbb5a6cdd83|d26193bbce203e08
inProgress|defaultHybrid|elapsed|DYNAMIC_HRR|2426.070847551026|1825.2470000000608|4251.317847551087|2426.070847551026|1440|1767312000|1767398340|706022a737009d26|0.0|null
inProgress|defaultHybrid|midday|stored|919.1599999999997
inProgress|defaultHybrid|midday|HEART_RATE|2916.125552568586|912.6235000000179|3828.749052568604|2916.125552568586|720|1767312000|1767355140|e6bf0780d12d0cd3
inProgress|defaultHybrid|midday|HYBRID|919.1599999999997|912.6235000000155|1831.7835000000152|0.0|919.1599999999997|0.0|720|1767312000|1767355140|676b4659eba450aa|aa4b7c7ed6b946c8
inProgress|defaultHybrid|midday|DYNAMIC_HRR|2426.070847551026|912.6235000000161|3338.694347551042|2426.070847551026|720|1767312000|1767355140|884585121b854e77|0.0|null
inProgress|defaultHybrid|earlyMorning|stored|170.55999999999995
inProgress|defaultHybrid|earlyMorning|HEART_RATE|546.2451231520496|114.07793749999973|660.3230606520493|546.2451231520496|90|1767312000|1767317340|af886d66dc7642e7
inProgress|defaultHybrid|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767312000|1767317340|3118814b138d970f|3ff1afc1afea9422
inProgress|defaultHybrid|earlyMorning|DYNAMIC_HRR|456.9895825504624|114.07793749999973|571.0675200504621|456.9895825504624|90|1767312000|1767317340|49e443436f475257|0.0|null
inProgress|knobsOffDefault|elapsed|stored|523.4799999999998
inProgress|knobsOffDefault|elapsed|HEART_RATE|2545.9850153629363|1825.2470000000644|4371.232015363001|2545.9850153629363|1440|1767312000|1767398340|3c7e058223c751eb
inProgress|knobsOffDefault|elapsed|HYBRID|523.4799999999998|1825.2470000000556|2348.7270000000553|0.0|523.4799999999998|0.0|1440|1767312000|1767398340|ee232523aa66c025|3c7298b7ba0d0018
inProgress|knobsOffDefault|elapsed|DYNAMIC_HRR|2426.070847551026|1825.2470000000608|4251.317847551087|2426.070847551026|1440|1767312000|1767398340|706022a737009d26|0.0|null
inProgress|knobsOffDefault|midday|stored|523.4799999999998
inProgress|knobsOffDefault|midday|HEART_RATE|2545.9850153629363|912.6235000000192|3458.6085153629556|2545.9850153629363|720|1767312000|1767355140|4b09e77d800bec42
inProgress|knobsOffDefault|midday|HYBRID|523.4799999999998|912.6235000000104|1436.1035000000102|0.0|523.4799999999998|0.0|720|1767312000|1767355140|bacfc2be94819984|e9d39501c9fafc98
inProgress|knobsOffDefault|midday|DYNAMIC_HRR|2426.070847551026|912.6235000000161|3338.694347551042|2426.070847551026|720|1767312000|1767355140|884585121b854e77|0.0|null
inProgress|knobsOffDefault|earlyMorning|stored|99.07999999999998
inProgress|knobsOffDefault|earlyMorning|HEART_RATE|475.6955740973292|114.07793750000008|589.7735115973293|475.6955740973292|90|1767312000|1767317340|da416a109b96c9ef
inProgress|knobsOffDefault|earlyMorning|HYBRID|99.07999999999998|114.07793750000025|213.15793750000023|0.0|99.07999999999998|0.0|90|1767312000|1767317340|cfa241c1036d8d33|cc4759ae743d84a8
inProgress|knobsOffDefault|earlyMorning|DYNAMIC_HRR|456.9895825504624|114.07793749999973|571.0675200504621|456.9895825504624|90|1767312000|1767317340|49e443436f475257|0.0|null
inProgress|defaultMeasuredBasal|elapsed|stored|2426.070847551026
inProgress|defaultMeasuredBasal|elapsed|HEART_RATE|2916.125552568586|1825.2470000000626|4741.372552568649|2916.125552568586|1440|1767312000|1767398340|59938bb896ed4b22
inProgress|defaultMeasuredBasal|elapsed|HYBRID|919.1599999999997|1825.2470000000603|2744.40700000006|0.0|919.1599999999997|0.0|1440|1767312000|1767398340|ca010dbb5a6cdd83|d26193bbce203e08
inProgress|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|2426.070847551026|1825.2470000000608|4251.317847551087|2426.070847551026|1440|1767312000|1767398340|706022a737009d26|0.0|null
inProgress|defaultMeasuredBasal|midday|stored|2426.070847551026
inProgress|defaultMeasuredBasal|midday|HEART_RATE|2916.125552568586|912.6235000000179|3828.749052568604|2916.125552568586|720|1767312000|1767355140|e6bf0780d12d0cd3
inProgress|defaultMeasuredBasal|midday|HYBRID|919.1599999999997|912.6235000000155|1831.7835000000152|0.0|919.1599999999997|0.0|720|1767312000|1767355140|676b4659eba450aa|aa4b7c7ed6b946c8
inProgress|defaultMeasuredBasal|midday|DYNAMIC_HRR|2426.070847551026|912.6235000000161|3338.694347551042|2426.070847551026|720|1767312000|1767355140|884585121b854e77|0.0|null
inProgress|defaultMeasuredBasal|earlyMorning|stored|456.9895825504624
inProgress|defaultMeasuredBasal|earlyMorning|HEART_RATE|546.2451231520496|114.07793749999973|660.3230606520493|546.2451231520496|90|1767312000|1767317340|af886d66dc7642e7
inProgress|defaultMeasuredBasal|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767312000|1767317340|3118814b138d970f|3ff1afc1afea9422
inProgress|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|456.9895825504624|114.07793749999973|571.0675200504621|456.9895825504624|90|1767312000|1767317340|49e443436f475257|0.0|null
inProgress|noVitalsFemale|elapsed|stored|666.3909999999998
inProgress|noVitalsFemale|elapsed|HEART_RATE|1997.835228854343|1284.1509999997968|3281.98622885414|1997.835228854343|1440|1767312000|1767398340|a15d5063f041ddaa
inProgress|noVitalsFemale|elapsed|HYBRID|666.3909999999998|1284.1510000000146|1950.5420000000145|0.0|666.3909999999998|0.0|1440|1767312000|1767398340|42eb5c65a2b0c5a8|c89536e4a2e05b83
inProgress|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
inProgress|noVitalsFemale|midday|stored|666.3909999999998
inProgress|noVitalsFemale|midday|HEART_RATE|1997.835228854343|642.0754999999506|2639.9107288542937|1997.835228854343|720|1767312000|1767355140|1798709b555008b
inProgress|noVitalsFemale|midday|HYBRID|666.3909999999998|642.0755000000047|1308.4665000000045|0.0|666.3909999999998|0.0|720|1767312000|1767355140|b6f3c02d7a231179|99e4c2db574e4dc3
inProgress|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
inProgress|noVitalsFemale|earlyMorning|stored|123.65600000000002
inProgress|noVitalsFemale|earlyMorning|HEART_RATE|373.8222246923242|80.2594375000001|454.0816621923243|373.8222246923242|90|1767312000|1767317340|8d2c6a86e64f001
inProgress|noVitalsFemale|earlyMorning|HYBRID|123.65600000000002|80.25943750000009|203.9154375000001|0.0|123.65600000000002|0.0|90|1767312000|1767317340|6df85499a623893|6a85b014113b535a
inProgress|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
spillover|defaultHr|elapsed|stored|1453.9585257199114
spillover|defaultHr|elapsed|HEART_RATE|1453.9585257199114|1825.2470000000098|3279.2055257199213|1453.9585257199114|1440|1767312000|1767398340|378995d763637a92
spillover|defaultHr|elapsed|HYBRID|1453.9585257199114|1825.2470000000098|3279.2055257199213|0.0|0.0|1453.9585257199114|1440|1767312000|1767398340|378995d763637a92|887cdc1617909bf
spillover|defaultHr|elapsed|DYNAMIC_HRR|1211.371984658997|1825.2470000000076|3036.6189846590046|1211.371984658997|1440|1767312000|1767398340|648458d28133c62c|0.0|null
spillover|defaultHr|midday|stored|null
spillover|defaultHr|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
spillover|defaultHr|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
spillover|defaultHr|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
spillover|defaultHr|earlyMorning|stored|null
spillover|defaultHr|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
spillover|defaultHr|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
spillover|defaultHr|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
spillover|defaultHybrid|elapsed|stored|1453.9585257199114
spillover|defaultHybrid|elapsed|HEART_RATE|1453.9585257199114|1825.2470000000098|3279.2055257199213|1453.9585257199114|1440|1767312000|1767398340|378995d763637a92
spillover|defaultHybrid|elapsed|HYBRID|1453.9585257199114|1825.2470000000098|3279.2055257199213|0.0|0.0|1453.9585257199114|1440|1767312000|1767398340|378995d763637a92|887cdc1617909bf
spillover|defaultHybrid|elapsed|DYNAMIC_HRR|1211.371984658997|1825.2470000000076|3036.6189846590046|1211.371984658997|1440|1767312000|1767398340|648458d28133c62c|0.0|null
spillover|defaultHybrid|midday|stored|null
spillover|defaultHybrid|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
spillover|defaultHybrid|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
spillover|defaultHybrid|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
spillover|defaultHybrid|earlyMorning|stored|null
spillover|defaultHybrid|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
spillover|defaultHybrid|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
spillover|defaultHybrid|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
spillover|knobsOffDefault|elapsed|stored|0.0
spillover|knobsOffDefault|elapsed|HEART_RATE|1268.3562630605754|1825.2470000000087|3093.603263060584|1268.3562630605754|1440|1767312000|1767398340|f279142afb4e29a8
spillover|knobsOffDefault|elapsed|HYBRID|0.0|1825.2470000000212|1825.2470000000212|0.0|0.0|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|d51e5a62be94e2eb
spillover|knobsOffDefault|elapsed|DYNAMIC_HRR|1211.371984658997|1825.2470000000076|3036.6189846590046|1211.371984658997|1440|1767312000|1767398340|648458d28133c62c|0.0|null
spillover|knobsOffDefault|midday|stored|null
spillover|knobsOffDefault|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
spillover|knobsOffDefault|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|d223a05eb2fee76b
spillover|knobsOffDefault|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
spillover|knobsOffDefault|earlyMorning|stored|null
spillover|knobsOffDefault|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
spillover|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|b5b409904d6fa25b
spillover|knobsOffDefault|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
spillover|defaultMeasuredBasal|elapsed|stored|1211.371984658997
spillover|defaultMeasuredBasal|elapsed|HEART_RATE|1453.9585257199114|1825.2470000000098|3279.2055257199213|1453.9585257199114|1440|1767312000|1767398340|378995d763637a92
spillover|defaultMeasuredBasal|elapsed|HYBRID|1453.9585257199114|1825.2470000000098|3279.2055257199213|0.0|0.0|1453.9585257199114|1440|1767312000|1767398340|378995d763637a92|887cdc1617909bf
spillover|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|1211.371984658997|1825.2470000000076|3036.6189846590046|1211.371984658997|1440|1767312000|1767398340|648458d28133c62c|0.0|null
spillover|defaultMeasuredBasal|midday|stored|null
spillover|defaultMeasuredBasal|midday|HEART_RATE|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d
spillover|defaultMeasuredBasal|midday|HYBRID|0.0|912.6234999999859|912.6234999999859|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
spillover|defaultMeasuredBasal|midday|DYNAMIC_HRR|0.0|912.6234999999859|912.6234999999859|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
spillover|defaultMeasuredBasal|earlyMorning|stored|null
spillover|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
spillover|defaultMeasuredBasal|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
spillover|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
spillover|noVitalsFemale|elapsed|stored|995.804455472944
spillover|noVitalsFemale|elapsed|HEART_RATE|995.804455472944|1284.1510000000126|2279.9554554729566|995.804455472944|1440|1767312000|1767398340|ff375afed12f4fae
spillover|noVitalsFemale|elapsed|HYBRID|995.804455472944|1284.1510000000126|2279.9554554729566|0.0|0.0|995.804455472944|1440|1767312000|1767398340|ff375afed12f4fae|aa4563ed69b679bf
spillover|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
spillover|noVitalsFemale|midday|stored|null
spillover|noVitalsFemale|midday|HEART_RATE|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d
spillover|noVitalsFemale|midday|HYBRID|0.0|642.0755000000079|642.0755000000079|0.0|0.0|0.0|720|1767312000|1767355140|3aed6bc931c5730d|e997de0449a57dab
spillover|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
spillover|noVitalsFemale|earlyMorning|stored|null
spillover|noVitalsFemale|earlyMorning|HEART_RATE|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa
spillover|noVitalsFemale|earlyMorning|HYBRID|0.0|80.25943749999998|80.25943749999998|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|d3fa07504c954153
spillover|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
    """

    private val RECORDED_3 = """
boundary|defaultHr|elapsed|stored|23.85136932801768
boundary|defaultHr|elapsed|HEART_RATE|23.85136932801768|1825.2470000000224|1849.09836932804|23.85136932801768|1440|1767312000|1767398340|252e57946e3c0547
boundary|defaultHr|elapsed|HYBRID|18.225872975573136|1825.247000000022|1843.472872975595|0.0|6.4|11.825872975573136|1440|1767312000|1767398340|c8ce73b3683e65c0|983cb3e1bf18fdd5
boundary|defaultHr|elapsed|DYNAMIC_HRR|0.381321103218455|1825.2470000000212|1825.6283211032396|0.381321103218455|1440|1767312000|1767398340|aece4cab5980ff8e|0.0|null
boundary|defaultHr|midday|stored|0.19709788292621894
boundary|defaultHr|midday|HEART_RATE|0.19709788292621894|912.6234999999859|912.8205978829121|0.19709788292621894|720|1767312000|1767355140|b67b126ab51a8b3
boundary|defaultHr|midday|HYBRID|3.2|912.6234999999857|915.8234999999858|0.0|3.2|0.0|720|1767312000|1767355140|78464b3d19ab8890|6436ea5cb8e3685c
boundary|defaultHr|midday|DYNAMIC_HRR|0.18915693399601288|912.6234999999858|912.8126569339819|0.18915693399601288|720|1767312000|1767355140|e9de38e674f65e8b|0.0|null
boundary|defaultHr|earlyMorning|stored|0.19709788292621894
boundary|defaultHr|earlyMorning|HEART_RATE|0.19709788292621894|114.0779375000002|114.27503538292642|0.19709788292621894|90|1767312000|1767317340|c2a2cfac72f05ff8
boundary|defaultHr|earlyMorning|HYBRID|3.2|114.0779375000002|117.2779375000002|0.0|3.2|0.0|90|1767312000|1767317340|829dca174a922277|e1e7e6b3cf624d94
boundary|defaultHr|earlyMorning|DYNAMIC_HRR|0.18915693399601288|114.07793750000019|114.2670944339962|0.18915693399601288|90|1767312000|1767317340|426f7e2cafadbd30|0.0|null
boundary|defaultHybrid|elapsed|stored|18.225872975573136
boundary|defaultHybrid|elapsed|HEART_RATE|23.85136932801768|1825.2470000000224|1849.09836932804|23.85136932801768|1440|1767312000|1767398340|252e57946e3c0547
boundary|defaultHybrid|elapsed|HYBRID|18.225872975573136|1825.247000000022|1843.472872975595|0.0|6.4|11.825872975573136|1440|1767312000|1767398340|c8ce73b3683e65c0|983cb3e1bf18fdd5
boundary|defaultHybrid|elapsed|DYNAMIC_HRR|0.381321103218455|1825.2470000000212|1825.6283211032396|0.381321103218455|1440|1767312000|1767398340|aece4cab5980ff8e|0.0|null
boundary|defaultHybrid|midday|stored|3.2
boundary|defaultHybrid|midday|HEART_RATE|0.19709788292621894|912.6234999999859|912.8205978829121|0.19709788292621894|720|1767312000|1767355140|b67b126ab51a8b3
boundary|defaultHybrid|midday|HYBRID|3.2|912.6234999999857|915.8234999999858|0.0|3.2|0.0|720|1767312000|1767355140|78464b3d19ab8890|6436ea5cb8e3685c
boundary|defaultHybrid|midday|DYNAMIC_HRR|0.18915693399601288|912.6234999999858|912.8126569339819|0.18915693399601288|720|1767312000|1767355140|e9de38e674f65e8b|0.0|null
boundary|defaultHybrid|earlyMorning|stored|3.2
boundary|defaultHybrid|earlyMorning|HEART_RATE|0.19709788292621894|114.0779375000002|114.27503538292642|0.19709788292621894|90|1767312000|1767317340|c2a2cfac72f05ff8
boundary|defaultHybrid|earlyMorning|HYBRID|3.2|114.0779375000002|117.2779375000002|0.0|3.2|0.0|90|1767312000|1767317340|829dca174a922277|e1e7e6b3cf624d94
boundary|defaultHybrid|earlyMorning|DYNAMIC_HRR|0.18915693399601288|114.07793750000019|114.2670944339962|0.18915693399601288|90|1767312000|1767317340|426f7e2cafadbd30|0.0|null
boundary|knobsOffDefault|elapsed|stored|6.4
boundary|knobsOffDefault|elapsed|HEART_RATE|23.85136932801768|1825.2470000000224|1849.09836932804|23.85136932801768|1440|1767312000|1767398340|252e57946e3c0547
boundary|knobsOffDefault|elapsed|HYBRID|6.4|1825.2470000000212|1831.6470000000213|0.0|6.4|0.0|1440|1767312000|1767398340|b549e29a9d2b8d0a|147b1e0603be438f
boundary|knobsOffDefault|elapsed|DYNAMIC_HRR|0.381321103218455|1825.2470000000212|1825.6283211032396|0.381321103218455|1440|1767312000|1767398340|aece4cab5980ff8e|0.0|null
boundary|knobsOffDefault|midday|stored|3.2
boundary|knobsOffDefault|midday|HEART_RATE|0.19709788292621894|912.6234999999859|912.8205978829121|0.19709788292621894|720|1767312000|1767355140|b67b126ab51a8b3
boundary|knobsOffDefault|midday|HYBRID|3.2|912.6234999999857|915.8234999999858|0.0|3.2|0.0|720|1767312000|1767355140|78464b3d19ab8890|3591a6807c35b331
boundary|knobsOffDefault|midday|DYNAMIC_HRR|0.18915693399601288|912.6234999999858|912.8126569339819|0.18915693399601288|720|1767312000|1767355140|e9de38e674f65e8b|0.0|null
boundary|knobsOffDefault|earlyMorning|stored|3.2
boundary|knobsOffDefault|earlyMorning|HEART_RATE|0.19709788292621894|114.0779375000002|114.27503538292642|0.19709788292621894|90|1767312000|1767317340|c2a2cfac72f05ff8
boundary|knobsOffDefault|earlyMorning|HYBRID|3.2|114.0779375000002|117.2779375000002|0.0|3.2|0.0|90|1767312000|1767317340|829dca174a922277|ea8ef30056f83c71
boundary|knobsOffDefault|earlyMorning|DYNAMIC_HRR|0.18915693399601288|114.07793750000019|114.2670944339962|0.18915693399601288|90|1767312000|1767317340|426f7e2cafadbd30|0.0|null
boundary|defaultMeasuredBasal|elapsed|stored|0.381321103218455
boundary|defaultMeasuredBasal|elapsed|HEART_RATE|23.85136932801768|1825.2470000000224|1849.09836932804|23.85136932801768|1440|1767312000|1767398340|252e57946e3c0547
boundary|defaultMeasuredBasal|elapsed|HYBRID|18.225872975573136|1825.247000000022|1843.472872975595|0.0|6.4|11.825872975573136|1440|1767312000|1767398340|c8ce73b3683e65c0|983cb3e1bf18fdd5
boundary|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|0.381321103218455|1825.2470000000212|1825.6283211032396|0.381321103218455|1440|1767312000|1767398340|aece4cab5980ff8e|0.0|null
boundary|defaultMeasuredBasal|midday|stored|0.18915693399601288
boundary|defaultMeasuredBasal|midday|HEART_RATE|0.19709788292621894|912.6234999999859|912.8205978829121|0.19709788292621894|720|1767312000|1767355140|b67b126ab51a8b3
boundary|defaultMeasuredBasal|midday|HYBRID|3.2|912.6234999999857|915.8234999999858|0.0|3.2|0.0|720|1767312000|1767355140|78464b3d19ab8890|6436ea5cb8e3685c
boundary|defaultMeasuredBasal|midday|DYNAMIC_HRR|0.18915693399601288|912.6234999999858|912.8126569339819|0.18915693399601288|720|1767312000|1767355140|e9de38e674f65e8b|0.0|null
boundary|defaultMeasuredBasal|earlyMorning|stored|0.18915693399601288
boundary|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.19709788292621894|114.0779375000002|114.27503538292642|0.19709788292621894|90|1767312000|1767317340|c2a2cfac72f05ff8
boundary|defaultMeasuredBasal|earlyMorning|HYBRID|3.2|114.0779375000002|117.2779375000002|0.0|3.2|0.0|90|1767312000|1767317340|829dca174a922277|e1e7e6b3cf624d94
boundary|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|0.18915693399601288|114.07793750000019|114.2670944339962|0.18915693399601288|90|1767312000|1767317340|426f7e2cafadbd30|0.0|null
boundary|noVitalsFemale|elapsed|stored|12.933792525759507
boundary|noVitalsFemale|elapsed|HEART_RATE|16.727596316368352|1284.151000000018|1300.8785963163864|16.727596316368352|1440|1767312000|1767398340|b55e497619f7d9a0
boundary|noVitalsFemale|elapsed|HYBRID|12.933792525759507|1284.1510000000178|1297.0847925257774|0.0|4.64|8.293792525759507|1440|1767312000|1767398340|4cc1ad452501c9bf|dd004d6032c83fd4
boundary|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
boundary|noVitalsFemale|midday|stored|2.32
boundary|noVitalsFemale|midday|HEART_RATE|0.1382298754293251|642.0755000000078|642.2137298754371|0.1382298754293251|720|1767312000|1767355140|c521ff7bc3414fa
boundary|noVitalsFemale|midday|HYBRID|2.32|642.0755000000079|644.3955000000079|0.0|2.32|0.0|720|1767312000|1767355140|6b0f31500abfc4a4|e29d3f1dbe0583f0
boundary|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
boundary|noVitalsFemale|earlyMorning|stored|2.32
boundary|noVitalsFemale|earlyMorning|HEART_RATE|0.1382298754293251|80.25943749999996|80.3976673754293|0.1382298754293251|90|1767312000|1767317340|496e26088e4ef859
boundary|noVitalsFemale|earlyMorning|HYBRID|2.32|80.25943749999998|82.57943749999997|0.0|2.32|0.0|90|1767312000|1767317340|612679538be1e923|c7a93b87622c7768
boundary|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
dstSpringForward|defaultHr|elapsed|stored|3651.3110541270516
dstSpringForward|defaultHr|elapsed|HEART_RATE|3651.3110541270516|1825.247000000054|5476.558054127106|3651.3110541270516|1440|1767308400|1767394740|d0907307677273a6
dstSpringForward|defaultHr|elapsed|HYBRID|1155.36|1825.2470000000542|2980.607000000054|0.0|1155.36|0.0|1440|1767308400|1767394740|a2ba4a97f366dd17|3b597a5425504605
dstSpringForward|defaultHr|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767308400|1767394740|22f8c678bcb37e36|0.0|null
dstSpringForward|defaultHr|midday|stored|3651.3110541270516
dstSpringForward|defaultHr|midday|HEART_RATE|3651.3110541270516|912.6235000000088|4563.93455412706|3651.3110541270516|720|1767308400|1767351540|9c125cf8009d39dc
dstSpringForward|defaultHr|midday|HYBRID|1155.36|912.623500000009|2067.983500000009|0.0|1155.36|0.0|720|1767308400|1767351540|607d0847fc07290d|faeb1487e8a35c45
dstSpringForward|defaultHr|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767308400|1767351540|db2285cf7a9de06c|0.0|null
dstSpringForward|defaultHr|earlyMorning|stored|545.6252835834629
dstSpringForward|defaultHr|earlyMorning|HEART_RATE|545.6252835834629|114.07793749999985|659.7032210834627|545.6252835834629|90|1767308400|1767313740|f99ab3becef6be35
dstSpringForward|defaultHr|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767308400|1767313740|a8e71fc96506d9d2|3ff1afc1afea9422
dstSpringForward|defaultHr|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767308400|1767313740|4d98eab0b8de64d1|0.0|null
dstSpringForward|defaultHybrid|elapsed|stored|1155.36
dstSpringForward|defaultHybrid|elapsed|HEART_RATE|3651.3110541270516|1825.247000000054|5476.558054127106|3651.3110541270516|1440|1767308400|1767394740|d0907307677273a6
dstSpringForward|defaultHybrid|elapsed|HYBRID|1155.36|1825.2470000000542|2980.607000000054|0.0|1155.36|0.0|1440|1767308400|1767394740|a2ba4a97f366dd17|3b597a5425504605
dstSpringForward|defaultHybrid|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767308400|1767394740|22f8c678bcb37e36|0.0|null
dstSpringForward|defaultHybrid|midday|stored|1155.36
dstSpringForward|defaultHybrid|midday|HEART_RATE|3651.3110541270516|912.6235000000088|4563.93455412706|3651.3110541270516|720|1767308400|1767351540|9c125cf8009d39dc
dstSpringForward|defaultHybrid|midday|HYBRID|1155.36|912.623500000009|2067.983500000009|0.0|1155.36|0.0|720|1767308400|1767351540|607d0847fc07290d|faeb1487e8a35c45
dstSpringForward|defaultHybrid|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767308400|1767351540|db2285cf7a9de06c|0.0|null
dstSpringForward|defaultHybrid|earlyMorning|stored|170.55999999999995
dstSpringForward|defaultHybrid|earlyMorning|HEART_RATE|545.6252835834629|114.07793749999985|659.7032210834627|545.6252835834629|90|1767308400|1767313740|f99ab3becef6be35
dstSpringForward|defaultHybrid|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767308400|1767313740|a8e71fc96506d9d2|3ff1afc1afea9422
dstSpringForward|defaultHybrid|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767308400|1767313740|4d98eab0b8de64d1|0.0|null
dstSpringForward|knobsOffDefault|elapsed|stored|657.3599999999998
dstSpringForward|knobsOffDefault|elapsed|HEART_RATE|3189.3301668429267|1825.2470000000585|5014.577166842985|3189.3301668429267|1440|1767308400|1767394740|da0edfc3a4c3874c
dstSpringForward|knobsOffDefault|elapsed|HYBRID|657.3599999999998|1825.247000000053|2482.6070000000527|0.0|657.3599999999998|0.0|1440|1767308400|1767394740|1b9dac5fbabf1273|e970d66572ece2c1
dstSpringForward|knobsOffDefault|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767308400|1767394740|22f8c678bcb37e36|0.0|null
dstSpringForward|knobsOffDefault|midday|stored|657.3599999999998
dstSpringForward|knobsOffDefault|midday|HEART_RATE|3189.3301668429267|912.6235000000133|4101.95366684294|3189.3301668429267|720|1767308400|1767351540|dd22032ff451a10a
dstSpringForward|knobsOffDefault|midday|HYBRID|657.3599999999998|912.6235000000078|1569.9835000000076|0.0|657.3599999999998|0.0|720|1767308400|1767351540|f8cf7fd816be2ea9|ea27e48f07e83ac1
dstSpringForward|knobsOffDefault|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767308400|1767351540|db2285cf7a9de06c|0.0|null
dstSpringForward|knobsOffDefault|earlyMorning|stored|99.07999999999998
dstSpringForward|knobsOffDefault|earlyMorning|HEART_RATE|475.860824755782|114.07793749999962|589.9387622557816|475.860824755782|90|1767308400|1767313740|105783d0435a82cf
dstSpringForward|knobsOffDefault|earlyMorning|HYBRID|99.07999999999998|114.07793750000025|213.15793750000023|0.0|99.07999999999998|0.0|90|1767308400|1767313740|b53af72dac22561e|cc4759ae743d84a8
dstSpringForward|knobsOffDefault|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767308400|1767313740|4d98eab0b8de64d1|0.0|null
dstSpringForward|defaultMeasuredBasal|elapsed|stored|3036.0374293995483
dstSpringForward|defaultMeasuredBasal|elapsed|HEART_RATE|3651.3110541270516|1825.247000000054|5476.558054127106|3651.3110541270516|1440|1767308400|1767394740|d0907307677273a6
dstSpringForward|defaultMeasuredBasal|elapsed|HYBRID|1155.36|1825.2470000000542|2980.607000000054|0.0|1155.36|0.0|1440|1767308400|1767394740|a2ba4a97f366dd17|3b597a5425504605
dstSpringForward|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767308400|1767394740|22f8c678bcb37e36|0.0|null
dstSpringForward|defaultMeasuredBasal|midday|stored|3036.0374293995483
dstSpringForward|defaultMeasuredBasal|midday|HEART_RATE|3651.3110541270516|912.6235000000088|4563.93455412706|3651.3110541270516|720|1767308400|1767351540|9c125cf8009d39dc
dstSpringForward|defaultMeasuredBasal|midday|HYBRID|1155.36|912.623500000009|2067.983500000009|0.0|1155.36|0.0|720|1767308400|1767351540|607d0847fc07290d|faeb1487e8a35c45
dstSpringForward|defaultMeasuredBasal|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767308400|1767351540|db2285cf7a9de06c|0.0|null
dstSpringForward|defaultMeasuredBasal|earlyMorning|stored|455.20609707709684
dstSpringForward|defaultMeasuredBasal|earlyMorning|HEART_RATE|545.6252835834629|114.07793749999985|659.7032210834627|545.6252835834629|90|1767308400|1767313740|f99ab3becef6be35
dstSpringForward|defaultMeasuredBasal|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767308400|1767313740|a8e71fc96506d9d2|3ff1afc1afea9422
dstSpringForward|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767308400|1767313740|4d98eab0b8de64d1|0.0|null
dstSpringForward|noVitalsFemale|elapsed|stored|837.6360000000003
dstSpringForward|noVitalsFemale|elapsed|HEART_RATE|2501.5234120628625|1284.150999999822|3785.6744120626845|2501.5234120628625|1440|1767308400|1767394740|28b9958083cfdf19
dstSpringForward|noVitalsFemale|elapsed|HYBRID|837.6360000000003|1284.150999999992|2121.7869999999925|0.0|837.6360000000003|0.0|1440|1767308400|1767394740|34b4d336a8cd695b|426958a5ad6393dd
dstSpringForward|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767308400|1767394740|3f30bc066338c279|0.0|null
dstSpringForward|noVitalsFemale|midday|stored|837.6360000000003
dstSpringForward|noVitalsFemale|midday|HEART_RATE|2501.5234120628625|642.0754999999758|3143.5989120628383|2501.5234120628625|720|1767308400|1767351540|3ee00f4dfa4bd7ff
dstSpringForward|noVitalsFemale|midday|HYBRID|837.6360000000003|642.0755000000012|1479.7115000000015|0.0|837.6360000000003|0.0|720|1767308400|1767351540|c3056c3eb029fd11|aabcbd110452221d
dstSpringForward|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767308400|1767351540|500ca1a2853508df|0.0|null
dstSpringForward|noVitalsFemale|earlyMorning|stored|123.65600000000002
dstSpringForward|noVitalsFemale|earlyMorning|HEART_RATE|373.6636215983065|80.25943749999982|453.92305909830634|373.6636215983065|90|1767308400|1767313740|acd48a616bfacbdd
dstSpringForward|noVitalsFemale|earlyMorning|HYBRID|123.65600000000002|80.25943750000009|203.9154375000001|0.0|123.65600000000002|0.0|90|1767308400|1767313740|b8812e6c4f9331b6|6a85b014113b535a
dstSpringForward|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767308400|1767313740|408a21d7851f99c9|0.0|null
dstFallBack|defaultHr|elapsed|stored|3651.3110541270516
dstFallBack|defaultHr|elapsed|HEART_RATE|3651.3110541270516|1825.247000000054|5476.558054127106|3651.3110541270516|1440|1767315600|1767401940|6363c197692f6ecd
dstFallBack|defaultHr|elapsed|HYBRID|1155.36|1825.2470000000542|2980.607000000054|0.0|1155.36|0.0|1440|1767315600|1767401940|5b98a7d934665408|3b597a5425504605
dstFallBack|defaultHr|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767315600|1767401940|6e4491a99736743b|0.0|null
dstFallBack|defaultHr|midday|stored|3651.3110541270516
dstFallBack|defaultHr|midday|HEART_RATE|3651.3110541270516|912.6235000000088|4563.93455412706|3651.3110541270516|720|1767315600|1767358740|4f59a0336c9e74a4
dstFallBack|defaultHr|midday|HYBRID|1155.36|912.623500000009|2067.983500000009|0.0|1155.36|0.0|720|1767315600|1767358740|48364757b7ec2119|faeb1487e8a35c45
dstFallBack|defaultHr|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767315600|1767358740|45db01282dbb532a|0.0|null
dstFallBack|defaultHr|earlyMorning|stored|545.6252835834629
dstFallBack|defaultHr|earlyMorning|HEART_RATE|545.6252835834629|114.07793749999985|659.7032210834627|545.6252835834629|90|1767315600|1767320940|2e9ce2cbefdf9824
dstFallBack|defaultHr|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767315600|1767320940|d25fad579a0ab05d|3ff1afc1afea9422
dstFallBack|defaultHr|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767315600|1767320940|21cb21d8b8a37320|0.0|null
dstFallBack|defaultHybrid|elapsed|stored|1155.36
dstFallBack|defaultHybrid|elapsed|HEART_RATE|3651.3110541270516|1825.247000000054|5476.558054127106|3651.3110541270516|1440|1767315600|1767401940|6363c197692f6ecd
dstFallBack|defaultHybrid|elapsed|HYBRID|1155.36|1825.2470000000542|2980.607000000054|0.0|1155.36|0.0|1440|1767315600|1767401940|5b98a7d934665408|3b597a5425504605
dstFallBack|defaultHybrid|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767315600|1767401940|6e4491a99736743b|0.0|null
dstFallBack|defaultHybrid|midday|stored|1155.36
dstFallBack|defaultHybrid|midday|HEART_RATE|3651.3110541270516|912.6235000000088|4563.93455412706|3651.3110541270516|720|1767315600|1767358740|4f59a0336c9e74a4
dstFallBack|defaultHybrid|midday|HYBRID|1155.36|912.623500000009|2067.983500000009|0.0|1155.36|0.0|720|1767315600|1767358740|48364757b7ec2119|faeb1487e8a35c45
dstFallBack|defaultHybrid|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767315600|1767358740|45db01282dbb532a|0.0|null
dstFallBack|defaultHybrid|earlyMorning|stored|170.55999999999995
dstFallBack|defaultHybrid|earlyMorning|HEART_RATE|545.6252835834629|114.07793749999985|659.7032210834627|545.6252835834629|90|1767315600|1767320940|2e9ce2cbefdf9824
dstFallBack|defaultHybrid|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767315600|1767320940|d25fad579a0ab05d|3ff1afc1afea9422
dstFallBack|defaultHybrid|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767315600|1767320940|21cb21d8b8a37320|0.0|null
dstFallBack|knobsOffDefault|elapsed|stored|657.3599999999998
dstFallBack|knobsOffDefault|elapsed|HEART_RATE|3189.3301668429267|1825.2470000000585|5014.577166842985|3189.3301668429267|1440|1767315600|1767401940|3e4fd562cb370389
dstFallBack|knobsOffDefault|elapsed|HYBRID|657.3599999999998|1825.247000000053|2482.6070000000527|0.0|657.3599999999998|0.0|1440|1767315600|1767401940|ce7e0bb542f775c6|e970d66572ece2c1
dstFallBack|knobsOffDefault|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767315600|1767401940|6e4491a99736743b|0.0|null
dstFallBack|knobsOffDefault|midday|stored|657.3599999999998
dstFallBack|knobsOffDefault|midday|HEART_RATE|3189.3301668429267|912.6235000000133|4101.95366684294|3189.3301668429267|720|1767315600|1767358740|9ad5e889f6382de8
dstFallBack|knobsOffDefault|midday|HYBRID|657.3599999999998|912.6235000000078|1569.9835000000076|0.0|657.3599999999998|0.0|720|1767315600|1767358740|e902d30e1e68b1b7|ea27e48f07e83ac1
dstFallBack|knobsOffDefault|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767315600|1767358740|45db01282dbb532a|0.0|null
dstFallBack|knobsOffDefault|earlyMorning|stored|99.07999999999998
dstFallBack|knobsOffDefault|earlyMorning|HEART_RATE|475.860824755782|114.07793749999962|589.9387622557816|475.860824755782|90|1767315600|1767320940|e72d51b53b111a1c
dstFallBack|knobsOffDefault|earlyMorning|HYBRID|99.07999999999998|114.07793750000025|213.15793750000023|0.0|99.07999999999998|0.0|90|1767315600|1767320940|147cd35cee86f089|cc4759ae743d84a8
dstFallBack|knobsOffDefault|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767315600|1767320940|21cb21d8b8a37320|0.0|null
dstFallBack|defaultMeasuredBasal|elapsed|stored|3036.0374293995483
dstFallBack|defaultMeasuredBasal|elapsed|HEART_RATE|3651.3110541270516|1825.247000000054|5476.558054127106|3651.3110541270516|1440|1767315600|1767401940|6363c197692f6ecd
dstFallBack|defaultMeasuredBasal|elapsed|HYBRID|1155.36|1825.2470000000542|2980.607000000054|0.0|1155.36|0.0|1440|1767315600|1767401940|5b98a7d934665408|3b597a5425504605
dstFallBack|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|3036.0374293995483|1825.2470000000521|4861.2844293996|3036.0374293995483|1440|1767315600|1767401940|6e4491a99736743b|0.0|null
dstFallBack|defaultMeasuredBasal|midday|stored|3036.0374293995483
dstFallBack|defaultMeasuredBasal|midday|HEART_RATE|3651.3110541270516|912.6235000000088|4563.93455412706|3651.3110541270516|720|1767315600|1767358740|4f59a0336c9e74a4
dstFallBack|defaultMeasuredBasal|midday|HYBRID|1155.36|912.623500000009|2067.983500000009|0.0|1155.36|0.0|720|1767315600|1767358740|48364757b7ec2119|faeb1487e8a35c45
dstFallBack|defaultMeasuredBasal|midday|DYNAMIC_HRR|3036.0374293995483|912.6235000000074|3948.6609293995557|3036.0374293995483|720|1767315600|1767358740|45db01282dbb532a|0.0|null
dstFallBack|defaultMeasuredBasal|earlyMorning|stored|455.20609707709684
dstFallBack|defaultMeasuredBasal|earlyMorning|HEART_RATE|545.6252835834629|114.07793749999985|659.7032210834627|545.6252835834629|90|1767315600|1767320940|2e9ce2cbefdf9824
dstFallBack|defaultMeasuredBasal|earlyMorning|HYBRID|170.55999999999995|114.07793750000019|284.63793750000013|0.0|170.55999999999995|0.0|90|1767315600|1767320940|d25fad579a0ab05d|3ff1afc1afea9422
dstFallBack|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|455.20609707709684|114.07793749999996|569.2840345770968|455.20609707709684|90|1767315600|1767320940|21cb21d8b8a37320|0.0|null
dstFallBack|noVitalsFemale|elapsed|stored|837.6360000000003
dstFallBack|noVitalsFemale|elapsed|HEART_RATE|2501.5234120628625|1284.150999999822|3785.6744120626845|2501.5234120628625|1440|1767315600|1767401940|b70bc0cc75ea9332
dstFallBack|noVitalsFemale|elapsed|HYBRID|837.6360000000003|1284.150999999992|2121.7869999999925|0.0|837.6360000000003|0.0|1440|1767315600|1767401940|3a899868d8ce6f4|426958a5ad6393dd
dstFallBack|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767315600|1767401940|9a477a27ca431f88|0.0|null
dstFallBack|noVitalsFemale|midday|stored|837.6360000000003
dstFallBack|noVitalsFemale|midday|HEART_RATE|2501.5234120628625|642.0754999999758|3143.5989120628383|2501.5234120628625|720|1767315600|1767358740|1f9d19e278fa8f0b
dstFallBack|noVitalsFemale|midday|HYBRID|837.6360000000003|642.0755000000012|1479.7115000000015|0.0|837.6360000000003|0.0|720|1767315600|1767358740|289ffbc8e5d3c99d|aabcbd110452221d
dstFallBack|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767315600|1767358740|e8fff1db17384c99|0.0|null
dstFallBack|noVitalsFemale|earlyMorning|stored|123.65600000000002
dstFallBack|noVitalsFemale|earlyMorning|HEART_RATE|373.6636215983065|80.25943749999982|453.92305909830634|373.6636215983065|90|1767315600|1767320940|d76f8d27e3d1a49a
dstFallBack|noVitalsFemale|earlyMorning|HYBRID|123.65600000000002|80.25943750000009|203.9154375000001|0.0|123.65600000000002|0.0|90|1767315600|1767320940|425c7c1f52c2f0f5|6a85b014113b535a
dstFallBack|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767315600|1767320940|19f78caf1730609e|0.0|null
dense1HzQuietAndWorking|defaultHr|elapsed|stored|1046.2559839648898
dense1HzQuietAndWorking|defaultHr|elapsed|HEART_RATE|1046.2559839648898|1825.2470000000694|2871.502983964959|1046.2559839648898|1440|1767312000|1767398340|cadfc269c84825ed
dense1HzQuietAndWorking|defaultHr|elapsed|HYBRID|1920.0|1825.2470000000817|3745.2470000000817|0.0|1920.0|0.0|1440|1767312000|1767398340|b733f11f037db2c0|7223eede5ffc1cfb
dense1HzQuietAndWorking|defaultHr|elapsed|DYNAMIC_HRR|950.5510090196947|1825.2470000000724|2775.798009019767|950.5510090196947|1440|1767312000|1767398340|638af0307f01e9b1|2.0|56.0
dense1HzQuietAndWorking|defaultHr|midday|stored|1046.2559839648898
dense1HzQuietAndWorking|defaultHr|midday|HEART_RATE|1046.2559839648898|912.6235000000245|1958.8794839649142|1046.2559839648898|720|1767312000|1767355140|b53bfa982a67c3dc
dense1HzQuietAndWorking|defaultHr|midday|HYBRID|1920.0|912.6235000000365|2832.6235000000365|0.0|1920.0|0.0|720|1767312000|1767355140|bed42b01f1740061|95394ef8a9796a3b
dense1HzQuietAndWorking|defaultHr|midday|DYNAMIC_HRR|950.5510090196947|912.6235000000274|1863.174509019722|950.5510090196947|720|1767312000|1767355140|82ff3acb289ee220|2.0|56.0
dense1HzQuietAndWorking|defaultHr|earlyMorning|stored|259.2645337541285
dense1HzQuietAndWorking|defaultHr|earlyMorning|HEART_RATE|259.2645337541285|114.07793750000002|373.3424712541285|259.2645337541285|90|1767312000|1767317340|25d5dc285c10145a
dense1HzQuietAndWorking|defaultHr|earlyMorning|HYBRID|480.0|114.07793749999996|594.0779375|0.0|480.0|0.0|90|1767312000|1767317340|d0dd62a4c297b0ae|30bd0db8ca902adf
dense1HzQuietAndWorking|defaultHr|earlyMorning|DYNAMIC_HRR|237.2594999644235|114.07793750000005|351.33743746442354|237.2594999644235|90|1767312000|1767317340|b4544bea2c565b93|1.0|52.0
dense1HzQuietAndWorking|defaultHybrid|elapsed|stored|1920.0
dense1HzQuietAndWorking|defaultHybrid|elapsed|HEART_RATE|1046.2559839648898|1825.2470000000694|2871.502983964959|1046.2559839648898|1440|1767312000|1767398340|cadfc269c84825ed
dense1HzQuietAndWorking|defaultHybrid|elapsed|HYBRID|1920.0|1825.2470000000817|3745.2470000000817|0.0|1920.0|0.0|1440|1767312000|1767398340|b733f11f037db2c0|7223eede5ffc1cfb
dense1HzQuietAndWorking|defaultHybrid|elapsed|DYNAMIC_HRR|950.5510090196947|1825.2470000000724|2775.798009019767|950.5510090196947|1440|1767312000|1767398340|638af0307f01e9b1|2.0|56.0
dense1HzQuietAndWorking|defaultHybrid|midday|stored|1920.0
dense1HzQuietAndWorking|defaultHybrid|midday|HEART_RATE|1046.2559839648898|912.6235000000245|1958.8794839649142|1046.2559839648898|720|1767312000|1767355140|b53bfa982a67c3dc
dense1HzQuietAndWorking|defaultHybrid|midday|HYBRID|1920.0|912.6235000000365|2832.6235000000365|0.0|1920.0|0.0|720|1767312000|1767355140|bed42b01f1740061|95394ef8a9796a3b
dense1HzQuietAndWorking|defaultHybrid|midday|DYNAMIC_HRR|950.5510090196947|912.6235000000274|1863.174509019722|950.5510090196947|720|1767312000|1767355140|82ff3acb289ee220|2.0|56.0
dense1HzQuietAndWorking|defaultHybrid|earlyMorning|stored|480.0
dense1HzQuietAndWorking|defaultHybrid|earlyMorning|HEART_RATE|259.2645337541285|114.07793750000002|373.3424712541285|259.2645337541285|90|1767312000|1767317340|25d5dc285c10145a
dense1HzQuietAndWorking|defaultHybrid|earlyMorning|HYBRID|480.0|114.07793749999996|594.0779375|0.0|480.0|0.0|90|1767312000|1767317340|d0dd62a4c297b0ae|30bd0db8ca902adf
dense1HzQuietAndWorking|defaultHybrid|earlyMorning|DYNAMIC_HRR|237.2594999644235|114.07793750000005|351.33743746442354|237.2594999644235|90|1767312000|1767317340|b4544bea2c565b93|1.0|52.0
dense1HzQuietAndWorking|knobsOffDefault|elapsed|stored|1920.0
dense1HzQuietAndWorking|knobsOffDefault|elapsed|HEART_RATE|1046.2559839648898|1825.2470000000694|2871.502983964959|1046.2559839648898|1440|1767312000|1767398340|cadfc269c84825ed
dense1HzQuietAndWorking|knobsOffDefault|elapsed|HYBRID|1920.0|1825.2470000000817|3745.2470000000817|0.0|1920.0|0.0|1440|1767312000|1767398340|b733f11f037db2c0|20f9e35837cb443b
dense1HzQuietAndWorking|knobsOffDefault|elapsed|DYNAMIC_HRR|950.5510090196947|1825.2470000000724|2775.798009019767|950.5510090196947|1440|1767312000|1767398340|638af0307f01e9b1|2.0|56.0
dense1HzQuietAndWorking|knobsOffDefault|midday|stored|1920.0
dense1HzQuietAndWorking|knobsOffDefault|midday|HEART_RATE|1046.2559839648898|912.6235000000245|1958.8794839649142|1046.2559839648898|720|1767312000|1767355140|b53bfa982a67c3dc
dense1HzQuietAndWorking|knobsOffDefault|midday|HYBRID|1920.0|912.6235000000365|2832.6235000000365|0.0|1920.0|0.0|720|1767312000|1767355140|bed42b01f1740061|7edff7e2d6b458bb
dense1HzQuietAndWorking|knobsOffDefault|midday|DYNAMIC_HRR|950.5510090196947|912.6235000000274|1863.174509019722|950.5510090196947|720|1767312000|1767355140|82ff3acb289ee220|2.0|56.0
dense1HzQuietAndWorking|knobsOffDefault|earlyMorning|stored|480.0
dense1HzQuietAndWorking|knobsOffDefault|earlyMorning|HEART_RATE|259.2645337541285|114.07793750000002|373.3424712541285|259.2645337541285|90|1767312000|1767317340|25d5dc285c10145a
dense1HzQuietAndWorking|knobsOffDefault|earlyMorning|HYBRID|480.0|114.07793749999996|594.0779375|0.0|480.0|0.0|90|1767312000|1767317340|d0dd62a4c297b0ae|30bd0db8ca902adf
dense1HzQuietAndWorking|knobsOffDefault|earlyMorning|DYNAMIC_HRR|237.2594999644235|114.07793750000005|351.33743746442354|237.2594999644235|90|1767312000|1767317340|b4544bea2c565b93|1.0|52.0
dense1HzQuietAndWorking|defaultMeasuredBasal|elapsed|stored|950.5510090196947
dense1HzQuietAndWorking|defaultMeasuredBasal|elapsed|HEART_RATE|1046.2559839648898|1825.2470000000694|2871.502983964959|1046.2559839648898|1440|1767312000|1767398340|cadfc269c84825ed
dense1HzQuietAndWorking|defaultMeasuredBasal|elapsed|HYBRID|1920.0|1825.2470000000817|3745.2470000000817|0.0|1920.0|0.0|1440|1767312000|1767398340|b733f11f037db2c0|7223eede5ffc1cfb
dense1HzQuietAndWorking|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|950.5510090196947|1825.2470000000724|2775.798009019767|950.5510090196947|1440|1767312000|1767398340|638af0307f01e9b1|2.0|56.0
dense1HzQuietAndWorking|defaultMeasuredBasal|midday|stored|950.5510090196947
dense1HzQuietAndWorking|defaultMeasuredBasal|midday|HEART_RATE|1046.2559839648898|912.6235000000245|1958.8794839649142|1046.2559839648898|720|1767312000|1767355140|b53bfa982a67c3dc
dense1HzQuietAndWorking|defaultMeasuredBasal|midday|HYBRID|1920.0|912.6235000000365|2832.6235000000365|0.0|1920.0|0.0|720|1767312000|1767355140|bed42b01f1740061|95394ef8a9796a3b
dense1HzQuietAndWorking|defaultMeasuredBasal|midday|DYNAMIC_HRR|950.5510090196947|912.6235000000274|1863.174509019722|950.5510090196947|720|1767312000|1767355140|82ff3acb289ee220|2.0|56.0
dense1HzQuietAndWorking|defaultMeasuredBasal|earlyMorning|stored|237.2594999644235
dense1HzQuietAndWorking|defaultMeasuredBasal|earlyMorning|HEART_RATE|259.2645337541285|114.07793750000002|373.3424712541285|259.2645337541285|90|1767312000|1767317340|25d5dc285c10145a
dense1HzQuietAndWorking|defaultMeasuredBasal|earlyMorning|HYBRID|480.0|114.07793749999996|594.0779375|0.0|480.0|0.0|90|1767312000|1767317340|d0dd62a4c297b0ae|30bd0db8ca902adf
dense1HzQuietAndWorking|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|237.2594999644235|114.07793750000005|351.33743746442354|237.2594999644235|90|1767312000|1767317340|b4544bea2c565b93|1.0|52.0
dense1HzQuietAndWorking|noVitalsFemale|elapsed|stored|1391.9999999999984
dense1HzQuietAndWorking|noVitalsFemale|elapsed|HEART_RATE|732.2614574569793|1284.1510000000183|2016.4124574569976|732.2614574569793|1440|1767312000|1767398340|871eb2f7b582a285
dense1HzQuietAndWorking|noVitalsFemale|elapsed|HYBRID|1391.9999999999984|1284.1509999998564|2676.150999999855|0.0|1391.9999999999984|0.0|1440|1767312000|1767398340|ae115cf8321d7c40|1ca4651c00d0ae63
dense1HzQuietAndWorking|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
dense1HzQuietAndWorking|noVitalsFemale|midday|stored|1391.9999999999984
dense1HzQuietAndWorking|noVitalsFemale|midday|HEART_RATE|732.2614574569793|642.0755000000083|1374.3369574569876|732.2614574569793|720|1767312000|1767355140|260f76514a1430e4
dense1HzQuietAndWorking|noVitalsFemale|midday|HYBRID|1391.9999999999984|642.0755000000065|2034.075500000005|0.0|1391.9999999999984|0.0|720|1767312000|1767355140|b43429f33e64a9e1|70ec3873961d05a3
dense1HzQuietAndWorking|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
dense1HzQuietAndWorking|noVitalsFemale|earlyMorning|stored|348.00000000000006
dense1HzQuietAndWorking|noVitalsFemale|earlyMorning|HEART_RATE|181.44340929732314|80.25943749999996|261.7028467973231|181.44340929732314|90|1767312000|1767317340|5e84e5be9f1d30ce
dense1HzQuietAndWorking|noVitalsFemale|earlyMorning|HYBRID|348.00000000000006|80.2594374999997|428.25943749999976|0.0|348.00000000000006|0.0|90|1767312000|1767317340|6cefbf189e824e6e|521df6449582831d
dense1HzQuietAndWorking|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
    """

    private val RECORDED_4 = """
bursty1HzGappedSession|defaultHr|elapsed|stored|1060.8306095225685
bursty1HzGappedSession|defaultHr|elapsed|HEART_RATE|1060.8306095225685|1825.2470000000762|2886.0776095226447|1060.8306095225685|1440|1767312000|1767398340|3f2a26fa2de6c068
bursty1HzGappedSession|defaultHr|elapsed|HYBRID|1920.0|1825.2470000000785|3745.2470000000785|0.0|1920.0|0.0|1440|1767312000|1767398340|22b8f62a684598e6|67deea40e9baca9b
bursty1HzGappedSession|defaultHr|elapsed|DYNAMIC_HRR|970.0677894168622|1825.2470000000726|2795.3147894169347|970.0677894168622|1440|1767312000|1767398340|72250c78c5a7f2b7|2.0|58.0
bursty1HzGappedSession|defaultHr|midday|stored|1060.8306095225685
bursty1HzGappedSession|defaultHr|midday|HEART_RATE|1060.8306095225685|912.6235000000311|1973.4541095225995|1060.8306095225685|720|1767312000|1767355140|5cedadf14990fc39
bursty1HzGappedSession|defaultHr|midday|HYBRID|1920.0|912.6235000000333|2832.6235000000333|0.0|1920.0|0.0|720|1767312000|1767355140|afbe3f084a317a37|3c3e2192c25d82db
bursty1HzGappedSession|defaultHr|midday|DYNAMIC_HRR|970.0677894168622|912.6235000000275|1882.6912894168897|970.0677894168622|720|1767312000|1767355140|5ebf9786a1d0509e|2.0|58.0
bursty1HzGappedSession|defaultHr|earlyMorning|stored|0.0
bursty1HzGappedSession|defaultHr|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
bursty1HzGappedSession|defaultHr|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|e5ba952c69034963
bursty1HzGappedSession|defaultHr|earlyMorning|DYNAMIC_HRR|3.450572386886323|114.0779375000001|117.52850988688643|3.450572386886323|90|1767312000|1767317340|449ec1d080a61798|1.0|52.0
bursty1HzGappedSession|defaultHybrid|elapsed|stored|1920.0
bursty1HzGappedSession|defaultHybrid|elapsed|HEART_RATE|1060.8306095225685|1825.2470000000762|2886.0776095226447|1060.8306095225685|1440|1767312000|1767398340|3f2a26fa2de6c068
bursty1HzGappedSession|defaultHybrid|elapsed|HYBRID|1920.0|1825.2470000000785|3745.2470000000785|0.0|1920.0|0.0|1440|1767312000|1767398340|22b8f62a684598e6|67deea40e9baca9b
bursty1HzGappedSession|defaultHybrid|elapsed|DYNAMIC_HRR|970.0677894168622|1825.2470000000726|2795.3147894169347|970.0677894168622|1440|1767312000|1767398340|72250c78c5a7f2b7|2.0|58.0
bursty1HzGappedSession|defaultHybrid|midday|stored|1920.0
bursty1HzGappedSession|defaultHybrid|midday|HEART_RATE|1060.8306095225685|912.6235000000311|1973.4541095225995|1060.8306095225685|720|1767312000|1767355140|5cedadf14990fc39
bursty1HzGappedSession|defaultHybrid|midday|HYBRID|1920.0|912.6235000000333|2832.6235000000333|0.0|1920.0|0.0|720|1767312000|1767355140|afbe3f084a317a37|3c3e2192c25d82db
bursty1HzGappedSession|defaultHybrid|midday|DYNAMIC_HRR|970.0677894168622|912.6235000000275|1882.6912894168897|970.0677894168622|720|1767312000|1767355140|5ebf9786a1d0509e|2.0|58.0
bursty1HzGappedSession|defaultHybrid|earlyMorning|stored|0.0
bursty1HzGappedSession|defaultHybrid|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
bursty1HzGappedSession|defaultHybrid|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|e5ba952c69034963
bursty1HzGappedSession|defaultHybrid|earlyMorning|DYNAMIC_HRR|3.450572386886323|114.0779375000001|117.52850988688643|3.450572386886323|90|1767312000|1767317340|449ec1d080a61798|1.0|52.0
bursty1HzGappedSession|knobsOffDefault|elapsed|stored|1920.0
bursty1HzGappedSession|knobsOffDefault|elapsed|HEART_RATE|1060.8306095225685|1825.2470000000762|2886.0776095226447|1060.8306095225685|1440|1767312000|1767398340|3f2a26fa2de6c068
bursty1HzGappedSession|knobsOffDefault|elapsed|HYBRID|1920.0|1825.2470000000785|3745.2470000000785|0.0|1920.0|0.0|1440|1767312000|1767398340|22b8f62a684598e6|43a1d498057050cb
bursty1HzGappedSession|knobsOffDefault|elapsed|DYNAMIC_HRR|970.0677894168622|1825.2470000000726|2795.3147894169347|970.0677894168622|1440|1767312000|1767398340|72250c78c5a7f2b7|2.0|58.0
bursty1HzGappedSession|knobsOffDefault|midday|stored|1920.0
bursty1HzGappedSession|knobsOffDefault|midday|HEART_RATE|1060.8306095225685|912.6235000000311|1973.4541095225995|1060.8306095225685|720|1767312000|1767355140|5cedadf14990fc39
bursty1HzGappedSession|knobsOffDefault|midday|HYBRID|1920.0|912.6235000000333|2832.6235000000333|0.0|1920.0|0.0|720|1767312000|1767355140|afbe3f084a317a37|d987415c084df54b
bursty1HzGappedSession|knobsOffDefault|midday|DYNAMIC_HRR|970.0677894168622|912.6235000000275|1882.6912894168897|970.0677894168622|720|1767312000|1767355140|5ebf9786a1d0509e|2.0|58.0
bursty1HzGappedSession|knobsOffDefault|earlyMorning|stored|0.0
bursty1HzGappedSession|knobsOffDefault|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
bursty1HzGappedSession|knobsOffDefault|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|e5ba952c69034963
bursty1HzGappedSession|knobsOffDefault|earlyMorning|DYNAMIC_HRR|3.450572386886323|114.0779375000001|117.52850988688643|3.450572386886323|90|1767312000|1767317340|449ec1d080a61798|1.0|52.0
bursty1HzGappedSession|defaultMeasuredBasal|elapsed|stored|970.0677894168622
bursty1HzGappedSession|defaultMeasuredBasal|elapsed|HEART_RATE|1060.8306095225685|1825.2470000000762|2886.0776095226447|1060.8306095225685|1440|1767312000|1767398340|3f2a26fa2de6c068
bursty1HzGappedSession|defaultMeasuredBasal|elapsed|HYBRID|1920.0|1825.2470000000785|3745.2470000000785|0.0|1920.0|0.0|1440|1767312000|1767398340|22b8f62a684598e6|67deea40e9baca9b
bursty1HzGappedSession|defaultMeasuredBasal|elapsed|DYNAMIC_HRR|970.0677894168622|1825.2470000000726|2795.3147894169347|970.0677894168622|1440|1767312000|1767398340|72250c78c5a7f2b7|2.0|58.0
bursty1HzGappedSession|defaultMeasuredBasal|midday|stored|970.0677894168622
bursty1HzGappedSession|defaultMeasuredBasal|midday|HEART_RATE|1060.8306095225685|912.6235000000311|1973.4541095225995|1060.8306095225685|720|1767312000|1767355140|5cedadf14990fc39
bursty1HzGappedSession|defaultMeasuredBasal|midday|HYBRID|1920.0|912.6235000000333|2832.6235000000333|0.0|1920.0|0.0|720|1767312000|1767355140|afbe3f084a317a37|3c3e2192c25d82db
bursty1HzGappedSession|defaultMeasuredBasal|midday|DYNAMIC_HRR|970.0677894168622|912.6235000000275|1882.6912894168897|970.0677894168622|720|1767312000|1767355140|5ebf9786a1d0509e|2.0|58.0
bursty1HzGappedSession|defaultMeasuredBasal|earlyMorning|stored|3.450572386886323
bursty1HzGappedSession|defaultMeasuredBasal|earlyMorning|HEART_RATE|0.0|114.07793750000019|114.07793750000019|0.0|90|1767312000|1767317340|6931107030edd6fa
bursty1HzGappedSession|defaultMeasuredBasal|earlyMorning|HYBRID|0.0|114.07793750000019|114.07793750000019|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|e5ba952c69034963
bursty1HzGappedSession|defaultMeasuredBasal|earlyMorning|DYNAMIC_HRR|3.450572386886323|114.0779375000001|117.52850988688643|3.450572386886323|90|1767312000|1767317340|449ec1d080a61798|1.0|52.0
bursty1HzGappedSession|noVitalsFemale|elapsed|stored|1391.9999999999984
bursty1HzGappedSession|noVitalsFemale|elapsed|HEART_RATE|742.5418557998727|1284.1510000000167|2026.6928557998895|742.5418557998727|1440|1767312000|1767398340|87fae44941502082
bursty1HzGappedSession|noVitalsFemale|elapsed|HYBRID|1391.9999999999984|1284.1509999998555|2676.150999999854|0.0|1391.9999999999984|0.0|1440|1767312000|1767398340|6ba92494c8b1ab26|1e8c5f4fc38d44a3
bursty1HzGappedSession|noVitalsFemale|elapsed|DYNAMIC_HRR|0.0|1284.1510000000178|1284.1510000000178|0.0|1440|1767312000|1767398340|e7e9f740a0a1b11c|0.0|null
bursty1HzGappedSession|noVitalsFemale|midday|stored|1391.9999999999984
bursty1HzGappedSession|noVitalsFemale|midday|HEART_RATE|742.5418557998727|642.0755000000069|1384.6173557998795|742.5418557998727|720|1767312000|1767355140|9760884687a2bb33
bursty1HzGappedSession|noVitalsFemale|midday|HYBRID|1391.9999999999984|642.0755000000058|2034.0755000000042|0.0|1391.9999999999984|0.0|720|1767312000|1767355140|3376e2c3b68bdc77|57a4b841e41ca1e3
bursty1HzGappedSession|noVitalsFemale|midday|DYNAMIC_HRR|0.0|642.0755000000079|642.0755000000079|0.0|720|1767312000|1767355140|3aed6bc931c5730d|0.0|null
bursty1HzGappedSession|noVitalsFemale|earlyMorning|stored|0.0
bursty1HzGappedSession|noVitalsFemale|earlyMorning|HEART_RATE|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa
bursty1HzGappedSession|noVitalsFemale|earlyMorning|HYBRID|0.0|80.25943749999998|80.25943749999998|0.0|0.0|0.0|90|1767312000|1767317340|6931107030edd6fa|e5ba952c69034963
bursty1HzGappedSession|noVitalsFemale|earlyMorning|DYNAMIC_HRR|0.0|80.25943749999998|80.25943749999998|0.0|90|1767312000|1767317340|6931107030edd6fa|0.0|null
    """
}
