package com.noop.analytics.calorie

/**
 * Converts energy to the level walking speed of equal metabolic cost.
 *
 * Immutable. The constructor arguments describe one person.
 *
 * Cost of transport — oxygen consumed per kilogram of body mass per kilometre travelled — is lowest
 * near 5.1 km/h and higher at every other speed. It was measured at eight speeds from 2.4 to 7.3 km/h
 * on 11 well-trained male runners (Abe 2015, PLoS One 10(9):e0138154). A trained runner consumes less
 * energy than an average person at the same speed, so the speed returned is higher than that person's
 * true speed. Outside 2.4 to 7.3 km/h the same curve is evaluated, which is an extrapolation.
 */
class WalkingPaceEstimator(
    private val weightKg: Double,
    private val restingKcalPerS: Double,
) {

    init {
        require(weightKg > 0.0) { "weightKg must be positive, was $weightKg" }
        require(restingKcalPerS >= 0.0) { "restingKcalPerS must not be negative, was $restingKcalPerS" }
    }

    /**
     * The speed in km/h of equal cost to [activeKcal] spent above resting over [spanS] seconds, or null
     * if either is not positive.
     *
     * Resting energy over the span is added to [activeKcal], because the curve gives gross cost, which
     * includes resting metabolism. Any positive speed can be returned, including a speed too low to
     * describe travel.
     */
    fun kmPerHourFor(activeKcal: Double, spanS: Double): Double? =
        speedFor(activeKcal, spanS, weightKg, restingKcalPerS) { kmPerHour ->
            COEFF_SPEED_SQUARED * kmPerHour * kmPerHour + COEFF_SPEED * kmPerHour + COEFF_CONSTANT
        }

    /** The level gross walking curve of Abe 2015 Fig 1, in mL·kg⁻¹·km⁻¹ against km/h. */
    private companion object {
        const val COEFF_SPEED_SQUARED: Double = 9.814
        const val COEFF_SPEED: Double = -100.884
        const val COEFF_CONSTANT: Double = 420.959
    }
}

/**
 * Converts energy to the level running speed of equal metabolic cost.
 *
 * Immutable. The constructor arguments describe one person.
 *
 * Cost of transport changes little with speed. It was measured at four speeds from 8.7 to 10.8 km/h on
 * the same 11 runners as [WalkingPaceEstimator]. Below 8.7 km/h the same line is evaluated, which is an
 * extrapolation: it gives 228.7 mL·kg⁻¹·km⁻¹ at 2.9 km/h against 212.5 mL·kg⁻¹·km⁻¹ at the measured
 * 8.7 km/h, a difference of 7.6%. The cost is therefore close, but 2.9 km/h is not a running speed.
 */
class RunningPaceEstimator(
    private val weightKg: Double,
    private val restingKcalPerS: Double,
) {

    init {
        require(weightKg > 0.0) { "weightKg must be positive, was $weightKg" }
        require(restingKcalPerS >= 0.0) { "restingKcalPerS must not be negative, was $restingKcalPerS" }
    }

    /** As [WalkingPaceEstimator.kmPerHourFor], for running. */
    fun kmPerHourFor(activeKcal: Double, spanS: Double): Double? =
        speedFor(activeKcal, spanS, weightKg, restingKcalPerS) { kmPerHour ->
            COEFF_SPEED * kmPerHour + COEFF_CONSTANT
        }

    /** The level gross running curve of Abe 2015 Fig 1, in mL·kg⁻¹·km⁻¹ against km/h. */
    private companion object {
        const val COEFF_SPEED: Double = -2.793
        const val COEFF_CONSTANT: Double = 236.817
    }
}

/** Constants of the two cost curves and of the speed search. */
internal object PaceConstants {

    /**
     * kcal released per litre of oxygen consumed.
     *
     * 5.0 corresponds to a respiratory exchange ratio near 0.96, that is almost pure carbohydrate. A
     * mixed-fuel value is near 4.85, which lowers a running speed by 3.1% and a walking speed by 0.9%.
     */
    const val KCAL_PER_LITRE_O2: Double = 5.0

    /**
     * The speed in km/h at which walking and running have the same cost of transport.
     *
     * Below it running costs more per kilometre; above it walking does. This is the mean measured for
     * the same subjects as the two curves, and it carries an uncertainty of ± 0.32 km/h (Abe 2015).
     * Solving the two curves for equal cost gives 7.4899, which agrees to three significant figures.
     */
    const val CROSSOVER_KM_PER_HOUR: Double = 7.49

    /**
     * The lowest speed in km/h that the walking curve was fitted to.
     *
     * Below it the curve has the wrong shape: gross cost of transport must increase without limit as
     * speed approaches zero, but the quadratic converges to a finite cost.
     */
    const val SLOWEST_KM_PER_HOUR: Double = 2.4

    /**
     * The upper bound in km/h of the speed search.
     *
     * Below 42.4 km/h, where the negative slope of the running curve would stop oxygen per hour from
     * increasing with speed. The search needs one speed per oxygen rate.
     */
    const val FASTEST_KM_PER_HOUR: Double = 40.0

    /**
     * The number of times the search interval is halved.
     *
     * Forty halvings narrow [FASTEST_KM_PER_HOUR] to 4e-11 km/h, which is finer than the reported
     * minutes and seconds, and finer than the precision of the coefficients.
     */
    const val SEARCH_STEPS: Int = 40
}

/**
 * The speed in km/h at which [oxygenPerKm] accounts for the total metabolic energy of the span.
 *
 * Bisection, not an algebraic solution: oxygen per hour is [oxygenPerKm] times speed, which is a cubic
 * for the walking curve. Both curves increase oxygen per hour with speed over the searched interval, so
 * the root is unique and the interval contains it. The running curve alone would solve in closed form,
 * at the price of a second code path and a discriminant to guard, for precision that reported minutes
 * and seconds cannot show.
 */
private inline fun speedFor(
    activeKcal: Double,
    spanS: Double,
    weightKg: Double,
    restingKcalPerS: Double,
    oxygenPerKm: (Double) -> Double,
): Double? {
    if (activeKcal <= 0.0 || spanS <= 0.0) return null
    val totalKcal = activeKcal + restingKcalPerS * spanS
    val oxygenPerKgPerHour = totalKcal / PaceConstants.KCAL_PER_LITRE_O2 * 1_000.0 /
        weightKg / (spanS / 3_600.0)
    var slow = 0.0
    var fast = PaceConstants.FASTEST_KM_PER_HOUR
    repeat(PaceConstants.SEARCH_STEPS) {
        val mid = 0.5 * (slow + fast)
        if (oxygenPerKm(mid) * mid < oxygenPerKgPerHour) slow = mid else fast = mid
    }
    return 0.5 * (slow + fast)
}
