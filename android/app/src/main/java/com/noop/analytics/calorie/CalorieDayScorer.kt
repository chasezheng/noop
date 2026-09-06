package com.noop.analytics.calorie

import com.noop.analytics.UserProfile
import com.noop.data.GravitySample
import com.noop.data.HrSample

/** What one day's scoring pass banks. */
data class ScoredDay(
    /** The day's energy above resting metabolism, in kcal. */
    val activeKcal: Double,
    /** The last basal heart rate the day measured, or null when the model measures none. */
    val basalHrBpm: Double?,
)

/**
 * The day's stored active energy, scored by the model the wearer selected.
 *
 * Pure: no database access and no clock, so the instant to score as of is supplied.
 */
object CalorieDayScorer {

    /**
     * What [day] banks, or null when the day cannot be scored.
     *
     * A day with no heart rate at all gets no number rather than a full day of basal, which would
     * otherwise be backfilled onto days before the wearer owned the strap. That decision is made here
     * rather than by each model, so no model can forget it. A model that declines a day whose data it
     * cannot work from lands the same way.
     */
    fun score(
        day: ActivityDay,
        nowUtc: Long?,
        hr: List<HrSample>,
        gravity: List<GravitySample>,
        workouts: List<LongRange>,
        beats: List<Pair<Long, Int>>,
        profile: UserProfile,
        hrmax: Double?,
        restingHR: Double?,
    ): ScoredDay? {
        val window = day.window()
        val inWindow = hr.filter { it.ts in window }
        if (inWindow.isEmpty()) return null

        // [nowUtc] cuts an in-progress day off at the present second, so the figure climbs through
        // the day rather than booking hours that have not happened. Null takes the whole window.
        val model = CalorieModels.create(
            model = profile.calorieModel,
            profile = profile,
            vitals = CalorieVitals(hrmax = hrmax, restingHR = restingHR),
            nowUtc = nowUtc,
        )

        // The model's own day figure, so a stored number and the minutes behind it cannot disagree.
        val timeline = model.timeline(
            day = day,
            inputs = CalorieInputs(hr = inWindow, gravity = gravity, workouts = workouts, rr = beats),
        )
        if (timeline.extras[CalorieTimeline.DECLINED] == 1.0) return null
        return ScoredDay(
            activeKcal = timeline.dayActiveKcal,
            basalHrBpm = timeline.extras[CalorieTimeline.MEASURED_BASAL_HR_BPM],
        )
    }
}
