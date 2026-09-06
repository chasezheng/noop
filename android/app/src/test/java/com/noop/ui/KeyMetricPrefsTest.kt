package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored Key-Metrics layout.
 *
 * The persisted ids cross the `.noopbak` boundary to the macOS twin, so they are pinned as literals
 * rather than derived from the enum, which would agree with itself however it were renamed.
 */
class KeyMetricPrefsTest {

    @Test fun anUnsetLayoutShowsEveryTile() {
        assertEquals(KeyMetric.defaultOrder, KeyMetricPrefs.decodeEnabled(null))
        assertEquals(KeyMetric.defaultOrder, KeyMetricPrefs.decodeEnabled(""))
        assertEquals(KeyMetric.defaultOrder, KeyMetricPrefs.decodeEnabled("   "))
    }

    /**
     * A tile outside [KeyMetric.defaultOrder] is opt-in: hidden on a fresh install and on any layout
     * that never named it. SKIN_TEMP is deliberately such a tile, so this fixes only that every
     * entry of the default order is a real tile and that none repeats.
     */
    @Test fun theDefaultOrderNamesRealTilesOnce() {
        assertEquals(KeyMetric.defaultOrder.toSet().size, KeyMetric.defaultOrder.size)
        assertTrue(KeyMetric.defaultOrder.all { it in KeyMetric.entries })
        assertTrue("the Total Energy tile ships enabled", KeyMetric.TOTAL_ENERGY in KeyMetric.defaultOrder)
    }

    @Test fun theStoredIdsAreTheOnesTheOtherPlatformReads() {
        assertEquals(
            "charge,effort,rest,hrv,restingHr,bloodOxygen,respiratory,steps,weight,calories," +
                "totalEnergy",
            KeyMetricPrefs.encode(KeyMetric.defaultOrder),
        )
    }

    @Test fun aSavedLayoutKeepsItsOrderAndDropsWhatItDoesNotName() {
        assertEquals(
            listOf(KeyMetric.TOTAL_ENERGY, KeyMetric.CHARGE),
            KeyMetricPrefs.decodeEnabled("totalEnergy, charge, notATile, charge"),
        )
    }

    /** A layout naming nothing this build knows falls back rather than blanking the grid. */
    @Test fun anUnreadableLayoutShowsEveryTile() {
        assertEquals(KeyMetric.defaultOrder, KeyMetricPrefs.decodeEnabled("nope,,zzz"))
    }
}
