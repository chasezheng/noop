package com.noop.data

import com.noop.analytics.FusionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-field provenance the read-time Health Connect fold records
 * ([WhoopRepository.healthConnectVitalMerge]).
 *
 * A merged [DailyMetric] carries one deviceId, so a vital another source won lands in a row stamped
 * with the strap's and every caption drawn from that deviceId credits the wrong source.
 *
 * The map must be exact in both directions: an entry only where the arbitration ran and Health Connect
 * won, and no entry at all on a day the strap or the computed row supplied the vital itself.
 */
class HealthConnectVitalProvenanceTest {

    private val day = "2026-08-29"

    private fun hcRow(
        day: String = this.day,
        rhr: Int? = null,
        hrv: Double? = null,
        spo2: Double? = null,
        resp: Double? = null,
        asleep: Double? = null,
    ) = DailyMetric(
        deviceId = WhoopRepository.HEALTH_CONNECT_SOURCE, day = day, totalSleepMin = asleep,
        restingHr = rhr, avgHrv = hrv, spo2Pct = spo2, respRateBpm = resp,
    )

    /** The reporter's 08-29: the computed row scored strain and nothing else, so all four vitals are
     *  Health Connect's and all four must say so. */
    @Test
    fun healthConnectWinsEveryVitalOnAStrainOnlyComputedDay() {
        val computed = listOf(DailyMetric(deviceId = "my-whoop-noop", day = day, strain = 12.4))
        val base = WhoopRepository.mergeDaily(imported = emptyList(), computed = computed)
        val merge = WhoopRepository.healthConnectVitalMerge(
            base,
            imported = emptyList(),
            computed = computed,
            healthConnect = listOf(hcRow(rhr = 34, hrv = 159.0, spo2 = 96.6, resp = 12.9)),
        )
        assertEquals(
            mapOf(
                day to mapOf(
                    "rhr" to FusionSource.HEALTH_CONNECT,
                    "hrv" to FusionSource.HEALTH_CONNECT,
                    "spo2" to FusionSource.HEALTH_CONNECT,
                    "resp_rate" to FusionSource.HEALTH_CONNECT,
                ),
            ),
            merge.vitalSources,
        )
        // Every one of the four arbitration keys is covered, so none can silently lose its label.
        assertEquals(
            WhoopRepository.HEALTH_CONNECT_VITAL_KEYS.toSet(),
            merge.vitalSources.getValue(day).keys,
        )
    }

    /** The reporter's 08-25/26: the computed row carries the vitals, so it keeps them AND keeps its
     *  "On-device" caption — no provenance entry is recorded to override it. */
    @Test
    fun aComputedRowThatSuppliesTheVitalRecordsNoProvenance() {
        val computed = listOf(
            DailyMetric(deviceId = "my-whoop-noop", day = day, restingHr = 50, avgHrv = 88.0, strain = 12.4),
        )
        val base = WhoopRepository.mergeDaily(imported = emptyList(), computed = computed)
        val merge = WhoopRepository.healthConnectVitalMerge(
            base,
            imported = emptyList(),
            computed = computed,
            healthConnect = listOf(hcRow(rhr = 61, hrv = 40.0)),
        )
        assertEquals(emptyMap<String, Map<String, FusionSource>>(), merge.vitalSources)
        assertEquals(50, merge.rows.single().restingHr)
        assertEquals(88.0, merge.rows.single().avgHrv)
    }

    /** An imported strap day is the same story: the strap outranks the phone, so nothing is relabelled. */
    @Test
    fun anImportedStrapVitalRecordsNoProvenance() {
        val imported = listOf(DailyMetric(deviceId = "my-whoop", day = day, restingHr = 48, avgHrv = 92.0))
        val base = WhoopRepository.mergeDaily(imported = imported, computed = emptyList())
        val merge = WhoopRepository.healthConnectVitalMerge(
            base, imported = imported, computed = emptyList(), healthConnect = listOf(hcRow(rhr = 61, hrv = 40.0)),
        )
        assertTrue(merge.vitalSources.isEmpty())
    }

    /** Mixed day: the strap measured resting HR but no HRV. Only the field Health Connect actually won
     *  is recorded — a whole-row label would misattribute the other one. */
    @Test
    fun onlyTheFieldsHealthConnectWonAreRecorded() {
        val computed = listOf(DailyMetric(deviceId = "my-whoop-noop", day = day, restingHr = 50))
        val base = WhoopRepository.mergeDaily(imported = emptyList(), computed = computed)
        val merge = WhoopRepository.healthConnectVitalMerge(
            base, imported = emptyList(), computed = computed, healthConnect = listOf(hcRow(rhr = 61, hrv = 40.0)),
        )
        assertEquals(mapOf(day to mapOf("hrv" to FusionSource.HEALTH_CONNECT)), merge.vitalSources)
        assertEquals(50, merge.rows.single().restingHr)
        assertEquals(40.0, merge.rows.single().avgHrv)
    }

    /** No Health Connect rows ⇒ nothing arbitrated and nothing to relabel. */
    @Test
    fun noHealthConnectDataRecordsNothing() {
        val imported = listOf(DailyMetric(deviceId = "my-whoop", day = day, restingHr = 48))
        val base = WhoopRepository.mergeDaily(imported = imported, computed = emptyList())
        val merge = WhoopRepository.healthConnectVitalMerge(base, imported, emptyList(), emptyList())
        assertEquals(base, merge.rows)
        assertTrue(merge.vitalSources.isEmpty())
    }

    /** A day no strap source covers takes the Health Connect row WHOLE, so the row's own deviceId is
     *  already "health-connect" and every existing caption is right — no entry needed, and adding one
     *  would claim an arbitration that never ran. */
    @Test
    fun anUncoveredDayRecordsNothingBecauseTheRowItselfSaysHealthConnect() {
        val hc = hcRow(rhr = 55, asleep = 431.0)
        val merge = WhoopRepository.healthConnectVitalMerge(emptyList(), emptyList(), emptyList(), listOf(hc))
        assertEquals(listOf(hc), merge.rows)
        assertTrue(merge.vitalSources.isEmpty())
        assertEquals(WhoopRepository.HEALTH_CONNECT_SOURCE, merge.rows.single().deviceId)
    }

    /** Provenance is per DAY: a strap-covered day beside a phone-only one must not borrow its label. */
    @Test
    fun provenanceIsKeyedPerDay() {
        val computed = listOf(
            DailyMetric(deviceId = "my-whoop-noop", day = "2026-08-25", restingHr = 50),
            DailyMetric(deviceId = "my-whoop-noop", day = "2026-08-27", strain = 9.0),
        )
        val base = WhoopRepository.mergeDaily(imported = emptyList(), computed = computed)
        val merge = WhoopRepository.healthConnectVitalMerge(
            base,
            imported = emptyList(),
            computed = computed,
            healthConnect = listOf(hcRow(day = "2026-08-25", rhr = 61), hcRow(day = "2026-08-27", rhr = 62)),
        )
        assertEquals(
            mapOf("2026-08-27" to mapOf("rhr" to FusionSource.HEALTH_CONNECT)),
            merge.vitalSources,
        )
    }

    /** The rows the fold returns are unchanged by the provenance capture: the wrapper the existing call
     *  sites use must stay byte-identical to the full merge. */
    @Test
    fun theRowOnlyWrapperMatchesTheFullMerge() {
        val computed = listOf(DailyMetric(deviceId = "my-whoop-noop", day = day, strain = 12.4))
        val base = WhoopRepository.mergeDaily(imported = emptyList(), computed = computed)
        val hc = listOf(hcRow(rhr = 34, hrv = 159.0, spo2 = 96.6, resp = 12.9))
        assertEquals(
            WhoopRepository.healthConnectVitalMerge(base, emptyList(), computed, hc).rows,
            WhoopRepository.mergeHealthConnectVitals(base, emptyList(), computed, hc),
        )
    }
}
