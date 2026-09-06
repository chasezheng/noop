package com.noop.ui

import com.noop.R
import com.noop.analytics.FusionSource
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The vitals surfaces' half of the read-time Health Connect arbitration: a merged [DailyMetric] carries
 * ONE deviceId, so the tiles and the readings table label the four arbitrated vitals from the per-field
 * winners the merge recorded, falling back to the row's deviceId for everything else.
 *
 * Two things have to hold or the caption lies. The lookup must reach the right ARBITRATION key (the
 * screens spell respiratory rate "resp", the policy "resp_rate"), and it must be keyed on the day the
 * shown number actually came from — Today reads each vital per-field with a carry, so a caption keyed on
 * the selected day would credit a carried number to a night that never measured it.
 */
class VitalFieldProvenanceTest {

    private val today = "2026-08-29"
    private val carried = "2026-08-25"
    private val hcWon = mapOf(
        today to mapOf(
            "rhr" to FusionSource.HEALTH_CONNECT,
            "hrv" to FusionSource.HEALTH_CONNECT,
            "spo2" to FusionSource.HEALTH_CONNECT,
            "resp_rate" to FusionSource.HEALTH_CONNECT,
        ),
    )
    private val healthConnect = DisplayText.Resource(R.string.today_source_health_connect)

    // --- key vocabulary ---

    @Test fun everyArbitrationKeyResolvesToItself() {
        WhoopRepository.HEALTH_CONNECT_VITAL_KEYS.forEach { key ->
            assertEquals(key, healthConnectVitalKey(key))
        }
    }

    /** The screens' "resp" must reach the policy's "resp_rate", or respiratory rate alone stays unlabelled. */
    @Test fun theScreensRespKeyReachesTheResolverRespRate() {
        assertEquals("resp_rate", healthConnectVitalKey("resp"))
    }

    @Test fun aNonArbitratedVitalHasNoArbitrationKey() {
        assertNull(healthConnectVitalKey("recovery"))
        assertNull(healthConnectVitalKey("skin"))
    }

    // --- which row supplied the number ---

    @Test fun theSelectedDayWinsWhenItCarriesTheField() {
        val day = DailyMetric(deviceId = "my-whoop-noop", day = today, avgHrv = 159.0)
        val fallback = DailyMetric(deviceId = "my-whoop-noop", day = carried, avgHrv = 88.0)
        assertEquals(today, vitalValueDay(day, fallback) { it.avgHrv != null })
    }

    @Test fun theCarryDayIsUsedWhenTheSelectedDayLacksTheField() {
        val day = DailyMetric(deviceId = "my-whoop-noop", day = today, strain = 12.4)
        val fallback = DailyMetric(deviceId = "my-whoop-noop", day = carried, avgHrv = 88.0)
        assertEquals(carried, vitalValueDay(day, fallback) { it.avgHrv != null })
    }

    @Test fun noRowSuppliesTheFieldIsNull() {
        val day = DailyMetric(deviceId = "my-whoop-noop", day = today, strain = 12.4)
        assertNull(vitalValueDay(day, null) { it.avgHrv != null })
    }

    // --- the label ---

    @Test fun aHealthConnectWinnerReadsHealthConnect() {
        assertEquals(healthConnect, vitalFieldSourceLabel("hrv", today, hcWon))
        assertEquals(healthConnect, vitalFieldSourceLabel("rhr", today, hcWon))
        assertEquals(healthConnect, vitalFieldSourceLabel("spo2", today, hcWon))
        assertEquals(healthConnect, vitalFieldSourceLabel("resp", today, hcWon))
    }

    /** A day the merge decided nothing for keeps its usual caption — the 08-25 computed-row case. */
    @Test fun aDayWithNoRecordedWinnerHasNoLabel() {
        assertNull(vitalFieldSourceLabel("hrv", carried, hcWon))
        assertNull(vitalFieldSourceLabel("hrv", today, emptyMap()))
        assertNull(vitalFieldSourceLabel("hrv", null, hcWon))
    }

    // --- the dashboard cards ---

    @Test fun eachVitalCardLabelsFromItsOwnField() {
        val day = DailyMetric(
            deviceId = "my-whoop-noop", day = today,
            restingHr = 34, avgHrv = 159.0, spo2Pct = 96.6, respRateBpm = 12.9,
        )
        listOf(
            DashboardCard.HRV,
            DashboardCard.RESTING_HR,
            DashboardCard.RESPIRATORY,
            DashboardCard.BLOOD_OXYGEN,
        ).forEach { card ->
            assertEquals(
                card.name,
                healthConnect,
                dashboardVitalSourceLabel(card, day, null, null, null, null, hcWon),
            )
        }
    }

    /** Respiratory reads the STALENESS-BOUNDED respDay carry, SpO2 the recovery-gated one — each card's
     *  caption must follow the same chain its value does or it names the wrong night. */
    @Test fun theCarryChainsMatchTheValueChains() {
        val strainOnly = DailyMetric(deviceId = "my-whoop-noop", day = carried, strain = 12.4)
        val respCarry = DailyMetric(deviceId = "my-whoop-noop", day = today, respRateBpm = 12.9)
        assertEquals(
            healthConnect,
            dashboardVitalSourceLabel(
                DashboardCard.RESPIRATORY, strainOnly, null, null, null, respCarry, hcWon,
            ),
        )
        val spo2Carry = DailyMetric(deviceId = "my-whoop-noop", day = today, spo2Pct = 96.6)
        assertEquals(
            healthConnect,
            dashboardVitalSourceLabel(
                DashboardCard.BLOOD_OXYGEN, strainOnly, null, null, spo2Carry, null, hcWon,
            ),
        )
    }

    @Test fun aNonVitalCardIsNeverRelabelled() {
        val day = DailyMetric(deviceId = "my-whoop-noop", day = today, totalSleepMin = 431.0, steps = 8000)
        assertNull(dashboardVitalSourceLabel(DashboardCard.SLEEP, day, null, null, null, null, hcWon))
        assertNull(dashboardVitalSourceLabel(DashboardCard.STEPS, day, null, null, null, null, hcWon))
    }

    // --- the Health readings table ---

    @Test fun aWonReadingIsStampedHealthConnect() {
        val row = DailyMetric(deviceId = "my-whoop-noop", day = today, avgHrv = 159.0)
        assertEquals(WhoopRepository.HEALTH_CONNECT_SOURCE, vitalReadingSource(row, "hrv", hcWon))
        assertEquals(
            DisplayText.Resource(R.string.today_source_health_connect),
            provenanceDisplayLabel(vitalReadingSource(row, "hrv", hcWon), "my-whoop"),
        )
    }

    /** The 08-25 case end to end: the computed row supplied the vital, so the table still reads
     *  "On-device" — the label this change must not break. */
    @Test fun aReadingTheComputedRowSuppliedKeepsItsOwnDeviceId() {
        val row = DailyMetric(deviceId = "my-whoop-noop", day = carried, avgHrv = 88.0)
        assertEquals("my-whoop-noop", vitalReadingSource(row, "hrv", hcWon))
        assertEquals(
            DisplayText.Resource(R.string.today_source_on_device),
            provenanceDisplayLabel(vitalReadingSource(row, "hrv", hcWon), "my-whoop"),
        )
    }

    @Test fun aNonArbitratedVitalKeepsItsRowDeviceId() {
        val row = DailyMetric(deviceId = "my-whoop-noop", day = today, recovery = 62.0)
        assertEquals("my-whoop-noop", vitalReadingSource(row, "recovery", hcWon))
    }
}
