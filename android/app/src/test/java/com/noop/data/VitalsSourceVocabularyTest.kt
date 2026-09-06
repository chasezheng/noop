package com.noop.data

import com.noop.analytics.AnalyticsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the ONE vitals source vocabulary and the resting-HR precedence it produces.
 *
 * The scan callers (the calorie pass's `vitals`, the engine's day-streams read) each hand-picked
 * `imported + computed`, so once the Health Connect vitals moved to their own source id they could no
 * longer see them at all — on a stretch the strap did not cover the pass fell back to an invented
 * 60 bpm. [WhoopRepository.vitalsSourceIdsFor] is the single declared list they now share.
 *
 * The precedence is a real behaviour change and is pinned in both directions: recency decides ACROSS
 * days (a recent phone measurement beats a stale strap one — the point of reading it), while the strap
 * wins outright any day both measured. Composing the two pure helpers is exactly what the call sites
 * do; their per-source `latestRestingHrDay` scan is only an index-bounded way of avoiding a whole-history
 * read, and `calorieRestingHR` makes the same latest-day pick over whatever it is handed.
 */
class VitalsSourceVocabularyTest {

    private val strap = "strap-abc123"

    private fun row(source: String, day: String, rhr: Int?) =
        DailyMetric(deviceId = source, day = day, restingHr = rhr)

    /** The call sites' composition: drop the outranked phone rows, then take the latest day's value. */
    private fun restingHr(rows: List<DailyMetric>, day: String): Double? =
        AnalyticsEngine.calorieRestingHR(WhoopRepository.preferMeasuredRestingHr(rows), day)

    @Test
    fun vocabularyIsImportedThenComputedThenHealthConnectLast() {
        assertEquals(
            listOf(strap, "my-whoop", "$strap-noop", "my-whoop-noop", "health-connect"),
            WhoopRepository.vitalsSourceIdsFor(strap),
        )
    }

    @Test
    fun singleWhoopInstallResolvesToThreeIds() {
        // No re-added strap ⇒ no active/canonical split; Health Connect is still appended last.
        assertEquals(
            listOf("my-whoop", "my-whoop-noop", "health-connect"),
            WhoopRepository.vitalsSourceIdsFor(WhoopRepository.WHOOP_SOURCE),
        )
    }

    @Test
    fun healthConnectIsLastSoItNeverOutranksAStrapSource() {
        val ids = WhoopRepository.vitalsSourceIdsFor(strap)
        assertEquals(WhoopRepository.HEALTH_CONNECT_SOURCE, ids.last())
        assertEquals(WhoopRepository.importedSourceIdsFor(strap), ids.take(2))
    }

    @Test
    fun strapWinsTheDayBothMeasured() {
        // NOT the 54.5 average of the two: calorieRestingHR averages every row it is handed for the
        // winning day, so the phone row has to be removed before it gets there.
        val rows = listOf(
            row("my-whoop", "2026-08-20", 48),
            row(WhoopRepository.HEALTH_CONNECT_SOURCE, "2026-08-20", 61),
        )
        assertEquals(48.0, restingHr(rows, "2026-08-20")!!, 1e-9)
    }

    @Test
    fun computedStrapRowAlsoWinsTheDayBothMeasured() {
        val rows = listOf(
            row("my-whoop-noop", "2026-08-20", 50),
            row(WhoopRepository.HEALTH_CONNECT_SOURCE, "2026-08-20", 61),
        )
        assertEquals(50.0, restingHr(rows, "2026-08-20")!!, 1e-9)
    }

    @Test
    fun recentHealthConnectValueBeatsAnOlderStrapValue() {
        // The behaviour change. The reporter's case: the strap is paired to the official WHOOP app, so
        // the last strap-measured day is weeks old while Health Connect carries last night's figure.
        val rows = listOf(
            row("my-whoop", "2026-08-01", 48),
            row(WhoopRepository.HEALTH_CONNECT_SOURCE, "2026-08-20", 61),
        )
        assertEquals(61.0, restingHr(rows, "2026-08-20")!!, 1e-9)
    }

    @Test
    fun recentStrapValueStillBeatsAnOlderHealthConnectValue() {
        val rows = listOf(
            row("my-whoop", "2026-08-20", 48),
            row(WhoopRepository.HEALTH_CONNECT_SOURCE, "2026-08-01", 61),
        )
        assertEquals(48.0, restingHr(rows, "2026-08-20")!!, 1e-9)
    }

    @Test
    fun healthConnectAloneIsUsedRatherThanTheInvented60Bpm() {
        val rows = listOf(row(WhoopRepository.HEALTH_CONNECT_SOURCE, "2026-08-19", 61))
        assertEquals(61.0, restingHr(rows, "2026-08-20")!!, 1e-9)
    }

    @Test
    fun aStrapRowWithNoRestingHrDoesNotSuppressTheHealthConnectOne() {
        // "Measured" is a non-null resting HR, not the mere presence of a strap row for the day: a row
        // carrying only steps must not blank the only figure anyone recorded.
        val rows = listOf(
            row("my-whoop", "2026-08-20", null),
            row(WhoopRepository.HEALTH_CONNECT_SOURCE, "2026-08-20", 61),
        )
        assertEquals(61.0, restingHr(rows, "2026-08-20")!!, 1e-9)
    }

    @Test
    fun twoStrapSourcesOnOneDayStillAverageAsBefore() {
        // The active id and the canonical import are peers; only the phone is demoted, so the existing
        // same-tier averaging is untouched.
        val rows = listOf(
            row(strap, "2026-08-20", 48),
            row("my-whoop", "2026-08-20", 52),
        )
        assertEquals(50.0, restingHr(rows, "2026-08-20")!!, 1e-9)
    }

    @Test
    fun noRestingHrAnywhereStaysNull() {
        assertNull(restingHr(listOf(row("my-whoop", "2026-08-20", null)), "2026-08-20"))
    }
}
