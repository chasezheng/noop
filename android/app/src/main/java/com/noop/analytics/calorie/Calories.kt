package com.noop.analytics.calorie

import com.noop.analytics.UserProfile
import com.noop.analytics.WorkoutDetector
import com.noop.data.GravitySample
import com.noop.data.HrSample
import kotlin.math.min

/**
 * Energy expenditure estimated from heart rate: Keytel 2005 active burn above a revised
 * Harris–Benedict basal rate.
 *
 * Every figure here is an approximation, not calorimetry and not medical advice. All timestamps are
 * unix seconds. Pure: no database access.
 *
 * Parity: `StrandAnalytics/Calories.swift` holds the same basal, Keytel and bout paths.
 */
object Calories {

    /** The sex-specific coefficients of the basal and active equations. */
    data class Coeffs(
        val restingAlpha: Double,
        val restingWeight: Double,
        /** Applied to height in metres. */
        val restingHeight: Double,
        val restingAge: Double,
        // Keytel 2005 base model: EE(kJ/min) = alpha + hr·HR + wt·W + age·A.
        val workoutHR: Double,
        val workoutWeight: Double,
        val workoutAge: Double,
        val workoutAlpha: Double,
        // Keytel 2005 fitness-adjusted model, which also reads VO2max:
        // EE(kJ/min) = fitAlpha + fitHR·HR + fitVO2·VO2max + fitWeight·W + fitAge·A.
        // (Keytel et al. 2005, J. Sports Sci. 23(3).)
        val fitHR: Double,
        val fitVO2: Double,
        val fitWeight: Double,
        val fitAge: Double,
        val fitAlpha: Double,
    )

    val male = Coeffs(
        restingAlpha = 88.362, restingWeight = 13.397, restingHeight = 479.9,
        restingAge = 5.677, workoutHR = 0.6309, workoutWeight = 0.1988,
        workoutAge = 0.2017, workoutAlpha = -55.0969,
        fitHR = 0.634, fitVO2 = 0.404, fitWeight = 0.394, fitAge = 0.271, fitAlpha = -95.7735,
    )
    val female = Coeffs(
        restingAlpha = 447.593, restingWeight = 9.247, restingHeight = 309.8,
        restingAge = 4.33, workoutHR = 0.4472, workoutWeight = -0.1263,
        workoutAge = 0.0740, workoutAlpha = -20.4022,
        fitHR = 0.450, fitVO2 = 0.380, fitWeight = 0.103, fitAge = 0.274, fitAlpha = -59.3954,
    )
    // The midpoint of the male and female coefficients.
    val nonbinary = Coeffs(
        restingAlpha = 267.9775, restingWeight = 11.322, restingHeight = 394.85,
        restingAge = 5.0035, workoutHR = 0.53905, workoutWeight = 0.03625,
        workoutAge = 0.13785, workoutAlpha = -37.74955,
        fitHR = 0.542, fitVO2 = 0.392, fitWeight = 0.2485, fitAge = 0.2725, fitAlpha = -77.58445,
    )
    const val activeHRRFraction: Double = 0.30
    const val workoutDivisor: Double = 251.04 // 60 s/min × 4.184 kJ/kcal

    /**
     * The longest gap between two day heart-rate samples still credited as continuous active effort
     * (s).
     *
     * Matches `StrainScorer.maxSampleGapMin`, the other analytic that integrates the same raw day
     * heart-rate union, so the two agree on what "continuous" means. It also covers the WHOOP 5/MG
     * live cadence of about one sample every 30 s.
     */
    const val dayMaxGapS: Double = 120.0

    fun resolveCoeffs(sex: String): Coeffs = when (sex.lowercase()) {
        "male" -> male
        "female" -> female
        "nonbinary" -> nonbinary
        else -> nonbinary
    }

    fun restingKcalPerS(c: Coeffs, weightKg: Double, heightCm: Double, age: Double): Double {
        val heightM = heightCm / 100.0
        val bmr = c.restingAlpha + c.restingWeight * weightKg + c.restingHeight * heightM - c.restingAge * age
        return maxOf(0.0, bmr) / 86_400.0
    }

    /**
     * Resting (basal) energy over a span of elapsed wall-clock seconds, in kcal.
     *
     * No column stores this term on its own. It is pure in the profile and the elapsed time, so
     * re-deriving it is exact rather than an approximation of a stored figure.
     *
     * [spanS] is elapsed seconds: a past day is the full 86 400, and an in-progress day is however
     * much of it has happened, so the figure climbs through the day rather than booking 24 h at
     * midnight.
     */
    fun basalKcalForSpan(profile: UserProfile, spanS: Double): Double {
        if (spanS <= 0.0) return 0.0
        val weightKg = if (profile.weightKg > 0) profile.weightKg else 70.0
        val heightCm = if (profile.heightCm > 0) profile.heightCm else 170.0
        val age = if (profile.age > 0) profile.age else 30.0
        return restingKcalPerS(resolveCoeffs(profile.sex), weightKg, heightCm, age) * spanS
    }

    /** HRmax assumed when a profile supplies none. The last-resort 220 constant, not Tanaka. */
    const val defaultHRmax: Double = 220.0

    /**
     * The bpm at or above which a heart-rate sample is scored as effort rather than as rest:
     * `resting + fraction × (HRmax − resting)`, the Karvonen heart-rate reserve.
     */
    fun activeHrGate(restingHR: Double, hrmax: Double, hrrFraction: Double): Double =
        restingHR + hrrFraction * (hrmax - restingHR)

    /**
     * The VO₂max the calorie models score with: the value the wearer entered, or the
     * heart-rate-ratio estimate below when they entered none.
     */
    fun vo2maxFor(profile: UserProfile, hrmax: Double, restingHR: Double?): Double? =
        profile.vo2maxOverride.takeIf { it > 0.0 } ?: vo2maxFor(hrmax, restingHR)

    /**
     * Uth–Sørensen VO2max estimate (ml·kg⁻¹·min⁻¹) ≈ 15.3 · HRmax / HRrest, or null when no usable
     * resting HR is known.
     *
     * A function of HRmax and resting HR alone, so a caller resolves it locally and no day's result
     * depends on another day. (Uth et al. 2004, Eur. J. Appl. Physiol. 91.)
     */
    fun vo2maxFor(hrmax: Double, restingHR: Double?): Double? {
        if (restingHR == null || restingHR <= 0.0 || hrmax <= 0.0) return null
        return 15.3 * hrmax / restingHR
    }

    /**
     * Active energy rate (kcal/s) at one heart rate.
     *
     * A [vo2max] selects the Keytel fitness-adjusted equation, which personalizes beyond age, weight
     * and sex; null selects the base equation. HR is capped at HRmax in both.
     */
    fun activeKcalPerS(c: Coeffs, hr: Double, hrmax: Double, weightKg: Double, age: Double, vo2max: Double? = null): Double {
        val eeKjMin = if (vo2max != null) {
            c.fitHR * minOf(hr, hrmax) + c.fitVO2 * vo2max + c.fitWeight * weightKg +
                c.fitAge * age + c.fitAlpha
        } else {
            c.workoutHR * minOf(hr, hrmax) + c.workoutWeight * weightKg +
                c.workoutAge * age + c.workoutAlpha
        }
        return maxOf(0.0, eeKjMin) / workoutDivisor
    }

    /**
     * Estimate (kcal, kJ) for one workout bout.
     *
     * Each sample is weighted by the elapsed time to the next one, capped at
     * [WorkoutDetector.mergeGapS], so a
     * stream below 1 Hz is counted over real seconds rather than one second per sample.
     *
     * @param hrSamples the bout's heart-rate samples, in any order.
     * @param profile weight, height, age and sex, for the coefficients.
     * @param hrmax maximum heart rate (bpm); null applies 220.
     * @param restingHR resting heart rate (bpm); null applies 60.
     */
    fun estimateBoutCalories(
        hrSamples: List<HrSample>,
        profile: UserProfile,
        hrmax: Double?,
        restingHR: Double?,
    ): Pair<Double, Double> {
        val weightKg = if (profile.weightKg > 0) profile.weightKg else 70.0
        val heightCm = if (profile.heightCm > 0) profile.heightCm else 170.0
        val age = if (profile.age > 0) profile.age else 30.0
        val coeffs = resolveCoeffs(profile.sex)

        val effHRmax = hrmax ?: 220.0
        val effResting = restingHR ?: 60.0
        val activeThreshold = effResting + activeHRRFraction * (effHRmax - effResting)

        val restingRate = restingKcalPerS(coeffs, weightKg, heightCm, age)
        // Constant across the bout, so it is resolved once.
        val vo2max = vo2maxFor(profile, effHRmax, restingHR)

        // `restingRate` and `activeKcalPerS` are per-second rates, so each sample is weighted by the
        // seconds it stands for rather than by a flat one second.
        val ordered = hrSamples.sortedBy { it.ts }
        var totalKcal = 0.0
        for (i in ordered.indices) {
            val bpm = ordered[i].bpm.toDouble()
            val dur: Double = if (i < ordered.size - 1) {
                val gap = (ordered[i + 1].ts - ordered[i].ts).toDouble()
                // A repeated timestamp measures no time; the next sample carries the real gap.
                if (gap > 0) min(gap, WorkoutDetector.mergeGapS) else 0.0
            } else {
                1.0 // last sample carries one representative second
            }
            totalKcal += if (bpm < activeThreshold) {
                restingRate * dur
            } else {
                activeKcalPerS(coeffs, bpm, effHRmax, weightKg, age, vo2max) * dur
            }
        }
        return totalKcal to (totalKcal * 4.184)
    }

    /**
     * One day's energy, scored by [HybridModel] over the day opening at [localMidnightUtc].
     *
     * [nowUtc] clamps an in-progress day at the present second rather than booking hours that have
     * not happened. Pass no [gravity] and no [workouts] to score from heart rate alone.
     */
    fun estimateDayEnergy(
        localMidnightUtc: Long,
        nowUtc: Long? = null,
        hr: List<HrSample> = emptyList(),
        gravity: List<GravitySample> = emptyList(),
        ringMET: List<Pair<Long, Double>> = emptyList(),
        workouts: List<LongRange> = emptyList(),
        profile: UserProfile,
        hrmax: Double?,
        restingHR: Double?,
    ): CalorieTimeline {
        val window = ActivityDay.atLocalMidnight(localMidnightUtc).window()
        val end = if (nowUtc != null) minOf(window.exclusiveEnd, nowUtc) else window.exclusiveEnd
        return HybridModel(profile, CalorieVitals(hrmax = hrmax, restingHR = restingHR))
            .timeline(window.first, end, CalorieInputs(hr, gravity, ringMET, workouts))
    }

    /**
     * One day's energy, scored by [KeytelModel] from the first of [hrSamples] onwards.
     *
     * [spanS] is how long the window runs; it defaults to the span the samples themselves cover. A
     * window longer than that still books resting metabolism over the whole of it, because resting
     * metabolism happened whether or not the strap was on the wrist. A shorter one discards the
     * samples past its end, so pass one only where the window is meant to bound them. A day with no
     * samples at all is zero whatever [spanS] says: nothing places it on the clock.
     */
    fun estimateDayEnergy(
        hrSamples: List<HrSample>,
        profile: UserProfile,
        hrmax: Double?,
        restingHR: Double?,
        spanS: Double? = null,
    ): CalorieTimeline {
        val model = KeytelModel(profile, CalorieVitals(hrmax = hrmax, restingHR = restingHR))
        if (hrSamples.isEmpty()) return model.timeline(0L, 0L, CalorieInputs())
        val ordered = hrSamples.sortedBy { it.ts }
        val startUtc = ordered[0].ts
        // The span is first..last inclusive, so a dense 1 Hz day yields one second per sample.
        val observedSpanS = (ordered[ordered.size - 1].ts - startUtc).toDouble() + 1.0
        return model.timeline(
            startUtc = startUtc,
            endUtc = startUtc + maxOf(0.0, spanS ?: observedSpanS).toLong(),
            inputs = CalorieInputs(hr = ordered),
        )
    }

}
