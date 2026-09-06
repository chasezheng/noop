package com.noop.analytics.calorie

import com.noop.analytics.UserProfile
import com.noop.data.GravitySample
import com.noop.data.HrSample

/*
 * CalorieCorpus.kt — a generated characterization corpus for the day-energy models.
 *
 * `DailyMetric.activeKcalEst` is a persisted column with no undo migration: a refactor that moves it
 * by a millionth of a kcal rewrites the wearer's history on the next pass with nothing to notice.
 * Every kcal literal elsewhere in the suite pins ONE day shape under ONE profile, which is why a
 * change can be green everywhere and still move the column on a sparse strap, a knobs-off wearer, or
 * a day whose last sample straddles the window edge.
 *
 * The corpus is generated in-tree rather than loaded from fixture files. A characterization test that
 * silently skips when its fixture is missing is a false green on exactly the change it exists to gate.
 *
 * Shape: day cases x profiles x clocks, each scored by EVERY model plus the stored figure the
 * analytics pass would persist. Every case is named for the property it holds, so a moved line names
 * the input that moved it.
 */

/** One day's raw streams, named for the property the shape holds. */
internal data class CalorieDayCase(
    val name: String,
    /** The day's local midnight in true epoch seconds, as `AnalyticsEngine` derives it. */
    val localMidnightUtc: Long,
    val hr: List<HrSample> = emptyList(),
    val gravity: List<GravitySample> = emptyList(),
    /** Only the `ringMETPresent` shape carries these; no port supplies ring MET today. */
    val ringMET: List<Pair<Long, Double>> = emptyList(),
    val workouts: List<LongRange> = emptyList(),
    /** A second and the beats recorded in it. */
    val beats: List<Pair<Long, Int>> = emptyList(),
)

/** A wearer: the profile the models are built from, plus the two heart-rate anchors. */
internal data class CalorieProfileCase(
    val name: String,
    val profile: UserProfile,
    val hrmax: Double?,
    val restingHR: Double?,
)

/** A reading of the clock, expressed relative to the case's own activity window. */
internal data class CalorieClockCase(val name: String, val nowUtcFor: (Long) -> Long)

internal object CalorieCorpus {

    /** 2026-01-02T00:00:00Z. Local midnight for every case whose name does not say otherwise. */
    const val BASE_MIDNIGHT: Long = 1_767_312_000L

    /** The activity window opens at local midnight. */
    private const val START_HOUR_S: Long = 0L

    private fun windowStart(localMidnightUtc: Long) = localMidnightUtc + START_HOUR_S

    // ── The streams ───────────────────────────────────────────────────────────────────────────

    /**
     * Heart rate over [seconds] at [cadenceS], varying beat to beat.
     *
     * The bpm walks a fixed 95-wide cycle over 55..149 rather than holding one value: a constant rate
     * makes every minute earn the same surplus, which hides an attribution change behind an unmoved
     * total.
     */
    private fun hrRun(from: Long, seconds: Int, cadenceS: Int = 1, phase: Int = 0): List<HrSample> {
        val out = ArrayList<HrSample>(seconds / cadenceS + 1)
        var i = 0
        while (i * cadenceS < seconds) {
            out.add(HrSample(deviceId = "corpus", ts = from + i.toLong() * cadenceS, bpm = 55 + ((i * 37 + phase) % 95)))
            i += 1
        }
        return out
    }

    /**
     * A day recorded a second at a time: quiet, working, quiet, working.
     *
     * The one shape the measured-basal model will estimate. It needs a heart rate every second, a
     * motion magnitude and beat coverage, and it needs quiet stretches long enough to measure a
     * resting anchor from — none of which the shapes above carry, so without this one every span
     * would record a declined day and the golden would pin nothing that model computes.
     */
    private fun measuredBasalRun(from: Long): CalorieDayCase = CalorieDayCase(
        name = "dense1HzQuietAndWorking",
        localMidnightUtc = BASE_MIDNIGHT,
        hr = (0 until 4 * 3_600).map {
            val bpm = when {
                it < 3_600 -> 52
                it < 7_200 -> 105 + (it % 29)
                it < 10_800 -> 56
                else -> 120
            }
            HrSample(deviceId = "corpus", ts = from + it, bpm = bpm)
        },
        gravity = (0 until 4 * 3_600).map {
            val still = it < 3_600 || it in 7_200 until 10_800
            GravitySample(
                deviceId = "corpus", ts = from + it,
                x = 0.0, y = 0.0, z = 1.0, dynAccel = if (still) 0.005 else 0.4,
            )
        },
        beats = (0 until 4 * 3_600).map { (from + it) to 1 },
    )

    /**
     * A day recorded in bursts: about a minute of readings, then about a minute of none.
     *
     * No other shape here interrupts a wear session, so no window in the measured-basal model ever
     * crosses a second that carried no reading. These gaps are far shorter than the silence that starts a new wear
     * session, so they interrupt the readings inside one session and leave coverage just above the
     * gate. The rate rises between the two quiet stretches, which puts a basal raise across them.
     */
    private fun burstyBasalRun(from: Long): CalorieDayCase {
        val seconds = 5 * 3_600
        val recorded = (0 until seconds).filter { it % 120 <= 60 }
        return CalorieDayCase(
            name = "bursty1HzGappedSession",
            localMidnightUtc = BASE_MIDNIGHT,
            hr = recorded.map {
                val bpm = when {
                    it < 2 * 3_600 -> 52 + it % 5
                    it < 4 * 3_600 -> 100 + it % 41
                    else -> 58 + it % 5
                }
                HrSample(deviceId = "corpus", ts = from + it, bpm = bpm)
            },
            gravity = (0 until seconds).map {
                val still = it < 2 * 3_600 || it >= 4 * 3_600
                GravitySample(
                    deviceId = "corpus", ts = from + it,
                    x = 0.0, y = 0.0, z = 1.0, dynAccel = if (still) 0.005 else 0.4,
                )
            },
            beats = (0 until seconds).map { (from + it) to 1 },
        )
    }

    /** Motion at one sample a minute, walking 0.000–0.099 g, which is 1.0–4.0 MET at the shipped gain. */
    private fun motionRun(from: Long, seconds: Int, cadenceS: Int = 60): List<GravitySample> {
        val out = ArrayList<GravitySample>(seconds / cadenceS + 1)
        var i = 0
        while (i * cadenceS < seconds) {
            out.add(
                GravitySample(
                    deviceId = "corpus", ts = from + i.toLong() * cadenceS,
                    x = 0.0, y = 0.0, z = 1.0, dynAccel = ((i * 17) % 100) / 1_000.0,
                ),
            )
            i += 1
        }
        return out
    }

    // ── The day shapes ────────────────────────────────────────────────────────────────────────

    val cases: List<CalorieDayCase> = listOf(
        CalorieDayCase(name = "empty", localMidnightUtc = BASE_MIDNIGHT),

        // Every sample sits before the activity day opens, so the day scores null.
        CalorieDayCase(
            name = "preWindowOnly", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = BASE_MIDNIGHT - 7_200L, seconds = 7_200),
        ),

        // The WHOOP 5/MG live cadence: one standard-HR sample about every 30 s.
        CalorieDayCase(
            name = "sparse30s", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT) + 6 * 3_600L, seconds = 8 * 3_600, cadenceS = 30),
        ),

        // A full-coverage day: 18 h of 1 Hz heart rate, so the grid is saturated end to end.
        CalorieDayCase(
            name = "dense1Hz18h", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT), seconds = 18 * 3_600),
        ),

        // A wear gap longer than the 120 s continuity cap, so the strap-off stretch earns no surplus.
        CalorieDayCase(
            name = "wearGap40min", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT) + 3_600L, seconds = 7_200, cadenceS = 5) +
                hrRun(from = windowStart(BASE_MIDNIGHT) + 13_200L, seconds = 7_200, cadenceS = 5, phase = 11),
        ),

        // Each second repeated three times. A duplicated stream must not triple the day.
        CalorieDayCase(
            name = "duplicateTimestamps", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT) + 7_200L, seconds = 3_600, cadenceS = 10)
                .flatMap { listOf(it, it, it) },
        ),

        // Four windows across the day, so most minutes are estimated at the workout gate.
        CalorieDayCase(
            name = "workoutHeavy", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT), seconds = 12 * 3_600, cadenceS = 10),
            gravity = motionRun(from = windowStart(BASE_MIDNIGHT), seconds = 12 * 3_600),
            workouts = (0 until 4).map {
                val s = windowStart(BASE_MIDNIGHT) + it * 3 * 3_600L
                s until s + 2 * 3_600L
            },
        ),

        // Motion with no heart rate at all: the stored figure is null, the models still read motion.
        CalorieDayCase(
            name = "motionOnly", localMidnightUtc = BASE_MIDNIGHT,
            gravity = motionRun(from = windowStart(BASE_MIDNIGHT) + 3_600L, seconds = 10 * 3_600),
        ),

        CalorieDayCase(
            name = "motionAndHr", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT) + 3_600L, seconds = 10 * 3_600, cadenceS = 15),
            gravity = motionRun(from = windowStart(BASE_MIDNIGHT) + 3_600L, seconds = 10 * 3_600),
        ),

        // Ring-emitted MET wins over the motion transfer wherever it exists. No port supplies it, so
        // the stored figure never sees it and only the model records do.
        CalorieDayCase(
            name = "ringMETPresent", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT) + 3_600L, seconds = 6 * 3_600, cadenceS = 30),
            gravity = motionRun(from = windowStart(BASE_MIDNIGHT) + 3_600L, seconds = 6 * 3_600),
            ringMET = (0 until 6 * 60).map {
                (windowStart(BASE_MIDNIGHT) + 3_600L + it * 60L) to (1.0 + ((it * 13) % 70) / 10.0)
            },
        ),

        // Coverage stops at noon, so a midday clock sees a saturated day and a later one a half day.
        CalorieDayCase(
            name = "inProgress", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT), seconds = 8 * 3_600, cadenceS = 30),
            gravity = motionRun(from = windowStart(BASE_MIDNIGHT), seconds = 8 * 3_600),
        ),

        // The last in-window sample lands 30 s before the window shuts and the stream keeps going
        // past it. Whether the tail is clipped before or after the gaps are computed decides whether
        // that sample stands for one second or for thirty.
        CalorieDayCase(
            name = "spillover", localMidnightUtc = BASE_MIDNIGHT,
            hr = hrRun(from = windowStart(BASE_MIDNIGHT) + 20 * 3_600L, seconds = 4 * 3_600 + 3_600, cadenceS = 30),
        ),

        // Samples on both edges: the second the window opens, the second before it shuts, and the
        // second it shuts (which belongs to the next day).
        CalorieDayCase(
            name = "boundary", localMidnightUtc = BASE_MIDNIGHT,
            hr = listOf(
                HrSample("corpus", windowStart(BASE_MIDNIGHT), 140),
                HrSample("corpus", windowStart(BASE_MIDNIGHT) + 86_399L, 141),
                HrSample("corpus", windowStart(BASE_MIDNIGHT) + 86_400L, 142),
            ),
            gravity = listOf(
                GravitySample("corpus", windowStart(BASE_MIDNIGHT), 0.0, 0.0, 1.0, dynAccel = 0.08),
                GravitySample("corpus", windowStart(BASE_MIDNIGHT) + 86_399L, 0.0, 0.0, 1.0, dynAccel = 0.08),
            ),
        ),

        // A local midnight that is not a whole number of days from the epoch, which is what a spring
        // transition leaves behind: the window is still a fixed 86 400 s, shifted by the offset.
        CalorieDayCase(
            name = "dstSpringForward", localMidnightUtc = BASE_MIDNIGHT - 3_600L,
            hr = hrRun(from = BASE_MIDNIGHT - 3_600L + START_HOUR_S, seconds = 10 * 3_600, cadenceS = 20),
            gravity = motionRun(from = BASE_MIDNIGHT - 3_600L + START_HOUR_S, seconds = 10 * 3_600),
        ),

        CalorieDayCase(
            name = "dstFallBack", localMidnightUtc = BASE_MIDNIGHT + 3_600L,
            hr = hrRun(from = BASE_MIDNIGHT + 3_600L + START_HOUR_S, seconds = 10 * 3_600, cadenceS = 20),
            gravity = motionRun(from = BASE_MIDNIGHT + 3_600L + START_HOUR_S, seconds = 10 * 3_600),
        ),

        measuredBasalRun(from = windowStart(BASE_MIDNIGHT)),

        burstyBasalRun(from = windowStart(BASE_MIDNIGHT)),
    )

    // ── The wearers ───────────────────────────────────────────────────────────────────────────

    private val subject = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 35.0, sex = "male")

    val profiles: List<CalorieProfileCase> = listOf(
        CalorieProfileCase(
            name = "defaultHr",
            profile = subject.copy(calorieModel = EnergyModel.HEART_RATE),
            hrmax = 185.0, restingHR = 55.0,
        ),
        CalorieProfileCase(
            name = "defaultHybrid",
            profile = subject.copy(calorieModel = EnergyModel.HYBRID),
            hrmax = 185.0, restingHR = 55.0,
        ),
        // The case nothing else in the suite covers. `hrFallbackWhenNoMET = false` is a HYBRID knob;
        // routing it into the heart-rate model labels every minute NO_DATA and stores a flat zero.
        CalorieProfileCase(
            name = "knobsOffDefault",
            profile = subject.copy(
                calorieModel = EnergyModel.HYBRID,
                heartRateGates = HeartRateGates(
                    dayActiveHRRFraction = 0.30,
                    boutActiveHRRFraction = 0.45,
                ),
                hybridModelSetting = HybridModelSetting(
                    activeAccrualMET = 3.0,
                    hrFallbackWhenNoMET = false,
                ),
            ),
            hrmax = 185.0, restingHR = 55.0,
        ),
        // The measured-basal model, which is the only one whose stored figure can be null on a day
        // the strap did observe: it declines a day it cannot estimate.
        CalorieProfileCase(
            name = "defaultMeasuredBasal",
            profile = subject.copy(calorieModel = EnergyModel.DYNAMIC_HRR),
            hrmax = 185.0, restingHR = 55.0,
        ),
        // No anchors at all: HRmax falls to the 220 constant and resting HR to the estimator's 60, so
        // no Uth VO2max is derived and the base Keytel model applies.
        CalorieProfileCase(
            name = "noVitalsFemale",
            profile = UserProfile(
                weightKg = 58.0, heightCm = 164.0, age = 48.0, sex = "female",
                calorieModel = EnergyModel.HYBRID,
            ),
            hrmax = null, restingHR = null,
        ),
    )

    // ── The clocks ────────────────────────────────────────────────────────────────────────────

    val clocks: List<CalorieClockCase> = listOf(
        // The whole window has elapsed, which is every past day.
        CalorieClockCase("elapsed") { windowStart(it) + 86_400L + 7_200L },
        // Halfway through: the window is clamped and half the day's basal has been earned.
        CalorieClockCase("midday") { windowStart(it) + 43_200L },
        // Ninety minutes in, so only a fraction of the day's basal has been earned.
        CalorieClockCase("earlyMorning") { windowStart(it) + 5_400L },
    )

    // ── Scoring ───────────────────────────────────────────────────────────────────────────────

    /**
     * One model's answer for one span, through the registry the app dispatches on.
     *
     * Deliberately not a second `when` over [EnergyModel]: a corpus that reimplements the dispatch
     * pins its own arithmetic, so the golden would stay green while the real models moved underneath
     * it. Everything below the call — which streams each variant reads, whether heart rate covers an
     * uncovered minute, where the epoch grid is anchored — is the production answer.
     *
     * Both models are estimated over the SAME span: the activity day clamped at the clock. The
     * per-minute series they return has to line up minute for minute for the comparison card to plot
     * them together.
     */
    fun timelineFor(
        model: EnergyModel,
        case: CalorieDayCase,
        who: CalorieProfileCase,
        nowUtc: Long,
    ): CalorieTimeline {
        val window = ActivityDay.atLocalMidnight(case.localMidnightUtc).window()
        val end = minOf(window.exclusiveEnd, nowUtc)
        val inputs = CalorieInputs(
            hr = case.hr, gravity = case.gravity, ringMET = case.ringMET, workouts = case.workouts,
            rr = case.beats,
        )
        return CalorieModels.timeline(
            model, who.profile, CalorieVitals(who.hrmax, who.restingHR), window.first, end, inputs,
        )
    }

    /** The figure the analytics pass would persist into `DailyMetric.activeKcalEst`, or null. */
    fun storedFigure(case: CalorieDayCase, who: CalorieProfileCase, nowUtc: Long): Double? =
        CalorieDayScorer.score(
            day = ActivityDay.atLocalMidnight(case.localMidnightUtc),
            nowUtc = nowUtc,
            hr = case.hr,
            gravity = case.gravity,
            workouts = case.workouts,
            beats = case.beats,
            profile = who.profile,
            hrmax = who.hrmax,
            restingHR = who.restingHR,
        )?.activeKcal

    // ── The record ────────────────────────────────────────────────────────────────────────────

    /**
     * FNV-1a (64-bit) over the UTF-16 code units of [s], as lowercase hex.
     *
     * Platform-neutral by the repo's cross-platform rule, so the Swift twin can pin the same digest;
     * `hashCode` cannot, and Swift's `hashValue` is randomized per process.
     *
     * The hex is UNSIGNED and unpadded, so a digest is 1 to 16 characters. A Swift twin must render it
     * as `String(UInt64(bitPattern: h), radix: 16)`; `String(h, radix: 16)` over a signed Int64 emits a
     * leading minus for half of all inputs and can never match.
     */
    fun fnv1a(s: String): String {
        var h = -3_750_763_034_362_895_579L // 0xcbf29ce484222325
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1_099_511_628_211L
        }
        return java.lang.Long.toHexString(h)
    }

    /**
     * The per-minute cost series, in the shape a model returns it: one `(timestamp, kcal)` per minute.
     *
     * Digested rather than written out. The corpus holds 255 scored spans of up to 1 440 minutes
     * each, so the series is pinned by a checksum a mismatch names the case for, not by a third of a
     * million literals no one would read.
     */
    private fun seriesDigest(timeline: CalorieTimeline): String =
        fnv1a(timeline.tsIndex.indices.joinToString(",") { "${timeline.tsIndex[it]}:${timeline.activeKcal[it]}" })

    /**
     * The hybrid's per-minute ATTRIBUTION, which no sum can catch a change in: the screen shades its
     * trace from these labels, so a minute relabelled from motion to fallback is user-visible while
     * every total holds.
     */
    private fun attributionDigest(timeline: CalorieTimeline): String {
        val sources = timeline.labeledSeries.getValue(HybridModel.Label.SOURCE)
        return fnv1a(
            timeline.tsIndex.indices.joinToString(",") {
                "${HybridModel.Source.values()[sources[it]!!.toInt()].name}:${timeline.activeKcal[it]}"
            },
        )
    }

    private fun line(vararg parts: Any?): String = parts.joinToString("|")

    /**
     * The case as the clock can actually see it: a pass cannot read a sample that has not been
     * recorded yet, so a stream never extends past [nowUtc].
     *
     * Without this the corpus would score days that cannot exist, and the only differences it found
     * would be between two readings of a future no strap has reported. Workout windows are left
     * alone — one in progress genuinely ends after the present second.
     */
    private fun asOf(case: CalorieDayCase, nowUtc: Long): CalorieDayCase = case.copy(
        hr = case.hr.filter { it.ts < nowUtc },
        gravity = case.gravity.filter { it.ts < nowUtc },
        ringMET = case.ringMET.filter { (ts, _) -> ts < nowUtc },
        beats = case.beats.filter { (ts, _) -> ts < nowUtc },
    )

    /** One scored span: a day as of a clock, a wearer, and that clock. */
    data class Span(val case: CalorieDayCase, val who: CalorieProfileCase, val nowUtc: Long, val clock: String)

    /** Every case x wearer x clock, each case already reduced to what its clock can see. */
    fun spans(): List<Span> = cases.flatMap { raw ->
        profiles.flatMap { who ->
            clocks.map { clock ->
                val now = clock.nowUtcFor(raw.localMidnightUtc)
                Span(asOf(raw, now), who, now, clock.name)
            }
        }
    }

    /**
     * One line per model per span, plus one for the figure the pass would store.
     *
     * Each model is recorded with the columns IT produces: every one states the three day figures,
     * the window and the per-minute series, and only the hybrid has a per-path split and an
     * attribution to digest. Recording a heart-rate line with three empty path columns would pin a
     * shape the model never had.
     */
    fun records(): List<String> {
        val out = ArrayList<String>()
        for ((case, who, now, clock) in spans()) {
            val key = line(case.name, who.name, clock)
            out.add(line(key, "stored", storedFigure(case, who, now)?.toString() ?: "null"))
            for (model in EnergyModel.values()) {
                val t = timelineFor(model, case, who, now)
                val day = listOf(t.dayActiveKcal, t.dayBasalKcal, t.dayTotalKcal)
                val window = listOf(t.tsIndex.size, t.tsIndex.firstOrNull(), t.tsIndex.lastOrNull())
                out.add(
                    when (model) {
                        EnergyModel.HEART_RATE -> line(
                            key, model.name, *day.toTypedArray(),
                            kcalFrom(t, KeytelModel.Label.HEART_RATE),
                            *window.toTypedArray(),
                            seriesDigest(t),
                        )
                        EnergyModel.HYBRID -> line(
                            key, model.name, *day.toTypedArray(),
                            kcalFrom(t, HybridModel.Label.WORKOUT),
                            kcalFrom(t, HybridModel.Label.MOTION_ACTIVE),
                            kcalFrom(t, HybridModel.Label.HR_FALLBACK),
                            *window.toTypedArray(),
                            seriesDigest(t),
                            attributionDigest(t),
                        )
                        EnergyModel.DYNAMIC_HRR -> line(
                            key, model.name, *day.toTypedArray(),
                            kcalFrom(t, DynamicHrrModel.Label.ACTIVE),
                            *window.toTypedArray(),
                            seriesDigest(t),
                            // Zero on every day this model declined, and on no day it estimated.
                            t.extras.getValue(DynamicHrrModel.Extra.QUIET_WINDOW_COUNT),
                            t.extras[CalorieTimeline.MEASURED_BASAL_HR_BPM],
                        )
                    },
                )
            }
        }
        return out
    }

    /** What one path contributed to the day, summed in minute order. */
    private fun kcalFrom(timeline: CalorieTimeline, label: String): Double {
        var total = 0.0
        for (v in timeline.labeledKcal.getValue(label)) total += v
        return total
    }
}
