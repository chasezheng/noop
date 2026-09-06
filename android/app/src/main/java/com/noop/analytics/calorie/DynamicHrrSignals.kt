package com.noop.analytics.calorie

import java.util.Arrays
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
     * Blocks are [blockS] seconds long and are cut on absolute time ([windowStartUtc] anchors index
     * 0), so where they fall does not depend on when the wearer put the strap on. Nothing outside a
     * block is read or written, so a burst at one block's edge cannot flatten the next.
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
        val scratch = DoubleArray(minOf(blockS, values.size))
        for (session in sessions) {
            var blockStart = session.first
            while (blockStart <= session.last) {
                val blockLast = (Math.floorDiv(windowStartUtc + blockStart, blockS.toLong()) + 1L) *
                    blockS - windowStartUtc - 1L
                val blockEnd = minOf(session.last.toLong(), blockLast).toInt()
                clipBlock(values, out, blockStart..blockEnd, percentile, scratch)
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
     * The lowest heart rate each quiet stretch measured, at the last second that stretch held.
     *
     * A stretch runs while it is still for [stillFrac] of its seconds and beat-covered for
     * [beatFrac] of them. Both are shares of the whole stretch, so one restless second does not end
     * it: the stretch PAUSES, and has [repairGraceS] seconds for the shares to climb back over their
     * thresholds as later seconds dilute the bad ones. A pause that repairs leaves the stretch
     * running and its seconds inside it, and the next pause gets the full grace again.
     *
     * A stretch closes three ways: a pause that outlasts the grace, a heart rate standing more than
     * [hrRangeBpm] above the lowest of the stretch behind it, and the end of the wear session. A
     * closed stretch measures the lowest rate it read while the shares held, reported at the last
     * second they did, and only where that much of it ran at least [minWindowS] seconds. Whatever
     * follows begins a new stretch.
     *
     * One value per stretch, not one per second: the rate a stretch measures is the lowest of the
     * whole of it, which is not known until it is over.
     */
    fun quietWindowMinHr(
        hr: DoubleArray,
        sessions: List<IntRange>,
        still: BooleanArray,
        beat: BooleanArray,
        minWindowS: Int,
        hrRangeBpm: Double,
        stillFrac: Double,
        beatFrac: Double,
        repairGraceS: Int,
    ): DoubleArray {
        val out = DoubleArray(hr.size) { Double.NaN }
        val stillCount = prefixCount(still)
        val beatCount = prefixCount(beat)
        for (session in sessions) {
            var start = session.first
            // The lowest rate of `[start, r]`, and the same as of [lastLive]. A stretch only ever
            // grows, so a running lowest is exact and nothing has to be scanned twice.
            var lowest = Double.NaN
            var measured = Double.NaN
            var lastLive = -1
            var pausedSince = -1

            fun close() {
                if (lastLive >= 0 && lastLive - start + 1 >= minWindowS) out[lastLive] = measured
            }

            for (r in session) {
                // Read against the stretch behind this second, before this second joins it.
                if (hr[r] - lowest > hrRangeBpm) {
                    close()
                    start = r
                    lowest = Double.NaN
                    measured = Double.NaN
                    lastLive = -1
                    pausedSince = -1
                }
                if (!hr[r].isNaN() && (lowest.isNaN() || hr[r] < lowest)) lowest = hr[r]
                val span = r - start + 1
                val stillShare = (stillCount[r + 1] - stillCount[start]).toDouble() / span
                val beatShare = (beatCount[r + 1] - beatCount[start]).toDouble() / span
                if (stillShare >= stillFrac && beatShare >= beatFrac) {
                    lastLive = r
                    measured = lowest
                    pausedSince = -1
                } else {
                    if (pausedSince < 0) pausedSince = r
                    if (r - pausedSince + 1 > repairGraceS) {
                        close()
                        start = r + 1
                        lowest = Double.NaN
                        measured = Double.NaN
                        lastLive = -1
                        pausedSince = -1
                    }
                }
            }
            close()
        }
        return out
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
        var count = 0
        for (i in block) {
            if (values[i].isNaN()) continue
            scratch[count++] = values[i]
        }
        if (count == 0) return
        scratch.sort(0, count)
        // Rank counts from one, the array from zero.
        val rank = Math.ceil(percentile * count).toInt().coerceIn(1, count)
        val ceiling = scratch[rank - 1]
        for (i in block) {
            if (!values[i].isNaN() && values[i] > ceiling) out[i] = ceiling
        }
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

/**
 * The values of one sliding window, held in sorted order so its median is a lookup.
 *
 * A window ends at every second of a day, and it differs from the second before it by one value
 * arriving and one departing. Sorting each window from scratch throws that away; holding the order
 * across seconds costs a binary search and a shift instead.
 *
 * It never holds a NaN. Adding or removing one does nothing, because a NaN has no place in the
 * ordering the search depends on.
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
        val found = Arrays.binarySearch(values, 0, size, value)
        val at = if (found >= 0) found else -(found + 1)
        values.copyInto(values, at + 1, at, size)
        values[at] = value
        size += 1
    }

    /** Drops one value equal to [value], which the window must hold. */
    fun remove(value: Double) {
        if (value.isNaN()) return
        // Which of several equal values goes is not a choice to make: each leaves the same window.
        val at = Arrays.binarySearch(values, 0, size, value)
        values.copyInto(values, at, at + 1, size)
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
