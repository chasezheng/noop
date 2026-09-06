package com.noop.data

import com.noop.analytics.AgreementState
import com.noop.analytics.FusionInput
import com.noop.analytics.FusionResolver
import com.noop.analytics.FusionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health Connect vitals are written under their own source, so nothing is dropped at import time and
 * the read side decides who is shown. This pins that read side, which resolves each metric over the
 * strap import, the on-device computed row and the phone aggregate.
 *
 * The guarantee runs in both directions: the strap must never lose a day it measured, and a day the
 * strap left empty must not stay blank. Pure: no storage.
 */
class HealthConnectVitalsArbitrationTest {

    private val day = "2026-08-20"

    private fun strapRow(source: String = "my-whoop", rhr: Int? = null, hrv: Double? = null) =
        DailyMetric(deviceId = source, day = day, restingHr = rhr, avgHrv = hrv)

    private fun hcRow(
        rhr: Int? = null,
        hrv: Double? = null,
        spo2: Double? = null,
        resp: Double? = null,
        asleep: Double? = null,
    ) = DailyMetric(
        deviceId = "health-connect", day = day, totalSleepMin = asleep,
        restingHr = rhr, avgHrv = hrv, spo2Pct = spo2, respRateBpm = resp,
    )

    @Test
    fun strapValueBeatsHealthConnectWhenBothCoverTheDay() {
        val imported = listOf(strapRow(rhr = 48, hrv = 92.0))
        val base = WhoopRepository.mergeDaily(imported = imported, computed = emptyList())
        val merged = WhoopRepository.mergeHealthConnectVitals(
            base, imported = imported, computed = emptyList(), healthConnect = listOf(hcRow(rhr = 61, hrv = 40.0)),
        )
        val row = merged.single { it.day == day }
        assertEquals(48, row.restingHr)
        assertEquals(92.0, row.avgHrv)
    }

    /** The on-device computed row is still a strap measurement — it outranks the phone too. */
    @Test
    fun computedStrapValueAlsoBeatsHealthConnect() {
        val computed = listOf(strapRow(source = "my-whoop-noop", rhr = 50, hrv = 88.0))
        val base = WhoopRepository.mergeDaily(imported = emptyList(), computed = computed)
        val merged = WhoopRepository.mergeHealthConnectVitals(
            base, imported = emptyList(), computed = computed, healthConnect = listOf(hcRow(rhr = 61, hrv = 40.0)),
        )
        val row = merged.single { it.day == day }
        assertEquals(50, row.restingHr)
        assertEquals(88.0, row.avgHrv)
    }

    /**
     * The reporter's case: the strap scored the day's strain but recorded no overnight vitals, so every
     * vital column is null and Today carried a week-old value forward. Health Connect has all four.
     */
    @Test
    fun healthConnectFillsTheVitalsTheStrapDayLeftNull() {
        val computed = listOf(DailyMetric(deviceId = "my-whoop-noop", day = day, strain = 12.4))
        val base = WhoopRepository.mergeDaily(imported = emptyList(), computed = computed)
        assertNull(base.single().avgHrv)

        val merged = WhoopRepository.mergeHealthConnectVitals(
            base,
            imported = emptyList(),
            computed = computed,
            healthConnect = listOf(hcRow(rhr = 34, hrv = 159.0, spo2 = 96.6, resp = 12.9)),
        )
        val row = merged.single { it.day == day }
        assertEquals(34, row.restingHr)
        assertEquals(159.0, row.avgHrv)
        assertEquals(96.6, row.spo2Pct)
        assertEquals(12.9, row.respRateBpm)
        // The strap's own score is untouched: the fold only ever writes the four vitals.
        assertEquals(12.4, row.strain)
    }

    /**
     * A day no strap source covers takes the Health Connect row whole, not just its vitals: without
     * that, a wearer with no strap history loses their sleep totals outright.
     */
    @Test
    fun anUncoveredDayTakesTheHealthConnectRowWhole() {
        val hc = hcRow(rhr = 55, asleep = 431.0)
        val merged = WhoopRepository.mergeHealthConnectVitals(
            base = emptyList(), imported = emptyList(), computed = emptyList(), healthConnect = listOf(hc),
        )
        assertEquals(listOf(hc), merged)
    }

    /** No Health Connect data ⇒ the merged series is returned untouched, identity-equal input rows. */
    @Test
    fun noHealthConnectRowsIsAPassthrough() {
        val base = WhoopRepository.mergeDaily(imported = listOf(strapRow(rhr = 48)), computed = emptyList())
        assertEquals(base, WhoopRepository.mergeHealthConnectVitals(base, base, emptyList(), emptyList()))
    }

    @Test
    fun foldReturnsOldestFirstRegardlessOfInputOrder() {
        val hc = listOf(
            DailyMetric(deviceId = "health-connect", day = "2026-08-22", restingHr = 51),
            DailyMetric(deviceId = "health-connect", day = "2026-08-20", restingHr = 52),
            DailyMetric(deviceId = "health-connect", day = "2026-08-21", restingHr = 53),
        )
        val merged = WhoopRepository.mergeHealthConnectVitals(emptyList(), emptyList(), emptyList(), hc)
        assertEquals(listOf("2026-08-20", "2026-08-21", "2026-08-22"), merged.map { it.day })
    }

    // --- the policy the fold leans on, asserted directly ---

    /**
     * `resp_rate` has no MetricKind of its own and tiers through OTHER. That is only acceptable while
     * OTHER happens to order strap import above computed above phone — pinned here, because the day it
     * stops being true the respiratory rate silently starts preferring the phone.
     */
    @Test
    fun respiratoryRateResolvesThroughTheOtherTierOrdering() {
        val point = FusionResolver.resolve(
            "resp_rate",
            listOf(
                FusionInput(FusionSource.HEALTH_CONNECT, 12.9),
                FusionInput(FusionSource.WHOOP_IMPORT, 14.1),
                FusionInput(FusionSource.NOOP_COMPUTED, 13.5),
            ),
        )!!
        assertEquals(FusionSource.WHOOP_IMPORT, point.winningSource)
        assertEquals(14.1, point.value, 0.0)
        assertEquals(
            listOf(FusionSource.WHOOP_IMPORT, FusionSource.NOOP_COMPUTED, FusionSource.HEALTH_CONNECT),
            point.contributors.map { it.source },
        )
    }

    /** One source ⇒ SINGLE: nothing to cross-check, and the screens show no agreement chip. */
    @Test
    fun aLoneHealthConnectVitalResolvesAsSingle() {
        val point = FusionResolver.resolve("hrv", listOf(FusionInput(FusionSource.HEALTH_CONNECT, 159.0)))!!
        assertEquals(FusionSource.HEALTH_CONNECT, point.winningSource)
        assertEquals(AgreementState.SINGLE, point.agreement)
    }

    /** The strap-preferred resolver branch must still reach Health Connect, or every series reader
     *  loses the vitals only that source carries. */
    @Test
    fun strapPreferredCandidatesReachHealthConnect() {
        val sources = WhoopRepository.sourceCandidates("hrv", "my-whoop", "my-whoop").map { it.source }
        assertTrue("health-connect" in sources)
        // Last, so every strap source still wins the days it covers.
        assertEquals("health-connect", sources.last())
    }
}
