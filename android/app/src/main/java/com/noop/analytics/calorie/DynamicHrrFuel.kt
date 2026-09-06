package com.noop.analytics.calorie

import com.noop.analytics.HrZoneSet

/**
 * How much energy a litre of oxygen releases, and how that follows the fuel being burned.
 *
 * Fat and carbohydrate cost different amounts of oxygen per kcal, so a model that tracks the mix
 * cannot use one constant. Every value here is thermochemistry rather than a preference, which is
 * why none of them is a wearer setting.
 */
internal object FuelMix {

    /** kcal released per litre of oxygen when the fuel is pure fat. */
    const val E_FAT: Double = 4.686

    /** kcal released per litre of oxygen when the fuel is pure carbohydrate. */
    const val E_CHO: Double = 5.047

    /** The brain's share of resting metabolism, the standard adult figure. It runs on glucose alone. */
    const val BRAIN_BASAL_FRACTION: Double = 0.20

    /** kcal per gram of fat. */
    const val KCAL_PER_G_FAT: Double = 9.4

    /** kcal per gram of carbohydrate. */
    const val KCAL_PER_G_CHO: Double = 4.1

    /** The caloric equivalent of oxygen when fat supplies [fatFraction] of the energy. */
    fun kcalPerLitreO2(fatFraction: Double): Double {
        val f = fatFraction.coerceIn(0.0, 1.0)
        return 1.0 / (f / E_FAT + (1.0 - f) / E_CHO)
    }

}

/**
 * [ys] at [x], linear between the anchors and held flat outside them.
 *
 * [xs] must be non-decreasing and the same length as [ys]. A setting that collapses a segment to
 * zero width is answered by the anchor before it, so no division by zero arises.
 */
internal fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
    require(xs.isNotEmpty() && xs.size == ys.size) { "an anchor needs both an x and a y" }
    if (x <= xs[0]) return ys[0]
    for (i in 1 until xs.size) {
        if (x > xs[i]) continue
        // Arriving here means x > xs[i-1] as well as x <= xs[i], which two anchors sharing an x
        // cannot both satisfy: a collapsed segment is answered at its earlier anchor and never
        // reaches this line, so the width below is positive.
        return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / (xs[i] - xs[i - 1])
    }
    return ys[ys.size - 1]
}

/**
 * The share of resting energy that comes from fat at a given local hour.
 *
 * Resting energy itself is constant — the demand for adenosine triphosphate (ATP) at rest does not
 * move across the day. What moves is the oxygen it takes to meet that demand, because fat costs more
 * oxygen per kcal than carbohydrate does.
 */
internal class BasalFatCurve(setting: DynamicHrrModelSetting) {

    private val hours: DoubleArray
    private val shares: DoubleArray

    init {
        // The overnight plateau is held from this hour to the start of the morning ramp, and the
        // curve wraps onto the same hour a day later. It is an anchor of the shape rather than a
        // measurement, so it is fixed here.
        val nightHour = 2.0
        val dayStart = setting.basalFatDayStartHour.coerceIn(nightHour + 1.0, nightHour + 24.0)
        val dayEnd = setting.basalFatDayEndHour.coerceIn(dayStart, nightHour + 24.0)
        hours = doubleArrayOf(nightHour, dayStart - 1.0, dayStart, dayEnd, nightHour + 24.0)
        shares = doubleArrayOf(
            setting.basalFatNight, setting.basalFatNight,
            setting.basalFatDay, setting.basalFatDay,
            setting.basalFatNight,
        )
    }

    /** The fat share of TOTAL resting energy at [hourOfDay], after the brain's glucose is removed. */
    fun fractionAt(hourOfDay: Double): Double {
        val wrapped = if (hourOfDay < hours[0]) hourOfDay + 24.0 else hourOfDay
        return interpolate(hours, shares, wrapped) * (1.0 - FuelMix.BRAIN_BASAL_FRACTION)
    }
}

/**
 * The share of active energy that comes from fat at a given heart rate.
 *
 * Anchored on heart rate itself rather than scaled from the basal heart rate, so the curve does not
 * move when the basal detector settles on a different resting level.
 */
internal class ActiveFatCurve(zones: HrZoneSet, setting: DynamicHrrModelSetting) {

    // The bottom of zone 1, the top of zone 2, and the anaerobic threshold at the bottom of zone 5.
    // Taken from the wearer's own zones, so the curve moves with any custom boundaries they set.
    private val bpm = doubleArrayOf(
        zones.zones[0].lower,
        zones.zones[1].upper,
        zones.zones[4].lower,
    )

    private val shares = doubleArrayOf(
        setting.activeFatAtZone1,
        setting.activeFatAtZone2Top,
        // Above the anaerobic threshold the fat contribution is nil. That is a property of the curve
        // rather than a choice, so it is not a setting.
        0.0,
    )

    /** The fat share of active energy at [bpm], held at the end values outside the anchors. */
    fun fractionAt(bpm: Double): Double =
        interpolate(this.bpm, shares, bpm).coerceIn(0.0, 1.0)
}
