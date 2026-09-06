package com.noop.analytics.calorie

import com.noop.data.GravitySample

/**
 * The motion-to-MET transfer, the minute grid and the display bands [HybridModel] scores from.
 *
 * MET is the metabolic equivalent of task, a multiple of resting burn. The structure follows what
 * Oura documents for its activity day: one averaged MET value per minute, energy from minutes at or
 * above an accrual floor, and minutes banded for display.
 *
 * Oura has never published the accelerometer-to-MET transfer or the low/medium/high band edges, so
 * every value filling those gaps is a project choice, labelled as such where it is defined. The
 * totals are not expected to match the Oura app.
 *
 * The published accuracy of ring and wrist energy expenditure is poor — mean absolute percentage
 * error above 20% against indirect calorimetry, worsening with intensity, and day-level limits of
 * agreement several hundred kcal wide (Kristiansson 2023, BMC Med Res Methodol 23:50; Henriksen 2022,
 * JMIR Form Res 6(5):e27248) — so this belongs behind an Experimental toggle and must not feed a
 * downstream score.
 *
 * Pure, deterministic and free of database access.
 *
 * Parity: the constants, the transfer and the banding are mirrored in
 * `StrandAnalytics/MetCalories.swift` and must stay byte-identical. The day estimator is not
 * mirrored: that is [HybridModel], which has no Swift counterpart.
 */
object HybridMet {

    // ---- Band edges ----

    /** The `class_5_min` band edges Oura documents: rest below 1.05, inactive to 2.0. */
    const val restBandMaxMET: Double = 1.05
    const val inactiveBandMaxMET: Double = 2.0

    /**
     * The low-to-medium and medium-to-high band edges.
     *
     * Age- and sex-dependent in Oura's model and never published, so these are project constants read
     * off the ACSM intensity bands (moderate 3–6 MET, vigorous at or above 6). Display only: neither
     * edge reaches a calorie total, so a wrong value mislabels minutes without changing energy.
     */
    const val mediumBandMinMET: Double = 4.0
    const val highBandMinMET: Double = 6.0

    /**
     * The ceiling on any one epoch's MET. Nothing a human sustains exceeds about 20 MET, so this
     * clamps a strap knock or a decode glitch rather than any real activity.
     */
    const val maxMET: Double = 20.0


    // ---- Transfer function ----

    /** The MET a gravity-removed motion magnitude (g) corresponds to, clamped to `[1, maxMET]`. */
    fun metFromDynAccel(dynAccelG: Double, setting: HybridModelSetting): Double {
        val g = maxOf(0.0, dynAccelG)
        return minOf(maxMET, 1.0 + setting.dynAccelMETGainPerG * g)
    }

    /**
     * The `class_5_min` band a MET value falls in: 1 rest, 2 inactive, 3 low, 4 medium, 5 high.
     *
     * Never 0, the non-wear band, which describes an epoch with no MET value at all.
     */
    fun bandForMET(met: Double): Int = when {
        met < restBandMaxMET -> 1
        met < inactiveBandMaxMET -> 2
        met < mediumBandMinMET -> 3
        met < highBandMinMET -> 4
        else -> 5
    }

    // ---- Epoch construction ----

    /**
     * One minute of the activity day. [met] is null when the minute carried no usable motion, which
     * is every minute on a strap that reports no dynamic acceleration.
     */
    data class Epoch(val start: Long, val met: Double?)

    /**
     * The per-minute epochs tiling `[start, end)`, each averaging the MET values that landed in it.
     *
     * A ring reports MET directly, so [ringMET] wins any minute it covers and carries none of
     * [HybridModelSetting.dynAccelMETGainPerG]'s uncertainty. [gravity] fills the rest; a minute with
     * neither stays null.
     */
    // [setting] takes no default: a caller that omitted it would silently score with the shipped MET
    // gain rather than the wearer's, with no compile error and no failing test.
    fun epochs(
        start: Long,
        end: Long,
        gravity: List<GravitySample>,
        ringMET: List<Pair<Long, Double>>,
        setting: HybridModelSetting,
    ): List<Epoch> {
        if (end <= start) return emptyList()
        val count = minuteCount(start, end)
        if (count <= 0) return emptyList()

        val ringSum = DoubleArray(count)
        val ringN = IntArray(count)
        val motionSum = DoubleArray(count)
        val motionN = IntArray(count)

        for ((ts, ringValue) in ringMET) {
            if (ts < start || ts >= end) continue
            val i = ((ts - start) / 60L).toInt()
            if (i < 0 || i >= count) continue
            ringSum[i] += maxOf(0.0, minOf(maxMET, ringValue))
            ringN[i] += 1
        }
        for (s in gravity) {
            val dyn = s.dynAccel ?: continue
            if (s.ts < start || s.ts >= end) continue
            val i = ((s.ts - start) / 60L).toInt()
            if (i < 0 || i >= count) continue
            motionSum[i] += metFromDynAccel(dyn, setting)
            motionN[i] += 1
        }

        val out = ArrayList<Epoch>(count)
        for (i in 0 until count) {
            val epochStart = start + i * 60L
            when {
                ringN[i] > 0 -> out.add(Epoch(epochStart, ringSum[i] / ringN[i]))
                motionN[i] > 0 -> out.add(Epoch(epochStart, motionSum[i] / motionN[i]))
                else -> out.add(Epoch(epochStart, null))
            }
        }
        return out
    }

    /**
     * Whether `[start, end)` overlaps any of [workouts].
     *
     * Half-open on both sides, so a workout ending exactly as a minute begins does not claim it.
     */
    fun overlapsWorkout(start: Long, end: Long, workouts: List<LongRange>): Boolean {
        for (w in workouts) {
            if (w.first < end && start < w.exclusiveEnd) return true
        }
        return false
    }
}
