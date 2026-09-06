package com.noop.analytics.calorie

import com.noop.analytics.UserProfile

/**
 * One day's energy, minute by minute, as the model that estimated it reports it.
 *
 * Every series is aligned to [tsIndex] and the same length, so a reader indexes them together rather
 * than joining on a timestamp. Which keys the maps carry is the producing model's own contract; the
 * day figures below are folded from the series and so are reported by every model.
 */
// A plain class rather than a data class: a generated `equals` would compare a DoubleArray by
// reference, and no caller compares whole timelines.
class CalorieTimeline(
    /** Minute-start unix seconds, ascending. */
    val tsIndex: LongArray,
    /**
     * Total energy per minute: resting metabolism plus whatever the minute earned above it.
     *
     * The resting term is taken one minute at a time, each following the seconds that elapsed in it,
     * so the last minute of an in-progress day is a partial one.
     */
    val totalKcal: DoubleArray,
    /** Energy above resting per minute. Resting is `totalKcal[i] - activeKcal[i]`. */
    val activeKcal: DoubleArray,
    /**
     * [activeKcal] split by the path that estimated each minute, so a minute is non-zero in at most
     * one series. Keys are the producing model's own label constants.
     */
    val labeledKcal: Map<String, DoubleArray>,
    /** Per-minute series that are not energy. Null where the minute carried no value. */
    val labeledSeries: Map<String, List<Double?>>,
    /** Whole-day figures the producing model reports about its own working. Keys are its constants. */
    val extras: Map<String, Double>,
) {

    /** The day's energy above resting, folded from [activeKcal] left to right. */
    // Floating-point addition is not associative, and this figure is stored: any other order would
    // move its last bits.
    val dayActiveKcal: Double
        get() {
            var sum = 0.0
            for (v in activeKcal) sum += v
            return sum
        }

    /** The day's whole energy, folded from [totalKcal] left to right. */
    val dayTotalKcal: Double
        get() {
            var sum = 0.0
            for (v in totalKcal) sum += v
            return sum
        }

    /** The day's resting metabolism over the scored minutes, which no series states on its own. */
    val dayBasalKcal: Double get() = dayTotalKcal - dayActiveKcal

    companion object {

        /**
         * [extras] key a model sets to 1 where it could not estimate the window at all.
         *
         * A declined window still reports its resting term, so without this a refusal reads as a
         * measured zero above resting. Absent from a model that estimates every window it is handed.
         */
        const val DECLINED: String = "declined"

        /**
         * [extras] key for the resting heart rate (bpm) the window's energy was anchored on.
         *
         * Absent from a model that assumes its resting anchor rather than measuring one.
         */
        const val MEASURED_BASAL_HR_BPM: String = "measuredBasalHrBpm"
    }
}

/**
 * One way of estimating what each minute of a wearer's day cost.
 *
 * A model answers that question only; a caller folds out whatever totals it wants. One method, so
 * every total drawn from a model agrees by construction. Deterministic: the same day and the same
 * inputs always give the same answer.
 */
interface CalorieModel {

    /** The cost of each minute of [day]'s activity window, in order, estimated from [inputs]. */
    fun timeline(day: ActivityDay, inputs: CalorieInputs): CalorieTimeline
}

/** The first second after the range. */
// Not `LongRange.endExclusive`, which is deprecated and throws on a range ending at Long.MAX_VALUE.
val LongRange.exclusiveEnd: Long get() = last + 1

/**
 * The wearer's body as every estimator reads it, with the substitutions a blank profile gets.
 *
 * Held rather than inherited, so the estimators agree on a wearer without sharing a supertype.
 */
internal class CalorieBody(profile: UserProfile) {
    val weightKg: Double = if (profile.weightKg > 0) profile.weightKg else 70.0
    val heightCm: Double = if (profile.heightCm > 0) profile.heightCm else 170.0
    val age: Double = if (profile.age > 0) profile.age else 30.0
    val coeffs: Calories.Coeffs = Calories.resolveCoeffs(profile.sex)

    /** Harris-Benedict basal, per second. */
    val restingRate: Double = Calories.restingKcalPerS(coeffs, weightKg, heightCm, age)
}

/** How many one-minute epochs tile `[startUtc, endUtc)`. */
internal fun minuteCount(startUtc: Long, endUtc: Long): Int {
    if (endUtc <= startUtc) return 0
    return ((endUtc - startUtc + 59L) / 60L).toInt()
}

/**
 * Resting metabolism over the minute opening at [minuteStart], inside a window ending at [endUtc].
 *
 * The last minute of an in-progress day is a partial one, so this follows the seconds that actually
 * elapsed rather than a flat sixty.
 */
internal fun basalKcalForMinute(profile: UserProfile, minuteStart: Long, endUtc: Long): Double =
    Calories.basalKcalForSpan(profile, (minOf(minuteStart + 60L, endUtc) - minuteStart).toDouble())

/**
 * The activity window for [day], ending no later than [nowUtc].
 *
 * [nowUtc] is the instant the day is scored as of, not the wall clock, so that re-scoring the same
 * day twice gives the same figure. Null applies no cut-off.
 */
internal fun activityWindow(day: ActivityDay, nowUtc: Long?): LongRange {
    val whole = day.window()
    return whole.first until if (nowUtc != null) minOf(whole.exclusiveEnd, nowUtc) else whole.exclusiveEnd
}

/**
 * Whether [met] reaches the floor at which a minute starts earning above resting.
 *
 * The floor is a wearer setting, so it is passed in: a minute is a measurement, and what counts as
 * active is a preference applied to it.
 */
internal fun clearsAccrualFloor(met: Double?, setting: HybridModelSetting): Boolean =
    met != null && met >= setting.activeAccrualMET
