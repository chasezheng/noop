package com.noop.analytics.calorie

import com.noop.analytics.HrZones
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fuel curves that decide what a litre of oxygen is worth.
 *
 * They move the day's energy without moving the heart rate behind it, so a curve that stops
 * personalising is invisible in every heart-rate-level assertion.
 */
class DynamicHrrFuelTest {

    @Test
    fun caloricEquivalent_isTheFatValueOnPureFat() {
        assertEquals(4.686, FuelMix.kcalPerLitreO2(1.0), 1e-12)
    }

    @Test
    fun caloricEquivalent_isTheCarbohydrateValueOnPureCarbohydrate() {
        assertEquals(5.047, FuelMix.kcalPerLitreO2(0.0), 1e-12)
    }

    @Test
    fun caloricEquivalent_isTheHarmonicBlendOfAMixture() {
        assertEquals(4.8598051988081785, FuelMix.kcalPerLitreO2(0.5), 1e-12)
    }

    @Test
    fun caloricEquivalent_holdsAtTheEndsForAShareOutsideZeroToOne() {
        assertEquals(4.686, FuelMix.kcalPerLitreO2(1.4), 1e-12)
    }

    @Test
    fun caloricEquivalent_holdsAtTheEndsForAShareBelowZero() {
        assertEquals(5.047, FuelMix.kcalPerLitreO2(-0.5), 1e-12)
    }

    // ── Interpolation between the curves' anchors ─────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun interpolation_refusesACurveWithNoAnchors() {
        interpolate(DoubleArray(0), DoubleArray(0), 12.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun interpolation_refusesAnchorsThatDoNotPairUp() {
        interpolate(doubleArrayOf(0.0, 1.0), doubleArrayOf(4.0), 0.5)
    }

    @Test
    fun interpolation_holdsAtTheFirstAnchorBelowIt() {
        // Descending anchors, so extrapolating off the front would run ABOVE the first value rather
        // than below it. Neither curve can show this: both clamp their own result to a share, and the
        // clamp rewrites an extrapolated value back to the anchor the hold would have returned.
        assertEquals(4.0, interpolate(doubleArrayOf(10.0, 20.0), doubleArrayOf(4.0, 1.0), 5.0), 1e-12)
    }

    @Test
    fun interpolation_holdsAtTheLastAnchorAboveIt() {
        assertEquals(1.0, interpolate(doubleArrayOf(10.0, 20.0), doubleArrayOf(4.0, 1.0), 25.0), 1e-12)
    }

    // ── The resting fuel mix through the day ──────────────────────────────────────────────────

    private val basal = RestingFatCurve(DynamicHrrModelSetting())

    /** The brain's glucose is removed from every resting share, so a plateau reads at 80% of itself. */
    private val awake = 0.8

    @Test
    fun basalFat_holdsTheOvernightShareThroughTheMorningPlateau() {
        assertEquals(0.80 * awake, basal.fractionAt(6.0), 1e-12)
    }

    @Test
    fun basalFat_holdsTheOvernightShareAtTheAnchorTheCurveOpensOn() {
        // 02:00 is the first anchor, and the curve is held flat at or below it rather than run off
        // the front of the day.
        assertEquals(0.80 * awake, basal.fractionAt(2.0), 1e-12)
    }

    @Test
    fun basalFat_keepsTheMorningRampInsideTheDayWhenTheStartHourIsBelowIt() {
        // The ramp is an hour long and the overnight plateau anchors at 02:00, so a start hour under
        // 03:00 is held there. Read at 02:30, halfway up the ramp the clamp put there: unclamped the
        // anchors run backwards and 02:30 lands past the whole ramp on the daytime share instead.
        val early = RestingFatCurve(DynamicHrrModelSetting(restingFatDayStartHour = 0.0))

        assertEquals(0.55 * awake, early.fractionAt(2.5), 1e-12)
    }

    @Test
    fun basalFat_holdsTheDaytimeShareBetweenItsAnchors() {
        assertEquals(0.30 * awake, basal.fractionAt(14.0), 1e-12)
    }

    @Test
    fun basalFat_rampsBetweenTheTwoPlateaus() {
        // Halfway down the one-hour ramp from 09:00 to 10:00.
        assertEquals(0.55 * awake, basal.fractionAt(9.5), 1e-12)
    }

    @Test
    fun basalFat_wrapsPastMidnightOntoTheOvernightClimb() {
        // 01:00 is 25:00 on the anchor axis: five sixths of the way from 20:00 back to 02:00.
        assertEquals((0.30 + (0.80 - 0.30) * 5.0 / 6.0) * awake, basal.fractionAt(1.0), 1e-12)
    }

    @Test
    fun basalFat_closesTheDaytimeWindowAtItsStartWhenTheHoursAreSetTheWrongWayRound() {
        // The persisted ranges let the daytime window close before it opens. The end hour is held up
        // to the start, collapsing the window to a point and starting the climb back to the overnight
        // share there. Read at 22:00, on that climb: left uncollapsed the climb starts nine hours
        // earlier and is far further along by then.
        val collapsed = RestingFatCurve(
            DynamicHrrModelSetting(restingFatDayStartHour = 20.0, restingFatDayEndHour = 9.0),
        )

        assertEquals((0.30 + 0.50 * 2.0 / 6.0) * awake, collapsed.fractionAt(22.0), 1e-12)
    }

    // ── The active fuel mix against heart rate ────────────────────────────────────────────────

    /** Conventional zones off a 180 bpm maximum: zone 1 opens at 90, zone 2 tops at 126, zone 5 at 162. */
    private val active = ActiveFatCurve(HrZones.zones(maxHR = 180.0), DynamicHrrModelSetting())

    @Test
    fun activeFat_isTheZone1AnchorAtTheBottomOfZone1() {
        assertEquals(1.0, active.fractionAt(90.0), 1e-12)
    }

    @Test
    fun activeFat_isTheZone2AnchorAtTheTopOfZone2() {
        assertEquals(0.66, active.fractionAt(126.0), 1e-12)
    }

    @Test
    fun activeFat_isNilAtTheAnaerobicThreshold() {
        assertEquals(0.0, active.fractionAt(162.0), 1e-12)
    }

    @Test
    fun activeFat_isLinearBetweenTheAnchors() {
        assertEquals(0.83, active.fractionAt(108.0), 1e-12)
    }

    @Test
    fun activeFat_holdsAtItsEndValuesOutsideTheAnchors() {
        assertEquals(1.0, active.fractionAt(40.0), 1e-12)
        assertEquals(0.0, active.fractionAt(200.0), 1e-12)
    }

    @Test
    fun activeFat_holdsAShareOutsideZeroToOneAtTheEnds() {
        // A share is a share whatever a hand-built setting says. Nothing enforces the persisted
        // range inside the curve, so the answer is clamped rather than left above one.
        val impossible = ActiveFatCurve(
            HrZones.zones(maxHR = 180.0),
            DynamicHrrModelSetting(activeFatZone1Frac = 5.0),
        )

        assertEquals(1.0, impossible.fractionAt(90.0), 1e-12)
    }

    @Test
    fun activeFat_followsTheWearersOwnZoneBoundaries() {
        val custom = ActiveFatCurve(
            HrZones.zones(maxHR = 180.0, customLowerBounds = listOf(80.0, 100.0, 135.0, 150.0, 162.0)),
            DynamicHrrModelSetting(),
        )

        assertEquals(1.0, custom.fractionAt(80.0), 1e-12)
        assertEquals(0.66, custom.fractionAt(135.0), 1e-12)
    }
}
