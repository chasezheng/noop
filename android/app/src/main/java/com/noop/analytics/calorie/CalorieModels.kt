package com.noop.analytics.calorie

import com.noop.analytics.UserProfile

/**
 * Which model produces the day's headline calorie figure.
 *
 * [id], not the ordinal, is the token a backup carries, so reordering the cases cannot re-point a
 * backup already written.
 */
enum class EnergyModel(val id: String) {
    /** Keytel 2005 over heart rate alone. The default. */
    HEART_RATE("hr"),

    /**
     * Motion for the background, heart rate for workouts.
     *
     * Not the default: the published accuracy of motion-derived energy is poor, so the wearer opts in
     * with that caveat in front of them.
     */
    HYBRID("hybrid"),

    /**
     * Heart rate against a basal rate the day itself measures, with a fuel-aware oxygen equivalent.
     *
     * Not the default: it has been checked against four days of one wearer, which is not validation.
     * It also declines any day that is not recorded at one sample a second or carries no motion.
     */
    DYNAMIC_HRR("dynamic_hrr"),
    ;

    companion object {
        /** The model [id] names, or null for a token this build does not know. */
        fun forId(id: String?): EnergyModel? = values().firstOrNull { it.id == id }
    }
}

/**
 * Every energy model this build has, and how to build one.
 *
 * Adding a model takes a class, an [EnergyModel] case and a line in [order]. Both `when` expressions
 * below are exhaustive, so a model that is never built fails to compile rather than silently scoring
 * as heart rate.
 */
object CalorieModels {

    /**
     * The order models are presented in.
     *
     * Fixed here rather than taken from the enum because it is user-visible, so it can change without
     * disturbing the backup tokens the enum owns.
     */
    val order: List<EnergyModel> =
        listOf(EnergyModel.HYBRID, EnergyModel.HEART_RATE, EnergyModel.DYNAMIC_HRR)

    /** The model [model] names, ready to score a day. */
    fun create(
        model: EnergyModel,
        profile: UserProfile,
        vitals: CalorieVitals,
        nowUtc: Long? = null,
    ): CalorieModel = when (model) {
        EnergyModel.HEART_RATE -> KeytelModel(profile, vitals, nowUtc)
        EnergyModel.HYBRID -> HybridModel(profile, vitals, nowUtc = nowUtc)
        EnergyModel.DYNAMIC_HRR -> DynamicHrrModel(profile, vitals, nowUtc)
    }

    /**
     * The cost of each minute of `[startUtc, endUtc)` under [model].
     *
     * Keyed by the enum rather than by a built model, because a caller comparing models wants the
     * answer rather than the object.
     */
    fun timeline(
        model: EnergyModel,
        profile: UserProfile,
        vitals: CalorieVitals,
        startUtc: Long,
        endUtc: Long,
        inputs: CalorieInputs,
    ): CalorieTimeline = when (model) {
        EnergyModel.HEART_RATE -> KeytelModel(profile, vitals).timeline(startUtc, endUtc, inputs)
        EnergyModel.HYBRID -> HybridModel(profile, vitals).timeline(startUtc, endUtc, inputs)
        EnergyModel.DYNAMIC_HRR -> DynamicHrrModel(profile, vitals).timeline(startUtc, endUtc, inputs)
    }
}
