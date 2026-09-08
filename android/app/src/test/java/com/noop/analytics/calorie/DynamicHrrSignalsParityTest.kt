package com.noop.analytics.calorie

import java.lang.Double.doubleToRawLongBits
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sliding stages held against a plain reading of what they are defined to produce.
 *
 * Three stages carry their window from one second to the next instead of rebuilding it. That is an
 * arithmetic claim about every shape of input, and the corpus and oracle fixtures cannot make it:
 * between them they hold one contiguous shape, no gap inside a wear session, and a basal rate the
 * ratchet only ever meets where the signal is flat. Each test here therefore generates the shapes
 * the fixtures lack and compares raw bits, so a moved last bit fails as loudly as a moved reading.
 *
 * The references below are the definitions in the production docstrings, written the slow way. They
 * stop being worth their runtime once a release has shipped the sliding versions.
 */
class DynamicHrrSignalsParityTest {

    @Test
    fun hampel_matchesAMedianTakenAfreshAtEverySecond() {
        val rng = Random(20_260_902)
        repeat(400) { case ->
            val shape = Shape.random(rng)
            val radiusS = rng.nextInt(1, 31)
            val sigmas = rng.nextDouble(0.5, 4.0)

            assertSameBits(
                "case $case",
                referenceHampel(shape.values, shape.sessions, radiusS, sigmas),
                DynamicHrrSignals.hampel(shape.values, shape.sessions, radiusS, sigmas),
            )
        }
    }

    @Test
    fun trailingMedian_matchesAMedianTakenAfreshAtEverySecond() {
        val rng = Random(20_260_903)
        repeat(400) { case ->
            val shape = Shape.random(rng)
            val widthS = rng.nextInt(1, 62)
            val minSamples = rng.nextInt(1, widthS + 1)

            assertSameBits(
                "case $case",
                referenceTrailingMedian(shape.values, shape.sessions, widthS, minSamples),
                DynamicHrrSignals.trailingMedian(shape.values, shape.sessions, widthS, minSamples),
            )
        }
    }

    @Test
    fun quietWindow_matchesCandidatesScannedAfreshAtEverySecond() {
        val rng = Random(20_260_904)
        repeat(400) { case ->
            val shape = Shape.random(rng)
            val still = BooleanArray(shape.values.size) { rng.nextInt(10) < 8 }
            val beat = BooleanArray(shape.values.size) { rng.nextInt(10) < 7 }
            val minWindowS = rng.nextInt(1, 40)
            val hrRangeBpm = rng.nextDouble(0.0, 12.0)
            val stillFrac = rng.nextDouble(0.0, 1.0)
            val beatFrac = rng.nextDouble(0.0, 1.0)
            val minGracePeriod = rng.nextInt(0, 60)

            assertSameBits(
                "case $case",
                referenceQuietWindowMinHr(
                    shape.values, shape.sessions, still, beat, minWindowS, hrRangeBpm, stillFrac,
                    beatFrac, minGracePeriod,
                ),
                DynamicHrrSignals.quietWindows(
                    shape.values, shape.sessions, still, beat, minWindowS, hrRangeBpm, stillFrac,
                    beatFrac, minGracePeriod,
                ).lowestHrAtWindowEnd,
            )
        }
    }

    // ── Generated inputs ──────────────────────────────────────────────────────────────────────

    /** One generated day: a per-second series and the wear sessions cut out of it. */
    private class Shape(val values: DoubleArray, val sessions: List<IntRange>) {

        companion object {

            /**
             * A series of runs of one value and runs of NaN, cut into up to four wear sessions.
             *
             * Values come from a narrow set of whole and half numbers so that equal readings, and
             * runs of them, are common rather than a coincidence.
             */
            fun random(rng: Random): Shape {
                val size = rng.nextInt(1, 201)
                val values = DoubleArray(size)
                var i = 0
                while (i < size) {
                    val runLength = minOf(size - i, rng.nextInt(1, 26))
                    val value = if (rng.nextInt(10) < 3) Double.NaN else 50.0 + rng.nextInt(21) * 0.5
                    for (j in i until i + runLength) values[j] = value
                    i += runLength
                }
                return Shape(values, sessionsOver(size, rng))
            }

            private fun sessionsOver(size: Int, rng: Random): List<IntRange> {
                val cuts = sortedSetOf(0, size)
                repeat(rng.nextInt(0, 4)) { cuts.add(rng.nextInt(0, size + 1)) }
                val edges = cuts.toList()
                val out = ArrayList<IntRange>()
                for (k in 0 until edges.size - 1) {
                    // A session of no seconds is not one the rasteriser can produce.
                    if (edges[k] < edges[k + 1]) out.add(edges[k]..(edges[k + 1] - 1))
                }
                return out
            }
        }
    }

    // ── The definitions, written the slow way ─────────────────────────────────────────────────

    private fun referenceHampel(
        values: DoubleArray,
        sessions: List<IntRange>,
        radiusS: Int,
        sigmas: Double,
    ): DoubleArray {
        val out = values.copyOf()
        val median = DoubleArray(values.size) { Double.NaN }
        val deviation = DoubleArray(values.size) { Double.NaN }
        for (session in sessions) {
            for (i in session) {
                median[i] = medianOver(values, session, i - radiusS, i + radiusS)
                if (!values[i].isNaN() && !median[i].isNaN()) deviation[i] = abs(values[i] - median[i])
            }
            for (i in session) {
                val robustSigma = 1.4826 * medianOver(deviation, session, i - radiusS, i + radiusS)
                if (robustSigma > 0.0 && deviation[i] > sigmas * robustSigma) out[i] = median[i]
            }
        }
        return out
    }

    private fun referenceTrailingMedian(
        values: DoubleArray,
        sessions: List<IntRange>,
        widthS: Int,
        minSamples: Int,
    ): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (session in sessions) {
            for (i in session) {
                val held = readingsOver(values, session, i - widthS + 1, i)
                if (held.size >= minSamples) out[i] = middleOf(held)
            }
        }
        return out
    }

    /**
     * The quiet-window rule written the slow way: no prefix sums, and no map of live candidates.
     *
     * Every count is rescanned from the candidate's first second, and the rate it measured is
     * rescanned too, so an error in the production prefix arithmetic cannot hide behind itself.
     */
    private fun referenceQuietWindowMinHr(
        hr: DoubleArray,
        sessions: List<IntRange>,
        still: BooleanArray,
        beat: BooleanArray,
        minWindowS: Int,
        hrRangeBpm: Double,
        stillFrac: Double,
        beatFrac: Double,
        minGracePeriod: Int,
    ): DoubleArray {
        val out = DoubleArray(hr.size) { Double.NaN }
        for (session in sessions) {
            val reports = referenceReportsOver(
                session, hr, still, beat, minWindowS, hrRangeBpm, stillFrac, beatFrac, minGracePeriod,
            )
            for (report in reports) {
                val beaten = reports.any { other ->
                    other !== report &&
                        report.start <= other.lastQualifyingSecond &&
                        other.start <= report.lastQualifyingSecond &&
                        (
                            other.lowestHr < report.lowestHr ||
                                (other.lowestHr == report.lowestHr && other.start > report.start)
                            )
                }
                if (!beaten) out[report.lastQualifyingSecond] = report.lowestHr
            }
        }
        return out
    }

    /** What one reference candidate measured, and over which seconds. */
    private class ReferenceReport(
        val start: Int,
        val lastQualifyingSecond: Int,
        val lowestHr: Double,
    )

    private fun referenceReportsOver(
        session: IntRange,
        hr: DoubleArray,
        still: BooleanArray,
        beat: BooleanArray,
        minWindowS: Int,
        hrRangeBpm: Double,
        stillFrac: Double,
        beatFrac: Double,
        minGracePeriod: Int,
    ): List<ReferenceReport> {
        val liveStarts = ArrayList<Int>()
        val lastQualifying = HashMap<Int, Int>()
        val pausedSince = HashMap<Int, Int>()
        val reports = ArrayList<ReferenceReport>()

        fun end(start: Int) {
            val last = lastQualifying[start] ?: -1
            if (last >= 0 && last - start + 1 >= minWindowS) {
                reports += ReferenceReport(start, last, lowestOver(hr, start, last))
            }
            lastQualifying.remove(start)
            pausedSince.remove(start)
            liveStarts.remove(start)
        }

        for (second in session) {
            // A stretch the rate has climbed too far above ends before this second joins it, so
            // that its replacement can begin at this second.
            for (start in liveStarts.toList()) {
                if (hr[second] - lowestOver(hr, start, second - 1) > hrRangeBpm) end(start)
            }
            val afterBadSecond =
                second > session.first && (!still[second - 1] || hr[second - 1].isNaN())
            if (second == session.first || afterBadSecond || liveStarts.isEmpty()) {
                if (second !in liveStarts) liveStarts += second
            }
            for (start in liveStarts.toList()) {
                val spanSeconds = second - start + 1
                val stillSeconds = trueCount(still, start, second)
                val beatSeconds = trueCount(beat, start, second)
                if (stillSeconds.toDouble() / spanSeconds >= stillFrac &&
                    beatSeconds.toDouble() / spanSeconds >= beatFrac
                ) {
                    lastQualifying[start] = second
                    pausedSince[start] = -1
                    continue
                }
                if ((pausedSince[start] ?: -1) < 0) pausedSince[start] = second
                val pausedForSeconds = second - pausedSince.getValue(start) + 1
                val requiredRecoverySeconds = maxOf(
                    (stillFrac * spanSeconds - stillSeconds) / (1 - stillFrac),
                    (beatFrac * spanSeconds - beatSeconds) / (1 - beatFrac),
                )
                if (pausedForSeconds > minGracePeriod && requiredRecoverySeconds > spanSeconds) {
                    end(start)
                }
            }
            // A candidate one of the three kept before it dominates is dropped, reporting nothing:
            // that one is more still, has itself reached minWindowS, and has read no higher a rate.
            val kept = ArrayList<Int>()
            for (start in liveStarts.toList()) {
                if (kept.isEmpty()) {
                    kept += start
                    continue
                }
                val stillFraction =
                    trueCount(still, start, second).toDouble() / (second - start + 1)
                val lowest = lowestOver(hr, start, second)
                var dominated = false
                var back = kept.size - 1
                while (back >= maxOf(0, kept.size - 3) && !dominated) {
                    val earlier = kept[back]
                    val earlierLastQualifying = lastQualifying[earlier] ?: -1
                    val earlierStillFraction =
                        trueCount(still, earlier, second).toDouble() / (second - earlier + 1)
                    dominated = earlierStillFraction > stillFraction &&
                        earlierLastQualifying >= 0 &&
                        earlierLastQualifying - earlier + 1 >= minWindowS &&
                        lowestOver(hr, earlier, second) <= lowest
                    back -= 1
                }
                if (dominated) {
                    lastQualifying.remove(start)
                    pausedSince.remove(start)
                    liveStarts.remove(start)
                } else {
                    kept += start
                }
            }
        }
        for (start in liveStarts.toList()) end(start)
        return reports
    }

    /** The lowest reading of `[from, to]`, or NaN where it carries none. */
    private fun lowestOver(hr: DoubleArray, from: Int, to: Int): Double {
        var low = Double.NaN
        for (i in from..to) {
            if (hr[i].isNaN()) continue
            if (low.isNaN() || hr[i] < low) low = hr[i]
        }
        return low
    }

    private fun medianOver(values: DoubleArray, session: IntRange, from: Int, to: Int): Double {
        val held = readingsOver(values, session, from, to)
        return if (held.isEmpty()) Double.NaN else middleOf(held)
    }

    /** The readings of `[from, to]` clamped to [session], ascending, with the missing seconds dropped. */
    private fun readingsOver(values: DoubleArray, session: IntRange, from: Int, to: Int): List<Double> {
        val held = ArrayList<Double>()
        for (i in maxOf(session.first, from)..minOf(session.last, to)) {
            if (!values[i].isNaN()) held.add(values[i])
        }
        held.sort()
        return held
    }

    private fun middleOf(held: List<Double>): Double {
        val middle = held.size / 2
        return if (held.size % 2 == 1) held[middle] else (held[middle - 1] + held[middle]) / 2.0
    }

    private fun trueCount(flags: BooleanArray, from: Int, to: Int): Int {
        var count = 0
        for (i in from..to) if (flags[i]) count += 1
        return count
    }

    private fun assertSameBits(message: String, expected: DoubleArray, actual: DoubleArray) {
        assertEquals(message, expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(
                "$message at second $i",
                doubleToRawLongBits(expected[i]),
                doubleToRawLongBits(actual[i]),
            )
        }
    }
}
