package com.noop.analytics

import com.noop.analytics.calorie.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `EnergyModel.id` is the token the `.noopbak` whitelist carries under `calorie.model`.
 *
 * A backup written by one build is read by another, and by the other platform, so the spelling is a
 * contract rather than an implementation detail. Pinning the literals here is what stops a rename or
 * a reordering from re-pointing every backup already written to a model the wearer never chose.
 */
class EnergyModelIdTest {

    @Test
    fun theWireTokensAreTheOnesAlreadyWritten() {
        assertEquals("hr", EnergyModel.HEART_RATE.id)
        assertEquals("hybrid", EnergyModel.HYBRID.id)
    }

    @Test
    fun everyModelRoundTripsThroughItsToken() {
        for (model in EnergyModel.values()) assertEquals(model, EnergyModel.forId(model.id))
    }

    @Test
    fun everyModelHasItsOwnToken() {
        val ids = EnergyModel.values().map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun anUnknownTokenResolvesToNothing() {
        // So a payload from a future build leaves the wearer's current selection alone rather than
        // silently landing on whichever case happens to be first.
        assertNull(EnergyModel.forId("met"))
        assertNull(EnergyModel.forId(""))
        assertNull(EnergyModel.forId(null))
    }
}
