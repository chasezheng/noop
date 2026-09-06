package com.noop.analytics.calorie

import com.noop.analytics.HrZones
import com.noop.analytics.UserProfile

/**
 * Daily energy from heart rate against a basal heart rate the day itself measures.
 *
 * The resting anchor is read off quiet stretches of the wearer's own day — still, heart-rate-steady
 * and beat-covered — so a heart rate at or below it yields exactly zero energy above resting by
 * construction, and no effort gate is needed. The caloric equivalent of oxygen follows the fuel mix
 * rather than being one constant, so resting oxygen uptake moves across the day even though resting
 * energy does not.
 *
 * The evidence behind it is four days of one wearer, so it is neither a default nor an input to any
 * other score.
 *
 * Requires one heart-rate sample a second and a strap that reports motion; a day that carries
 * neither is declined rather than estimated (see [CalorieTimeline.DECLINED]).
 */
class DynamicHrrModel(
    private val profile: UserProfile,
    vitals: CalorieVitals,
    /** The instant the day is scored as of; null scores the whole window. See [activityWindow]. */
    private val nowUtc: Long? = null,
) : CalorieModel {

    /** The keys this model writes into a [CalorieTimeline]'s two per-minute maps. */
    object Label {

        /** [CalorieTimeline.labeledKcal]: energy above resting. This model has one path. */
        const val ACTIVE: String = "active"

        /**
         * [CalorieTimeline.labeledSeries]: what the spike and peak filters took off the minute (bpm).
         *
         * The cleaned reading less the strap's own, averaged over the seconds that carried one, so
         * zero means the minute was scored exactly as recorded and a negative value is how far the
         * filters pulled it down.
         */
        const val DELTA_HR: String = "deltaHrBpm"

        /** [CalorieTimeline.labeledSeries]: the measured basal heart rate (bpm). */
        const val BASAL_HR: String = "basalHrBpm"

        /** [CalorieTimeline.labeledSeries]: the percentage of the minute's energy that came from fat. */
        const val FAT_PERCENTAGE: String = "fatPercentage"
    }

    /** The [CalorieTimeline.extras] keys this model writes. */
    object Extra {

        /**
         * How many quiet stretches measured the basal heart rate.
         *
         * Zero on a day the model declined — most often because no stretch was quiet for long
         * enough, but also where the day was too sparse, reported no motion, or yielded no maximal
         * oxygen uptake.
         */
        const val QUIET_WINDOW_COUNT: String = "quietWindowCount"

        /** Grams of fat the day's energy came from. */
        const val FAT_GRAMS: String = "fatGrams"

        /** Grams of carbohydrate the day's energy came from. */
        const val CHO_GRAMS: String = "choGrams"

        /** The share of the day's wear-session seconds that carried a heart rate. */
        const val HR_COVERAGE_FRAC: String = "hrCoverageFrac"
    }

    private val setting = profile.dynamicHrrModelSetting
    private val body = CalorieBody(profile)
    private val hrmax: Double = vitals.hrmax ?: Calories.defaultHRmax

    /** Maximal oxygen uptake in litres a minute; null when nothing in the profile yields one. */
    private val vo2maxLPerMin: Double? =
        Calories.vo2maxFor(profile, hrmax, vitals.restingHR)?.times(body.weightKg / 1_000.0)

    /**
     * Where the fold starts before a quiet stretch has measured anything.
     *
     * The sleep-derived resting heart rate sits a little below a basal rate read over a still waking
     * window, which is what the offset spans. NaN where no day carries a resting heart rate: the
     * lead-in is then left unscored rather than anchored on a fabricated rate.
     */
    private val seedBasalHrBpm: Double =
        vitals.restingHR?.plus(setting.basalHrSeedOffsetBpm) ?: Double.NaN

    private val basalFat = BasalFatCurve(setting)
    private val activeFat = ActiveFatCurve(
        HrZones.zones(maxHR = hrmax, customLowerBounds = profile.hrZoneThresholds),
        setting,
    )

    /**
     * How the beats above rest are weighted, or null where they are all worth the same.
     *
     * The wearer's resting rate can be absent on a day that is still estimated, since a maximal
     * oxygen uptake entered by hand needs no resting rate. There is then nothing to pin the bands to.
     */
    private val reserveRamp: HrReserveRamp? =
        HrReserveRamp.of(vitals.restingHR, setting.reserveRampBandBpm)

    /** Resting energy per second: the wearer's measured figure, or the estimate every model uses. */
    private val basalKcalPerS: Double =
        if (setting.measuredBasalKcalDay > 0.0) setting.measuredBasalKcalDay / 86_400.0 else body.restingRate

    /**
     * The cost of each minute of `[startUtc, endUtc)`. Pure.
     *
     * The window must open at the wearer's local midnight: the resting fuel mix follows the local
     * hour, and it is read as the offset from [startUtc].
     */
    fun timeline(startUtc: Long, endUtc: Long, inputs: CalorieInputs): CalorieTimeline {
        // A day model, held to a day-sized window: the per-second arrays below are indexed by an
        // Int, and a caller-chosen span is not otherwise bounded.
        if (endUtc <= startUtc || endUtc - startUtc > MAX_SPAN_S) {
            return declined(startUtc, endUtc, hrCoverageFrac = 0.0)
        }
        val seconds = (endUtc - startUtc).toInt()
        val clipped = inputs.clippedTo(startUtc, endUtc)

        val hr = DoubleArray(seconds) { Double.NaN }
        for (sample in clipped.hr) {
            val i = (sample.ts - startUtc).toInt()
            // A repeated second is one measurement; the first reading of it stands.
            if (hr[i].isNaN()) hr[i] = sample.bpm.toDouble()
        }
        // Filling a gap does not move a session edge, since only a silence far longer than one cuts
        // a session, so these stand for the filled series too.
        val sessions = wearSessions(hr)
        // What the strap recorded, read before the gaps are filled.
        val coverage = coverageFraction(hr, sessions)
        // How much of a session was recorded is no longer a gate, but a window carrying no session
        // at all was never worn, and there is nothing in it to score above resting.
        if (sessions.isEmpty()) return declined(startUtc, endUtc, coverage)
        // A silence this short is a gap between two readings of one stretch rather than an absence
        // of one. Filling it lets a strap reporting every half minute be scored second by second.
        val filled = DynamicHrrSignals.interpolatedShortGaps(hr, HR_GAP_FILL_MAX_S)

        val motion = DoubleArray(seconds) { Double.NaN }
        for (sample in clipped.gravity) {
            val magnitude = sample.dynAccel ?: continue
            if (sample.ts < startUtc || sample.ts >= endUtc) continue
            val i = (sample.ts - startUtc).toInt()
            if (!motion[i].isNaN()) continue
            motion[i] = magnitude
        }
        // Motion is not a gate. Without it no second is still and no stretch measures anything, and
        // the seed then anchors the day exactly as it does on a day nothing qualified.
        if (vo2maxLPerMin == null) return declined(startUtc, endUtc, coverage)

        val beat = BooleanArray(seconds)
        for ((ts, beats) in clipped.rr) {
            if (beats <= 0 || ts < startUtc || ts >= endUtc) continue
            beat[(ts - startUtc).toInt()] = true
        }

        val filtered = DynamicHrrSignals.hampel(filled, sessions, setting.hampelRadiusS, setting.hampelSigmas)
        val settled = if (!setting.suppressPeaks) {
            filtered
        } else {
            DynamicHrrSignals.clipBlockPeaks(
                filtered, sessions, startUtc, setting.peakBlockS, setting.peakPercentile,
            )
        }

        val smoothedMotion = DynamicHrrSignals.trailingMean(
            motion, sessions, setting.motionSmoothS, MOTION_SMOOTH_MIN_SAMPLES,
        )
        val still = BooleanArray(seconds) {
            !smoothedMotion[it].isNaN() && smoothedMotion[it] <= setting.motionStillG
        }
        // Read before the peaks are clipped, not after: clipping only lowers the high readings, so a
        // stretch broken by a spike would be handed the spike already pulled down, and would qualify
        // as quiet rather than being cut back at it.
        val quietWindowHr = DynamicHrrSignals.quietWindowMinHr(
            filtered, sessions, still, beat,
            setting.basalMinWindowS, setting.basalHrRangeBpm,
            setting.basalStillFrac, setting.basalBeatCoverageFrac, BASAL_REPAIR_GRACE_S,
        )
        val ratchetHr = DynamicHrrSignals.trailingMedian(
            settled, sessions, setting.restSmoothS, setting.restSmoothMinSamples,
        )
        val basalHr = DynamicHrrSignals.smoothBasalRaises(
            DynamicHrrSignals.trackBasalHr(quietWindowHr, ratchetHr, seedBasalHrBpm),
            quietWindowHr,
            filtered,
        )
        var quietWindows = 0
        for (v in quietWindowHr) if (!v.isNaN()) quietWindows += 1
        // A day no stretch measured is still scorable: the seed anchors the fold from its first
        // second and the ratchet lowers it wherever a reading contradicts it. Only a day with
        // neither a measurement nor a seed has nothing to anchor on.
        if (quietWindows == 0 && seedBasalHrBpm.isNaN()) return declined(startUtc, endUtc, coverage)

        return fold(startUtc, endUtc, filled, settled, basalHr, coverage, quietWindows)
    }

    override fun timeline(day: ActivityDay, inputs: CalorieInputs): CalorieTimeline {
        val window = activityWindow(day, nowUtc)
        return timeline(window.first, window.exclusiveEnd, inputs)
    }

    /**
     * The per-second energy of `[startUtc, endUtc)`, folded to the minutes the interface returns.
     *
     * Resting energy accrues every second of the window whether or not the strap was worn. Energy
     * above resting is the reserve between the measured basal heart rate and the maximum, applied to
     * the oxygen reserve above rest and converted at the active fuel mix's caloric equivalent.
     */
    private fun fold(
        startUtc: Long,
        endUtc: Long,
        recorded: DoubleArray,
        hr: DoubleArray,
        basalHr: DoubleArray,
        hrCoverageFrac: Double,
        quietWindows: Int,
    ): CalorieTimeline {
        val minutes = minuteCount(startUtc, endUtc)
        val activeKcal = DoubleArray(minutes)
        val totalKcal = DoubleArray(minutes)
        val fatKcal = DoubleArray(minutes)
        val deltaHrSum = DoubleArray(minutes)
        val deltaHrCount = IntArray(minutes)
        val minuteBasalHr = DoubleArray(minutes) { Double.NaN }
        val vo2max = vo2maxLPerMin ?: 0.0
        var finalBasalHr = Double.NaN

        for (i in hr.indices) {
            val minute = i / 60
            val fatShareOfBasal = basalFat.fractionAt(localHourAt(i))
            // Resting energy is fixed; what moves is the oxygen it takes to deliver it, since fat
            // costs more oxygen per kcal than carbohydrate does.
            val basalVo2LPerMin = basalKcalPerS * 60.0 / FuelMix.kcalPerLitreO2(fatShareOfBasal)
            var activeThisSecond = 0.0
            var fatShareOfActive = 0.0
            val bpm = hr[i]
            val anchor = basalHr[i]
            if (!bpm.isNaN() && !recorded[i].isNaN()) {
                deltaHrSum[minute] += bpm - recorded[i]
                deltaHrCount[minute] += 1
            }
            if (!bpm.isNaN() && !anchor.isNaN()) {
                finalBasalHr = anchor
                // Weighted on both sides of the ratio, so a maximal effort still reads as the whole
                // reserve and the ramp moves energy toward the higher rates rather than removing it.
                val reserve = reserveRamp?.excess(anchor, hrmax) ?: (hrmax - anchor)
                val above = reserveRamp?.excess(anchor, bpm) ?: (bpm - anchor)
                val fraction = if (reserve > 0.0) (above / reserve).coerceIn(0.0, 1.0) else 0.0
                val activeVo2LPerMin = maxOf(0.0, fraction * (vo2max - basalVo2LPerMin))
                fatShareOfActive = activeFat.fractionAt(bpm)
                activeThisSecond = activeVo2LPerMin * FuelMix.kcalPerLitreO2(fatShareOfActive) / 60.0
            }
            activeKcal[minute] += activeThisSecond
            totalKcal[minute] += basalKcalPerS + activeThisSecond
            fatKcal[minute] += basalKcalPerS * fatShareOfBasal + activeThisSecond * fatShareOfActive
            if (!anchor.isNaN()) minuteBasalHr[minute] = anchor
        }

        var dayTotalKcal = 0.0
        var dayFatKcal = 0.0
        val fatPercentage = ArrayList<Double?>(minutes)
        val deltaHr = ArrayList<Double?>(minutes)
        val tsIndex = LongArray(minutes)
        for (m in 0 until minutes) {
            dayTotalKcal += totalKcal[m]
            dayFatKcal += fatKcal[m]
            tsIndex[m] = startUtc + m * 60L
            val share = if (totalKcal[m] > 0.0) fatKcal[m] / totalKcal[m] else null
            fatPercentage.add(share?.times(100.0))
            deltaHr.add(if (deltaHrCount[m] > 0) deltaHrSum[m] / deltaHrCount[m] else null)
        }

        val extras = LinkedHashMap<String, Double>()
        extras[Extra.QUIET_WINDOW_COUNT] = quietWindows.toDouble()
        extras[Extra.FAT_GRAMS] = dayFatKcal / FuelMix.KCAL_PER_G_FAT
        extras[Extra.CHO_GRAMS] = (dayTotalKcal - dayFatKcal) / FuelMix.KCAL_PER_G_CHO
        extras[Extra.HR_COVERAGE_FRAC] = hrCoverageFrac
        // Only where a stretch actually measured it. With none, the anchor was the seed the day
        // assumed, and this key is absent from a model that assumes its anchor.
        if (!finalBasalHr.isNaN() && quietWindows > 0) {
            extras[CalorieTimeline.MEASURED_BASAL_HR_BPM] = finalBasalHr
        }

        return CalorieTimeline(
            tsIndex = tsIndex,
            totalKcal = totalKcal,
            activeKcal = activeKcal,
            labeledKcal = mapOf(Label.ACTIVE to activeKcal),
            labeledSeries = mapOf(
                Label.DELTA_HR to deltaHr,
                Label.BASAL_HR to minuteBasalHr.map { if (it.isNaN()) null else it },
                Label.FAT_PERCENTAGE to fatPercentage,
            ),
            extras = extras,
        )
    }

    /** A window this model will not estimate above resting: resting energy alone, no fuel split. */
    private fun declined(startUtc: Long, endUtc: Long, hrCoverageFrac: Double): CalorieTimeline {
        val minutes = minuteCount(startUtc, endUtc)
        val tsIndex = LongArray(minutes)
        val totalKcal = DoubleArray(minutes)
        for (m in 0 until minutes) {
            val minuteStart = startUtc + m * 60L
            tsIndex[m] = minuteStart
            totalKcal[m] = basalKcalPerS * (minOf(minuteStart + 60L, endUtc) - minuteStart)
        }
        val empty = arrayOfNulls<Double>(minutes).toList()
        return CalorieTimeline(
            tsIndex = tsIndex,
            totalKcal = totalKcal,
            activeKcal = DoubleArray(minutes),
            labeledKcal = mapOf(Label.ACTIVE to DoubleArray(minutes)),
            labeledSeries = mapOf(
                Label.DELTA_HR to empty,
                Label.BASAL_HR to empty,
                Label.FAT_PERCENTAGE to empty,
            ),
            // The fuel split is absent rather than zero: the day still books resting energy, and
            // zero grams beside a non-zero total would assert that none of it came from anywhere.
            extras = mapOf(
                CalorieTimeline.DECLINED to 1.0,
                Extra.QUIET_WINDOW_COUNT to 0.0,
                Extra.HR_COVERAGE_FRAC to hrCoverageFrac,
            ),
        )
    }

    /** The index ranges the strap was worn over, split where the silence exceeds the gap setting. */
    private fun wearSessions(hr: DoubleArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var first = -1
        var previous = -1
        for (i in hr.indices) {
            if (hr[i].isNaN()) continue
            if (first < 0) {
                first = i
            } else if (i - previous > setting.sessionGapS) {
                out.add(first..previous)
                first = i
            }
            previous = i
        }
        if (first >= 0) out.add(first..previous)
        return out
    }

    /**
     * The share of the wear sessions' seconds that carried a heart rate.
     *
     * The density the day was actually recorded at, never inferred from which strap wrote it: a model
     * name says nothing about how dense one day's stream is.
     */
    private fun coverageFraction(hr: DoubleArray, sessions: List<IntRange>): Double {
        var covered = 0
        var total = 0
        for (session in sessions) {
            total += session.last - session.first + 1
            for (i in session) if (!hr[i].isNaN()) covered += 1
        }
        return if (total == 0) 0.0 else covered.toDouble() / total
    }

    /** The local hour of the second [i] seconds into a window that opens at local midnight. */
    private fun localHourAt(i: Int): Double = (i / 60) / 60.0

    private companion object {

        /**
         * How many of the smoothed seconds must carry a motion reading before the mean stands.
         *
         * Below this a single sample would decide a whole window's stillness.
         */
        const val MOTION_SMOOTH_MIN_SAMPLES: Int = 3

        /**
         * How long a quiet stretch's still and beat shares may sit under their thresholds before the
         * stretch is closed.
         *
         * A share is diluted by the seconds after it, so a brief disturbance repairs itself given a
         * little time. Long enough that turning over in bed does not end a restful hour, short enough
         * that getting up does.
         */
        const val BASAL_REPAIR_GRACE_S: Int = 300

        /**
         * The longest silence, in seconds, that is filled from the readings around it.
         *
         * Wide enough to span a live Bluetooth cadence of about one reading every thirty seconds,
         * narrow enough that a strap taken off is not given a rate it never read.
         */
        const val HR_GAP_FILL_MAX_S: Int = 60

        /** The longest window this model will estimate: two days, which no activity day reaches. */
        const val MAX_SPAN_S: Long = 2 * 86_400L
    }
}

/**
 * The wearer settings [DynamicHrrModel] reads.
 *
 * Ranges are enforced where the value is persisted ([DynamicHrrModelSettingRanges]) rather than here,
 * so a hand-built setting in a test says exactly what it means.
 *
 * Parity debt: this type has no Swift twin, and neither does the model it configures.
 */
data class DynamicHrrModelSetting(

    /** Silence longer than this many seconds starts a new wear session. */
    val sessionGapS: Int = 1_800,

    /**
     * The least share of a wear session's seconds that must carry a heart rate for the day to be
     * estimated.
     *
     * The model works per second and never fills the gap between two samples. Offloaded records
     * arrive at one sample a second; a live Bluetooth stream arrives about every thirty, which is far
     * below any usable value here.
     */
    val minHrCoverageFrac: Double = 0.5,

    /** Half-width, in seconds, of the centred window the spike filter compares each second against. */
    val hampelRadiusS: Int = 5,

    /** How many robust deviations from the local median a reading must sit at to count as a spike. */
    val hampelSigmas: Double = 3.0,

    /**
     * Whether the highest reading in each block, and the seconds around it, are replaced.
     *
     * This drops real effort along with artifacts. It replaces a run of readings with one number, so
     * it moves the day's energy in either direction rather than only lowering it: which way depends
     * on where the block's peak sits and on what the readings bracketing it read.
     */
    val suppressPeaks: Boolean = true,

    /** The length in seconds of the block one peak is removed from. */
    val peakBlockS: Int = 300,

    /**
     * The share of a block's readings left untouched; the rest are pulled down to the reading at it.
     *
     * One means no clipping at all, since nothing can exceed the block's own maximum.
     */
    val peakPercentile: Double = 0.97,

    /** Smoothed motion at or below this many g counts as still. */
    val motionStillG: Double = 0.02,

    /** How many seconds of motion are averaged before the stillness test. */
    val motionSmoothS: Int = 10,

    /** The shortest quiet stretch, in seconds, that can measure the basal heart rate. */
    val basalMinWindowS: Int = 300,

    /**
     * How far, in bpm, the rate at a second may stand above the lowest rate of the stretch behind it.
     *
     * Once the rate has risen further than this above that lowest, the lowest is a rate the wearer
     * has left behind, and the stretch is cut back until what it measures is a rate this second is
     * close to.
     */
    val basalHrRangeBpm: Double = 10.0,

    /**
     * The share of a quiet stretch that must be still.
     *
     * Predominantly still rather than perfectly still: a brief tick would otherwise chop a genuinely
     * restful period into pieces too short to qualify.
     */
    val basalStillFrac: Double = 0.98,

    /**
     * The share of a quiet stretch that must carry beat-to-beat intervals.
     *
     * Beat coverage stands in for a clean optical lock, which stillness alone does not establish.
     */
    val basalBeatCoverageFrac: Double = 0.5,

    /** How many seconds of heart rate are averaged before the basal value is allowed to fall. */
    val restSmoothS: Int = 30,

    /**
     * The least readings those seconds must carry for the average to count.
     *
     * Above [restSmoothS] no window can ever hold enough, and the basal rate then only ever moves
     * where a quiet stretch measures it.
     */
    val restSmoothMinSamples: Int = 20,

    /**
     * How far above the wearer's resting heart rate the day starts, in bpm.
     *
     * Resting heart rate is read over sleep and a basal heart rate over a still waking stretch, which
     * sits a little above it.
     */
    val basalHrSeedOffsetBpm: Double = 5.0,

    /**
     * The width in bpm of each band over which a beat above the resting rate is discounted.
     *
     * Three bands sit above the resting heart rate, worth a quarter, a half and three quarters of a
     * beat each; a beat above all three is worth a whole one. Zero counts every beat in full, which
     * is the straight heart-rate reserve.
     */
    val reserveRampBandBpm: Double = 10.0,

    /** The wearer's measured resting energy per day, in kcal; zero estimates it from their body. */
    val measuredBasalKcalDay: Double = 0.0,

    /**
     * The share of resting energy OUTSIDE the brain's that comes from fat overnight.
     *
     * The brain takes a fifth of resting metabolism and runs on glucose alone, so this is a share of
     * the remaining four fifths rather than of the whole.
     */
    val basalFatNight: Double = 0.80,

    /** The same share during the day, on the same four fifths. */
    val basalFatDay: Double = 0.30,

    /** The local hour the daytime fat share is reached. */
    val basalFatDayStartHour: Double = 10.0,

    /** The local hour the climb back to the overnight fat share begins. */
    val basalFatDayEndHour: Double = 20.0,

    /** The share of active energy that comes from fat at the bottom of zone 1. */
    val activeFatAtZone1: Double = 1.0,

    /** The share of active energy that comes from fat at the top of zone 2. */
    val activeFatAtZone2Top: Double = 0.66,
)

/**
 * The valid range of every value in [DynamicHrrModelSetting].
 *
 * Beside the type rather than in the settings layer so that persistence cannot drift from what the
 * model accepts. Each range bounds one value on its own; no combination of two is checked, so a pair
 * that disables a stage between them is reachable and is documented on the fields themselves.
 */
object DynamicHrrModelSettingRanges {
    val SESSION_GAP_S: IntRange = 60..7_200
    val MIN_HR_COVERAGE_FRAC: ClosedFloatingPointRange<Double> = 0.05..1.0
    val HAMPEL_RADIUS_S: IntRange = 1..30
    val HAMPEL_SIGMAS: ClosedFloatingPointRange<Double> = 1.0..10.0
    val PEAK_BLOCK_S: IntRange = 30..3_600
    val PEAK_PERCENTILE: ClosedFloatingPointRange<Double> = 0.50..1.00
    val MOTION_STILL_G: ClosedFloatingPointRange<Double> = 0.001..0.5
    // Never below the samples the mean requires, which would leave every second not still and every
    // day declined for a reason no readout distinguishes from a strap that reports no motion.
    val MOTION_SMOOTH_S: IntRange = 3..300
    val BASAL_MIN_WINDOW_S: IntRange = 60..3_600
    val BASAL_HR_RANGE_BPM: ClosedFloatingPointRange<Double> = 1.0..40.0
    val BASAL_STILL_FRAC: ClosedFloatingPointRange<Double> = 0.5..1.0
    val BASAL_BEAT_COVERAGE_FRAC: ClosedFloatingPointRange<Double> = 0.0..1.0
    val REST_SMOOTH_S: IntRange = 5..600
    val REST_SMOOTH_MIN_SAMPLES: IntRange = 1..600
    val BASAL_HR_SEED_OFFSET_BPM: ClosedFloatingPointRange<Double> = 0.0..30.0
    val RESERVE_RAMP_BAND_BPM: ClosedFloatingPointRange<Double> = 0.0..40.0
    val MEASURED_BASAL_KCAL_DAY: ClosedFloatingPointRange<Double> = 0.0..6_000.0
    val BASAL_FAT_NIGHT: ClosedFloatingPointRange<Double> = 0.0..1.0
    val BASAL_FAT_DAY: ClosedFloatingPointRange<Double> = 0.0..1.0
    val BASAL_FAT_DAY_START_HOUR: ClosedFloatingPointRange<Double> = 3.0..22.0
    val BASAL_FAT_DAY_END_HOUR: ClosedFloatingPointRange<Double> = 3.0..26.0
    val ACTIVE_FAT_AT_ZONE1: ClosedFloatingPointRange<Double> = 0.0..1.0
    val ACTIVE_FAT_AT_ZONE2_TOP: ClosedFloatingPointRange<Double> = 0.0..1.0
}
