package com.noop.analytics.calorie

import kotlin.math.abs

/**
 * The per-second signal processing behind [DynamicHrrModel].
 *
 * Every array is indexed by whole seconds since one window's first second, and [Double.NaN] marks a
 * second that carried no reading. A wear session is a range of those indices; no window ever reaches
 * across two of them, because the silence between sessions is not a measurement.
 *
 * Pure: an array in, a new array out, and no state kept between calls.
 */
internal object DynamicHrrSignals {

    /** How many seconds at a block's end decide whether that end sits inside one rise or fall. */
    private const val PEAK_EDGE_PROBE_S = 20

    /** How many seconds a block's end is extended by each time those seconds say it sits inside one. */
    private const val PEAK_EDGE_STEP_S = 5

    /** The shortest block whose end is extended at all. */
    private const val PEAK_EDGE_MIN_BLOCK_S = 90

    /** The longest a block may be extended to. A block already this long is not extended. */
    private const val PEAK_MAX_BLOCK_S = 600

    /** How many seconds at a block's end decide whether that end sits at a high level. */
    private const val PEAK_LEVEL_PROBE_S = 5

    /** The share of a block's readings that the seconds at its end must stand above to be high. */
    private const val PEAK_LEVEL_FRAC = 0.90

    /**
     * [values] with each local outlier replaced by its local median (a Hampel filter).
     *
     * A second is an outlier when it sits more than [sigmas] robust deviations from the median of the
     * centred `±radiusS` window around it. The test also requires a non-zero deviation: a flat
     * neighbourhood has a deviation of zero, where every 1 bpm step would exceed any threshold and a
     * quiet stretch would be shredded rather than left alone.
     */
    fun hampel(values: DoubleArray, sessions: List<IntRange>, radiusS: Int, sigmas: Double): DoubleArray {
        val out = values.copyOf()
        val window = SortedWindow(2 * radiusS + 1)
        val median = DoubleArray(values.size) { Double.NaN }
        val deviation = DoubleArray(values.size) { Double.NaN }
        for (session in sessions) {
            forEachCentredMedian(values, session, radiusS, window) { i, middle ->
                median[i] = middle
                if (!values[i].isNaN() && !middle.isNaN()) deviation[i] = abs(values[i] - middle)
            }
            // A second pass: the median of the deviations around a second reads deviations of
            // the seconds after it, which the first pass has not reached yet.
            forEachCentredMedian(deviation, session, radiusS, window) { i, middle ->
                // 1.4826 rescales a median absolute deviation to a standard deviation for a normal
                // distribution, which is what makes [sigmas] read as deviations.
                val robustSigma = 1.4826 * middle
                if (robustSigma > 0.0 && deviation[i] > sigmas * robustSigma) out[i] = median[i]
            }
        }
        return out
    }

    /**
     * [values] with every reading above its block's [percentile] pulled down to that percentile.
     *
     * A block starts one second after the block before it and runs to the next [blockS] boundary in
     * absolute time ([windowStartUtc] anchors index 0), so where a block falls does not depend on
     * when the wearer put the strap on. Nothing outside a block is read or written, so a burst at
     * one block's edge cannot flatten the next.
     *
     * That end is then extended [PEAK_EDGE_STEP_S] seconds at a time while either of two tests
     * passes. The first is that the block's last [PEAK_EDGE_PROBE_S] seconds are still climbing or
     * falling as a whole, which they are when their net change stands above twice their mean absolute
     * deviation; an end that lands inside one rise or fall would otherwise rank its two halves
     * against different neighbours. The second is that the mean of the block's last
     * [PEAK_LEVEL_PROBE_S] seconds stands above the reading at [PEAK_LEVEL_FRAC] of the block so far,
     * recomputed at each step, so that an end does not land inside a stretch that is high throughout.
     * Extending stops at a second that carries no reading, at the end of the session, and at a block
     * of [PEAK_MAX_BLOCK_S] seconds. A block shorter than [PEAK_EDGE_MIN_BLOCK_S] seconds is not
     * extended at all, and the block after one that was starts after its extended end rather than
     * on the boundary.
     *
     * No reading is lowered at all in a block that extension carried to [PEAK_MAX_BLOCK_S] seconds
     * and that is still above [PEAK_LEVEL_FRAC] of itself there. Such a block is one sustained effort
     * rather than a short burst inside a quieter block, and a ceiling taken from its own readings
     * would lower that effort. A block the grid alone made that long is clipped as usual, because
     * nothing about it was measured to be high.
     *
     * The percentile is the nearest rank: the block's readings sorted ascending, and the one at
     * `ceil(percentile x count) - 1`. Nearest rank rather than an interpolated one because the same
     * arithmetic has to hold on another platform, and an integer index cannot disagree about its last
     * bits.
     *
     * This removes real effort along with artifacts, so it lowers the window's energy. A second that
     * carried no reading is left without one: the clip stands in for a reading that was taken.
     */
    fun clipBlockPeaks(
        values: DoubleArray,
        sessions: List<IntRange>,
        windowStartUtc: Long,
        blockS: Int,
        percentile: Double,
    ): DoubleArray {
        val out = values.copyOf()
        val scratch =
            DoubleArray(minOf(values.size.toLong(), maxOf(blockS, PEAK_MAX_BLOCK_S).toLong()).toInt())
        for (session in sessions) {
            var blockStart = session.first
            while (blockStart <= session.last) {
                val blockLast = (Math.floorDiv(windowStartUtc + blockStart, blockS.toLong()) + 1L) *
                    blockS - windowStartUtc - 1L
                val gridEnd = minOf(session.last.toLong(), blockLast).toInt()
                val blockEnd = extendedBlockEnd(values, blockStart, gridEnd, session.last, scratch)
                if (!blockIsSustainedHigh(values, blockStart, blockEnd, gridEnd, scratch)) {
                    clipBlock(values, out, blockStart..blockEnd, percentile, scratch)
                }
                blockStart = blockEnd + 1
            }
        }
        return out
    }

    /**
     * [values] with every gap of at most [maxGapS] missing seconds filled from the readings around it.
     *
     * A strap reporting every thirty seconds leaves twenty-nine seconds empty between two readings of
     * one continuous stretch, and a rate that moved across them moved smoothly. A longer silence is
     * not a gap inside a stretch and is left empty, as are the seconds before the first reading and
     * after the last: nothing sits on both sides of them to interpolate between.
     */
    fun interpolatedShortGaps(values: DoubleArray, maxGapS: Int): DoubleArray {
        val out = values.copyOf()
        var last = -1
        for (i in values.indices) {
            if (values[i].isNaN()) continue
            if (last >= 0 && i - last - 1 in 1..maxGapS) {
                val span = (i - last).toDouble()
                for (j in last + 1 until i) {
                    out[j] = values[last] + (values[i] - values[last]) * (j - last) / span
                }
            }
            last = i
        }
        return out
    }

    /** The trailing mean of the [widthS] seconds ending at each second, or NaN below [minSamples]. */
    fun trailingMean(values: DoubleArray, sessions: List<IntRange>, widthS: Int, minSamples: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (session in sessions) {
            for (i in session) {
                var sum = 0.0
                var count = 0
                for (j in maxOf(session.first, i - widthS + 1)..i) {
                    if (values[j].isNaN()) continue
                    sum += values[j]
                    count += 1
                }
                if (count >= minSamples) out[i] = sum / count
            }
        }
        return out
    }

    /** The trailing median of the [widthS] seconds ending at each second, or NaN below [minSamples]. */
    fun trailingMedian(values: DoubleArray, sessions: List<IntRange>, widthS: Int, minSamples: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        val window = SortedWindow(widthS)
        for (session in sessions) {
            window.clear()
            for (i in session) {
                // The departing second leaves before the arriving one lands, so the window never
                // holds more than it has room for.
                val departing = i - widthS
                if (departing >= session.first) window.remove(values[departing])
                window.add(values[i])
                if (window.size >= minSamples) out[i] = window.median()
            }
        }
        return out
    }

    /**
     * What the quiet stretches measured, and how many were being tracked at each second.
     *
     * A stretch is a WINDOW once it has qualified for at least `minWindowS` seconds and a CANDIDATE
     * before that; either is PAUSED while either of its two fractions sits under its threshold, and
     * LIVE otherwise. The four counts partition the stretches alive at a second, so their sum is how
     * many the search was carrying at once.
     */
    class QuietWindows(
        val lowestHrAtWindowEnd: DoubleArray,
        val liveCandidates: IntArray,
        val pausedCandidates: IntArray,
        val liveWindows: IntArray,
        val pausedWindows: IntArray,
    )

    /**
     * The lowest heart rate the quiet stretches measured, each at the last second its stretch held.
     *
     * Every interrupted second starts a new candidate stretch beside the ones already running, so a
     * stretch beginning after a disturbance is weighed against the one the disturbance ran through.
     * A candidate runs while it is still for [stillFrac] of its seconds and beat-covered for
     * [beatFrac] of them. Both are fractions of the whole candidate, so one restless second does not
     * end it: the candidate PAUSES, and later seconds can dilute the bad ones back over the
     * thresholds.
     *
     * A paused candidate ends once it has been paused for more than [minGracePeriod] seconds AND would
     * need more further qualifying seconds to recover than it has already run. The second test scales
     * the tolerance with how much the candidate has to lose; the first keeps a short one from being
     * ended by a moment's noise.
     *
     * A candidate also ends where the rate stands more than [hrRangeBpm] above the lowest reading it
     * has taken, since that lowest is the rate it would report and the wearer is no longer at it;
     * where a candidate that began before it is more still than it; and at the end of the wear
     * session. It measures the lowest rate it read up to the last second both fractions stood at
     * their thresholds, and only where that many seconds reach [minWindowS].
     *
     * Where two measurements were taken over seconds that overlap, only one is reported: the lower
     * rate, and on a tie the one measured over the later stretch. Overlap is not followed from one
     * measurement to the next, so two that do not themselves overlap are both reported however many
     * measurements lie between them.
     */
    fun quietWindows(
        hr: DoubleArray,
        sessions: List<IntRange>,
        still: BooleanArray,
        beat: BooleanArray,
        minWindowS: Int,
        hrRangeBpm: Double,
        stillFrac: Double,
        beatFrac: Double,
        minGracePeriod: Int,
    ): QuietWindows {
        val windows = QuietWindows(
            DoubleArray(hr.size) { Double.NaN },
            IntArray(hr.size), IntArray(hr.size), IntArray(hr.size), IntArray(hr.size),
        )
        val scan = QuietWindowScan(
            hr, still, prefixCount(still), prefixCount(beat),
            minWindowS, hrRangeBpm, stillFrac, beatFrac, minGracePeriod,
        )
        for (session in sessions) {
            val reports = scan.reportsOver(session, windows)
            for (report in reports) {
                val beaten = reports.any { other ->
                    other !== report && report.overlaps(other) && report.losesTo(other)
                }
                if (!beaten) {
                    windows.lowestHrAtWindowEnd[report.lastQualifyingSecond] =
                        report.lowestHrMeasuredAt
                }
            }
        }
        return windows
    }

    /**
     * The basal heart rate at each second, from the windows that measured it and the rate that
     * contradicts it.
     *
     * Where a window ends, the value RESETS to that window's lowest heart rate — the one way it can
     * rise. Elsewhere it may only ratchet DOWN to [ratchetHr], because a rate below the current basal
     * contradicts it whatever an earlier window certified.
     *
     * [seedBpm] starts the fold before any window has ended; NaN leaves those seconds unmeasured. It
     * is a starting value and not a floor: the ratchet may lower it at once, and the first qualifying
     * window overwrites it. The ratchet cannot establish a value of its own — a trailing median over
     * a stretch nothing qualified is not a measurement of a resting rate, only of whatever the wearer
     * was doing.
     */
    fun trackBasalHr(quietWindowHr: DoubleArray, ratchetHr: DoubleArray, seedBpm: Double): DoubleArray {
        val out = DoubleArray(quietWindowHr.size)
        var current = seedBpm
        for (i in quietWindowHr.indices) {
            if (!quietWindowHr[i].isNaN()) {
                current = quietWindowHr[i]
            } else if (!current.isNaN() && !ratchetHr[i].isNaN()) {
                current = minOf(current, ratchetHr[i])
            }
            out[i] = current
        }
        return out
    }

    /**
     * [basalHr] with each raise ramped back over the stretch that produced it.
     *
     * A raise arrives as a step at the one second a quiet window ends, but the rate rose over the
     * stretch before it. Each second of that stretch is weighted by how far [hr] ran above the basal
     * rate it is being raised from, so a second at rest advances the ramp not at all and a hard second
     * advances it a lot. The ramp reaches the measured rate at the second before the window ends,
     * leaving no step behind.
     *
     * It stops at the latest of the last second the basal rate changed, the last second a window
     * reported, and the first second [hr] carries a reading. A window reporting the value the fold
     * already held changed nothing, but it measured the rate at that second all the same, and a ramp
     * through it would raise it above what was measured.
     *
     * Only raises: a rate below the current basal is the ratchet's business. Where nothing in the
     * stretch ran above the rate being raised from there is no shape to ramp along, and the step
     * stands.
     */
    fun smoothBasalRaises(basalHr: DoubleArray, quietWindowHr: DoubleArray, hr: DoubleArray): DoubleArray {
        val out = basalHr.copyOf()
        // The seconds before the first reading are filled with that reading rather than left empty,
        // so a ramp reaching into them would draw its shape from a rate the strap never recorded at
        // a time it was not worn. On a day opened at local midnight and worn from the morning, those
        // seconds outnumber the recorded stretch and would carry most of the ramp.
        val firstReading = hr.indexOfFirst { !it.isNaN() }
        if (firstReading < 0) return out
        val readings = interpolatedGaps(hr)
        var lastChange = -1
        var lastReport = -1
        for (r in basalHr.indices) {
            val reported = !quietWindowHr[r].isNaN()
            if (reported && r > 0 && basalHr[r] > basalHr[r - 1]) {
                val start = maxOf(maxOf(lastChange, lastReport) + 1, firstReading)
                rampTo(out, readings, start, r, basalHr[r - 1], basalHr[r])
            }
            // An unmeasured second reads as a change against its neighbour, which is what bounds a
            // ramp at the stretch the fold could measure.
            if (r > 0 && basalHr[r] != basalHr[r - 1]) lastChange = r
            if (reported) lastReport = r
        }
        return out
    }

    /** Ramps `[start, r)` of [out] from [prev] up to [new], at the speed [readings] ran above [prev]. */
    private fun rampTo(
        out: DoubleArray,
        readings: DoubleArray,
        start: Int,
        r: Int,
        prev: Double,
        new: Double,
    ) {
        var total = 0.0
        for (t in start until r) total += maxOf(0.0, readings[t] - prev)
        // Nothing behind the raise, or nothing in it above the rate being raised from. Either way the
        // step is the only thing the stretch supports.
        if (total <= 0.0) return
        var weighed = 0.0
        for (t in start until r) {
            weighed += maxOf(0.0, readings[t] - prev)
            val ramped = prev + (new - prev) * (weighed / total)
            // Never above what the strap read, and never below where the rate already stood: this
            // stage raises and does not lower.
            out[t] = maxOf(prev, minOf(ramped, readings[t]))
        }
    }

    /** [values] with each interior gap filled linearly, and each end held at the nearest reading. */
    private fun interpolatedGaps(values: DoubleArray): DoubleArray {
        val out = values.copyOf()
        var last = -1
        for (i in values.indices) {
            if (values[i].isNaN()) continue
            if (last < 0) {
                for (j in 0 until i) out[j] = values[i]
            } else {
                val span = (i - last).toDouble()
                for (j in last + 1 until i) {
                    out[j] = values[last] + (values[i] - values[last]) * (j - last) / span
                }
            }
            last = i
        }
        if (last >= 0) for (j in last + 1 until values.size) out[j] = values[last]
        return out
    }

    /** Pulls one block's readings down to its own percentile. See [clipBlockPeaks]. */
    private fun clipBlock(
        values: DoubleArray,
        out: DoubleArray,
        block: IntRange,
        percentile: Double,
        scratch: DoubleArray,
    ) {
        val ceiling = rankedReading(values, block, percentile, scratch)
        if (ceiling.isNaN()) return
        for (i in block) {
            if (!values[i].isNaN() && values[i] > ceiling) out[i] = ceiling
        }
    }

    /**
     * The reading at [percentile] of [block] by nearest rank, or [Double.NaN] where it holds none.
     *
     * Nearest rank rather than an interpolated one because the same arithmetic has to hold on another
     * platform, and an integer index cannot disagree about its last bits.
     */
    private fun rankedReading(
        values: DoubleArray,
        block: IntRange,
        percentile: Double,
        scratch: DoubleArray,
    ): Double {
        var count = 0
        for (i in block) {
            if (values[i].isNaN()) continue
            scratch[count++] = values[i]
        }
        if (count == 0) return Double.NaN
        scratch.sort(0, count)
        // Rank counts from one, the array from zero.
        val rank = Math.ceil(percentile * count).toInt().coerceIn(1, count)
        return scratch[rank - 1]
    }

    /**
     * Whether extension carried [blockStart]..[blockEnd] to [PEAK_MAX_BLOCK_S] seconds and its end is
     * still above [PEAK_LEVEL_FRAC] of it. See [clipBlockPeaks].
     */
    private fun blockIsSustainedHigh(
        values: DoubleArray,
        blockStart: Int,
        blockEnd: Int,
        gridEnd: Int,
        scratch: DoubleArray,
    ): Boolean =
        blockEnd > gridEnd &&
            blockEnd - blockStart + 1 >= PEAK_MAX_BLOCK_S &&
            edgeIsAboveBlockPercentile(values, blockStart, blockEnd, scratch)

    /**
     * [gridEnd] extended while the seconds ending at it are still on one course or still above
     * [PEAK_LEVEL_FRAC] of the block, never past [sessionLast] and never to a block longer than
     * [PEAK_MAX_BLOCK_S]. See [clipBlockPeaks].
     */
    private fun extendedBlockEnd(
        values: DoubleArray,
        blockStart: Int,
        gridEnd: Int,
        sessionLast: Int,
        scratch: DoubleArray,
    ): Int {
        if (gridEnd - blockStart + 1 < PEAK_EDGE_MIN_BLOCK_S) return gridEnd
        // maxOf, because a block the grid already made longer than the limit is left as it is.
        val furthest = minOf(sessionLast, maxOf(gridEnd, blockStart + PEAK_MAX_BLOCK_S - 1))
        var end = gridEnd
        while (end < furthest &&
            (edgeIsOnOneCourse(values, end) ||
                edgeIsAboveBlockPercentile(values, blockStart, end, scratch))
        ) {
            end = minOf(furthest, end + PEAK_EDGE_STEP_S)
        }
        return end
    }

    /**
     * Whether the [PEAK_EDGE_PROBE_S] seconds ending at [end] moved further as a whole than twice
     * their mean absolute deviation.
     *
     * A stretch that climbs or falls throughout does; a stretch that varies around one level does
     * not, because its net change is small beside how far its readings stand from their own mean.
     * A missing second ends the test rather than being skipped: a stretch with a hole in it does not
     * say what happened across the hole.
     */
    private fun edgeIsOnOneCourse(values: DoubleArray, end: Int): Boolean {
        val from = end - PEAK_EDGE_PROBE_S + 1
        var sum = 0.0
        for (i in from..end) {
            if (values[i].isNaN()) return false
            sum += values[i]
        }
        val mean = sum / PEAK_EDGE_PROBE_S
        var spread = 0.0
        for (i in from..end) spread += abs(values[i] - mean)
        return abs(values[end] - values[from]) > 2.0 * (spread / PEAK_EDGE_PROBE_S)
    }

    /**
     * Whether the mean of the [PEAK_LEVEL_PROBE_S] seconds ending at [end] stands above the reading
     * at [PEAK_LEVEL_FRAC] of [blockStart]..[end].
     *
     * The threshold is the block's own reading at that share, so it rises as high readings join the
     * block: a stretch that levels off stops passing once enough of it has been taken in. A missing
     * second ends the test rather than being skipped, as in [edgeIsOnOneCourse].
     */
    private fun edgeIsAboveBlockPercentile(
        values: DoubleArray,
        blockStart: Int,
        end: Int,
        scratch: DoubleArray,
    ): Boolean {
        var sum = 0.0
        for (i in (end - PEAK_LEVEL_PROBE_S + 1)..end) {
            if (values[i].isNaN()) return false
            sum += values[i]
        }
        val level = rankedReading(values, blockStart..end, PEAK_LEVEL_FRAC, scratch)
        return !level.isNaN() && sum / PEAK_LEVEL_PROBE_S > level
    }

    /**
     * Calls [use] with each second of [session] and the median of the centred `±radiusS` window
     * around it, clamped to the session.
     */
    private inline fun forEachCentredMedian(
        values: DoubleArray,
        session: IntRange,
        radiusS: Int,
        window: SortedWindow,
        use: (Int, Double) -> Unit,
    ) {
        window.clear()
        var arriving = session.first
        for (i in session) {
            val departing = i - radiusS - 1
            if (departing >= session.first) window.remove(values[departing])
            val ahead = minOf(session.last, i + radiusS)
            while (arriving <= ahead) window.add(values[arriving++])
            use(i, window.median())
        }
    }

    /** `out[i]` is how many of the first `i` entries of [flags] are true. */
    private fun prefixCount(flags: BooleanArray): IntArray {
        val out = IntArray(flags.size + 1)
        for (i in flags.indices) out[i + 1] = out[i] + if (flags[i]) 1 else 0
        return out
    }
}

/** How many candidates before one are looked at for a candidate that dominates it. */
private const val LOOK_BACK = 3

/**
 * One call's search of the wear sessions for quiet stretches.
 *
 * Built per call and discarded, so [DynamicHrrSignals] still keeps no state between calls. It holds
 * the arrays the search reads so that a candidate can answer questions about itself.
 */
private class QuietWindowScan(
    private val hr: DoubleArray,
    private val still: BooleanArray,
    private val stillCount: IntArray,
    private val beatCount: IntArray,
    private val minWindowS: Int,
    private val hrRangeBpm: Double,
    private val stillFrac: Double,
    private val beatFrac: Double,
    private val minGracePeriod: Int,
) {

    /**
     * Every candidate in [session] that ended having measured a rate.
     *
     * See [DynamicHrrSignals.quietWindows].
     */
    fun reportsOver(
        session: IntRange,
        census: DynamicHrrSignals.QuietWindows,
    ): List<QuietWindowReport> {
        // In ascending order of start, which is also descending order of length. Spawning only ever
        // appends, since a candidate begins at the second being read.
        val liveCandidates = ArrayList<Candidate>()
        val reports = ArrayList<QuietWindowReport>()
        for (second in session) {
            // Read against the stretch behind this second, before this second joins it, and end
            // first: a candidate replacing one the rate has climbed too far above begins AT this
            // second, which is a second the rate itself gives no reason to skip.
            endWhere(liveCandidates, reports) { candidate -> candidate.isTooFarAboveItsLowest(second) }
            if (second == session.first || interrupts(second - 1) || liveCandidates.isEmpty()) {
                if (liveCandidates.lastOrNull()?.start != second) liveCandidates += Candidate(second)
            }
            endWhere(liveCandidates, reports) { candidate -> !candidate.observe(second) }
            dropDominated(liveCandidates, second)
            for (candidate in liveCandidates) candidate.countIn(census, second)
        }
        // The end of the wear session ends every candidate still running.
        for (candidate in liveCandidates) if (candidate.isWindow()) reports += candidate.report()
        return reports
    }

    /**
     * Drops every candidate that one of the [LOOK_BACK] candidates before it dominates.
     *
     * An earlier candidate dominates a later one when it is more still, has itself run long enough
     * to measure a rate, and has read no higher a rate. The first decides which of the two ends
     * sooner, since a candidate ends once its still fraction falls under `2 * stillFrac - 1`
     * whatever its length. The second asks only that the earlier one has reached [minWindowS]; it
     * may be paused at [second], since a pause it repairs leaves it measuring the longer stretch.
     * The third holds for any two live candidates whose readings are numbers, the earlier spanning
     * every second of the later; where either has read nothing yet it is NaN, the comparison is
     * false, and neither is dropped.
     *
     * Without this the search carries every candidate a day spawns. A candidate whose still fraction
     * hovers at [stillFrac] crosses it either way, so no pause reaches [minGracePeriod] consecutive
     * seconds, nothing ends, and the candidates accumulate for the rest of the session.
     */
    private fun dropDominated(liveCandidates: MutableList<Candidate>, second: Int) {
        if (liveCandidates.size < 2) return
        var kept = 1
        for (index in 1 until liveCandidates.size) {
            val candidate = liveCandidates[index]
            var back = kept - 1
            var dominated = false
            while (back >= maxOf(0, kept - LOOK_BACK) && !dominated) {
                val earlier = liveCandidates[back]
                dominated = earlier.stillFractionAt(second) > candidate.stillFractionAt(second) &&
                    earlier.isWindow() &&
                    earlier.lowestHr <= candidate.lowestHr
                back -= 1
            }
            if (dominated) continue
            liveCandidates[kept++] = candidate
        }
        while (liveCandidates.size > kept) liveCandidates.removeAt(liveCandidates.size - 1)
    }

    /** Ends and removes every candidate [hasEnded] accepts, reporting the ones that measured a rate. */
    private inline fun endWhere(
        liveCandidates: MutableList<Candidate>,
        reports: MutableList<QuietWindowReport>,
        hasEnded: (Candidate) -> Boolean,
    ) {
        var kept = 0
        for (index in liveCandidates.indices) {
            val candidate = liveCandidates[index]
            if (hasEnded(candidate)) {
                if (candidate.isWindow()) reports += candidate.report()
                continue
            }
            liveCandidates[kept++] = candidate
        }
        while (liveCandidates.size > kept) liveCandidates.removeAt(liveCandidates.size - 1)
    }

    /** Whether [second] interrupts a stretch: excessive motion, or a silence too long to have been filled. */
    private fun interrupts(second: Int): Boolean = !still[second] || hr[second].isNaN()

    /**
     * A stretch under construction, beginning at [start].
     *
     * Not a data class. It mutates, and a candidate is identified by which object it is rather than
     * by what it currently holds, so a generated `equals` and `hashCode` would say two distinct
     * stretches are the same one whenever their fields happen to agree.
     */
    private inner class Candidate(val start: Int) {

        /** The lowest bpm read over `[start, second]`, NaN before the first reading. A rate. */
        var lowestHr = Double.NaN
            private set

        /** [lowestHr] as it stood at [lastQualifyingSecond], NaN where there is none. A rate. */
        private var lowestHrMeasuredAt = Double.NaN

        /** The most recent second this candidate qualified at, or -1. An index. */
        private var lastQualifyingSecond = -1

        /** The first second of the current unbroken run of paused seconds, or -1 while qualifying. */
        private var pausedSinceSecond = -1

        /** Advances this candidate to [second]. False once it has ended. */
        fun observe(second: Int): Boolean {
            val reading = hr[second]
            if (!reading.isNaN() && (lowestHr.isNaN() || reading < lowestHr)) lowestHr = reading
            if (qualifiesAt(second)) {
                lastQualifyingSecond = second
                lowestHrMeasuredAt = lowestHr
                pausedSinceSecond = -1
                return true
            }
            if (pausedSinceSecond < 0) pausedSinceSecond = second
            val pausedForSeconds = second - pausedSinceSecond + 1
            return !(
                pausedForSeconds > minGracePeriod &&
                    requiredRecoverySecondsAt(second) > spanSeconds(second)
                )
        }

        /** Whether this candidate has qualified for long enough to measure a rate. */
        fun isWindow(): Boolean =
            lastQualifyingSecond >= 0 && lastQualifyingSecond - start + 1 >= minWindowS

        /** Adds this candidate to whichever of [census]'s four counts it belongs to at [second]. */
        fun countIn(census: DynamicHrrSignals.QuietWindows, second: Int) {
            val counts = when {
                isWindow() -> if (pausedSinceSecond < 0) census.liveWindows else census.pausedWindows
                else -> if (pausedSinceSecond < 0) census.liveCandidates else census.pausedCandidates
            }
            counts[second] += 1
        }

        fun report(): QuietWindowReport =
            QuietWindowReport(start, lastQualifyingSecond, lowestHrMeasuredAt)

        private fun spanSeconds(second: Int): Int = second - start + 1

        private fun stillSecondsInSpan(second: Int): Int = stillCount[second + 1] - stillCount[start]

        private fun beatSecondsInSpan(second: Int): Int = beatCount[second + 1] - beatCount[start]

        fun stillFractionAt(second: Int): Double =
            stillSecondsInSpan(second).toDouble() / spanSeconds(second)

        private fun hasBeatFractionAt(second: Int): Double =
            beatSecondsInSpan(second).toDouble() / spanSeconds(second)

        private fun qualifiesAt(second: Int): Boolean =
            stillFractionAt(second) >= stillFrac && hasBeatFractionAt(second) >= beatFrac

        /**
         * Whether the rate at [second] stands more than [hrRangeBpm] above [lowestHr].
         *
         * [lowestHr] is what this candidate would report as the wearer's basal rate. Once the rate
         * stands this far above it, it is a rate the wearer has left, and a stretch that kept
         * growing around it would report a basal rate the wearer is no longer at.
         */
        fun isTooFarAboveItsLowest(second: Int): Boolean = hr[second] - lowestHr > hrRangeBpm

        /**
         * How many further consecutive qualifying seconds this candidate needs before it qualifies.
         *
         * What ends the candidate is needing strictly more of them than it has already run. An
         * equality would instead end any candidate whose beat count is still zero, which at a
         * [beatFrac] of one half is every candidate that has not yet recorded a beat — a statement
         * about when beats happened to be sampled rather than about how the wearer rested.
         */
        private fun requiredRecoverySecondsAt(second: Int): Double {
            val spanSeconds = spanSeconds(second)
            fun requiredSeconds(threshold: Double, matchingSeconds: Int): Double =
                (threshold * spanSeconds - matchingSeconds) / (1 - threshold)
            return maxOf(
                requiredSeconds(stillFrac, stillSecondsInSpan(second)),
                requiredSeconds(beatFrac, beatSecondsInSpan(second)),
            )
        }
    }
}

/**
 * What one ended candidate measured, and over which seconds it measured it.
 *
 * A data class, unlike the candidate it came from: nothing about it changes after it is built.
 */
private data class QuietWindowReport(
    val start: Int,
    val lastQualifyingSecond: Int,
    val lowestHrMeasuredAt: Double,
) {

    fun overlaps(other: QuietWindowReport): Boolean =
        start <= other.lastQualifyingSecond && other.start <= lastQualifyingSecond

    /** Whether [other] beats this one: a lower rate, or an equal rate measured over a later stretch. */
    fun losesTo(other: QuietWindowReport): Boolean =
        other.lowestHrMeasuredAt < lowestHrMeasuredAt ||
            (other.lowestHrMeasuredAt == lowestHrMeasuredAt && other.start > start)
}

/**
 * The values of one sliding window, held in sorted order so its median is a lookup.
 *
 * A window ends at every second of a day, and it differs from the second before it by one value
 * arriving and one departing. Sorting each window from scratch throws that away; holding the order
 * across seconds costs one insertion into an already ordered array instead.
 *
 * It never holds a NaN. Adding or removing one does nothing, because a NaN has no place in the
 * ordering the walk depends on.
 *
 * At the widths this file asks for, eleven and twenty, the place for a value is found faster by
 * walking the window than by bisecting it and copying a block. The walk compares through
 * [java.lang.Double.compare] rather than with `<`, so it orders -0.0 before 0.0 exactly as
 * `Arrays.sort` does, and which of two values equal under `==` is removed does not depend on how
 * they were inserted.
 */
private class SortedWindow(capacity: Int) {

    private val values = DoubleArray(capacity)

    /** How many values the window holds. */
    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(value: Double) {
        if (value.isNaN()) return
        var at = size
        while (at > 0 && java.lang.Double.compare(values[at - 1], value) > 0) {
            values[at] = values[at - 1]
            at -= 1
        }
        values[at] = value
        size += 1
    }

    /** Drops one value equal to [value], which the window must hold. */
    fun remove(value: Double) {
        if (value.isNaN()) return
        // Which of several equal values goes is not a choice to make: each leaves the same window.
        var at = 0
        while (java.lang.Double.compare(values[at], value) != 0) at += 1
        while (at < size - 1) {
            values[at] = values[at + 1]
            at += 1
        }
        size -= 1
    }

    /** The middle value, the mean of the middle two of an even count, or NaN while empty. */
    fun median(): Double {
        if (size == 0) return Double.NaN
        val middle = size / 2
        return if (size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }
}

/**
 * A weight on each beat of heart rate above a wearer's resting rate.
 *
 * The beats just above rest are the least likely to be effort — a posture change, a warm room, a
 * meal — and they occupy most of a day, so counting them in full lets them dominate a day's active
 * energy. Each beat is instead worth a share that rises band by band until the rate is well clear of
 * rest, and [excess] integrates that weight between two rates. Weighting each beat rather than the
 * whole span keeps the result continuous: two rates either side of a band edge differ by the beat
 * between them and nothing else.
 *
 * The bands are pinned to the resting rate and do not move with the basal rate the day measures.
 */
internal class HrReserveRamp private constructor(private val restingHr: Double, private val bandBpm: Double) {

    /**
     * The weighted beats between [from] and [to], negative when [to] is the lower rate.
     *
     * Beats below the resting rate carry the lowest band's weight, so the value rises with the rate
     * everywhere and a basal rate measured under the resting rate is no special case.
     */
    fun excess(from: Double, to: Double): Double = weighted(to) - weighted(from)

    /** The weighted beats between the resting rate and [bpm]. */
    private fun weighted(bpm: Double): Double {
        var remaining = bpm - restingHr
        var total = 0.0
        for (weight in WEIGHTS) {
            if (remaining <= bandBpm) return total + weight * remaining
            total += weight * bandBpm
            remaining -= bandBpm
        }
        return total + remaining
    }

    companion object {

        /** What a beat in each band is worth; a beat above the last band is worth one whole beat. */
        private val WEIGHTS = doubleArrayOf(0.25, 0.50, 0.75)

        /**
         * A ramp of [bandBpm]-wide bands above [restingHr], or null where there is nothing to build
         * one on: no resting rate to pin the bands to, or bands of no width. Null rather than a ramp
         * that charges every beat in full, so the two cases cannot differ in their last bits.
         */
        fun of(restingHr: Double?, bandBpm: Double): HrReserveRamp? =
            if (restingHr == null || restingHr <= 0.0 || bandBpm <= 0.0) null
            else HrReserveRamp(restingHr, bandBpm)
    }
}
