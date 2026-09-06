package com.noop.analytics.calorie

import com.noop.analytics.UserProfile

/**
 * Where a model gets one day's inputs from.
 *
 * The analytics package holds no database access, so a model that must resolve a day it was only
 * named asks through this port, and the implementation is supplied from the app layer.
 *
 * Every method suspends because a real implementation reads storage. The scoring arithmetic does not
 * go through here, so it gains no suspension point.
 */
interface CalorieDayStreams {

    /** The day's local midnight, in epoch seconds. */
    suspend fun localMidnightUtc(day: ActivityDay): Long

    /** The single device whose rows this day is scored from. */
    suspend fun owner(day: ActivityDay): String

    /** The streams inside [window], for [day]. */
    suspend fun load(day: ActivityDay, window: LongRange): CalorieInputs

    /** The heart-rate anchors this day is scored against. */
    suspend fun vitals(day: ActivityDay, profile: UserProfile, maxHROverride: Double? = null): CalorieVitals
}

/** A port for a caller that already holds every stream, which answers from what it was handed. */
class PreloadedCalorieStreams(
    private val localMidnightUtc: Long,
    private val owner: String = "",
    private val inputs: CalorieInputs = CalorieInputs(),
    private val vitals: CalorieVitals = CalorieVitals(hrmax = null, restingHR = null),
) : CalorieDayStreams {
    override suspend fun localMidnightUtc(day: ActivityDay): Long = localMidnightUtc
    override suspend fun owner(day: ActivityDay): String = owner
    override suspend fun load(day: ActivityDay, window: LongRange): CalorieInputs = inputs
    override suspend fun vitals(day: ActivityDay, profile: UserProfile, maxHROverride: Double?): CalorieVitals = vitals
}

/**
 * The port for a model that will only ever be scored over an explicit span: every method fails.
 *
 * Answering a day-keyed call with an empty day would return a plausible figure for what is a wiring
 * mistake, and no reader could tell that from a real one.
 */
object NoCalorieStreams : CalorieDayStreams {
    override suspend fun localMidnightUtc(day: ActivityDay): Long = noPort()
    override suspend fun owner(day: ActivityDay): String = noPort()
    override suspend fun load(day: ActivityDay, window: LongRange): CalorieInputs = noPort()
    override suspend fun vitals(day: ActivityDay, profile: UserProfile, maxHROverride: Double?): CalorieVitals = noPort()

    private fun noPort(): Nothing =
        error("this model was built without a port; score it with timeline(startUtc, endUtc, inputs)")
}
