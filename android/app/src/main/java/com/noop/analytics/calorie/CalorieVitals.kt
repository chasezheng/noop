package com.noop.analytics.calorie

import com.noop.analytics.StrainScorer
import com.noop.analytics.UserProfile

/**
 * The two heart-rate values every calorie estimate is anchored on.
 *
 * Grouped because they are never independent: both feed the effort gate and the VO₂max (maximal
 * oxygen uptake) estimate, so a caller that resolves one without the other scores a different day.
 */
data class CalorieVitals(val hrmax: Double?, val restingHR: Double?) {

    companion object {

        /**
         * The maximum heart rate the day's energy is scored against: [maxHROverride] when the wearer
         * entered one, otherwise the unrounded Tanaka estimate.
         *
         * Null, rather than an estimate from an age of zero, when the profile has no age. An
         * estimator then applies its own default instead of a value derived from a missing age.
         */
        fun hrmaxFor(profile: UserProfile, maxHROverride: Double?): Double? =
            maxHROverride ?: if (profile.age > 0) StrainScorer.tanakaHRmax(profile.age) else null
    }
}
