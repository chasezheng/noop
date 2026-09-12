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

        /**
         * [CalorieTimeline.labeledSeries]: quiet stretches still qualifying but not yet long enough
         * to measure a rate, at the busiest second of the minute.
         *
         * The four counts below say what the basal-rate search was carrying, which is otherwise
         * invisible: the day states only how many stretches finished. They are maxima over the
         * minute rather than means, because what they answer is how many stood at once.
         */
        const val LIVE_CANDIDATES: String = "liveCandidates"

        /** [CalorieTimeline.labeledSeries]: the same for stretches whose shares have fallen under. */
        const val PAUSED_CANDIDATES: String = "pausedCandidates"

        /** [CalorieTimeline.labeledSeries]: stretches long enough to measure a rate, still qualifying. */
        const val LIVE_WINDOWS: String = "liveWindows"

        /** [CalorieTimeline.labeledSeries]: stretches long enough to measure a rate, now paused. */
        const val PAUSED_WINDOWS: String = "pausedWindows"
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
        vitals.restingHR?.plus(setting.basalSeedOffsetBpm) ?: Double.NaN

    private val restingFat = RestingFatCurve(setting)
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
    private val restingKcalPerS: Double =
        if (setting.restingEnergyKcalPerDay > 0.0) setting.restingEnergyKcalPerDay / 86_400.0 else body.restingRate

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

        val filtered = DynamicHrrSignals.hampel(filled, sessions, setting.spikeWindowRadiusS, setting.spikeThresholdSigmas)
        val settled = if (!setting.peakClipEnabled) {
            filtered
        } else {
            DynamicHrrSignals.clipBlockPeaks(
                filtered, sessions, startUtc, setting.peakClipBlockS, setting.peakClipKeptFrac,
            )
        }

        val smoothedMotion = DynamicHrrSignals.trailingMean(
            motion, sessions, setting.stillSmoothingS, STILL_SMOOTHING_MIN_SAMPLES,
        )
        val still = BooleanArray(seconds) {
            !smoothedMotion[it].isNaN() && smoothedMotion[it] <= setting.stillMaxG
        }
        // Read before the peaks are clipped, not after: clipping only lowers the high readings, so a
        // stretch broken by a spike would be handed the spike already pulled down, and would qualify
        // as quiet rather than being cut back at it.
        val quiet = DynamicHrrSignals.quietWindows(
            filtered, sessions, still, beat,
            setting.quietStretchMinLengthS, setting.quietStretchMaxRiseBpm,
            setting.quietStretchMinStillFrac, setting.quietStretchMinBeatFrac, MIN_GRACE_PERIOD,
        )
        val quietWindowHr = quiet.lowestHrAtWindowEnd
        val ratchetHr = DynamicHrrSignals.trailingMedian(
            settled, sessions, setting.basalLowerWindowS, setting.basalLowerMinSamples,
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

        return fold(startUtc, endUtc, filled, settled, basalHr, coverage, quietWindows, quiet)
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
        quiet: DynamicHrrSignals.QuietWindows,
    ): CalorieTimeline {
        val minutes = minuteCount(startUtc, endUtc)
        val activeKcal = DoubleArray(minutes)
        val totalKcal = DoubleArray(minutes)
        val fatKcal = DoubleArray(minutes)
        val deltaHrSum = DoubleArray(minutes)
        val deltaHrCount = IntArray(minutes)
        val minuteBasalHr = DoubleArray(minutes) { Double.NaN }
        val busiest = List(4) { IntArray(minutes) }
        val perSecondCounts = listOf(
            quiet.liveCandidates, quiet.pausedCandidates, quiet.liveWindows, quiet.pausedWindows,
        )
        val vo2max = vo2maxLPerMin ?: 0.0
        var finalBasalHr = Double.NaN

        // The resting fuel mix is read from the local hour, which [localHourAt] resolves to the
        // minute, so these three hold for every second of a minute and are read once for each.
        var minuteRead = -1
        var fatShareOfResting = 0.0
        var restingVo2LPerMin = 0.0
        for (i in hr.indices) {
            val minute = i / 60
            if (minute != minuteRead) {
                minuteRead = minute
                fatShareOfResting = restingFat.fractionAt(localHourAt(i))
                // Resting energy is fixed; what moves is the oxygen it takes to deliver it, since
                // fat costs more oxygen per kcal than carbohydrate does.
                restingVo2LPerMin = restingKcalPerS * 60.0 / FuelMix.kcalPerLitreO2(fatShareOfResting)
            }
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
                val activeVo2LPerMin = maxOf(0.0, fraction * (vo2max - restingVo2LPerMin))
                fatShareOfActive = activeFat.fractionAt(bpm)
                activeThisSecond = activeVo2LPerMin * FuelMix.kcalPerLitreO2(fatShareOfActive) / 60.0
            }
            activeKcal[minute] += activeThisSecond
            totalKcal[minute] += restingKcalPerS + activeThisSecond
            fatKcal[minute] += restingKcalPerS * fatShareOfResting + activeThisSecond * fatShareOfActive
            if (!anchor.isNaN()) minuteBasalHr[minute] = anchor
            for (which in busiest.indices) {
                val atThisSecond = perSecondCounts[which][i]
                if (atThisSecond > busiest[which][minute]) busiest[which][minute] = atThisSecond
            }
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
                Label.LIVE_CANDIDATES to busiest[0].map { it.toDouble() },
                Label.PAUSED_CANDIDATES to busiest[1].map { it.toDouble() },
                Label.LIVE_WINDOWS to busiest[2].map { it.toDouble() },
                Label.PAUSED_WINDOWS to busiest[3].map { it.toDouble() },
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
            totalKcal[m] = restingKcalPerS * (minOf(minuteStart + 60L, endUtc) - minuteStart)
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
                Label.LIVE_CANDIDATES to empty,
                Label.PAUSED_CANDIDATES to empty,
                Label.LIVE_WINDOWS to empty,
                Label.PAUSED_WINDOWS to empty,
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
            } else if (i - previous > setting.wearSessionMaxSilenceS) {
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
        const val STILL_SMOOTHING_MIN_SAMPLES: Int = 3

        /**
         * How many seconds a quiet stretch's still and beat fractions may sit under their thresholds
         * before the stretch is judged on whether it can still recover.
         *
         * Past this the stretch ends as soon as it would need more further qualifying seconds to
         * recover than it has already run, a tolerance that scales with how long the stretch is. This
         * grace is what a short stretch has instead, so a moment's noise does not end one that has
         * barely begun.
         */
        const val MIN_GRACE_PERIOD: Int = 30

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

    /**
     * A silence this long in the heart-rate stream, or longer, ends one wear session and starts
     * the next.
     *
     * Sessions bound every later stage: a quiet stretch never spans two, and coverage is measured
     * inside them. Shorter cuts a night with brief dropouts into pieces too short to measure a
     * basal rate.
     */
    val wearSessionMaxSilenceS: Int = 1_800,

    /**
     * The share of the wear sessions' seconds that must carry a heart rate before the day is
     * estimated above resting.
     *
     * Below it the day gets resting energy only. The model works per second and never fills the
     * gap between two samples, so a live Bluetooth stream at about one sample every thirty seconds
     * sits far below any usable value.
     */
    val minHrCoverageFrac: Double = 0.5,

    /**
     * Half the width of the centred window each reading is compared against.
     *
     * Wider judges a reading against a longer stretch, so a sustained climb is less likely to be
     * called a spike; narrower catches short artifacts but can mistake a real surge for one.
     */
    val spikeWindowRadiusS: Int = 5,

    /**
     * How far from its window's median a reading must sit, in robust deviations, before it is
     * replaced by that median.
     *
     * Lower replaces more readings, real ones included; higher leaves artifacts in the stream.
     */
    val spikeThresholdSigmas: Double = 3.0,

    /**
     * Whether every reading above its block's kept share is pulled down to the reading at that
     * share.
     *
     * This drops real effort along with artifacts. It only ever lowers a reading, so it can only
     * lower the day's energy above resting.
     */
    val peakClipEnabled: Boolean = true,

    /**
     * The length of the block one clipping ceiling is computed over.
     *
     * A block whose last seconds are still climbing or falling, or still stand above the block's own
     * ninetieth percentile, is extended past this length, so that it does not end inside one rise or
     * inside a stretch that is high throughout. A block that this carries to ten minutes and that is
     * still high there is left unclipped, since ten minutes of high readings are one effort rather
     * than an artifact. Longer blocks weigh a reading against more of the day, so a single hard
     * effort is less likely to set its own ceiling.
     */
    val peakClipBlockS: Int = 300,

    /**
     * The share of a block's readings left untouched; every reading above the one at that share is
     * pulled down to it.
     *
     * One clips nothing, since no reading exceeds its own block's maximum. Lower keeps less and
     * pulls more real effort down with the artifacts.
     */
    val peakClipKeptFrac: Double = 0.97,

    /**
     * Smoothed wrist motion at or below this counts the second as still.
     *
     * Higher counts light activity as rest, so quiet stretches form during it and report a basal
     * rate above the true one; lower finds fewer stretches.
     */
    val stillMaxG: Double = 0.02,

    /**
     * How many seconds of motion are averaged before the stillness test.
     *
     * Longer averaging makes a single twitch harmless but spreads the edge of a real movement over
     * neighbouring seconds. It cannot go below three, the samples the average itself requires.
     */
    val stillSmoothingS: Int = 10,

    /**
     * How long a stretch must have qualified before its lowest reading may set the basal heart
     * rate.
     *
     * Longer asks for more evidence and finds fewer stretches; shorter lets one calm minute set
     * the rate the rest of the day is measured against.
     */
    val quietStretchMinLengthS: Int = 300,

    /**
     * How far the heart rate may climb above the lowest reading of the stretch behind it before
     * that stretch ends.
     *
     * That lowest is what the stretch would report, so once the rate stands this far above it the
     * wearer has left it. Wider lets a stretch grow across a real climb and report a rate the
     * wearer is no longer at.
     */
    val quietStretchMaxRiseBpm: Double = 10.0,

    /**
     * The share of a stretch's seconds that must be still.
     *
     * It is a share of the whole stretch, so one restless second pauses the stretch rather than
     * ending it and later still seconds can dilute it back. Lower admits stretches with real
     * movement inside them.
     */
    val quietStretchMinStillFrac: Double = 0.98,

    /**
     * The share of a stretch's seconds that must carry a beat-to-beat interval.
     *
     * Beat coverage stands in for a clean optical lock, which stillness alone does not establish.
     * At one half or below it can pause a stretch but never end one; above that it can end one.
     */
    val quietStretchMinBeatFrac: Double = 0.5,

    /**
     * The trailing median of this many seconds of heart rate is the only value that may lower the
     * basal rate between measurements, because a rate under the current basal contradicts it
     * whatever an earlier stretch measured.
     *
     * Longer windows lower it more slowly.
     */
    val basalLowerWindowS: Int = 30,

    /**
     * How many of that window's seconds must carry a reading before its median counts.
     *
     * Above the window length no window can ever hold enough, and the basal rate then only moves
     * where a quiet stretch measures it.
     */
    val basalLowerMinSamples: Int = 20,

    /**
     * How far above the wearer's resting heart rate the basal rate starts, before any quiet
     * stretch has measured it.
     *
     * Resting heart rate is read over sleep, a basal rate over a still waking stretch, which sits
     * a little above it. A starting value and not a floor: the first measurement replaces it, and
     * the lowering path may go under it at once.
     */
    val basalSeedOffsetBpm: Double = 5.0,

    /**
     * The width of each band above the wearer's resting heart rate over which a beat counts for
     * less: a quarter, then a half, then three quarters, then in full.
     *
     * Zero counts every beat in full, which is the straight heart-rate reserve. Wider bands
     * discount more of a day spent just above rest.
     */
    val reserveRampBandBpm: Double = 10.0,

    /**
     * The wearer's own measured resting energy per day.
     *
     * Zero estimates it from the wearer's height, weight, age and sex instead. It sets the floor
     * every second of the day accrues, worn or not, so it moves every day's total.
     */
    val restingEnergyKcalPerDay: Double = 0.0,

    /**
     * The share of resting energy from fat overnight, taken over the four fifths outside the
     * brain's demand — the brain takes a fifth of resting metabolism and runs on glucose alone.
     *
     * It changes the fuel split reported, not the energy. Held from 02:00 until an hour before the
     * daytime share is reached.
     */
    val restingFatNightFrac: Double = 0.80,

    /**
     * The same share during the day, over the same four fifths.
     *
     * Resting energy itself does not move across the day; what moves is the oxygen it takes to
     * deliver it, because fat costs more oxygen per kcal than carbohydrate.
     */
    val restingFatDayFrac: Double = 0.30,

    /**
     * The local hour the daytime share is fully reached.
     *
     * The climb to it takes the hour before, and the share then holds until the overnight climb
     * begins.
     */
    val restingFatDayStartHour: Double = 10.0,

    /**
     * The local hour the climb back to the overnight share begins, reaching it at 02:00.
     *
     * The range runs past 24 because that climb may start after midnight; set to 26 there is no
     * climb at all and the daytime share holds through to 02:00.
     */
    val restingFatDayEndHour: Double = 20.0,

    /**
     * The share of energy above resting that comes from fat at the bottom of zone 1, and at every
     * rate below it.
     *
     * The share between here and the top of zone 2 is interpolated across the wearer's own zone
     * boundaries.
     */
    val activeFatZone1Frac: Double = 1.0,

    /**
     * The share of energy above resting that comes from fat at the top of zone 2.
     *
     * From there it falls to nil at the anaerobic threshold, the bottom of zone 5 — that last
     * anchor is a property of the curve, not a setting.
     */
    val activeFatZone2TopFrac: Double = 0.66,
)

/**
 * The valid range of every value in [DynamicHrrModelSetting].
 *
 * Beside the type rather than in the settings layer so that persistence cannot drift from what the
 * model accepts. Each range bounds one value on its own; no combination of two is checked, so a pair
 * that disables a stage between them is reachable and is documented on the fields themselves.
 */
object DynamicHrrModelSettingRanges {
    val WEAR_SESSION_MAX_SILENCE_S: IntRange = 60..7_200
    val MIN_HR_COVERAGE_FRAC: ClosedFloatingPointRange<Double> = 0.05..1.0
    val SPIKE_WINDOW_RADIUS_S: IntRange = 1..30
    val SPIKE_THRESHOLD_SIGMAS: ClosedFloatingPointRange<Double> = 1.0..10.0
    val PEAK_CLIP_BLOCK_S: IntRange = 30..3_600
    val PEAK_CLIP_KEPT_FRAC: ClosedFloatingPointRange<Double> = 0.50..1.00
    val STILL_MAX_G: ClosedFloatingPointRange<Double> = 0.001..0.5
    // Never below the samples the mean requires, which would leave every second not still and every
    // day declined for a reason no readout distinguishes from a strap that reports no motion.
    val STILL_SMOOTHING_S: IntRange = 3..300
    val QUIET_STRETCH_MIN_LENGTH_S: IntRange = 60..3_600
    val QUIET_STRETCH_MAX_RISE_BPM: ClosedFloatingPointRange<Double> = 1.0..40.0
    val QUIET_STRETCH_MIN_STILL_FRAC: ClosedFloatingPointRange<Double> = 0.5..1.0
    val QUIET_STRETCH_MIN_BEAT_FRAC: ClosedFloatingPointRange<Double> = 0.0..1.0
    val BASAL_LOWER_WINDOW_S: IntRange = 5..600
    val BASAL_LOWER_MIN_SAMPLES: IntRange = 1..600
    val BASAL_SEED_OFFSET_BPM: ClosedFloatingPointRange<Double> = 0.0..30.0
    val RESERVE_RAMP_BAND_BPM: ClosedFloatingPointRange<Double> = 0.0..40.0
    val RESTING_ENERGY_KCAL_PER_DAY: ClosedFloatingPointRange<Double> = 0.0..6_000.0
    val RESTING_FAT_NIGHT_FRAC: ClosedFloatingPointRange<Double> = 0.0..1.0
    val RESTING_FAT_DAY_FRAC: ClosedFloatingPointRange<Double> = 0.0..1.0
    val RESTING_FAT_DAY_START_HOUR: ClosedFloatingPointRange<Double> = 3.0..22.0
    val RESTING_FAT_DAY_END_HOUR: ClosedFloatingPointRange<Double> = 3.0..26.0
    val ACTIVE_FAT_ZONE1_FRAC: ClosedFloatingPointRange<Double> = 0.0..1.0
    val ACTIVE_FAT_ZONE2_TOP_FRAC: ClosedFloatingPointRange<Double> = 0.0..1.0
}
