package com.noop.ui

import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.CalorieDayTrace
import com.noop.analytics.calorie.CalorieDayTraceBuilder
import com.noop.analytics.calorie.CalorieModels
import com.noop.analytics.calorie.CalorieTimeline
import com.noop.analytics.calorie.Calories
import com.noop.analytics.calorie.EnergyModel
import com.noop.analytics.calorie.exclusiveEnd
import com.noop.data.RepositoryCalorieStreams
import com.noop.data.WhoopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

// MARK: - One day's energy, for a screen that recomputes one
//
// A screen that recomputes a day rather than reading the stored row must resolve every input exactly
// as the analytics pass does, or it explains a tile with a number the tile was never given. Those
// resolutions live in the port, and the SCORED result is read from here rather than the inputs, so
// one input set cannot be scored twice into two different figures.

/** One day scored by one model, with the provenance needed to describe the inputs used. */
internal data class CalorieDay(
    val day: String,
    /** The day's window and inputs, in the shapes a plot needs. */
    val trace: CalorieDayTrace,
    /** The model [timeline] came from. */
    val model: EnergyModel,
    /** What [model] made of the day. */
    val timeline: CalorieTimeline,
    /**
     * Resting energy over the whole 24 hours: what [model] reported over the scored window, plus the
     * default rate over the hours the window does not reach.
     *
     * A day in progress has a window that stops at the present second, so the model's own resting term
     * covers only the hours that have happened.
     */
    val restingKcal24h: Double,
    /**
     * Every workout window that overlaps the day, whether or not [model] was handed it.
     *
     * The strap's own sessions plus the phone's: Health Connect records exercise the strap never
     * detected, and a window the model did not read still covers minutes it scored.
     */
    val workouts: List<LongRange>,
    /**
     * When the wearer woke, or null when no sleep session ends inside the day.
     *
     * The end of the latest recorded session inside the window, so a nap does not displace the night
     * it followed.
     */
    val wakeTs: Long?,
    /**
     * The device this day resolved to.
     *
     * Reported so that a read against the wrong device is visible rather than silent: every stream id
     * follows from this one.
     */
    val owner: String,
    /**
     * What the phone reports for this day, or null when it covers none of it.
     *
     * A card may show the phone's figure in preference to NOOP's, so a screen without this can report
     * a correct number that still disagrees with the card it explains.
     */
    val importedKcal: Double?,
    /**
     * What NOOP's own analytics pass stored for this day, or null when it scored none.
     *
     * Read rather than folded from [timeline], whose figure is never null and so cannot express that
     * no pass ever scored the day.
     */
    val onDeviceKcal: Double?,
)

/**
 * Read and score one day with [model].
 *
 * Every stream, anchor and window comes from [RepositoryCalorieStreams], which answers each of them
 * the way the analytics pass does.
 */
internal suspend fun loadCalorieDay(
    vm: AppViewModel,
    profile: ProfileStore,
    day: LocalDate,
    model: EnergyModel = profile.calorieModel,
): CalorieDay = withContext(Dispatchers.Default) {
    val userProfile = profile.toUserProfile()
    val dayKey = day.toString()
    val nowUtc = System.currentTimeMillis() / 1_000L

    val streams = RepositoryCalorieStreams(
        repo = vm.repo,
        registry = vm.deviceRegistry,
        fallbackDeviceId = vm.activeStrapId,
        nowUtc = nowUtc,
    )
    val activityDay = RepositoryCalorieStreams.activityDay(day, nowUtc)
    val midnight = streams.localMidnightUtc(activityDay)
    // The same override the pass is given; the port turns it into the effective maximum heart rate.
    val vitals = streams.vitals(activityDay, userProfile, profile.hrMaxOverride.takeIf { it > 0 }?.toDouble())
    val whole = ActivityDay.atLocalMidnight(midnight).window()
    val window = whole.first until minOf(whole.exclusiveEnd, nowUtc)
    val inputs = streams.load(activityDay, window)
    val owner = streams.owner(activityDay)

    val timeline = CalorieModels.timeline(
        model, userProfile, vitals, window.first, window.exclusiveEnd, inputs,
    )
    val unscoredS = (86_400L - (window.exclusiveEnd - window.first)).coerceAtLeast(0L)

    CalorieDay(
        day = dayKey,
        trace = CalorieDayTraceBuilder.build(
            localMidnightUtc = midnight,
            nowUtc = nowUtc,
            gravity = inputs.gravity,
            hr = inputs.hr,
            workouts = inputs.workouts,
        ),
        model = model,
        timeline = timeline,
        workouts = (
            inputs.workouts +
                // Read separately from the strap's, and NOT handed to the model: a window that did
                // not reach the scoring pass must not silently start changing what it stores.
                vm.repo.workouts(WhoopRepository.HEALTH_CONNECT_SOURCE, midnight, window.exclusiveEnd)
                    .filter { it.endTs > window.first && it.startTs < window.exclusiveEnd }
                    .map { it.startTs until it.endTs }
            )
            .distinctBy { it.first to it.last }
            .sortedBy { it.first },
        restingKcal24h = timeline.dayBasalKcal +
            Calories.basalKcalForSpan(userProfile, unscoredS.toDouble()),
        // From 30 h before midnight, so a night that began the previous evening is still found by the
        // end it is keyed on here.
        wakeTs = vm.repo.sleepSessionsMerged(owner, midnight - 30 * 3_600L, window.exclusiveEnd)
            .filter { it.endTs in window }
            .maxOfOrNull { it.endTs },
        owner = owner,
        importedKcal = importedActiveKcal(vm, dayKey),
        onDeviceKcal = storedActiveKcal(vm, dayKey),
    )
}
