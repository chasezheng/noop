package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The resting HR the calorie models score a day with, resolved across sources.
 *
 * `analyzeDay` derives its own from the sleep sessions NOOP staged, and falls back to 60 bpm when a day
 * has none. That invented number moves both the %HRR gate and the Uth VO₂max, so it moves the whole
 * day's energy — while a WHOOP or Apple import often carries a real measurement for the same day, or a
 * recent one — however old, since any measurement beats an invented number.
 */
class CalorieRestingHrTest {

    private fun row(day: String, restingHr: Int?) =
        DailyMetric(deviceId = "t", day = day, restingHr = restingHr)

    private fun resolve(rows: List<DailyMetric>, day: String = "2026-01-15") =
        AnalyticsEngine.calorieRestingHR(rows, day)

    @Test
    fun theDaysOwnValue_wins() {
        assertEquals(48.0, resolve(listOf(row("2026-01-15", 48), row("2026-01-10", 55)))!!, 0.0)
    }

    @Test
    fun anOlderValue_carriesForwardWhenTheDayHasNone() {
        assertEquals(55.0, resolve(listOf(row("2026-01-10", 55)))!!, 0.0)
    }

    @Test
    fun theMostRecentOfSeveral_wins() {
        assertEquals(52.0, resolve(listOf(row("2026-01-05", 60), row("2026-01-14", 52)))!!, 0.0)
    }

    @Test
    fun anArbitrarilyOldValue_isStillUsed() {
        // Any measurement beats the 60 bpm the estimators otherwise invent, however stale.
        assertEquals(55.0, resolve(listOf(row("2019-03-02", 55)))!!, 0.0)
    }

    @Test
    fun severalSourcesOnTheLatestDay_areAveraged() {
        // No precedence between sources is defensible when they disagree by a few bpm.
        assertEquals(52.0, resolve(listOf(row("2026-01-14", 48), row("2026-01-14", 56)))!!, 0.0)
    }

    @Test
    fun onlyTheLatestDaysSources_areAveraged() {
        // An older day must not dilute the most recent measurement.
        val rows = listOf(row("2026-01-14", 48), row("2026-01-14", 56), row("2026-01-02", 90))
        assertEquals(52.0, resolve(rows)!!, 0.0)
    }

    @Test
    fun aLaterDaysValue_isNotUsed() {
        // Scoring a past day must not read a resting HR measured after it.
        assertNull(resolve(listOf(row("2026-01-20", 48))))
    }

    @Test
    fun rowsWithoutARestingHr_areSkipped() {
        assertEquals(55.0, resolve(listOf(row("2026-01-14", null), row("2026-01-10", 55)))!!, 0.0)
    }

    @Test
    fun noUsableRow_resolvesToNothing() {
        assertNull(resolve(emptyList()))
    }
}
