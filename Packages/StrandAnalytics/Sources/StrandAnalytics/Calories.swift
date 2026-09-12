import Foundation
import WhoopProtocol

/// Energy expenditure estimated from heart rate: Keytel 2005 active burn above a revised
/// Harris–Benedict basal rate.
///
/// Every figure here is an approximation, not calorimetry and not medical advice.
///
/// Parity: Android `analytics/calorie/Calories.kt` holds the same basal, Keytel and bout paths. The
/// two day estimators differ, so check a function before assuming a twin.
public enum Calories {

    struct Coeffs {
        let restingAlpha: Double
        let restingWeight: Double
        let restingHeight: Double  // applied to height in metres
        let restingAge: Double
        // Keytel 2005 base model: EE(kJ/min) = alpha + hr·HR + wt·W + age·A.
        let workoutHR: Double
        let workoutWeight: Double
        let workoutAge: Double
        let workoutAlpha: Double
        // Keytel 2005 fitness-adjusted model, which also reads VO2max:
        // EE(kJ/min) = fitAlpha + fitHR·HR + fitVO2·VO2max + fitWeight·W + fitAge·A.
        // (Keytel et al. 2005, J. Sports Sci. 23(3).)
        let fitHR: Double
        let fitVO2: Double
        let fitWeight: Double
        let fitAge: Double
        let fitAlpha: Double
    }

    static let male = Coeffs(restingAlpha: 88.362, restingWeight: 13.397, restingHeight: 479.9,
                             restingAge: 5.677, workoutHR: 0.6309, workoutWeight: 0.1988,
                             workoutAge: 0.2017, workoutAlpha: -55.0969,
                             fitHR: 0.634, fitVO2: 0.404, fitWeight: 0.394, fitAge: 0.271,
                             fitAlpha: -95.7735)
    static let female = Coeffs(restingAlpha: 447.593, restingWeight: 9.247, restingHeight: 309.8,
                               restingAge: 4.33, workoutHR: 0.4472, workoutWeight: -0.1263,
                               workoutAge: 0.0740, workoutAlpha: -20.4022,
                               fitHR: 0.450, fitVO2: 0.380, fitWeight: 0.103, fitAge: 0.274,
                               fitAlpha: -59.3954)
    // The midpoint of the male and female coefficients.
    static let nonbinary = Coeffs(restingAlpha: 267.9775, restingWeight: 11.322, restingHeight: 394.85,
                                  restingAge: 5.0035, workoutHR: 0.53905, workoutWeight: 0.03625,
                                  workoutAge: 0.13785, workoutAlpha: -37.74955,
                                  fitHR: 0.542, fitVO2: 0.392, fitWeight: 0.2485, fitAge: 0.2725,
                                  fitAlpha: -77.58445)

    static let activeHRRFraction = 0.30

    /// Fraction of heart-rate reserve above which a second of an ordinary day is scored at the Keytel
    /// rate rather than at the basal rate alone.
    ///
    /// One fraction cannot suit both ends of the population. For a fit wearer — 42 resting against a
    /// 180 maximum — this sits above walking and an ordinary day scores as pure basal. For a wearer
    /// resting near 60 it falls at about 72 bpm, at or below their sitting rate, and Keytel does not
    /// decay to basal, so sedentary hours score generously. An absolute offset above a measured
    /// sitting rate would be the better rule.
    static let dayActiveHRRFraction = 0.10
    static let workoutDivisor = 251.04  // 60 s/min × 4.184 kJ/kcal

    static func resolveCoeffs(_ sex: String) -> Coeffs {
        switch sex.lowercased() {
        case "male": return male
        case "female": return female
        case "nonbinary": return nonbinary
        default: return nonbinary
        }
    }

    static func restingKcalPerS(_ c: Coeffs, weightKg: Double, heightCm: Double, age: Double) -> Double {
        let heightM = heightCm / 100.0
        let bmr = c.restingAlpha + c.restingWeight * weightKg + c.restingHeight * heightM - c.restingAge * age
        return max(0.0, bmr) / 86_400.0
    }

    /// Resting (basal) energy over a span of elapsed wall-clock seconds, in kcal.
    ///
    /// No column stores this term on its own. It is pure in the profile and the elapsed time, so
    /// re-deriving it is exact rather than an approximation of a stored figure. Twin of Kotlin
    /// `basalKcalForSpan`.
    public static func basalKcalForSpan(profile: UserProfile, spanS: Double) -> Double {
        if spanS <= 0.0 { return 0.0 }
        let weightKg = profile.weightKg > 0 ? profile.weightKg : 70.0
        let heightCm = profile.heightCm > 0 ? profile.heightCm : 170.0
        let age = profile.age > 0 ? profile.age : 30.0
        return restingKcalPerS(resolveCoeffs(profile.sex), weightKg: weightKg,
                               heightCm: heightCm, age: age) * spanS
    }

    /// HRmax assumed when a profile supplies none. The last-resort 220 constant, not Tanaka.
    public static let defaultHRmax: Double = 220.0

    /// The bpm at or above which a heart-rate sample is scored as effort rather than as rest:
    /// `resting + fraction × (HRmax − resting)`, the Karvonen heart-rate reserve.
    public static func activeHrGate(restingHR: Double, hrmax: Double, hrrFraction: Double) -> Double {
        restingHR + hrrFraction * (hrmax - restingHR)
    }

    /// The VO₂max the calorie models score with: the value the wearer entered, or the
    /// heart-rate-ratio estimate below when they entered none.
    public static func vo2maxFor(profile: UserProfile, hrmax: Double, restingHR: Double?) -> Double? {
        if profile.vo2maxOverride > 0 { return profile.vo2maxOverride }
        return vo2maxFor(hrmax: hrmax, restingHR: restingHR)
    }

    /// Uth–Sørensen VO2max estimate (ml·kg⁻¹·min⁻¹) ≈ 15.3 · HRmax / HRrest, or nil when no usable
    /// resting HR is known.
    ///
    /// A function of HRmax and resting HR alone, so a caller resolves it locally and no day's result
    /// depends on another day. (Uth et al. 2004, Eur. J. Appl. Physiol. 91.)
    public static func vo2maxFor(hrmax: Double, restingHR: Double?) -> Double? {
        guard let rhr = restingHR, rhr > 0, hrmax > 0 else { return nil }
        return 15.3 * hrmax / rhr
    }

    /// Active energy rate (kcal/s) at one heart rate.
    ///
    /// A `vo2max` selects the Keytel fitness-adjusted equation, which personalizes beyond age, weight
    /// and sex; nil selects the base equation. HR is capped at HRmax in both.
    static func activeKcalPerS(_ c: Coeffs, hr: Double, hrmax: Double, weightKg: Double, age: Double,
                               vo2max: Double? = nil) -> Double {
        let eeKjMin: Double
        if let vo2 = vo2max {
            eeKjMin = c.fitHR * min(hr, hrmax) + c.fitVO2 * vo2 + c.fitWeight * weightKg
                + c.fitAge * age + c.fitAlpha
        } else {
            eeKjMin = c.workoutHR * min(hr, hrmax) + c.workoutWeight * weightKg
                + c.workoutAge * age + c.workoutAlpha
        }
        return max(0.0, eeKjMin) / workoutDivisor
    }

    /// Estimate (kcal, kJ) for one workout bout.
    ///
    /// Each sample is weighted by the elapsed time to the next one, capped at
    /// `WorkoutDetector.mergeGapS`, so a
    /// stream below 1 Hz is counted over real seconds rather than one second per sample.
    public static func estimateBoutCalories(_ hrSamples: [HRSample],
                                            profile: UserProfile,
                                            hrmax: Double?,
                                            restingHR: Double?) -> (Double, Double) {
        let weightKg = profile.weightKg > 0 ? profile.weightKg : 70.0
        let heightCm = profile.heightCm > 0 ? profile.heightCm : 170.0
        let age = profile.age > 0 ? profile.age : 30.0
        let coeffs = resolveCoeffs(profile.sex)

        let effHRmax = hrmax ?? 220.0
        let effResting = restingHR ?? 60.0
        let activeThreshold = effResting + activeHRRFraction * (effHRmax - effResting)

        let restingRate = restingKcalPerS(coeffs, weightKg: weightKg, heightCm: heightCm, age: age)
        // Constant across the bout, so it is resolved once.
        let vo2max = vo2maxFor(profile: profile, hrmax: effHRmax, restingHR: restingHR)

        // `restingRate` and `activeKcalPerS` are per-second rates, so each sample is weighted by the
        // seconds it stands for rather than by a flat one second.
        let ordered = hrSamples.sorted { $0.ts < $1.ts }
        var totalKcal = 0.0
        for i in ordered.indices {
            let bpm = Double(ordered[i].bpm)
            let dur: Double
            if i < ordered.count - 1 {
                let gap = Double(ordered[i + 1].ts - ordered[i].ts)
                // A repeated timestamp measures no time; the next sample carries the real gap.
                dur = gap > 0 ? min(gap, WorkoutDetector.mergeGapS) : 0.0
            } else {
                dur = 1.0   // last sample carries one representative second
            }
            if bpm < activeThreshold {
                totalKcal += restingRate * dur
            } else {
                totalKcal += activeKcalPerS(coeffs, hr: bpm, hrmax: effHRmax, weightKg: weightKg, age: age, vo2max: vo2max) * dur
            }
        }
        return (totalKcal, totalKcal * 4.184)
    }

    /// The longest gap between two day heart-rate samples still credited as continuous active effort
    /// (s).
    ///
    /// Matches `StrainScorer.maxSampleGapMin`, the other analytic that integrates the same raw day
    /// heart-rate union, so the two agree on what "continuous" means. It also covers the WHOOP 5/MG
    /// live cadence of about one sample every 30 s.
    static let dayMaxGapS = 120.0

    /// Whole-day energy estimate (kcal), the sum of the two terms `estimateDayEnergy` returns.
    ///
    /// An estimate from heart rate alone: not calorimetry, not the Apple or WHOOP cloud figure, and
    /// not medical advice.
    public static func estimateDayCalories(_ hrSamples: [HRSample],
                                           profile: UserProfile,
                                           hrmax: Double?,
                                           restingHR: Double?,
                                           bmrSpanS: Double? = nil) -> Double {
        estimateDayEnergy(hrSamples, profile: profile, hrmax: hrmax,
                          restingHR: restingHR, bmrSpanS: bmrSpanS).totalKcal
    }

    /// One day's energy, reported as a basal term and an active term.
    ///
    /// Basal accrues over `bmrSpanS` seconds of elapsed wall clock, because resting metabolism is
    /// true whether or not a sample landed; nil falls back to the span the samples themselves cover.
    /// Active accrues only from the surplus above the resting rate, over measured wear, so no second
    /// is counted twice. The two answer different questions and are reported apart so that a caller
    /// cannot conflate them. Twin of Kotlin `estimateDayEnergy(hrSamples:)`.
    public static func estimateDayEnergy(_ hrSamples: [HRSample],
                                         profile: UserProfile,
                                         hrmax: Double?,
                                         restingHR: Double?,
                                         bmrSpanS: Double? = nil) -> DayEnergy {
        if hrSamples.isEmpty { return DayEnergy(totalKcal: 0.0, basalKcal: 0.0, activeKcal: 0.0) }

        let weightKg = profile.weightKg > 0 ? profile.weightKg : 70.0
        let heightCm = profile.heightCm > 0 ? profile.heightCm : 170.0
        let age = profile.age > 0 ? profile.age : 30.0
        let coeffs = resolveCoeffs(profile.sex)

        let effHRmax = hrmax ?? defaultHRmax
        let effResting = restingHR ?? StrainScorer.defaultRestingHR
        let activeThreshold = activeHrGate(restingHR: effResting, hrmax: effHRmax,
                                           hrrFraction: dayActiveHRRFraction)

        let restingRate = restingKcalPerS(coeffs, weightKg: weightKg, heightCm: heightCm, age: age)
        // Constant across the day, so it is resolved once.
        let vo2max = vo2maxFor(profile: profile, hrmax: effHRmax, restingHR: restingHR)

        let ordered = hrSamples.sorted { $0.ts < $1.ts }

        // Term 1 — basal over elapsed wall clock. The sample-derived fallback spans first..last
        // inclusive, so a dense 1 Hz day yields exactly one second per sample.
        let observedSpanS = Double(ordered[ordered.count - 1].ts - ordered[0].ts) + 1.0
        let spanS = max(0.0, bmrSpanS ?? observedSpanS)
        let basalKcal = restingRate * spanS
        var activeKcal = 0.0

        // Term 2 — surplus above resting, over measured wear only.
        for i in ordered.indices {
            let bpm = Double(ordered[i].bpm)
            if bpm < activeThreshold { continue }   // basal already covers this second
            let active = activeKcalPerS(coeffs, hr: bpm, hrmax: effHRmax, weightKg: weightKg,
                                        age: age, vo2max: vo2max)
            // For some profiles a sample just past the gate falls below the basal rate. Floor the
            // surplus at zero so such a sample cannot subtract from the day.
            let surplus = max(0.0, active - restingRate)
            if surplus <= 0.0 { continue }
            let dur: Double
            if i < ordered.count - 1 {
                let gap = Double(ordered[i + 1].ts - ordered[i].ts)
                // A repeated timestamp measures no time; the next sample carries the real gap.
                dur = gap > 0 ? min(gap, dayMaxGapS) : 0.0
            } else {
                dur = 1.0   // last sample carries one representative second
            }
            activeKcal += surplus * dur
        }
        return DayEnergy(totalKcal: basalKcal + activeKcal, basalKcal: basalKcal, activeKcal: activeKcal)
    }
}
