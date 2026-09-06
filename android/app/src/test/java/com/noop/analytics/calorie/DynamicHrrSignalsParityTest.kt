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
    fun quietWindow_matchesAMinimumScannedAfreshAtEverySecond() {
        val rng = Random(20_260_904)
        repeat(400) { case ->
            val shape = Shape.random(rng)
            val still = BooleanArray(shape.values.size) { rng.nextInt(10) < 8 }
            val beat = BooleanArray(shape.values.size) { rng.nextInt(10) < 7 }
            val minWindowS = rng.nextInt(1, 40)
            val hrRangeBpm = rng.nextDouble(0.0, 12.0)
            val stillFrac = rng.nextDouble(0.0, 1.0)
            val beatFrac = rng.nextDouble(0.0, 1.0)
            val repairGraceS = rng.nextInt(0, 60)

            assertSameBits(
                "case $case",
                referenceQuietWindowMinHr(
                    shape.values, shape.sessions, still, beat, minWindowS, hrRangeBpm, stillFrac,
                    beatFrac, repairGraceS,
                ),
                DynamicHrrSignals.quietWindowMinHr(
                    shape.values, shape.sessions, still, beat, minWindowS, hrRangeBpm, stillFrac,
                    beatFrac, repairGraceS,
                ),
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

    private fun referenceQuietWindowMinHr(
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
        for (session in sessions) {
            var start = session.first
            var lastLive = -1
            var pausedSince = -1

            fun close() {
                if (lastLive >= 0 && lastLive - start + 1 >= minWindowS) {
                    out[lastLive] = lowestOver(hr, start, lastLive)
                }
            }

            for (r in session) {
                if (hr[r] - lowestOver(hr, start, r - 1) > hrRangeBpm) {
                    close()
                    start = r
                    lastLive = -1
                    pausedSince = -1
                }
                val span = r - start + 1
                val stillShare = trueCount(still, start, r).toDouble() / span
                val beatShare = trueCount(beat, start, r).toDouble() / span
                if (stillShare >= stillFrac && beatShare >= beatFrac) {
                    lastLive = r
                    pausedSince = -1
                } else {
                    if (pausedSince < 0) pausedSince = r
                    if (r - pausedSince + 1 > repairGraceS) {
                        close()
                        start = r + 1
                        lastLive = -1
                        pausedSince = -1
                    }
                }
            }
            close()
        }
        return out
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
