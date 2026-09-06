import Foundation
import WhoopProtocol

/// One MET reading at a wall-clock second, as a ring that emits MET directly reports it.
public struct METSample: Equatable, Sendable {
    public let ts: Int
    public let met: Double
    public init(ts: Int, met: Double) {
        self.ts = ts
        self.met = met
    }
}

/// Daily energy expenditure derived from motion, in the structure Oura documents for its activity
/// day.
///
/// Movement is reduced to a MET (metabolic-equivalent) value per one-minute epoch; energy accrues
/// only from epochs at or above `activeAccrualMET`, and minutes are banded for display. Heart rate
/// covers detected workouts, because the two methods fail in disjoint regimes: accelerometry
/// saturates at running speed and misses cycling, rowing and lifting, while heart rate over-reads at
/// rest and is inflated by caffeine, stress, heat and illness.
///
/// Oura has never published the accelerometer-to-MET transfer or the low/medium/high band edges, so
/// every constant filling those gaps is a project choice, labelled as such where it is defined. The
/// totals are not expected to match the Oura app.
///
/// The published accuracy of ring and wrist energy expenditure is poor — MAPE above 20% against
/// indirect calorimetry, worsening with intensity, and day-level limits of agreement several hundred
/// kcal wide (Kristiansson 2023, BMC Med Res Methodol 23:50; Henriksen 2022, JMIR Form Res
/// 6(5):e27248) — so this belongs behind an Experimental toggle and must not feed a downstream score.
///
/// Parity: the constants, the day window, the MET transfer and the banding mirror Android
/// `analytics/calorie/HybridMet.kt` and must stay byte-identical.
public enum MetCalories {

    // MARK: - Structural constants (documented by Oura)

    /// The length of one epoch (s). Oura's `met.interval` is 60 s.
    public static let metEpochS = 60.0

    /// The lowest MET that contributes active energy. Oura documents 1.5, and 1.0–1.5 MET as
    /// sedentary.
    ///
    /// Deliberately below the inactive-to-low display edge of 2.0 MET: the lower value decides what
    /// earns energy, the higher what is shown as activity. Keeping them apart is what stops
    /// low-grade motion noise inflating a daily total.
    public static let activeAccrualMET = 1.5

    /// The `class_5_min` band edges Oura documents: rest below 1.05, inactive to 2.0.
    public static let restBandMaxMET = 1.05
    public static let inactiveBandMaxMET = 2.0

    /// The low-to-medium and medium-to-high band edges.
    ///
    /// Age- and sex-dependent in Oura's model and never published, so these are project constants
    /// read off the ACSM intensity bands (moderate 3–6 MET, vigorous at or above 6). Display only:
    /// neither edge reaches a calorie total, so a wrong value mislabels minutes without changing
    /// energy.
    public static let mediumBandMinMET = 4.0
    public static let highBandMinMET = 6.0

    // MARK: - Project constants (the parts Oura has never published)

    /// MET added per 1 g of gravity-removed acceleration, in `MET = 1.0 + gain × dynAccel`.
    ///
    /// This constant is ours and it is uncalibrated. Oura's transfer function is proprietary, and
    /// this one number sets the magnitude of every motion-derived figure here. The gain is anchored
    /// rather than fitted, on two physiological points: a still strap reads about 0 g and is 1.0 MET,
    /// and ordinary brisk walking sits near 0.1 g and costs about 4 MET. It has not been validated
    /// against indirect calorimetry, and an accelerometry estimate is known to under-read at running
    /// intensity, so the linear form is wrong at the top of its range.
    public static let dynAccelMETGainPerG = 30.0

    /// The ceiling on any one epoch's MET. Nothing a human sustains exceeds about 20 MET, so this
    /// clamps a strap knock or a decode glitch rather than any real activity.
    public static let maxMET = 20.0

    /// The longest gap between two heart-rate samples still credited as continuous effort inside a
    /// workout window (s). Matches `Calories.dayMaxGapS`, so both calorie paths treat a dropout alike.
    public static let hrMaxGapS = 120.0

    // MARK: - Day window

    /// The `[start, end)` UTC window for the activity day whose local midnight is `localMidnightUtc`.
    ///
    /// The activity day is the calendar day: it opens at local midnight and runs a fixed 86 400 s, so
    /// energy buckets on the same boundary as every other daily figure.
    public static func dayWindowUtc(localMidnightUtc: Int) -> (start: Int, end: Int) {
        (localMidnightUtc, localMidnightUtc + 86_400)
    }

    // MARK: - Transfer function

    /// The MET a gravity-removed motion magnitude (g) corresponds to, clamped to `[1, maxMET]`.
    public static func metFromDynAccel(_ dynAccelG: Double) -> Double {
        let g = max(0.0, dynAccelG)
        return min(maxMET, 1.0 + dynAccelMETGainPerG * g)
    }

    /// The `class_5_min` band a MET value falls in: 1 rest, 2 inactive, 3 low, 4 medium, 5 high.
    ///
    /// Never 0, the non-wear band, which describes an epoch with no MET value at all.
    public static func band(forMET met: Double) -> Int {
        if met < restBandMaxMET { return 1 }
        if met < inactiveBandMaxMET { return 2 }
        if met < mediumBandMinMET { return 3 }
        if met < highBandMinMET { return 4 }
        return 5
    }

    // MARK: - Epoch construction

    /// One minute of the activity day. `met` is nil when the minute carried no usable motion, which
    /// is every minute on a strap that reports no dynamic acceleration.
    public struct Epoch: Equatable, Sendable {
        public let start: Int
        public let met: Double?
        public init(start: Int, met: Double?) {
            self.start = start
            self.met = met
        }
    }

    /// The per-minute epochs tiling `[start, end)`, each averaging the MET values that landed in it.
    ///
    /// A ring reports MET directly, so `ringMET` wins any minute it covers and carries none of
    /// `dynAccelMETGainPerG`'s uncertainty. `gravity` fills the rest; a minute with neither stays nil.
    public static func epochs(start: Int, end: Int,
                              gravity: [GravitySample],
                              ringMET: [METSample]) -> [Epoch] {
        if end <= start { return [] }
        let epochLen = Int(metEpochS)
        let count = (end - start + epochLen - 1) / epochLen
        if count <= 0 { return [] }

        var ringSum = [Double](repeating: 0.0, count: count)
        var ringN = [Int](repeating: 0, count: count)
        var motionSum = [Double](repeating: 0.0, count: count)
        var motionN = [Int](repeating: 0, count: count)

        for s in ringMET {
            if s.ts < start || s.ts >= end { continue }
            let i = (s.ts - start) / epochLen
            if i < 0 || i >= count { continue }
            ringSum[i] += max(0.0, min(maxMET, s.met))
            ringN[i] += 1
        }
        for s in gravity {
            guard let dyn = s.dynAccel else { continue }
            if s.ts < start || s.ts >= end { continue }
            let i = (s.ts - start) / epochLen
            if i < 0 || i >= count { continue }
            motionSum[i] += metFromDynAccel(dyn)
            motionN[i] += 1
        }

        var out = [Epoch]()
        out.reserveCapacity(count)
        for i in 0..<count {
            let epochStart = start + i * epochLen
            if ringN[i] > 0 {
                out.append(Epoch(start: epochStart, met: ringSum[i] / Double(ringN[i])))
            } else if motionN[i] > 0 {
                out.append(Epoch(start: epochStart, met: motionSum[i] / Double(motionN[i])))
            } else {
                out.append(Epoch(start: epochStart, met: nil))
            }
        }
        return out
    }

}
