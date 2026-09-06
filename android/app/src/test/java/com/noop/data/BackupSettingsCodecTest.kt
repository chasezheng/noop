package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The `settings.json` half of #1000 ("restore doesn't bring back settings/weight/height"): the pure
 * whitelist/JSON codec, plus the REAL ZIP container round trip through the same
 * [DataBackup.writeBackupZip] / [DataBackup.stageBackupSqlite] pair the live export/import uses.
 * Plain JVM (real org.json + java.util.zip, no Robolectric); the SharedPreferences apply/snapshot
 * bridge needs a Context and is covered by the shared restore path at the platform level.
 *
 * Twin of the Apple `BackupSettingsTests` in Packages/WhoopStore — the canonical keys and kinds
 * asserted here are the cross-platform contract, so a drift on either side fails one of the twins.
 */
class BackupSettingsCodecTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── Codec: encode/decode round trip ──────────────────────────────────────────

    @Test fun encodeDecodeRoundTripsEveryWhitelistedKey() {
        val values = mapOf(
            "profile.age" to 34,
            "profile.sex" to "female",
            "profile.weightKg" to 62.5,
            "profile.heightCm" to 168.0,
            "profile.waistCm" to 71.0,
            "profile.hrMax" to 191,
            "profile.hrZoneThresholds" to "95,118,142,168,184",
            "units.system" to "imperial",
            "units.distance" to "metric",
            "units.temperature" to "celsius",
            "units.skinTempDisplay" to "deviation",
            "effort.scale" to "whoop",
            "dayCycle.mode" to "sleep_onset",
            // #today-hosted-cards: the one layout pref carried, a JSON [String] stored under the String kind.
            "today.hostedCards" to "[\"sleep.sleepMarks\"]",
            // #1361: custom journal behaviours, a newline-joined name list — the embedded newline must
            // survive the JSON round-trip (and stay byte-identical to the Apple value).
            "journal.customBehaviors" to "Cold plunge\nMagnesium",
            // The wearer's own body measurement and the calorie knobs behind their day totals. The two
            // Bool knobs ride the Int kind — the wire has no boolean.
            "profile.vo2max" to 47.5,
            "calorie.model" to "hybrid",
            "calorie.preferOnDevice" to 1,
            "calorie.dayActiveHRRFraction" to 0.4,
            "calorie.boutActiveHRRFraction" to 0.3,
            "calorie.activeAccrualMET" to 1.6,
            "calorie.dynAccelMETGainPerG" to 28.0,
            "calorie.hrFallbackWhenNoMET" to 0,
            // Every field of the measured-basal model, each moved off its default so a key that
            // stopped crossing the wire cannot pass by coincidence.
            "calorie.sessionGapS" to 900,
            "calorie.minHrCoverageFrac" to 0.6,
            "calorie.hampelRadiusS" to 7,
            "calorie.hampelSigmas" to 2.5,
            "calorie.suppressPeaks" to 0,
            "calorie.peakBlockS" to 240,
            "calorie.peakPercentile" to 0.93,
            "calorie.motionStillG" to 0.015,
            "calorie.motionSmoothS" to 12,
            "calorie.basalMinWindowS" to 420,
            "calorie.basalHrRangeBpm" to 8.0,
            "calorie.basalStillFrac" to 0.95,
            "calorie.basalBeatCoverageFrac" to 0.4,
            "calorie.restSmoothS" to 45,
            "calorie.restSmoothMinSamples" to 25,
            "calorie.basalHrSeedOffsetBpm" to 4.0,
            "calorie.reserveRampBandBpm" to 12.0,
            "calorie.measuredBasalKcalDay" to 1577.0,
            "calorie.basalFatNight" to 0.75,
            "calorie.basalFatDay" to 0.35,
            "calorie.basalFatDayStartHour" to 9.0,
            "calorie.basalFatDayEndHour" to 21.0,
            "calorie.activeFatAtZone1" to 0.95,
            "calorie.activeFatAtZone2Top" to 0.6,
        )
        val json = requireNotNull(BackupSettingsCodec.encode(values))
        val back = BackupSettingsCodec.decode(json)

        assertEquals(34, back["profile.age"])
        assertEquals("female", back["profile.sex"])
        assertEquals(62.5, back["profile.weightKg"])
        assertEquals(168.0, back["profile.heightCm"])
        assertEquals(71.0, back["profile.waistCm"])
        assertEquals(191, back["profile.hrMax"])
        assertEquals("95,118,142,168,184", back["profile.hrZoneThresholds"])
        assertEquals("imperial", back["units.system"])
        assertEquals("metric", back["units.distance"])
        assertEquals("celsius", back["units.temperature"])
        assertEquals("deviation", back["units.skinTempDisplay"])
        assertEquals("whoop", back["effort.scale"])
        assertEquals("sleep_onset", back["dayCycle.mode"])
        assertEquals("[\"sleep.sleepMarks\"]", back["today.hostedCards"])
        assertEquals("Cold plunge\nMagnesium", back["journal.customBehaviors"])
        assertEquals(47.5, back["profile.vo2max"])
        assertEquals("hybrid", back["calorie.model"])
        assertEquals(1, back["calorie.preferOnDevice"])
        assertEquals(0.4, back["calorie.dayActiveHRRFraction"])
        assertEquals(0.3, back["calorie.boutActiveHRRFraction"])
        assertEquals(1.6, back["calorie.activeAccrualMET"])
        assertEquals(28.0, back["calorie.dynAccelMETGainPerG"])
        assertEquals(0, back["calorie.hrFallbackWhenNoMET"])
        assertEquals(900, back["calorie.sessionGapS"])
        assertEquals(0.6, back["calorie.minHrCoverageFrac"])
        assertEquals(7, back["calorie.hampelRadiusS"])
        assertEquals(2.5, back["calorie.hampelSigmas"])
        assertEquals(0, back["calorie.suppressPeaks"])
        assertEquals(240, back["calorie.peakBlockS"])
        assertEquals(0.93, back["calorie.peakPercentile"])
        assertEquals(0.015, back["calorie.motionStillG"])
        assertEquals(12, back["calorie.motionSmoothS"])
        assertEquals(420, back["calorie.basalMinWindowS"])
        assertEquals(8.0, back["calorie.basalHrRangeBpm"])
        assertEquals(0.95, back["calorie.basalStillFrac"])
        assertEquals(0.4, back["calorie.basalBeatCoverageFrac"])
        assertEquals(45, back["calorie.restSmoothS"])
        assertEquals(25, back["calorie.restSmoothMinSamples"])
        assertEquals(4.0, back["calorie.basalHrSeedOffsetBpm"])
        assertEquals(12.0, back["calorie.reserveRampBandBpm"])
        assertEquals(1577.0, back["calorie.measuredBasalKcalDay"])
        assertEquals(0.75, back["calorie.basalFatNight"])
        assertEquals(0.35, back["calorie.basalFatDay"])
        assertEquals(9.0, back["calorie.basalFatDayStartHour"])
        assertEquals(21.0, back["calorie.basalFatDayEndHour"])
        assertEquals(0.95, back["calorie.activeFatAtZone1"])
        assertEquals(0.6, back["calorie.activeFatAtZone2Top"])
        assertEquals(values.size, back.size)
        assertEquals("every whitelisted key must be exercised here",
                     BackupSettingsCodec.WHITELIST.size, values.size)
    }

    @Test fun crossPlatformShapedJsonDecodes() {
        // What the Apple exporter writes (JSONSerialization, sorted keys, integral doubles possible).
        val appleJson = """{"profile.age":34.0,"profile.hrMax":191,"profile.sex":"male","profile.weightKg":80,"units.system":"metric"}"""
        val back = BackupSettingsCodec.decode(appleJson)
        assertEquals("Integral JSON numbers must land as Int for int-kind keys", 34, back["profile.age"])
        assertEquals(191, back["profile.hrMax"])
        assertEquals("A bare JSON int must land as Double for double-kind keys", 80.0, back["profile.weightKg"])
        assertEquals("male", back["profile.sex"])
        assertEquals("metric", back["units.system"])
    }

    // ── Codec: whitelist + type enforcement ──────────────────────────────────────

    @Test fun nonWhitelistedKeysAreDroppedOnEncodeAndDecode() {
        val json = requireNotNull(
            BackupSettingsCodec.encode(
                mapOf(
                    "profile.age" to 30,
                    "device.peripheralId" to "AA:BB:CC:DD:EE:FF",
                    "sync.cursor" to 12345,
                ),
            ),
        )
        assertFalse(json.contains("peripheralId"))
        assertFalse(json.contains("cursor"))

        val back = BackupSettingsCodec.decode("""{"profile.age": 28, "injected.key": "evil"}""")
        assertNull(back["injected.key"])
        assertEquals(28, back["profile.age"])
    }

    @Test fun wrongTypedValuesAreDroppedNotCoerced() {
        val back = BackupSettingsCodec.decode(
            """{"profile.age": true, "profile.sex": 5, "profile.weightKg": "heavy", "profile.hrMax": 185}""",
        )
        assertNull("JSON true must never become age 1", back["profile.age"])
        assertNull(back["profile.sex"])
        assertNull(back["profile.weightKg"])
        assertEquals("Valid siblings still decode", 185, back["profile.hrMax"])
    }

    @Test fun garbageDecodesToEmptyAndEmptyEncodesToNull() {
        assertTrue(BackupSettingsCodec.decode("not json at all").isEmpty())
        assertTrue(BackupSettingsCodec.decode("[1,2,3]").isEmpty())
        assertNull(BackupSettingsCodec.encode(emptyMap()))
        assertNull(BackupSettingsCodec.encode(mapOf("unrelated.key" to 1)))
    }

    // ── Container: settings entry round-trips through the real ZIP layer ─────────

    /** The 16-byte SQLite magic, so the staged file passes the importer's header validation. */
    private val sqliteMagic = byteArrayOf(
        0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    private fun fakeSqlite(payload: String): File {
        val f = tmp.newFile()
        f.outputStream().use { it.write(sqliteMagic); it.write(payload.toByteArray()) }
        return f
    }

    @Test fun zipWithSettingsStagesBothDbAndSettings() {
        val liveDb = fakeSqlite("rows")
        val settingsJson = requireNotNull(
            BackupSettingsCodec.encode(mapOf("profile.age" to 41, "profile.weightKg" to 90.5)),
        )
        val backup = tmp.newFile("with-settings.noopbak")
        DataBackup.writeBackupZip(liveDb, backup, settingsJson)

        val stagedDb = tmp.newFile()
        val stagedSettings = File(tmp.root, "staged-settings.json")
        val result = DataBackup.stageBackupSqlite(
            backup.inputStream(), DataBackup.peekHeader(backup), stagedDb, stagedSettings,
        )

        assertEquals(DataBackup.StageResult.OK, result)
        assertEquals(liveDb.readBytes().toList(), stagedDb.readBytes().toList())
        assertTrue("settings.json must be staged alongside the DB", stagedSettings.exists())
        val back = BackupSettingsCodec.decode(stagedSettings.readText(Charsets.UTF_8))
        assertEquals(41, back["profile.age"])
        assertEquals(90.5, back["profile.weightKg"])
    }

    @Test fun legacySingleEntryZipStagesDbAndNoSettings() {
        val liveDb = fakeSqlite("legacy-rows")
        val backup = tmp.newFile("legacy.noopbak")
        DataBackup.writeBackupZip(liveDb, backup) // settingsJson defaults null → pre-#1000 shape

        val stagedDb = tmp.newFile()
        val stagedSettings = File(tmp.root, "staged-settings.json")
        val result = DataBackup.stageBackupSqlite(
            backup.inputStream(), DataBackup.peekHeader(backup), stagedDb, stagedSettings,
        )

        assertEquals("A legacy 1-entry zip still stages fine", DataBackup.StageResult.OK, result)
        assertEquals(liveDb.readBytes().toList(), stagedDb.readBytes().toList())
        assertFalse("No settings entry → no staged settings, no error", stagedSettings.exists())
    }

    @Test fun stagingWithoutSettingsDestStillWorksAsBefore() {
        // The pre-#1000 call shape (no settingsDest) keeps working for a 2-entry zip.
        val liveDb = fakeSqlite("rows2")
        val backup = tmp.newFile("two-entry.noopbak")
        DataBackup.writeBackupZip(liveDb, backup, """{"profile.age":30}""")

        val stagedDb = tmp.newFile()
        val result = DataBackup.stageBackupSqlite(backup.inputStream(), DataBackup.peekHeader(backup), stagedDb)
        assertEquals(DataBackup.StageResult.OK, result)
        assertEquals(liveDb.readBytes().toList(), stagedDb.readBytes().toList())
    }

    @Test fun zipWithNonCanonicalSqliteEntryIsRejected() {
        val backup = tmp.newFile("wrong-entry.noopbak")
        ZipOutputStream(backup.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("evil.sqlite"))
            zip.write(sqliteMagic)
            zip.write("rows".toByteArray())
            zip.closeEntry()
        }

        val stagedDb = tmp.newFile()
        val result = DataBackup.stageBackupSqlite(
            backup.inputStream(), DataBackup.peekHeader(backup), stagedDb,
        )

        assertEquals(DataBackup.StageResult.NO_DB_IN_ZIP, result)
    }
}
