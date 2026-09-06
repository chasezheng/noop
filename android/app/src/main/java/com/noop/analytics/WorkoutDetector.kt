package com.noop.analytics

import com.noop.analytics.calorie.Calories
import com.noop.data.GravitySample
import com.noop.data.HrSample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 * WorkoutDetector.kt — retroactive workout detection from the 1 Hz store.
 *
 * Faithful Kotlin port of StrandAnalytics/WorkoutDetector.swift (verified on macOS),
 * itself ported from server/ingest/app/analysis/exercise.py (+ activity.py).
 *
 * A workout is a SUSTAINED window (≥ MIN_EXERCISE_MIN) of elevated HR (above
 * resting + HR_MARGIN_BPM) AND sustained motion (gravity-derived intensity above
 * MOTION_THRESHOLD). Both gates must hold for a sample to count as active.
 *
 * Per detected bout: avg/peak HR, duration, Edwards zone time-%, mean %HRR,
 * strain (StrainScorer), and estimated calories ([Calories.estimateBoutCalories]).
 *
 * All intensity/energy outputs are APPROXIMATE and not medical advice.
 *
 * Types note: [UserProfile], [ExerciseSession] and [ActivityPoint] live in
 * AnalyticsModels.kt (shared value types) and are NOT redefined here. Inputs are
 * the Room entities com.noop.data.HrSample (ts:Long seconds, bpm:Int) and
 * com.noop.data.GravitySample (ts:Long seconds, x/y/z:Double). All `ts`/`start`/`end`
 * are unix SECONDS as Long. The Swift source used Int seconds.
 */
object WorkoutDetector {

    // ---- Constants (exercise.py) ----

    const val minExerciseMin: Double = 5.0
    const val hrMarginBPM: Double = 15.0
    const val motionThreshold: Double = 0.20
    const val motionSmoothS: Double = 10.0
    const val mergeGapS: Double = 150.0
    const val minIntensityZ2Plus: Double = 0.50
    const val alignToleranceS: Double = 5.0
    const val restingPercentile: Double = 10.0

    /**
     * Second-pass bridge window (#303). Two adjacent active runs separated by a
     * below-motion-threshold gap no longer than this are stitched into one workout —
     * BUT ONLY while HR stays elevated across the gap (see [bridgeRuns]). A sustained
     * endurance effort (e.g. a long bike ride) routinely dips below the motion gate
     * for a few minutes — coasting a descent, a junction, a brief sensor dropout —
     * without the athlete actually resting; [mergeGapS] (150 s) is too tight to ride
     * through those, so the bout used to shatter into many sub-bouts, most of which
     * then fell under [minExerciseMin] and vanished. A genuine rest between two
     * separate workouts is gated out by the HR check, not by this window.
     */
    const val bridgeGapS: Double = 300.0

    // ---- Activity series (activity.py) ----

    /**
     * Per-record motion-intensity series: L2 magnitude of the gravity change vs
     * the previous record. First row → 0. Empty input → []. (GravitySample always
     * carries finite x/y/z, so no dropout sentinel is required here.)
     */
    fun activitySeries(gravity: List<GravitySample>): List<ActivityPoint> {
        if (gravity.isEmpty()) return emptyList()
        val rows = gravity.sortedBy { it.ts }
        val series = ArrayList<ActivityPoint>(rows.size)
        var prev: GravitySample? = null
        for ((i, row) in rows.withIndex()) {
            val intensity: Double
            val p = prev
            if (i == 0) {
                intensity = 0.0
            } else if (p != null) {
                val dx = row.x - p.x
                val dy = row.y - p.y
                val dz = row.z - p.z
                intensity = sqrt(dx * dx + dy * dy + dz * dz)
            } else {
                intensity = 0.0
            }
            series.add(ActivityPoint(ts = row.ts, intensity = intensity))
            prev = row
        }
        return series
    }

    // ---- Helpers ----

    /**
     * Sorted (ts, bpm) pairs.
     *
     * Swift `cleanHR` mapped to `(ts: Int, bpm: Double)`; here the Room [HrSample]
     * already carries an Int bpm, so we keep the rows sorted by ts and read bpm as a
     * Double on demand — equivalent and avoids losing the deviceId needed downstream.
     */
    internal fun cleanHR(hr: List<HrSample>): List<HrSample> = hr.sortedBy { it.ts }

    /** Day resting-HR baseline = nearest-rank RESTING_PERCENTILE of bpm values. */
    internal fun deriveRestingHR(hrSeg: List<HrSample>): Double {
        val bpms = hrSeg.map { it.bpm.toDouble() }.sorted()
        require(bpms.isNotEmpty()) { "deriveRestingHR called with empty segment" }
        val rank = maxOf(1, ceil(restingPercentile / 100.0 * bpms.size.toDouble()).toInt())
        return bpms[rank - 1]
    }

    /**
     * #1545: why a day produced no workout, counted at each gate the detector actually applies.
     *
     * The `effort bout` line explains a bout that EXISTS. It is silent when none does — and "no workouts at
     * all" is the harder report to answer, because every gate looks equally plausible from outside. A
     * reporter with 37 days and zero detected bouts previously had nothing to send that could distinguish
     * "the strap never registered motion" (a WHOOP 4.0 banks it coarsely, #345/#28) from "HR never cleared
     * resting + 15" from "the efforts were real but under five minutes".
     *
     * Counted during the detector's OWN walk, never recomputed alongside it: a funnel free to disagree with
     * the code it describes is worse than no funnel, because it will be believed. Byte-parity twin of Swift
     * `WorkoutDetector.DetectionFunnel`.
     */
    data class DetectionFunnel(
        /** Inputs the day actually had. */
        var hrSamples: Int = 0,
        var motionSamples: Int = 0,
        /** The bar a sample had to clear, in bpm — resting + [hrMarginBPM]. */
        var restingHR: Double? = null,
        var hrFloor: Double? = null,
        /** Motion samples whose smoothed intensity cleared [motionThreshold]. */
        var motionPassed: Int = 0,
        /** Of those, how many had NO HR sample within [alignToleranceS] (a sensor gap, not a quiet body). */
        var hrMissing: Int = 0,
        /** Of those, how many had HR at or below [hrFloor] (moving, but not working). */
        var hrTooLow: Int = 0,
        /** Samples that cleared BOTH gates. */
        var active: Int = 0,
        /** Contiguous runs after gap-merging, and after the #303 HR-gated bridge. */
        var runs: Int = 0,
        var bridged: Int = 0,
        /**
         * The LONGEST and MEAN bridged run, in seconds, before any qualification gate drops anything.
         *
         * They answer how SUBSTANTIAL the best candidate was, which the counts cannot. [droppedShort] is
         * checked first and short-circuits, so [droppedLowIntensity] already implies some run cleared the
         * duration bar — but not by how much, and that is the whole difference between a candidate
         * scraping five minutes and an hour-long effort rejected on intensity. The second is worth
         * investigating; the first is a walk.
         *
         * From the field log that motivated this: 22 days of `kept=0`, 1271 runs that never reached
         * [minExerciseMin] and 390 that did and failed the intensity bar instead. The counts said the
         * duration gate was not the whole story; only [longestRunS] says whether what survived it was a
         * five-minute stroll or something that should have scored.
         *
         * READ [longestRunS] AGAINST 290 s, NOT 300. The gate is `minDurS - motionSmoothS`
         * (5 min − 10 s), because the smoothing window costs a run its first samples. Comparing against
         * a round five minutes misjudges everything in the 290..300 window — a 295 s run cleared the
         * gate and would look as though it had not.
         */
        var longestRunS: Int = 0,
        var meanRunS: Int = 0,
        /** Runs rejected by each qualification gate, and the survivors. */
        var droppedShort: Int = 0,
        var droppedNoHR: Int = 0,
        var droppedLowIntensity: Int = 0,
        var kept: Int = 0,
    )

    /**
     * #1545: the always-on per-day line naming where the detector lost every candidate workout.
     *
     * Byte-identical string to the Swift twin. No PII: a day key and counts, plus the two bpm thresholds the
     * day was measured against — the same privacy class as the sibling `sleep day=` line.
     */
    fun detectionFunnelLine(day: String, f: DetectionFunnel): String =
        "workout detect day=$day hr=${f.hrSamples} motion=${f.motionSamples} " +
            "restHR=${round0(f.restingHR)} floor=${round0(f.hrFloor)} " +
            "motionOK=${f.motionPassed} hrMissing=${f.hrMissing} hrTooLow=${f.hrTooLow} " +
            "active=${f.active} runs=${f.runs} bridged=${f.bridged} " +
            "longestRunS=${f.longestRunS} meanRunS=${f.meanRunS} " +
            "short=${f.droppedShort} noHR=${f.droppedNoHR} lowIntensity=${f.droppedLowIntensity} " +
            "kept=${f.kept}"

    /**
     * #1545: how much of [start]..[end] the HR sensor actually covered, as a percentage of
     * [bucketSeconds]-wide buckets holding at least one reading.
     *
     * Bucketed rather than sample-counted on purpose. A WHOOP 5/MG sends live HR only about every 30 s, so
     * counting samples against a 1 Hz expectation would report ~3% for a perfectly captured bout, which is
     * worse than no number. A bucket is either seen or not, so a 30 s cadence reads as full coverage and a
     * genuine dropout reads as the gap it is. Byte-parity twin of Swift `hrCoveragePct`.
     */
    fun hrCoveragePct(sampleTs: List<Long>, start: Long, end: Long, bucketSeconds: Long = 60L): Double? {
        if (end <= start || bucketSeconds <= 0) return null
        val buckets = maxOf(1L, ((end - start) + bucketSeconds - 1) / bucketSeconds)
        val seen = HashSet<Long>()
        for (ts in sampleTs) if (ts in start until end) seen.add((ts - start) / bucketSeconds)
        return seen.size.toDouble() / buckets.toDouble() * 100.0
    }

    /**
     * #1545: the always-on per-bout line naming what this workout's Effort was actually scored against.
     *
     * HRmax is the single biggest determinant of an Effort score -- it sets every zone boundary, so being
     * wrong by a few bpm can move real work across the 50% floor and score it zero -- and until this line
     * existed a user could not see which number had been used, or whether it came from their own setting
     * or an age formula. Working that out previously meant reversing the arithmetic from the displayed
     * score, which is what #1545 took to diagnose.
     *
     * No PII: a day key, a duration, bpm and percentages. Byte-identical string to the Swift twin.
     */
    fun boutCalibrationLine(
        day: String, durMin: Int, hrmax: Double?, hrmaxSource: String,
        avgHRRPct: Double?, hrCoveragePct: Double?, strain: Double?,
    ): String =
        "effort bout day=$day durMin=$durMin hrmax=${round0(hrmax)} src=$hrmaxSource " +
            "avgHRR=${round0(avgHRRPct)} cover=${round0(hrCoveragePct)} effort=${round1(strain)}"

    /**
     * The two numeric formatters this line uses, written as integer arithmetic over the value's MAGNITUDE
     * rather than %.0f / %.1f.
     *
     * Three things this shape avoids, all of which would break a line whose entire job is being comparable
     * between two users' logs — and between an iOS log and an Android one:
     *
     * - The positive tie. C printf (Swift) breaks a rounding tie to even; Java's String.format
     *   (Kotlin) breaks it up. A bout at exactly 52.5% HRR would print 52 on iOS and 53 on Android.
     * - The negative tie. Swift's .rounded() is half-AWAY-from-zero and Java's Math.round is
     *   half-UP, so they disagree on -4.5 (-5 vs -4). Rounding abs(v) and re-applying the sign makes the
     *   two identical in both directions; it also keeps the minus sign, which integer / and % truncating
     *   toward zero would otherwise drop (-0.4 printing as 0.4).
     * - The trap. Swift's Int(_: Double) CRASHES on a finite value past Int.max while Kotlin's
     *   Math.round silently saturates to Long.MAX_VALUE. Today's caller can't produce one (the detector
     *   gates maxHR > restingHR before computing %HRR), but this is public API, and a diagnostic that kills
     *   the process is the worst possible way for one to fail. Past the bound both platforms print nil,
     *   which is also the more honest answer: such a value is not a heart rate, a percentage or an Effort.
     */
    internal const val PRINTABLE_MAGNITUDE_LIMIT = 1e15

    internal fun round0(v: Double?): String {
        if (v == null || !v.isFinite() || abs(v) >= PRINTABLE_MAGNITUDE_LIMIT) return "nil"
        return (if (v < 0) "-" else "") + Math.round(abs(v)).toString()
    }

    internal fun round1(v: Double?): String {
        if (v == null || !v.isFinite() || abs(v) >= PRINTABLE_MAGNITUDE_LIMIT) return "nil"
        val t = Math.round(abs(v) * 10.0)
        return "${if (v < 0) "-" else ""}${t / 10}.${t % 10}"
    }

    /**
     * Value whose ts is nearest to [ts] within [tol] seconds, else null. Ties go
     * to the later timestamp (matches the Python <= behaviour).
     */
    internal fun nearest(sortedTs: List<Long>, values: List<Double>, ts: Long, tol: Double): Double? {
        if (sortedTs.isEmpty()) return null
        // bisect_left
        var lo = 0
        var hi = sortedTs.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sortedTs[mid] < ts) lo = mid + 1 else hi = mid
        }
        val i = lo
        var bestV: Double? = null
        var bestD = tol
        for (j in intArrayOf(i - 1, i)) {
            if (j in sortedTs.indices) {
                val d = abs((sortedTs[j] - ts).toDouble())
                if (d <= bestD) {
                    bestD = d
                    bestV = values[j]
                }
            }
        }
        return bestV
    }

    /** Trailing rolling mean (over window_s) of intensities (all finite here). */
    internal fun smoothedIntensity(motion: List<ActivityPoint>, windowS: Double): List<Double> {
        val ts = motion.map { it.ts }
        val raw = motion.map { if (it.intensity.isFinite()) it.intensity else 0.0 }
        val out = ArrayList<Double>(motion.size)
        var lo = 0
        var running = 0.0
        for (i in motion.indices) {
            running += raw[i]
            while ((ts[i] - ts[lo]).toDouble() > windowS) {
                running -= raw[lo]
                lo += 1
            }
            out.add(running / (i - lo + 1).toDouble())
        }
        return out
    }

    /** Per-bout Edwards zone breakdown (%) + mean %HRR. APPROXIMATE. */
    internal fun boutIntensity(
        hrSeries: List<HrSample>,
        restingHR: Double,
        maxHR: Double,
    ): Pair<Map<Int, Double>, Double?> {
        if (hrSeries.isEmpty() || maxHR <= restingHR) return emptyMap<Int, Double>() to null
        val hrReserve = maxHR - restingHR
        val zoneCounts = HashMap<Int, Int>()
        for (z in 0..5) zoneCounts[z] = 0
        val hrrVals = ArrayList<Double>(hrSeries.size)
        for (r in hrSeries) {
            val bpm = r.bpm.toDouble()
            val z = StrainScorer.zoneWeight(bpm, restingHR, hrReserve)
            zoneCounts[z] = (zoneCounts[z] ?: 0) + 1
            hrrVals.add(StrainScorer.pctHRR(bpm, restingHR, hrReserve))
        }
        val n = hrSeries.size.toDouble()
        val zonePct = HashMap<Int, Double>()
        for ((z, c) in zoneCounts) {
            zonePct[z] = round1(c.toDouble() / n * 100.0)
        }
        val avgHRR = round1(hrrVals.sum() / n)
        return zonePct to avgHRR
    }

    /** Round to one decimal place. All inputs here are non-negative (matches Swift `.rounded()`). */
    private fun round1(v: Double): Double = (v * 10).roundToLong() / 10.0

    /**
     * Second-pass merge over raw active runs (#303).
     *
     * Stitch run `i+1` onto the current span when the inter-run gap (start of the
     * next minus end of the current) is ≤ [bridgeGapS] AND HR stays elevated across
     * that gap — i.e. the athlete kept working through a brief motion lull rather than
     * resting. "Elevated" = the mean of the HR samples strictly inside the gap is
     * still above [hrFloor] (resting + HR_MARGIN_BPM). If the gap carries NO HR
     * samples it is treated as a same-effort sensor dropout and bridged; a real rest
     * always lands HR samples in the gap (the strap streams 1 Hz), so it fails the
     * elevation test and the two workouts stay separate. Runs must arrive sorted by
     * start (they do — built from a sorted timeline).
     */
    internal fun bridgeRuns(
        runs: List<Pair<Long, Long>>,
        hrSeg: List<HrSample>,
        hrFloor: Double,
    ): List<Pair<Long, Long>> {
        if (runs.size <= 1) return runs
        val merged = ArrayList<Pair<Long, Long>>()
        var curStart = runs[0].first
        var curEnd = runs[0].second
        for (k in 1 until runs.size) {
            val next = runs[k]
            val gap = (next.first - curEnd).toDouble()
            var bridge = false
            if (gap <= bridgeGapS) {
                // HR samples strictly between the two runs (the lull itself).
                val gapHR = hrSeg.filter { it.ts > curEnd && it.ts < next.first }.map { it.bpm.toDouble() }
                bridge = if (gapHR.isEmpty()) {
                    true // sensor dropout mid-effort → same workout
                } else {
                    val meanGapHR = gapHR.sum() / gapHR.size.toDouble()
                    meanGapHR > hrFloor // still working → same workout
                }
            }
            if (bridge) {
                curEnd = maxOf(curEnd, next.second)
            } else {
                merged.add(curStart to curEnd)
                curStart = next.first
                curEnd = next.second
            }
        }
        merged.add(curStart to curEnd)
        return merged
    }

    /**
     * #148: back-date a confirmed run's start over the warm-up. Motion leads HR at the onset of
     * the first effort — cardiac warm-up climbs over minutes, so the HR-AND-motion gate clips the
     * leading "moving but HR not yet elevated" stretch (the reporter's first 10-15 min). Once a run
     * has ALREADY QUALIFIED on its HR-elevated core, extend the start backward across contiguous
     * above-[motionThreshold] samples, stopping at the first motion gap > [mergeGapS] (a real pause)
     * or the series start. Same motion gate as detection — recovers the warm-up without inventing
     * activity, and can't bridge a genuine rest into the workout. [coreStart] is an active-sample ts,
     * so it exists in [motionTs]; [smooth] is index-aligned to [motionTs].
     */
    internal fun backdatedStart(coreStart: Long, motionTs: List<Long>, smooth: List<Double>): Long {
        // indexOfFirst (not binarySearch): mirrors Swift's firstIndex exactly, so duplicate same-second
        // motion timestamps resolve to the SAME index on both platforms (byte-parity).
        var i = motionTs.indexOfFirst { it >= coreStart }
        if (i !in motionTs.indices) return coreStart
        var start = coreStart
        var prevTs = motionTs[i]
        while (i > 0) {
            i--
            if (smooth[i] <= motionThreshold) break                    // motion dropped → warm-up start
            if ((prevTs - motionTs[i]).toDouble() > mergeGapS) break   // real pause → stop
            start = motionTs[i]
            prevTs = motionTs[i]
        }
        return start
    }

    // ---- Public API ----

    /**
     * Detect workouts from the 1 Hz HR + gravity store.
     *
     * @param hr heart-rate stream (required; empty → []).
     * @param gravity gravity stream (required; empty → []).
     * @param restingHR day resting-HR baseline (bpm). null → derived as the 10th
     *   percentile of the day's HR.
     * @param maxHR HRmax (bpm). null → estimated via StrainScorer.estimateHRmax.
     * @param age used only for the Tanaka fallback when maxHR is null.
     * @param profile when provided, per-bout calories are estimated.
     */
    fun detect(
        hr: List<HrSample>,
        gravity: List<GravitySample>,
        restingHR: Double? = null,
        maxHR: Double? = null,
        age: Double? = null,
        profile: UserProfile? = null,
        // #1545: TRIMP recipe for each bout's Effort. Defaults to EDWARDS so every existing caller and
        // test is byte-identical; the app threads the user's choice so a bout and the day it sits in are
        // never scored by different recipes, which would be worse than either one being "wrong".
        effortMethod: StrainScorer.Method = StrainScorer.Method.EDWARDS,
        // #1545: receives the gate-by-gate counts for THIS call. null (the default) keeps every existing
        // caller and test byte-identical — nothing is computed that the detector was not already
        // computing, the counters just record it.
        funnel: ((DetectionFunnel) -> Unit)? = null,
    ): List<ExerciseSession> {
        // try/finally (Swift uses `defer`) so the funnel is reported on EVERY exit, including the early
        // returns below. A day that bails at "no motion rows at all" is precisely the day whose report
        // matters most, and it is the one a happy-path-only emit would stay silent about.
        val f = DetectionFunnel()
        try {
            val hrSeg = cleanHR(hr)
            val motion = activitySeries(gravity)
            f.hrSamples = hrSeg.size
            f.motionSamples = motion.size
            if (hrSeg.isEmpty() || motion.isEmpty()) return emptyList()

            val restHR = restingHR ?: deriveRestingHR(hrSeg)
            val hrFloor = restHR + hrMarginBPM
            f.restingHR = restHR
            f.hrFloor = hrFloor

            val effMaxHR: Double?
            val hrmaxSource: String
            if (maxHR != null) {
                effMaxHR = maxHR
                hrmaxSource = "caller"
            } else {
                val (est, src) = StrainScorer.estimateHRmax(hrSeg.map { it.bpm.toDouble() }, age)
                effMaxHR = if (est == 0.0) null else est
                hrmaxSource = src
            }

            val hrTs = hrSeg.map { it.ts }
            val hrBpm = hrSeg.map { it.bpm.toDouble() }
            val smooth = smoothedIntensity(motion, motionSmoothS)
            val motionTs = motion.map { it.ts }

            // Walk the gravity timeline; flag samples where BOTH gates hold.
            val activeTs = ArrayList<Long>()
            for (idx in motion.indices) {
                val p = motion[idx]
                val inten = smooth[idx]
                if (inten <= motionThreshold) continue
                f.motionPassed++
                // Split the HR rejection two ways: no sample within tolerance is a SENSOR GAP, a sample at or
                // below the floor is a body that simply was not working. They read identically in a bout count
                // of zero and call for opposite responses.
                val bpm = nearest(hrTs, hrBpm, p.ts, alignToleranceS)
                if (bpm == null) { f.hrMissing++; continue }
                if (bpm <= hrFloor) { f.hrTooLow++; continue }
                activeTs.add(p.ts)
            }
            f.active = activeTs.size
            if (activeTs.isEmpty()) return emptyList()

            // Group contiguous active samples into runs, merging gaps < MERGE_GAP_S.
            val rawRuns = ArrayList<Pair<Long, Long>>()
            var runStart = activeTs[0]
            var prev = activeTs[0]
            for (k in 1 until activeTs.size) {
                val ts = activeTs[k]
                if ((ts - prev).toDouble() > mergeGapS) {
                    rawRuns.add(runStart to prev)
                    runStart = ts
                }
                prev = ts
            }
            rawRuns.add(runStart to prev)
            f.runs = rawRuns.size

            // Second pass (#303): bridge adjacent runs across a brief, still-elevated-HR
            // lull so a sustained effort isn't shattered by coasting / junctions / sensor
            // gaps. Runs over a genuine rest (HR falls to resting) are NOT bridged.
            val runs = bridgeRuns(rawRuns, hrSeg, hrFloor)
            f.bridged = runs.size
            // Measured on the BRIDGED runs, before any qualification gate: this is the shape of what the
            // detector was offered, which is the thing a `kept=0` day has to be judged against.
            if (runs.isNotEmpty()) {
                val durs = runs.map { maxOf(0L, it.second - it.first) }
                f.longestRunS = durs.max().toInt()
                f.meanRunS = (durs.sum() / durs.size).toInt()
            }

            val minDurS = minExerciseMin * 60.0
            val sessions = ArrayList<ExerciseSession>()
            for ((idx, run) in runs.withIndex()) {
                val (start, end) = run
                // Onset latency tolerance equal to the smoothing window.
                if ((end - start).toDouble() < minDurS - motionSmoothS) { f.droppedShort++; continue }
                // Qualify on the HR-elevated CORE (unchanged gates) so the warm-up's low intensity
                // can't dilute a real workout below the zone-2 bar and drop it (#148).
                val core = hrSeg.filter { it.ts in start..end }
                if (core.isEmpty()) { f.droppedNoHR++; continue }

                var zonePct: Map<Int, Double> = emptyMap()
                var avgHRR: Double? = null
                val m = effMaxHR
                if (m != null && m > restHR) {
                    val (zp, ah) = boutIntensity(core, restHR, m)
                    zonePct = zp
                    avgHRR = ah
                }

                // Intensity qualification: require ≥ MIN_INTENSITY_Z2PLUS in zone 2+.
                if (zonePct.isNotEmpty()) {
                    val z2plus = (2..5).sumOf { zonePct[it] ?: 0.0 } / 100.0
                    if (z2plus < minIntensityZ2Plus) { f.droppedLowIntensity++; continue }
                }

                // Qualified → back-date the start over the warm-up and report stats on the full window (#148).
                // Never back-date past the previous run's end: a continuous-motion stretch whose HR dipped to
                // resting BETWEEN two efforts (so bridgeRuns kept them separate) must not overlap the earlier one.
                val floor = if (idx > 0) runs[idx - 1].second + 1 else Long.MIN_VALUE
                val effStart = maxOf(backdatedStart(start, motionTs, smooth), floor)
                val window = hrSeg.filter { it.ts in effStart..end }
                if (window.isEmpty()) { f.droppedNoHR++; continue }
                val bpms = window.map { it.bpm.toDouble() }

                var kcal: Double? = null
                var kj: Double? = null
                if (profile != null) {
                    val (k, j) = Calories.estimateBoutCalories(window, profile, effMaxHR, restHR)
                    kcal = k
                    kj = j
                }

                val avg = bpms.sum() / bpms.size.toDouble()
                val peak = window.maxOf { it.bpm }
                val strain = StrainScorer.strain(
                    window, effMaxHR, restHR, effortMethod, profile?.sex ?: "male")

                sessions.add(
                    ExerciseSession(
                        start = effStart,
                        end = end,
                        avgHR = avg,
                        peakHR = peak,
                        strain = strain,
                        durationS = (end - effStart).toDouble(),
                        zoneTimePct = zonePct,
                        avgHRRPct = avgHRR,
                        hrmax = effMaxHR,
                        hrmaxSource = hrmaxSource,
                        caloriesKcal = kcal,
                        caloriesKJ = kj,
                        hrCoveragePct = hrCoveragePct(window.map { it.ts }, effStart, end),
                    )
                )
            }
            f.kept = sessions.size
            return sessions
        } finally {
            funnel?.invoke(f)
        }
    }
}
