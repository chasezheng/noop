package com.noop.calbench

import com.noop.analytics.calorie.HeartRateGates
import com.noop.analytics.calorie.HybridModelSetting
import com.noop.analytics.calorie.EnergyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Opening a backup: which container it is, what its `settings.json` means, and when to refuse it. */
class BackupSourceTest {

    @Test
    fun `a noopbak and the bare sqlite inside it read the same`() = withTempDir { dir ->
        val zipped = TestStore().device("my-whoop").hr("my-whoop", 1_700_000_000L, 60)
            .writeNoopbak(File(dir, "a.noopbak"), settings = """{"profile.weightKg": 81.5}""")
        val bare = TestStore().device("my-whoop").hr("my-whoop", 1_700_000_000L, 60)
            .writeSqlite(File(dir, "b.sqlite"))

        BackupSource.open(zipped).use { source ->
            assertEquals(35, source.manifest.schemaVersion)
            assertEquals("android", source.manifest.platform)
            assertEquals(1_788_045_497_009L, source.manifest.exportedAtMs)
            assertEquals(81.5, source.profile.weightKg, 0.0)
        }
        BackupSource.open(bare).use { source ->
            // No manifest beside it, so the version comes off the file itself and the profile is the
            // app's own defaults — not [com.noop.analytics.UserProfile]'s.
            assertEquals(35, source.manifest.schemaVersion)
            assertEquals("", source.manifest.platform)
            assertEquals(75.0, source.profile.weightKg, 0.0)
            assertEquals(178.0, source.profile.heightCm, 0.0)
            assertEquals("male", source.profile.sex)
        }
    }

    @Test
    fun `a bare sqlite reads the settings and manifest sitting beside it`() = withTempDir { dir ->
        val bare = TestStore().device("my-whoop").writeSqlite(File(dir, "noop-backup.sqlite"))
        File(dir, "settings.json").writeText("""{"profile.age": 41}""")
        File(dir, "manifest.json").writeText(TestStore.MANIFEST)

        BackupSource.open(bare).use { source ->
            assertEquals(41.0, source.profile.age, 0.0)
            assertEquals("10.7.0", source.manifest.appVersion)
        }
    }

    @Test
    fun `settings map onto the profile and the tuning, and a missing key keeps the shipped default`() =
        withTempDir { dir ->
            val settings = """
                {
                  "profile.age": 34, "profile.sex": "female", "profile.weightKg": 62.0,
                  "profile.heightCm": 168.0, "profile.waistCm": 71.0, "profile.hrMax": 188,
                  "profile.vo2max": 47.5,
                  "calorie.model": "hybrid",
                  "calorie.dayActiveHRRFraction": 0.2, "calorie.activeAccrualMET": 2.0,
                  "calorie.dynAccelMETGainPerG": 25.0
                }
            """
            val file = TestStore().device("my-whoop").writeNoopbak(File(dir, "a.noopbak"), settings = settings)

            BackupSource.open(file).use { source ->
                val gates = source.profile.heartRateGates
                val hybrid = source.profile.hybridModelSetting
                assertEquals(34.0, source.profile.age, 0.0)
                assertEquals("female", source.profile.sex)
                assertEquals(71.0, source.profile.waistCm, 0.0)
                assertEquals(47.5, source.profile.vo2maxOverride, 0.0)
                assertEquals(188.0, source.maxHROverride)
                assertEquals(EnergyModel.HYBRID, source.profile.calorieModel)
                assertEquals(0.2, gates.dayActiveHRRFraction, 0.0)
                assertEquals(2.0, hybrid.activeAccrualMET, 0.0)
                assertEquals(25.0, hybrid.dynAccelMETGainPerG, 0.0)
                // Neither key is exported unless the wearer moved it, so both stay on the engine's own.
                assertEquals(HeartRateGates().boutActiveHRRFraction, gates.boutActiveHRRFraction, 0.0)
                assertEquals(HybridModelSetting().hrFallbackWhenNoMET, hybrid.hrFallbackWhenNoMET)
            }
        }

    @Test
    fun `every measured-basal setting maps onto the model that reads it`() = withTempDir { dir ->
        // The harness restates the app's whitelist by hand, so a key added on one side and forgotten
        // on this one would silently score every backup on the shipped default.
        val settings = """
            {
              "profile.hrZoneThresholds": "88,110,132,154,176",
              "calorie.sessionGapS": 900, "calorie.minHrCoverageFrac": 0.6,
              "calorie.hampelRadiusS": 7, "calorie.hampelSigmas": 2.5,
              "calorie.suppressPeaks": 0, "calorie.peakBlockS": 240,
              "calorie.peakPercentile": 0.93, "calorie.motionStillG": 0.015, "calorie.motionSmoothS": 12,
              "calorie.basalMinWindowS": 420, "calorie.basalHrRangeBpm": 8.0,
              "calorie.basalStillFrac": 0.95, "calorie.basalBeatCoverageFrac": 0.4,
              "calorie.restSmoothS": 45, "calorie.restSmoothMinSamples": 25,
              "calorie.basalHrSeedOffsetBpm": 4.0, "calorie.reserveRampBandBpm": 12.0,
              "calorie.measuredBasalKcalDay": 1577.0,
              "calorie.basalFatNight": 0.75, "calorie.basalFatDay": 0.35,
              "calorie.basalFatDayStartHour": 9.0, "calorie.basalFatDayEndHour": 21.0,
              "calorie.activeFatAtZone1": 0.95, "calorie.activeFatAtZone2Top": 0.6
            }
        """
        val file = TestStore().device("my-whoop").writeNoopbak(File(dir, "a.noopbak"), settings = settings)

        BackupSource.open(file).use { source ->
            val measured = source.profile.dynamicHrrModelSetting
            assertEquals(listOf(88.0, 110.0, 132.0, 154.0, 176.0), source.profile.hrZoneThresholds)
            assertEquals(900, measured.wearSessionMaxSilenceS)
            assertEquals(0.6, measured.minHrCoverageFrac, 0.0)
            assertEquals(7, measured.spikeWindowRadiusS)
            assertEquals(2.5, measured.spikeThresholdSigmas, 0.0)
            assertEquals(false, measured.peakClipEnabled)
            assertEquals(240, measured.peakClipBlockS)
            assertEquals(0.93, measured.peakClipKeptFrac, 0.0)
            assertEquals(0.015, measured.stillMaxG, 0.0)
            assertEquals(12, measured.stillSmoothingS)
            assertEquals(420, measured.quietStretchMinLengthS)
            assertEquals(8.0, measured.quietStretchMaxRiseBpm, 0.0)
            assertEquals(0.95, measured.quietStretchMinStillFrac, 0.0)
            assertEquals(0.4, measured.quietStretchMinBeatFrac, 0.0)
            assertEquals(45, measured.basalLowerWindowS)
            assertEquals(25, measured.basalLowerMinSamples)
            assertEquals(4.0, measured.basalSeedOffsetBpm, 0.0)
            assertEquals(12.0, measured.reserveRampBandBpm, 0.0)
            assertEquals(1577.0, measured.restingEnergyKcalPerDay, 0.0)
            assertEquals(0.75, measured.restingFatNightFrac, 0.0)
            assertEquals(0.35, measured.restingFatDayFrac, 0.0)
            assertEquals(9.0, measured.restingFatDayStartHour, 0.0)
            assertEquals(21.0, measured.restingFatDayEndHour, 0.0)
            assertEquals(0.95, measured.activeFatZone1Frac, 0.0)
            assertEquals(0.6, measured.activeFatZone2TopFrac, 0.0)
        }
    }

    @Test
    fun `a measured-basal setting outside the range the model accepts is clamped`() = withTempDir { dir ->
        val file = TestStore().device("my-whoop").writeNoopbak(
            File(dir, "a.noopbak"),
            settings = """{"calorie.basalStillFrac": 4.0, "calorie.hampelRadiusS": 9000}""",
        )
        BackupSource.open(file).use { source ->
            assertEquals(1.0, source.profile.dynamicHrrModelSetting.quietStretchMinStillFrac, 0.0)
            assertEquals(30, source.profile.dynamicHrrModelSetting.spikeWindowRadiusS)
        }
    }

    @Test
    fun `zone thresholds that are not five increasing values are treated as absent`() = withTempDir { dir ->
        val file = TestStore().device("my-whoop").writeNoopbak(
            File(dir, "a.noopbak"),
            settings = """{"profile.hrZoneThresholds": "120,110,132,154,176"}""",
        )
        BackupSource.open(file).use { assertNull(it.profile.hrZoneThresholds) }
    }

    @Test
    fun `an hrMax of zero is automatic, not an override of zero`() = withTempDir { dir ->
        val file = TestStore().device("my-whoop")
            .writeNoopbak(File(dir, "a.noopbak"), settings = """{"profile.hrMax": 0}""")
        BackupSource.open(file).use { assertNull(it.maxHROverride) }
    }

    @Test
    fun `a wrong-typed or unknown value is dropped rather than guessed at`() {
        val decoded = BackupSource.decodeSettings(
            """{"profile.age": true, "profile.sex": 4, "calorie.model": "hybrid", "nonsense.key": 9}""",
        )
        assertEquals(mapOf("calorie.model" to "hybrid"), decoded)
        assertEquals(emptyMap<String, Any>(), BackupSource.decodeSettings("not json at all"))
    }

    @Test
    fun `a backup missing a table the calorie path reads is refused, not half-read`() = withTempDir { dir ->
        val file = TestStore().apply { omit = setOf("gravitySample") }
            .device("my-whoop")
            .writeNoopbak(File(dir, "old.noopbak"))
        try {
            BackupSource.open(file).close()
            fail("expected the too-old backup to be refused")
        } catch (e: UnreadableBackup) {
            assertEquals(35, e.schemaVersion)
            assertTrue(e.message!!, e.message!!.contains("gravitySample"))
        }
    }

    @Test
    fun `a file that is neither a ZIP nor a SQLite is refused`() = withTempDir { dir ->
        val file = File(dir, "notes.txt").apply { writeText("this is not a backup") }
        try {
            BackupSource.open(file).close()
            fail("expected the non-backup to be refused")
        } catch (e: UnreadableBackup) {
            assertNull(e.schemaVersion)
        }
    }
}
