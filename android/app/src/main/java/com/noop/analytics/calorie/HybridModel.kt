package com.noop.analytics.calorie

import com.noop.analytics.UserProfile

/**
 * Daily energy from motion, with a [KeytelModel] covering detected workouts and any minute motion
 * could not.
 *
 * The two methods fail in disjoint regimes — accelerometry saturates at running speed and misses
 * cycling, rowing and lifting entirely, while heart rate over-reads at rest and is inflated by
 * caffeine, stress, heat and illness — so exactly one of them values a minute and nothing is
 * double-counted. The motion-to-MET transfer itself is [HybridMet].
 */
class HybridModel(
    private val profile: UserProfile,
    vitals: CalorieVitals,
    private val streams: CalorieDayStreams = NoCalorieStreams,
    /** The instant the day is scored as of; null scores the whole window. See [activityWindow]. */
    private val nowUtc: Long? = null,
) : CalorieModel {

    /** Which path estimated one minute of the activity day. Exactly one path owns a minute. */
    enum class Source {
        /** No motion, and heart rate was not allowed to stand in. The minute earned basal only. */
        NO_DATA,

        /** Motion below [HybridModelSetting.activeAccrualMET]: covered, and worth nothing above basal. */
        MOTION_IDLE,

        /** Motion at or above the accrual floor, outside any workout. */
        MOTION_ACTIVE,

        /** Inside a workout window, so heart rate estimated it instead of motion. */
        WORKOUT,

        /** Outside a workout and with no motion at all, so heart rate estimated it. */
        HR_FALLBACK,
    }

    /** The keys this model writes into a [CalorieTimeline]'s two per-minute maps. */
    object Label {

        /** [CalorieTimeline.labeledKcal]: what heart rate earned inside a workout window. */
        const val WORKOUT: String = "workout"

        /** [CalorieTimeline.labeledKcal]: what motion earned outside one. */
        const val MOTION_ACTIVE: String = "motionActive"

        /** [CalorieTimeline.labeledKcal]: what heart rate earned where motion could not cover. */
        const val HR_FALLBACK: String = "hrFallback"

        /** [CalorieTimeline.labeledSeries]: motion-derived MET, null where the minute had none. */
        const val MET: String = "met"

        /**
         * [CalorieTimeline.labeledSeries]: [Source.ordinal] as a Double.
         *
         * Carried rather than derived from [CalorieTimeline.labeledKcal], which cannot express it: a
         * minute can earn zero under every label and still have a source.
         */
        const val SOURCE: String = "source"
    }

    /**
     * The [CalorieTimeline.extras] keys this model writes.
     *
     * The names follow Oura's `daily_activity` payload, so a value can be compared against the Oura
     * app during validation.
     */
    object Extra {

        /** Σ MET over covered minutes, one minute each. */
        const val MET_MINUTES: String = "metMinutes"

        /** Mean MET across covered minutes (0 when nothing was covered). */
        const val MEAN_MET: String = "meanMET"

        /** Peak MET across covered minutes. */
        const val MAX_MET: String = "maxMET"

        /** Minutes at or above [HybridModelSetting.activeAccrualMET] — the minutes that earned energy. */
        const val ACTIVE_MINUTES: String = "activeMinutes"

        const val REST_MINUTES: String = "restMinutes"
        const val INACTIVE_MINUTES: String = "inactiveMinutes"
        const val LOW_MINUTES: String = "lowMinutes"
        const val MEDIUM_MINUTES: String = "mediumMinutes"
        const val HIGH_MINUTES: String = "highMinutes"

        /** Minutes in the window with NO usable motion or ring MET. */
        const val NON_WEAR_MINUTES: String = "nonWearMinutes"

        /** Minutes that carried usable data (window minutes − [NON_WEAR_MINUTES]). */
        const val COVERAGE_MINUTES: String = "coverageMinutes"
    }

    private val setting = profile.hybridModelSetting
    private val body = CalorieBody(profile)
    private val heartRate = KeytelModel(profile, vitals, nowUtc)

    /** The cost of each minute of `[startUtc, endUtc)`. Pure. */
    fun timeline(startUtc: Long, endUtc: Long, inputs: CalorieInputs): CalorieTimeline {
        if (endUtc <= startUtc) return emptySpan()

        val clipped = inputs.clippedTo(startUtc, endUtc)
        val epochs = HybridMet.epochs(startUtc, endUtc, clipped.gravity, clipped.ringMET, setting)
        val count = epochs.size

        val tsIndex = LongArray(count)
        val met = ArrayList<Double?>(count)
        val sources = ArrayList<Source>(count)
        val motionKcal = DoubleArray(count)
        for (i in 0 until count) {
            val epoch = epochs[i]
            tsIndex[i] = epoch.start
            met.add(epoch.met)
            val minuteEnd = minOf(epoch.start + 60L, endUtc)
            sources.add(
                if (minuteEnd <= epoch.start) {
                    Source.NO_DATA
                } else {
                    when {
                        HybridMet.overlapsWorkout(epoch.start, minuteEnd, clipped.workouts) -> Source.WORKOUT
                        // A WHOOP 4.0 reports no motion magnitude at all, so without the fallback the
                        // model degrades to basal plus workouts on that strap.
                        epoch.met == null ->
                            if (setting.hrFallbackWhenNoMET) Source.HR_FALLBACK else Source.NO_DATA
                        clearsAccrualFloor(epoch.met, setting) -> Source.MOTION_ACTIVE
                        else -> Source.MOTION_IDLE
                    }
                },
            )
            if (sources[i] == Source.MOTION_ACTIVE) {
                // The excess above 1 MET only; basal already covers the 1-MET floor.
                motionKcal[i] = maxOf(0.0, epoch.met!! - 1.0) * body.weightKg * (1.0 / 60.0)
            }
        }

        // Two walks of the same samples, so each class of minute is held to its own gate. A minute
        // belongs to exactly one class, so the two never credit the same minute.
        val workoutKcal = heartRate.surplusByMinute(
            startUtc, endUtc, count, clipped.hr, heartRate.workoutGate,
        ) { sources[it] == Source.WORKOUT }
        val fallbackKcal = heartRate.surplusByMinute(
            startUtc, endUtc, count, clipped.hr, heartRate.dayGate,
        ) { sources[it] == Source.HR_FALLBACK }

        val activeKcal = DoubleArray(count)
        for (i in 0 until count) activeKcal[i] = motionKcal[i] + (workoutKcal[i] + fallbackKcal[i])

        val totalKcal = DoubleArray(count)
        for (i in 0 until count) {
            totalKcal[i] = basalKcalForMinute(profile, tsIndex[i], endUtc) + activeKcal[i]
        }

        return CalorieTimeline(
            tsIndex = tsIndex,
            totalKcal = totalKcal,
            activeKcal = activeKcal,
            labeledKcal = mapOf(
                Label.WORKOUT to workoutKcal,
                Label.MOTION_ACTIVE to motionKcal,
                Label.HR_FALLBACK to fallbackKcal,
            ),
            labeledSeries = mapOf(
                Label.MET to met,
                Label.SOURCE to sources.map { it.ordinal.toDouble() },
            ),
            extras = bandStats(met),
        )
    }

    /** DB-backed: resolve [day]'s window and inputs through the port, then score them. */
    suspend fun timeline(day: ActivityDay): CalorieTimeline {
        val window = activityWindow(day, nowUtc)
        return timeline(window.first, window.exclusiveEnd, streams.load(day, window))
    }

    override fun timeline(day: ActivityDay, inputs: CalorieInputs): CalorieTimeline {
        val window = activityWindow(day, nowUtc)
        return timeline(window.first, window.exclusiveEnd, inputs)
    }

    /** A window with no minutes in it: no series, and so no energy of any kind. */
    private fun emptySpan(): CalorieTimeline = CalorieTimeline(
        tsIndex = LongArray(0),
        totalKcal = DoubleArray(0),
        activeKcal = DoubleArray(0),
        labeledKcal = mapOf(
            Label.WORKOUT to DoubleArray(0),
            Label.MOTION_ACTIVE to DoubleArray(0),
            Label.HR_FALLBACK to DoubleArray(0),
        ),
        labeledSeries = mapOf(Label.MET to emptyList(), Label.SOURCE to emptyList()),
        extras = bandStats(emptyList()),
    )

    /**
     * The MET and minute-band statistics for [met], one minute per entry.
     *
     * Display only: no band edge reaches an energy term.
     */
    private fun bandStats(met: List<Double?>): Map<String, Double> {
        var metMinutes = 0.0
        var metSum = 0.0
        var peakMET = 0.0
        var activeMinutes = 0.0
        var restMinutes = 0.0
        var inactiveMinutes = 0.0
        var lowMinutes = 0.0
        var mediumMinutes = 0.0
        var highMinutes = 0.0
        var nonWearMinutes = 0.0
        var coveredMinutes = 0
        for (value in met) {
            if (value == null) {
                nonWearMinutes += 1.0
                continue
            }
            coveredMinutes += 1
            metSum += value
            metMinutes += value
            if (value > peakMET) peakMET = value
            when (HybridMet.bandForMET(value)) {
                1 -> restMinutes += 1.0
                2 -> inactiveMinutes += 1.0
                3 -> lowMinutes += 1.0
                4 -> mediumMinutes += 1.0
                else -> highMinutes += 1.0
            }
            if (value >= setting.activeAccrualMET) activeMinutes += 1.0
        }
        return mapOf(
            Extra.MET_MINUTES to metMinutes,
            Extra.MEAN_MET to if (coveredMinutes > 0) metSum / coveredMinutes else 0.0,
            Extra.MAX_MET to peakMET,
            Extra.ACTIVE_MINUTES to activeMinutes,
            Extra.REST_MINUTES to restMinutes,
            Extra.INACTIVE_MINUTES to inactiveMinutes,
            Extra.LOW_MINUTES to lowMinutes,
            Extra.MEDIUM_MINUTES to mediumMinutes,
            Extra.HIGH_MINUTES to highMinutes,
            Extra.NON_WEAR_MINUTES to nonWearMinutes,
            Extra.COVERAGE_MINUTES to maxOf(0.0, met.size - nonWearMinutes),
        )
    }
}

/**
 * The wearer settings behind the motion path of the day's energy.
 *
 * Ranges are enforced where the value is persisted ([HybridModelSettingRanges]) rather than here, so
 * a hand-built setting in a test says exactly what it means.
 *
 * Parity debt: this type has no Swift twin, so a wearer who edits one of these values gets a
 * different day total on the two platforms.
 */
data class HybridModelSetting(

    /**
     * The MET at or above which a minute earns energy above resting. Oura documents 1.5, and 1.0–1.5
     * MET as sedentary.
     *
     * MET is a multiple of resting burn: 1 MET is sitting still, about 4 a brisk walk, about 8 a run.
     *
     * Deliberately below the display banding's inactive-to-low edge of 2.0 MET: the lower value
     * decides what earns energy, the higher what is shown as activity. Keeping them apart is what
     * stops low-grade motion noise inflating a daily total.
     */
    val activeAccrualMET: Double = 1.5,

    /**
     * The MET added per 1 g of gravity-removed motion.
     *
     * This number is ours and it is uncalibrated. Oura's transfer function is proprietary, so this is
     * anchored rather than fitted, on two physiological points: a still strap reads about 0 g and is
     * 1.0 MET, and ordinary brisk walking sits near 0.1 g and costs about 4 MET. It has not been
     * validated against indirect calorimetry, and it is the least-evidenced value in either model.
     */
    val dynAccelMETGainPerG: Double = 30.0,

    /**
     * Whether a minute with no usable motion falls back to the heart-rate model.
     *
     * A WHOOP 4.0 reports no dynamic acceleration at all, so turning this off reduces the model to
     * basal plus detected workouts and the day total drops sharply.
     */
    val hrFallbackWhenNoMET: Boolean = true,
)

/**
 * The valid range of every value in [HybridModelSetting].
 *
 * Each range is wide enough to be useful and narrow enough that a mis-tap cannot produce a number
 * with no physical meaning, such as a motion floor below the 1-MET resting floor.
 */
object HybridModelSettingRanges {
    const val ACCRUAL_MET_MIN = 1.0
    const val ACCRUAL_MET_MAX = 4.0
    const val MET_GAIN_MIN = 5.0
    const val MET_GAIN_MAX = 100.0
}
