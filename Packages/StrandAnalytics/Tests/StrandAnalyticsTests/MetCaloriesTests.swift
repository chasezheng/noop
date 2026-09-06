import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// The motion-to-MET transfer, the day window and the display banding.
///
/// Pure: no database and no strap. Mirrors the Android vectors value for value.
///
/// Standard subject throughout: 80 kg / 180 cm / 35 y male.
///   Harris–Benedict BMR = 88.362 + 13.397×80 + 479.9×1.80 − 5.677×35 = 1825.247 kcal/day
///   restingRate         = 1825.247 / 86 400                          = 0.021125544 kcal/s
final class MetCaloriesTests: XCTestCase {

    private let profile = UserProfile(weightKg: 80.0, heightCm: 180.0, age: 35.0, sex: "male")

    private let bmrPerDay = 1825.247
    private let hrmax = 185.0
    private let restingHR = 55.0

    /// Local midnight at UTC 0, so the activity window is [14400, 100800).
    private let localMidnight = 0
    private let winStart = 14_400
    private let winEnd = 100_800

    private func gravity(_ ts: Int, _ dyn: Double?) -> GravitySample {
        GravitySample(ts: ts, x: 0.0, y: 0.0, z: 1.0, dynAccel: dyn)
    }

    private func hr(_ ts: Int, _ bpm: Int) -> HRSample { HRSample(ts: ts, bpm: bpm) }

    // MARK: - Day window: local midnight

    func testDayWindowOpensAtLocalMidnightAndRunsTwentyFourHours() {
        let w = MetCalories.dayWindowUtc(localMidnightUtc: localMidnight)
        XCTAssertEqual(w.start, 0)
        XCTAssertEqual(w.end, 86_400)
        XCTAssertEqual(w.end - w.start, 86_400)
    }

    func testDayWindowShiftsWithTheLocalMidnightItIsGiven() {
        // A caller in UTC+2 passes localMidnightUtc = -7200; local midnight is then UTC -7200.
        let w = MetCalories.dayWindowUtc(localMidnightUtc: -7_200)
        XCTAssertEqual(w.start, -7_200)
        XCTAssertEqual(w.end, 79_200)
    }

    func testMetFromDynAccelAnchorsAtRestAndBriskWalk() {
        XCTAssertEqual(MetCalories.metFromDynAccel(0.0), 1.0, accuracy: 1e-9)   // still -> resting
        XCTAssertEqual(MetCalories.metFromDynAccel(0.1), 4.0, accuracy: 1e-9)   // brisk walk anchor
        XCTAssertEqual(MetCalories.metFromDynAccel(-5.0), 1.0, accuracy: 1e-9)  // negative -> rest
    }

    func testMetFromDynAccelClampsAtMaxMET() {
        XCTAssertEqual(MetCalories.metFromDynAccel(1.0), MetCalories.maxMET, accuracy: 1e-9)
        XCTAssertEqual(MetCalories.metFromDynAccel(99.0), MetCalories.maxMET, accuracy: 1e-9)
    }

    func testBandMatchesTheDocumentedOuraEdges() {
        XCTAssertEqual(MetCalories.band(forMET: 0.9), 1)   // rest, < 1.05
        XCTAssertEqual(MetCalories.band(forMET: 1.5), 2)   // inactive, 1.05..2.0
        XCTAssertEqual(MetCalories.band(forMET: 3.0), 3)   // low, 2.0..4.0
        XCTAssertEqual(MetCalories.band(forMET: 5.0), 4)   // medium, 4.0..6.0
        XCTAssertEqual(MetCalories.band(forMET: 9.0), 5)   // high, >= 6.0
    }

    // MARK: - Basal: credited on the wall clock, never gated on wear

    func testWithinAMinuteMetValuesAverage() {
        // Half the minute still, half at 0.1 g -> mean 2.5 MET.
        let g = (0..<30).map { gravity(winStart + $0, 0.0) }
            + (30..<60).map { gravity(winStart + $0, 0.1) }
        let eps = MetCalories.epochs(start: winStart, end: winStart + 60, gravity: g, ringMET: [])
        XCTAssertEqual(eps.count, 1)
        XCTAssertEqual(eps[0].met ?? .nan, 2.5, accuracy: 1e-9)
    }
