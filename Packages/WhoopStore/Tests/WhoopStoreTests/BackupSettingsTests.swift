import XCTest
@testable import WhoopStore

/// The `settings.json` codec + UserDefaults round trip for #1000 ("restore doesn't bring back
/// settings/weight/height"). These run headlessly (`swift test --filter BackupSettingsTests`); the
/// full ZIP-container round trip through `DataBackup` lives in the app target's
/// `BackupSyncRoundTripTests` (central build).
final class BackupSettingsTests: XCTestCase {

    // MARK: - Encode / decode round trip

    func testEncodeDecodeRoundTripsEveryWhitelistedKey() throws {
        // Every field of the Android-only measured-basal model. This platform never reads them, so
        // nothing but this test says they still cross the wire the two platforms share. Kept in its
        // own literal: `[String: Any]` is heterogeneous, and one 44-entry literal is slow to type-check.
        let measuredBasal: [String: Any] = [
            "calorie.sessionGapS": 900,
            "calorie.minHrCoverageFrac": 0.6,
            "calorie.hampelRadiusS": 7,
            "calorie.hampelSigmas": 2.5,
            "calorie.suppressPeaks": 0,
            "calorie.peakBlockS": 240,
            "calorie.peakPercentile": 0.93,
            "calorie.motionStillG": 0.015,
            "calorie.motionSmoothS": 12,
            "calorie.basalMinWindowS": 420,
            "calorie.basalHrRangeBpm": 8.0,
            "calorie.basalStillFrac": 0.95,
            "calorie.basalBeatCoverageFrac": 0.4,
            "calorie.restSmoothS": 45,
            "calorie.restSmoothMinSamples": 25,
            "calorie.basalHrSeedOffsetBpm": 4.0,
            "calorie.reserveRampBandBpm": 12.0,
            "calorie.measuredBasalKcalDay": 1577.0,
            "calorie.basalFatNight": 0.75,
            "calorie.basalFatDay": 0.35,
            "calorie.basalFatDayStartHour": 9.0,
            "calorie.basalFatDayEndHour": 21.0,
            "calorie.activeFatAtZone1": 0.95,
            "calorie.activeFatAtZone2Top": 0.6,
        ]
        let values: [String: Any] = ([
            "profile.age": 34,
            "profile.sex": "female",
            "profile.weightKg": 62.5,
            "profile.heightCm": 168.0,
            "profile.waistCm": 71.0,
            "profile.hrMax": 191,
            "profile.hrZoneThresholds": "95,118,142,168,184",
            "units.system": "imperial",
            "units.distance": "metric",
            "units.temperature": "celsius",
            "effort.scale": "whoop",
            "dayCycle.mode": "sleep_onset",
            // #today-hosted-cards: the one layout pref carried, a JSON [String] stored under the String kind.
            "today.hostedCards": "[\"sleep.sleepMarks\"]",
            // #1361: custom journal behaviours, a newline-joined name list — the embedded newline must
            // survive the JSON round-trip (and stay byte-identical to Android's pref value).
            "journal.customBehaviors": "Cold plunge\nMagnesium",
            // A measured value and the calorie settings behind the wearer's day totals. Booleans ride
            // the Int kind: the wire has none.
            "profile.vo2max": 47.5,
            "calorie.model": "hybrid",
            "calorie.preferOnDevice": 1,
            "calorie.dayActiveHRRFraction": 0.4,
            "calorie.boutActiveHRRFraction": 0.3,
            "calorie.activeAccrualMET": 1.6,
            "calorie.dynAccelMETGainPerG": 28.0,
            "calorie.hrFallbackWhenNoMET": 0,
        ] as [String: Any]).merging(measuredBasal) { current, _ in current }
        let data = try XCTUnwrap(BackupSettings.encode(values))
        let back = BackupSettings.decode(data)

        XCTAssertEqual(back["profile.age"] as? Int, 34)
        XCTAssertEqual(back["profile.sex"] as? String, "female")
        XCTAssertEqual(back["profile.weightKg"] as? Double, 62.5)
        XCTAssertEqual(back["profile.heightCm"] as? Double, 168.0)
        XCTAssertEqual(back["profile.waistCm"] as? Double, 71.0)
        XCTAssertEqual(back["profile.hrMax"] as? Int, 191)
        XCTAssertEqual(back["profile.hrZoneThresholds"] as? String, "95,118,142,168,184")
        XCTAssertEqual(back["units.system"] as? String, "imperial")
        XCTAssertEqual(back["units.distance"] as? String, "metric")
        XCTAssertEqual(back["units.temperature"] as? String, "celsius")
        XCTAssertEqual(back["effort.scale"] as? String, "whoop")
        XCTAssertEqual(back["dayCycle.mode"] as? String, "sleep_onset")
        XCTAssertEqual(back["today.hostedCards"] as? String, "[\"sleep.sleepMarks\"]")
        XCTAssertEqual(back["journal.customBehaviors"] as? String, "Cold plunge\nMagnesium")
        XCTAssertEqual(back["profile.vo2max"] as? Double, 47.5)
        XCTAssertEqual(back["calorie.model"] as? String, "hybrid")
        XCTAssertEqual(back["calorie.preferOnDevice"] as? Int, 1)
        XCTAssertEqual(back["calorie.dayActiveHRRFraction"] as? Double, 0.4)
        XCTAssertEqual(back["calorie.boutActiveHRRFraction"] as? Double, 0.3)
        XCTAssertEqual(back["calorie.activeAccrualMET"] as? Double, 1.6)
        XCTAssertEqual(back["calorie.dynAccelMETGainPerG"] as? Double, 28.0)
        XCTAssertEqual(back["calorie.hrFallbackWhenNoMET"] as? Int, 0)
        XCTAssertEqual(back["calorie.sessionGapS"] as? Int, 900)
        XCTAssertEqual(back["calorie.minHrCoverageFrac"] as? Double, 0.6)
        XCTAssertEqual(back["calorie.hampelRadiusS"] as? Int, 7)
        XCTAssertEqual(back["calorie.hampelSigmas"] as? Double, 2.5)
        XCTAssertEqual(back["calorie.suppressPeaks"] as? Int, 0)
        XCTAssertEqual(back["calorie.peakBlockS"] as? Int, 240)
        XCTAssertEqual(back["calorie.peakPercentile"] as? Double, 0.93)
        XCTAssertEqual(back["calorie.motionStillG"] as? Double, 0.015)
        XCTAssertEqual(back["calorie.motionSmoothS"] as? Int, 12)
        XCTAssertEqual(back["calorie.basalMinWindowS"] as? Int, 420)
        XCTAssertEqual(back["calorie.basalHrRangeBpm"] as? Double, 8.0)
        XCTAssertEqual(back["calorie.basalStillFrac"] as? Double, 0.95)
        XCTAssertEqual(back["calorie.basalBeatCoverageFrac"] as? Double, 0.4)
        XCTAssertEqual(back["calorie.restSmoothS"] as? Int, 45)
        XCTAssertEqual(back["calorie.restSmoothMinSamples"] as? Int, 25)
        XCTAssertEqual(back["calorie.basalHrSeedOffsetBpm"] as? Double, 4.0)
        XCTAssertEqual(back["calorie.reserveRampBandBpm"] as? Double, 12.0)
        XCTAssertEqual(back["calorie.measuredBasalKcalDay"] as? Double, 1577.0)
        XCTAssertEqual(back["calorie.basalFatNight"] as? Double, 0.75)
        XCTAssertEqual(back["calorie.basalFatDay"] as? Double, 0.35)
        XCTAssertEqual(back["calorie.basalFatDayStartHour"] as? Double, 9.0)
        XCTAssertEqual(back["calorie.basalFatDayEndHour"] as? Double, 21.0)
        XCTAssertEqual(back["calorie.activeFatAtZone1"] as? Double, 0.95)
        XCTAssertEqual(back["calorie.activeFatAtZone2Top"] as? Double, 0.6)
        XCTAssertEqual(back.count, values.count, "Nothing extra should appear")
        XCTAssertEqual(BackupSettings.whitelist.count, values.count,
                       "Every whitelisted key must be exercised here")
    }

    func testEncodeIsDeterministic() throws {
        let values: [String: Any] = ["profile.age": 40, "profile.sex": "male", "profile.weightKg": 80.0]
        let a = try XCTUnwrap(BackupSettings.encode(values))
        let b = try XCTUnwrap(BackupSettings.encode(values))
        XCTAssertEqual(a, b, "Same settings must produce identical bytes (.sortedKeys)")
    }

    // MARK: - Whitelist enforcement (the anonymity/scope gate)

    func testNonWhitelistedKeysAreDroppedOnEncodeAndDecode() throws {
        // Encode side: anything sensitive or device-specific never reaches the JSON.
        let dirty: [String: Any] = [
            "profile.age": 30,
            "device.peripheralId": "AA:BB:CC:DD:EE:FF",
            "noop.acceptedTermsVersion": "3",
            "sync.cursor": 12345,
            "profile.avatarImageData": "base64…",
        ]
        let data = try XCTUnwrap(BackupSettings.encode(dirty))
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertFalse(json.contains("peripheralId"))
        XCTAssertFalse(json.contains("cursor"))
        XCTAssertFalse(json.contains("avatar"))
        XCTAssertFalse(json.contains("acceptedTerms"))

        // Decode side: a hand-crafted settings.json can't smuggle keys in either.
        let crafted = Data(#"{"profile.age": 28, "injected.key": "evil", "profile.sex": "male"}"#.utf8)
        let back = BackupSettings.decode(crafted)
        XCTAssertNil(back["injected.key"])
        XCTAssertEqual(back["profile.age"] as? Int, 28)
        XCTAssertEqual(back["profile.sex"] as? String, "male")
    }

    func testWrongTypedValuesAreDroppedNotCoerced() {
        // Strings where numbers belong, numbers where strings belong, booleans posing as ints.
        let crafted = Data(#"{"profile.age": true, "profile.sex": 5, "profile.weightKg": "heavy", "profile.hrMax": 185}"#.utf8)
        let back = BackupSettings.decode(crafted)
        XCTAssertNil(back["profile.age"], "JSON true must never become age 1")
        XCTAssertNil(back["profile.sex"])
        XCTAssertNil(back["profile.weightKg"])
        XCTAssertEqual(back["profile.hrMax"] as? Int, 185, "Valid siblings still decode")
    }

    func testIntegralDoubleDecodesToIntForIntKeys() {
        // Android writes JSON numbers; 34.0 for an int-kind key must land as Int 34.
        let crafted = Data(#"{"profile.age": 34.0}"#.utf8)
        XCTAssertEqual(BackupSettings.decode(crafted)["profile.age"] as? Int, 34)
    }

    // MARK: - Degradation

    func testGarbageAndNonObjectJsonDecodeToEmpty() {
        XCTAssertTrue(BackupSettings.decode(Data("not json at all".utf8)).isEmpty)
        XCTAssertTrue(BackupSettings.decode(Data("[1,2,3]".utf8)).isEmpty)
        XCTAssertTrue(BackupSettings.decode(Data()).isEmpty)
    }

    func testEncodeReturnsNilWhenNothingWhitelistedIsPresent() {
        XCTAssertNil(BackupSettings.encode([:]))
        XCTAssertNil(BackupSettings.encode(["unrelated.key": 1]))
    }

    // MARK: - UserDefaults snapshot / apply (the platform boundary)

    func testSnapshotOmitsUnsetKeysAndMapsHrMaxOverride() throws {
        let defaults = try freshDefaults()
        defaults.set(29, forKey: "profile.age")
        defaults.set(82.5, forKey: "profile.weightKg")
        defaults.set(198, forKey: "profile.hrMaxOverride") // storage key, not the canonical name
        defaults.set("imperial", forKey: "units.system")
        defaults.set("metric", forKey: "units.distance")

        let snap = BackupSettings.snapshot(from: defaults)
        XCTAssertEqual(snap["profile.age"] as? Int, 29)
        XCTAssertEqual(snap["profile.weightKg"] as? Double, 82.5)
        XCTAssertEqual(snap["profile.hrMax"] as? Int, 198, "hrMaxOverride surfaces under the canonical key")
        XCTAssertEqual(snap["units.system"] as? String, "imperial")
        XCTAssertEqual(snap["units.distance"] as? String, "metric")
        XCTAssertNil(snap["profile.heightCm"], "Never-set keys are omitted, not defaulted")
        XCTAssertNil(snap["profile.sex"])
    }

    func testApplyWritesPlatformKeysAndLeavesUntouchedKeysAlone() throws {
        let defaults = try freshDefaults()
        defaults.set(175.0, forKey: "profile.heightCm") // pre-existing target value, not in payload

        BackupSettings.apply([
            "profile.age": 41,
            "profile.hrMax": 187,
            "units.temperature": "fahrenheit",
        ], to: defaults)

        XCTAssertEqual(defaults.object(forKey: "profile.age") as? Int, 41)
        XCTAssertEqual(defaults.object(forKey: "profile.hrMaxOverride") as? Int, 187,
                       "Canonical profile.hrMax lands on the profile.hrMaxOverride storage key")
        XCTAssertEqual(defaults.string(forKey: "units.temperature"), "fahrenheit")
        XCTAssertEqual(defaults.object(forKey: "profile.heightCm") as? Double, 175.0,
                       "Keys absent from the payload keep the target's value")
        XCTAssertNil(defaults.object(forKey: "profile.hrMax"),
                     "The canonical name itself is never written to defaults")
    }

    /// #146: applying a restored age must clear a pre-existing `profile.dateOfBirth`, so the target's
    /// old DOB can't silently override the restore — `ProfileStore` re-derives the DOB from the
    /// restored age on the forced post-restore relaunch.
    func testApplyClearsStaleDateOfBirthWhenAgeRestored() throws {
        let defaults = try freshDefaults()
        defaults.set(Date(timeIntervalSince1970: 0), forKey: "profile.dateOfBirth") // target's own DOB

        BackupSettings.apply(["profile.age": 44], to: defaults)

        XCTAssertEqual(defaults.object(forKey: "profile.age") as? Int, 44)
        XCTAssertNil(defaults.object(forKey: "profile.dateOfBirth"),
                     "A restored age must clear the target's stale DOB so it re-derives from the restore")
    }

    /// A restore payload with no age must leave a target's date of birth alone.
    func testApplyLeavesDateOfBirthWhenNoAgeInPayload() throws {
        let defaults = try freshDefaults()
        let dob = Date(timeIntervalSince1970: 500_000_000)
        defaults.set(dob, forKey: "profile.dateOfBirth")

        BackupSettings.apply(["profile.weightKg": 70.0], to: defaults)

        XCTAssertEqual(defaults.object(forKey: "profile.dateOfBirth") as? Date, dob,
                       "No age in the payload → the target's DOB is untouched")
    }

    func testFullExportImportShapedRoundTripThroughDefaults() throws {
        // Device A: user-set values → snapshot → encode (what export writes into the zip).
        let deviceA = try freshDefaults()
        deviceA.set(52, forKey: "profile.age")
        deviceA.set("nonbinary", forKey: "profile.sex")
        deviceA.set(90.25, forKey: "profile.weightKg")
        deviceA.set(0, forKey: "profile.hrMaxOverride") // explicit "auto" is still a value
        let payload = try XCTUnwrap(BackupSettings.encode(BackupSettings.snapshot(from: deviceA)))

        // Device B: decode → apply (what restore does after the DB swap succeeds).
        let deviceB = try freshDefaults()
        BackupSettings.apply(BackupSettings.decode(payload), to: deviceB)

        XCTAssertEqual(deviceB.object(forKey: "profile.age") as? Int, 52)
        XCTAssertEqual(deviceB.string(forKey: "profile.sex"), "nonbinary")
        XCTAssertEqual(deviceB.object(forKey: "profile.weightKg") as? Double, 90.25)
        XCTAssertEqual(deviceB.object(forKey: "profile.hrMaxOverride") as? Int, 0)
    }

    // MARK: - Suite-scoped defaults (never the test runner's real domain)

    private var suites: [String] = []

    private func freshDefaults() throws -> UserDefaults {
        let name = "BackupSettingsTests-\(UUID().uuidString)"
        guard let d = UserDefaults(suiteName: name) else {
            throw XCTSkip("Couldn't create a suite-scoped UserDefaults")
        }
        suites.append(name)
        return d
    }

    override func tearDown() {
        for name in suites { UserDefaults(suiteName: name)?.removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }
}
