package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Health Connect half of the HRV/RHR baseline seed.
 *
 * Without the Health Connect rows, a spell paired to another app leaves the baseline nothing to fold
 * and recovery unscored, on exactly the days Health Connect carries HRV and resting HR.
 *
 * Two orderings are load-bearing and both are pinned here. Chronological, because the fold replays
 * oldest first and winsorizes against the run so far, so a row out of order is a different baseline.
 * And by source: strap import, then this pass's own nights, then Health Connect.
 */
class BaselineSeedHealthConnectTest {

    private fun strapRow(day: String, hrv: Double? = null, rhr: Int? = null) =
        DailyMetric(deviceId = "my-whoop", day = day, avgHrv = hrv, restingHr = rhr)

    private fun hcRow(day: String, hrv: Double? = null, rhr: Int? = null) =
        DailyMetric(
            deviceId = WhoopRepository.HEALTH_CONNECT_SOURCE, day = day, avgHrv = hrv, restingHr = rhr,
        )

    @Test
    fun healthConnectFillsTheDaysTheStrapNeverCovered() {
        val seed = IntelligenceEngine.baselineSeedRows(
            imported = listOf(strapRow("2026-08-01", hrv = 90.0, rhr = 48)),
            healthConnect = listOf(
                hcRow("2026-08-02", hrv = 71.0, rhr = 55),
                hcRow("2026-08-03", hrv = 68.0, rhr = 56),
            ),
        )
        assertEquals(listOf("2026-08-01", "2026-08-02", "2026-08-03"), seed.map { it.day })
        assertEquals(listOf(90.0, 71.0, 68.0), seed.map { it.avgHrv })
        assertEquals(listOf(48, 55, 56), seed.map { it.restingHr })
    }

    @Test
    fun oneRowPerDayOldestFirst() {
        // foldHistory replays in order, so an interleaved Health Connect row has to land in its date
        // slot rather than at the end, and a day both sources hold must not fold twice.
        val seed = IntelligenceEngine.baselineSeedRows(
            imported = listOf(strapRow("2026-08-01", hrv = 90.0), strapRow("2026-08-03", hrv = 88.0)),
            healthConnect = listOf(hcRow("2026-08-04", hrv = 70.0), hcRow("2026-08-02", hrv = 71.0)),
        )
        assertEquals(
            listOf("2026-08-01", "2026-08-02", "2026-08-03", "2026-08-04"),
            seed.map { it.day },
        )
        assertEquals(seed.map { it.day }.distinct(), seed.map { it.day })
    }

    @Test
    fun strapValueWinsTheDayBothCover() {
        val seed = IntelligenceEngine.baselineSeedRows(
            imported = listOf(strapRow("2026-08-01", hrv = 90.0, rhr = 48)),
            healthConnect = listOf(hcRow("2026-08-01", hrv = 40.0, rhr = 61)),
        )
        assertEquals(1, seed.size)
        assertEquals(90.0, seed.single().avgHrv)
        assertEquals(48, seed.single().restingHr)
    }

    @Test
    fun healthConnectFillsAColumnTheStrapRowLeftBlank() {
        // A row present with a NULL value used to starve the baseline forever (the "Needs the strap"
        // bug `mergeNightlyIntoHistory` guards against); the same must not happen here.
        val seed = IntelligenceEngine.baselineSeedRows(
            imported = listOf(strapRow("2026-08-01", hrv = null, rhr = 48)),
            healthConnect = listOf(hcRow("2026-08-01", hrv = 71.0, rhr = 61)),
        )
        assertEquals(71.0, seed.single().avgHrv)
        assertEquals(48, seed.single().restingHr)
    }

    @Test
    fun noHealthConnectRowsLeavesTheImportUntouched() {
        val imported = listOf(strapRow("2026-08-01", hrv = 90.0))
        assertEquals(imported, IntelligenceEngine.baselineSeedRows(imported, emptyList()))
    }

    @Test
    fun passTwoRanksImportOverOwnNightsOverHealthConnect() {
        // Mirrors pass 2's call order: the strap history seeds the map, this pass's nightly values fill
        // what it left, and Health Connect fills only what neither reached.
        val hist = linkedMapOf<String, Double?>(
            "2026-08-01" to 90.0,      // strap import
            "2026-08-02" to null,      // strap row exists but blank
        )
        IntelligenceEngine.mergeNightlyIntoHistory(hist, mapOf("2026-08-02" to 85.0, "2026-08-03" to 84.0))
        IntelligenceEngine.mergeNightlyIntoHistory(
            hist, mapOf("2026-08-01" to 40.0, "2026-08-02" to 41.0, "2026-08-03" to 42.0, "2026-08-04" to 70.0),
        )
        assertEquals(90.0, hist["2026-08-01"])
        assertEquals(85.0, hist["2026-08-02"])
        assertEquals(84.0, hist["2026-08-03"])
        assertEquals(70.0, hist["2026-08-04"])
    }

    @Test
    fun computedRowsAreNotPartOfTheSeed() {
        // The computed "-noop" rows are DERIVED from this baseline; seeding from them would let it chase
        // itself. baselineSeedRows takes exactly two lists, and nothing it emits carries the suffix.
        val seed = IntelligenceEngine.baselineSeedRows(
            imported = listOf(strapRow("2026-08-01", hrv = 90.0)),
            healthConnect = listOf(hcRow("2026-08-02", hrv = 71.0)),
        )
        assertNull(seed.firstOrNull { it.deviceId.endsWith("-noop") })
    }
}
