import XCTest
@testable import StrandAnalytics
import WhoopStore

/// The resting HR the calorie models score a day with, resolved across sources.
///
/// A day with no measurement of its own would otherwise take an invented default, which moves both
/// the effort gate and the VO₂max estimate, and so moves the whole day's energy.
///
/// Kotlin twin: `CalorieRestingHrTest`.
final class CalorieRestingHrTests: XCTestCase {

    private func row(_ day: String, _ restingHr: Int?) -> DailyMetric {
        DailyMetric(day: day, totalSleepMin: nil, efficiency: nil, deepMin: nil, remMin: nil,
                    lightMin: nil, disturbances: nil, restingHr: restingHr, avgHrv: nil,
                    recovery: nil, strain: nil, exerciseCount: nil)
    }

    private func resolve(_ rows: [DailyMetric], day: String = "2026-01-15") -> Double? {
        AnalyticsEngine.calorieRestingHR(rows: rows, day: day)
    }

    func testTheDaysOwnValueWins() {
        XCTAssertEqual(resolve([row("2026-01-15", 48), row("2026-01-10", 55)])!, 48.0, accuracy: 0.0)
    }

    func testAnOlderValueCarriesForwardWhenTheDayHasNone() {
        XCTAssertEqual(resolve([row("2026-01-10", 55)])!, 55.0, accuracy: 0.0)
    }

    func testTheMostRecentOfSeveralWins() {
        XCTAssertEqual(resolve([row("2026-01-05", 60), row("2026-01-14", 52)])!, 52.0, accuracy: 0.0)
    }

    func testAnArbitrarilyOldValueIsStillUsed() {
        // A measurement of any age beats the default the estimators otherwise apply.
        XCTAssertEqual(resolve([row("2019-03-02", 55)])!, 55.0, accuracy: 0.0)
    }

    func testSeveralSourcesOnTheLatestDayAreAveraged() {
        // No precedence between sources is defensible when they disagree by a few bpm.
        XCTAssertEqual(resolve([row("2026-01-14", 48), row("2026-01-14", 56)])!, 52.0, accuracy: 0.0)
    }

    func testOnlyTheLatestDaysSourcesAreAveraged() {
        // An older day must not dilute the most recent measurement.
        let rows = [row("2026-01-14", 48), row("2026-01-14", 56), row("2026-01-02", 90)]
        XCTAssertEqual(resolve(rows)!, 52.0, accuracy: 0.0)
    }

    func testALaterDaysValueIsNotUsed() {
        // Scoring a past day must not read a resting HR measured after it.
        XCTAssertNil(resolve([row("2026-01-20", 48)]))
    }

    func testRowsWithoutARestingHrAreSkipped() {
        XCTAssertEqual(resolve([row("2026-01-14", nil), row("2026-01-10", 55)])!, 55.0, accuracy: 0.0)
    }

    func testNoUsableRowResolvesToNothing() {
        XCTAssertNil(resolve([]))
    }
}
