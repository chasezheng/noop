package com.noop.analytics.calorie

import com.noop.analytics.StrainScorer
import com.noop.analytics.UserProfile
import com.noop.data.HrSample

/**
 * Daily energy estimated from heart rate alone, by the Keytel 2005 equations.
 *
 * Every minute reaches the same path and is held to the same gate; there is no motion channel and no
 * workout window.
 */
class KeytelModel(
    private val profile: UserProfile,
    vitals: CalorieVitals,
    /** The instant the day is scored as of; null scores the whole window. See [activityWindow]. */
    private val nowUtc: Long? = null,
) : CalorieModel {

    /** The single [CalorieTimeline.labeledKcal] key this model writes. */
    object Label {
        const val HEART_RATE: String = "heartRate"
    }

    private val gates = profile.heartRateGates
    private val body = CalorieBody(profile)
    private val effHRmax: Double = vitals.hrmax ?: Calories.defaultHRmax
    private val effResting: Double = vitals.restingHR ?: StrainScorer.defaultRestingHR
    private val vo2max: Double? = Calories.vo2maxFor(profile, effHRmax, vitals.restingHR)

    /** The bpm at or above which an ordinary day-minute earns the Keytel rate rather than basal. */
    val dayGate: Double = Calories.activeHrGate(effResting, effHRmax, gates.dayActiveHRRFraction)

    /** The same gate inside a workout the detector already found. */
    val workoutGate: Double = Calories.activeHrGate(effResting, effHRmax, gates.boutActiveHRRFraction)

    /**
     * The surplus above resting that [hr] earns, per minute of `[startUtc, endUtc)`, for the minutes
     * [owns] accepts at [gate].
     *
     * A minute the caller does not claim advances the walk but earns nothing, so one caller can value
     * two classes of minute at two different gates.
     */
    fun surplusByMinute(
        startUtc: Long,
        endUtc: Long,
        minutes: Int,
        hr: List<HrSample>,
        gate: Double,
        owns: (Int) -> Boolean,
    ): DoubleArray {
        val perMinute = DoubleArray(minutes)
        val ordered = hr.sortedBy { it.ts }
        for (i in ordered.indices) {
            val s = ordered[i]
            if (s.ts < startUtc || s.ts >= endUtc) continue
            val bpm = s.bpm.toDouble()
            if (bpm < gate) continue // basal already covers this second
            // For some profiles a sample just past the gate falls below the basal rate. Floor the
            // surplus at zero so such a sample cannot subtract from the day.
            val rate = Calories.activeKcalPerS(body.coeffs, bpm, effHRmax, body.weightKg, body.age, vo2max)
            val surplus = maxOf(0.0, rate - body.restingRate)
            if (surplus <= 0.0) continue
            var remaining = if (i < ordered.size - 1) {
                val gap = (ordered[i + 1].ts - s.ts).toDouble()
                // A repeated timestamp measures no time; the next sample carries the real gap.
                if (gap > 0) minOf(gap, Calories.dayMaxGapS) else 0.0
            } else {
                1.0 // last sample carries one representative second
            }
            var t = s.ts.toDouble()
            while (remaining > 0.0) {
                val idx = ((t.toLong() - startUtc) / 60L).toInt()
                if (idx < 0 || idx >= minutes) break
                val minuteEnd = minOf(startUtc + idx * 60L + 60L, endUtc).toDouble()
                val take = minOf(remaining, minuteEnd - t)
                if (take <= 0.0) break
                if (owns(idx)) perMinute[idx] += surplus * take
                remaining -= take
                t += take
            }
        }
        return perMinute
    }

    /** The cost of each minute of `[startUtc, endUtc)`. Pure. */
    fun timeline(startUtc: Long, endUtc: Long, inputs: CalorieInputs): CalorieTimeline {
        if (endUtc <= startUtc) {
            return CalorieTimeline(
                tsIndex = LongArray(0),
                totalKcal = DoubleArray(0),
                activeKcal = DoubleArray(0),
                labeledKcal = mapOf(Label.HEART_RATE to DoubleArray(0)),
                labeledSeries = emptyMap(),
                extras = emptyMap(),
            )
        }

        // Motion, ring MET and workout windows are discarded; this model reads heart rate only.
        val hr = CalorieInputs(hr = inputs.hr).clippedTo(startUtc, endUtc).hr
        val minutes = minuteCount(startUtc, endUtc)
        val activeKcal = surplusByMinute(startUtc, endUtc, minutes, hr, dayGate) { true }

        val tsIndex = LongArray(minutes)
        val totalKcal = DoubleArray(minutes)
        for (i in 0 until minutes) {
            val minuteStart = startUtc + i * 60L
            tsIndex[i] = minuteStart
            totalKcal[i] = basalKcalForMinute(profile, minuteStart, endUtc) + activeKcal[i]
        }
        return CalorieTimeline(
            tsIndex = tsIndex,
            totalKcal = totalKcal,
            activeKcal = activeKcal,
            labeledKcal = mapOf(Label.HEART_RATE to activeKcal),
            labeledSeries = emptyMap(),
            extras = emptyMap(),
        )
    }

    override fun timeline(day: ActivityDay, inputs: CalorieInputs): CalorieTimeline {
        val window = activityWindow(day, nowUtc)
        return timeline(window.first, window.exclusiveEnd, inputs)
    }
}

/**
 * The heart-rate-reserve fractions that decide which seconds are scored as effort rather than as rest.
 *
 * Ranges are enforced where the value is persisted ([HeartRateGatesRanges]) rather than here, so
 * hand-built gates in a test say exactly what they mean.
 *
 * Parity debt: this type has no Swift twin. Swift holds the same values as constants, and every
 * default here equals its constant, so a wearer who never edits one gets the same numbers on both
 * platforms and a wearer who edits one diverges.
 */
data class HeartRateGates(

    /**
     * The fraction above which a second of an ordinary day earns the Keytel rate instead of basal
     * alone.
     *
     * One fraction cannot suit both ends of the population, which is why this is settable. For a fit
     * wearer — 42 resting against a 180 maximum — the default sits above walking and an ordinary day
     * scores as pure basal. For a wearer resting near 60 it falls at about 72 bpm, at or below their
     * sitting rate, and Keytel does not decay to basal, so sedentary hours score generously.
     */
    val dayActiveHRRFraction: Double = 0.10,

    /**
     * The same fraction inside a detected or manual workout, where Keytel is applied as intended.
     *
     * Not a detection threshold: it applies only to a workout that already exists. A real session is
     * not effort end to end — the warm-up, the cool-down, the rests between lifting sets and a coasted
     * descent all sit inside the window without being work.
     */
    val boutActiveHRRFraction: Double = 0.10,
)

/**
 * The valid range of both fractions in [HeartRateGates].
 *
 * Beside the type rather than in the settings layer so that persistence and the editing screen cannot
 * drift from what the model accepts. The range is wide enough to be useful and narrow enough that a
 * mis-tap cannot produce a number with no physical meaning, such as a negative gate.
 */
object HeartRateGatesRanges {
    const val HRR_MIN = 0.0
    const val HRR_MAX = 0.60
}
