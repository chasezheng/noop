package com.noop.ui

import android.content.SharedPreferences
import com.noop.analytics.calorie.DynamicHrrModelSetting
import com.noop.analytics.calorie.DynamicHrrModelSettingRanges
import com.noop.analytics.calorie.EnergyModel
import com.noop.analytics.calorie.HeartRateGates
import com.noop.analytics.calorie.HeartRateGatesRanges
import com.noop.analytics.calorie.HybridModelSetting
import com.noop.analytics.calorie.HybridModelSettingRanges
import com.noop.data.BackupSettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence half of Settings → Calorie tracking.
 *
 * The contract that matters is that a preference can never reach an engine as a value the engine
 * does not accept. A gate stored as a negative fraction, or a gap cap of zero, would not crash — it
 * would quietly produce a day total with no physical meaning, and the wearer would report the number
 * rather than the setting. So every getter clamps, and an absent or unreadable preference falls back
 * to the shipped default rather than to zero.
 *
 * No Robolectric (junit only) — the real [ProfileStore] runs over an in-memory
 * [FakeSharedPreferences], the same per-file fake the other ProfileStore tests use.
 */
class CalorieSettingsPrefsTest {

    @Test
    fun everyKnob_roundTripsIntoItsOwnField() {
        // Guards TRANSPOSITION, which pinning the default values cannot. Both %HRR knobs ship at
        // 0.10 and their getters differ only in which preference key and which default they pass, so
        // swapping the two lines changes no number today and passes every other test — it would
        // surface only once the two defaults diverge, as a settings bug with no obvious cause. The
        // six values below are deliberately distinct so no pairing can be swapped undetected. It
        // guards the .noopbak keys by the same argument.
        val prefs = FakeSharedPreferences()
        ProfileStore(prefs).apply {
            calorieModel = EnergyModel.HYBRID
            calorieDayActiveHRRFraction = 0.11
            calorieBoutActiveHRRFraction = 0.22
            calorieActiveAccrualMET = 2.5
            calorieDynAccelMETGainPerG = 44.0
            calorieHrFallbackWhenNoMET = false
        }

        // Read back through a FRESH store, so the assertion crosses the preference keys rather than
        // any state the writing instance happens to hold.
        val fresh = ProfileStore(prefs)
        val gates = fresh.toHeartRateGates()
        val hybrid = fresh.toHybridModelSetting()
        assertEquals(EnergyModel.HYBRID, fresh.calorieModel)
        assertEquals(0.11, gates.dayActiveHRRFraction, 1e-12)
        assertEquals(0.22, gates.boutActiveHRRFraction, 1e-12)
        assertEquals(2.5, hybrid.activeAccrualMET, 1e-12)
        assertEquals(44.0, hybrid.dynAccelMETGainPerG, 1e-12)
        assertEquals(false, hybrid.hrFallbackWhenNoMET)
    }


    @Test
    fun freshInstall_readsTheEngineDefaults() {
        val profile = ProfileStore(FakeSharedPreferences())
        assertEquals(HeartRateGates(), profile.toHeartRateGates())
        assertEquals(HybridModelSetting(), profile.toHybridModelSetting())
    }

    @Test
    fun freshInstall_usesTheHeartRateModel() {
        val profile = ProfileStore(FakeSharedPreferences())
        assertEquals(EnergyModel.HEART_RATE, profile.calorieModel)
    }

    @Test
    fun theModelChoice_roundTrips() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieModel = EnergyModel.HYBRID
        assertEquals(EnergyModel.HYBRID, profile.calorieModel)
    }

    @Test
    fun anUnrecognisedStoredModel_fallsBackToHeartRate() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("calorie_model", "something-we-never-shipped").apply()
        assertEquals(EnergyModel.HEART_RATE, ProfileStore(prefs).calorieModel)
    }

    @Test
    fun aNegativeGate_isClampedToZero() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieDayActiveHRRFraction = -1.0
        assertEquals(0.0, profile.calorieDayActiveHRRFraction, 0.0)
    }

    @Test
    fun anAbsurdlyHighGate_isClampedToTheCeiling() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieDayActiveHRRFraction = 5.0
        assertEquals(HeartRateGatesRanges.HRR_MAX, profile.calorieDayActiveHRRFraction, 0.0)
    }

    @Test
    fun anOutOfRangeStoredValue_readsBackAsTheClampedBound() {
        // A preference written by a future build with a wider range must not reach the engine as-is.
        // Knobs are stored as the Double's raw bits (see ProfileStore.calorieKnob), so the test writes
        // what the store actually writes rather than a shape it would never produce.
        val prefs = FakeSharedPreferences()
        prefs.edit().putLong("calorie_dyn_accel_met_gain", 100_000.0.toRawBits()).apply()
        assertEquals(HybridModelSettingRanges.MET_GAIN_MAX, ProfileStore(prefs).calorieDynAccelMETGainPerG, 0.0)
    }

    @Test
    fun anOutOfRangeStoredMeasuredBasalValue_readsBackAsTheClampedBound() {
        // The same defence for the range-typed knobs, which every measured-basal setting uses, and for
        // the Int store. Written straight into preferences rather than through the setter: the setter
        // clamps too, so a test that goes through it is satisfied by either clamp alone and cannot say
        // which one held. This is the half that answers a restored backup or an older build's value.
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putLong("calorie_dhrr_basal_still_frac", 4.0.toRawBits())
            .putInt("calorie_dhrr_hampel_radius_s", 9_000)
            .apply()

        val profile = ProfileStore(prefs)

        assertEquals(DynamicHrrModelSettingRanges.QUIET_STRETCH_MIN_STILL_FRAC.endInclusive, profile.calorieDhrrQuietStretchMinStillFrac, 0.0)
        assertEquals(DynamicHrrModelSettingRanges.SPIKE_WINDOW_RADIUS_S.last, profile.calorieDhrrSpikeWindowRadiusS)
    }

    @Test
    fun resetting_restoresEveryKnobToTheEngineDefault() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieModel = EnergyModel.HYBRID
        profile.calorieDayActiveHRRFraction = 0.5
        profile.calorieDynAccelMETGainPerG = 90.0
        profile.calorieHrFallbackWhenNoMET = false

        profile.resetCalorieSettings()

        assertEquals(HeartRateGates(), profile.toHeartRateGates())
        assertEquals(HybridModelSetting(), profile.toHybridModelSetting())
    }

    // ── The knobs survive a backup and restore ────────────────────────────────────────────────

    @Test
    fun anUntouchedInstall_exportsNoCalorieKnobs() {
        // Only what the wearer actually chose travels, so restoring onto a device that made its own
        // choices overwrites none of them.
        val exported = ProfileStore(FakeSharedPreferences()).backupSnapshot()
        assertTrue(exported.keys.none { it.startsWith("calorie.") })
        assertTrue("profile.vo2max" !in exported)
    }

    // ── The measured-basal settings ───────────────────────────────────────────────────────────

    /** Every field moved off its default, and each to a distinct value. */
    private fun ProfileStore.chooseEveryMeasuredBasalSetting() {
        calorieDhrrWearSessionMaxSilenceS = 900
        calorieDhrrMinHrCoverageFrac = 0.61
        calorieDhrrSpikeWindowRadiusS = 7
        calorieDhrrSpikeThresholdSigmas = 2.5
        calorieDhrrPeakClipEnabled = false
        calorieDhrrPeakClipBlockS = 240
        calorieDhrrPeakClipKeptFrac = 0.93
        calorieDhrrStillMaxG = 0.015
        calorieDhrrStillSmoothingS = 12
        calorieDhrrQuietStretchMinLengthS = 420
        calorieDhrrQuietStretchMaxRiseBpm = 8.0
        calorieDhrrQuietStretchMinStillFrac = 0.95
        calorieDhrrQuietStretchMinBeatFrac = 0.41
        calorieDhrrBasalLowerWindowS = 45
        calorieDhrrBasalLowerMinSamples = 25
        calorieDhrrBasalSeedOffsetBpm = 4.0
        calorieDhrrReserveRampBandBpm = 12.0
        calorieDhrrRestingEnergyKcalPerDay = 1_577.0
        calorieDhrrRestingFatNightFrac = 0.75
        calorieDhrrRestingFatDayFrac = 0.35
        calorieDhrrRestingFatDayStartHour = 9.0
        calorieDhrrRestingFatDayEndHour = 21.0
        calorieDhrrActiveFatZone1Frac = 0.95
        calorieDhrrActiveFatZone2TopFrac = 0.6
    }

    private val chosenMeasuredBasal = DynamicHrrModelSetting(
        wearSessionMaxSilenceS = 900,
        minHrCoverageFrac = 0.61,
        spikeWindowRadiusS = 7,
        spikeThresholdSigmas = 2.5,
        peakClipEnabled = false,
        peakClipBlockS = 240,
        peakClipKeptFrac = 0.93,
        stillMaxG = 0.015,
        stillSmoothingS = 12,
        quietStretchMinLengthS = 420,
        quietStretchMaxRiseBpm = 8.0,
        quietStretchMinStillFrac = 0.95,
        quietStretchMinBeatFrac = 0.41,
        basalLowerWindowS = 45,
        basalLowerMinSamples = 25,
        basalSeedOffsetBpm = 4.0,
        reserveRampBandBpm = 12.0,
        restingEnergyKcalPerDay = 1_577.0,
        restingFatNightFrac = 0.75,
        restingFatDayFrac = 0.35,
        restingFatDayStartHour = 9.0,
        restingFatDayEndHour = 21.0,
        activeFatZone1Frac = 0.95,
        activeFatZone2TopFrac = 0.6,
    )

    @Test
    fun everyMeasuredBasalSetting_roundTripsIntoItsOwnField() {
        // Guards TRANSPOSITION as above: twenty-four getters differ only in which preference key and
        // which default they pass, so two swapped lines would change no number until the two defaults
        // diverged. Every value below is distinct, so no pairing can be swapped undetected.
        val prefs = FakeSharedPreferences()
        ProfileStore(prefs).chooseEveryMeasuredBasalSetting()

        assertEquals(chosenMeasuredBasal, ProfileStore(prefs).toDynamicHrrModelSetting())
    }

    @Test
    fun freshInstall_readsTheMeasuredBasalDefaults() {
        assertEquals(
            DynamicHrrModelSetting(),
            ProfileStore(FakeSharedPreferences()).toDynamicHrrModelSetting(),
        )
    }

    @Test
    fun resetting_restoresEveryMeasuredBasalSettingToTheEngineDefault() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.chooseEveryMeasuredBasalSetting()

        profile.resetCalorieSettings()

        assertEquals(DynamicHrrModelSetting(), profile.toDynamicHrrModelSetting())
    }

    @Test
    fun everyMeasuredBasalSetting_survivesABackupAndRestore() {
        val source = ProfileStore(FakeSharedPreferences())
        source.chooseEveryMeasuredBasalSetting()

        val restored = ProfileStore(FakeSharedPreferences())
        restored.applyBackup(source.backupSnapshot())

        assertEquals(chosenMeasuredBasal, restored.toDynamicHrrModelSetting())
    }

    @Test
    fun aMeasuredBasalSettingAboveItsRange_readsBackAsTheCeiling() {
        // Twenty-three ranges, two storage mechanisms: a Double stored as its raw bits and an Int.
        // The clamp lives in the mechanism, so one value of each kind covers all of them.
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieDhrrQuietStretchMinStillFrac = 4.0
        profile.calorieDhrrSpikeWindowRadiusS = 9_000

        assertEquals(1.0, profile.calorieDhrrQuietStretchMinStillFrac, 0.0)
        assertEquals(30, profile.calorieDhrrSpikeWindowRadiusS)
    }

    @Test
    fun aMeasuredBasalSettingBelowItsRange_readsBackAsTheFloor() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieDhrrQuietStretchMinStillFrac = -1.0
        profile.calorieDhrrSpikeWindowRadiusS = 0

        assertEquals(0.5, profile.calorieDhrrQuietStretchMinStillFrac, 0.0)
        assertEquals(1, profile.calorieDhrrSpikeWindowRadiusS)
    }

    // ── The measured-basal keys renamed, read back through their new names ─────────────────

    @Test
    fun aValueStoredUnderTheOldKeyName_readsBackThroughTheNewOne() {
        // A device that has been running the build from before the rename. All three stored kinds:
        // an Int count, a Double as raw Long bits, a Boolean.
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putInt("calorie_dhrr_basal_min_window_s", 900)
            .putLong("calorie_dhrr_basal_still_frac", 0.93.toRawBits())
            .putBoolean("calorie_dhrr_suppress_peaks", false)
            .apply()

        val profile = ProfileStore(prefs)

        assertEquals(900, profile.calorieDhrrQuietStretchMinLengthS)
        assertEquals(0.93, profile.calorieDhrrQuietStretchMinStillFrac, 0.0)
        assertEquals(false, profile.calorieDhrrPeakClipEnabled)
    }

    @Test
    fun theOldKeyNameIsGoneOnceItHasBeenCarriedOver() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putInt("calorie_dhrr_basal_min_window_s", 900).apply()

        ProfileStore(prefs)

        assertTrue("the old name must not be left behind", !prefs.contains("calorie_dhrr_basal_min_window_s"))
        assertEquals(900, prefs.getInt("calorie_dhrr_quiet_stretch_min_length_s", 0))
    }

    @Test
    fun aValueAlreadyStoredUnderTheNewKeyName_survivesTheOldOne() {
        // The wearer moved this setting after the rename, so the new name is the deliberate choice
        // and the stale old one must not overwrite it.
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putInt("calorie_dhrr_basal_min_window_s", 900)
            .putInt("calorie_dhrr_quiet_stretch_min_length_s", 1_200)
            .apply()

        val profile = ProfileStore(prefs)

        assertEquals(1_200, profile.calorieDhrrQuietStretchMinLengthS)
        assertTrue(!prefs.contains("calorie_dhrr_basal_min_window_s"))
    }

    @Test
    fun aSettingNeitherNameHolds_isNotWrittenByTheMigration() {
        // [backupSnapshot] exports on key PRESENCE, so a migration that defaulted an absent key would
        // mark a shipped default as a deliberate choice and let a backup stamp it over another device.
        val prefs = FakeSharedPreferences()

        val profile = ProfileStore(prefs)

        assertTrue(!prefs.contains("calorie_dhrr_quiet_stretch_min_length_s"))
        assertEquals(0, profile.backupSnapshot().keys.count { it.startsWith("calorie.") })
    }

    @Test
    fun everyRenamedKeyCarriesOver() {
        // Names the whole table rather than one entry of it, so an entry omitted from
        // RENAMED_CALORIE_KEYS fails here instead of silently resetting that one setting.
        val prefs = FakeSharedPreferences()
        val edit = prefs.edit()
        for ((key, value) in LEGACY_INT_KEYS) edit.putInt(key, value)
        for ((key, value) in LEGACY_DOUBLE_KEYS) edit.putLong(key, value.toRawBits())
        edit.putBoolean("calorie_dhrr_suppress_peaks", false)
        edit.apply()

        val setting = ProfileStore(prefs).toDynamicHrrModelSetting()

        assertEquals(1_500, setting.wearSessionMaxSilenceS)
        assertEquals(11, setting.spikeWindowRadiusS)
        assertEquals(2.25, setting.spikeThresholdSigmas, 0.0)
        assertEquals(false, setting.peakClipEnabled)
        assertEquals(210, setting.peakClipBlockS)
        assertEquals(0.91, setting.peakClipKeptFrac, 0.0)
        assertEquals(0.031, setting.stillMaxG, 0.0)
        assertEquals(14, setting.stillSmoothingS)
        assertEquals(900, setting.quietStretchMinLengthS)
        assertEquals(7.5, setting.quietStretchMaxRiseBpm, 0.0)
        assertEquals(0.93, setting.quietStretchMinStillFrac, 0.0)
        assertEquals(0.41, setting.quietStretchMinBeatFrac, 0.0)
        assertEquals(55, setting.basalLowerWindowS)
        assertEquals(31, setting.basalLowerMinSamples)
        assertEquals(6.5, setting.basalSeedOffsetBpm, 0.0)
        assertEquals(1_611.0, setting.restingEnergyKcalPerDay, 0.0)
        assertEquals(0.71, setting.restingFatNightFrac, 0.0)
        assertEquals(0.41, setting.restingFatDayFrac, 0.0)
        assertEquals(8.0, setting.restingFatDayStartHour, 0.0)
        assertEquals(21.0, setting.restingFatDayEndHour, 0.0)
        assertEquals(0.94, setting.activeFatZone1Frac, 0.0)
        assertEquals(0.61, setting.activeFatZone2TopFrac, 0.0)
        for (key in LEGACY_INT_KEYS.keys + LEGACY_DOUBLE_KEYS.keys) {
            assertTrue("$key must not be left behind", !prefs.contains(key))
        }
    }

    /** Pre-rename names of the Int settings, with a value distinct from every default. */
    private val LEGACY_INT_KEYS = mapOf(
        "calorie_dhrr_session_gap_s" to 1_500,
        "calorie_dhrr_hampel_radius_s" to 11,
        "calorie_dhrr_peak_block_s" to 210,
        "calorie_dhrr_motion_smooth_s" to 14,
        "calorie_dhrr_basal_min_window_s" to 900,
        "calorie_dhrr_rest_smooth_s" to 55,
        "calorie_dhrr_rest_smooth_min_samples" to 31,
    )

    /** The same for the Double settings, which store their raw bits under a Long. */
    private val LEGACY_DOUBLE_KEYS = mapOf(
        "calorie_dhrr_hampel_sigmas" to 2.25,
        "calorie_dhrr_peak_percentile" to 0.91,
        "calorie_dhrr_motion_still_g" to 0.031,
        "calorie_dhrr_basal_hr_range_bpm" to 7.5,
        "calorie_dhrr_basal_still_frac" to 0.93,
        "calorie_dhrr_basal_beat_coverage_frac" to 0.41,
        "calorie_dhrr_basal_hr_seed_offset_bpm" to 6.5,
        "calorie_dhrr_measured_basal_kcal_day" to 1_611.0,
        "calorie_dhrr_basal_fat_night" to 0.71,
        "calorie_dhrr_basal_fat_day" to 0.41,
        "calorie_dhrr_basal_fat_day_start_hour" to 8.0,
        "calorie_dhrr_basal_fat_day_end_hour" to 21.0,
        "calorie_dhrr_active_fat_at_zone1" to 0.94,
        "calorie_dhrr_active_fat_at_zone2_top" to 0.61,
    )

    // In-memory SharedPreferences reproducing the read/write contract ProfileStore relies on.
    private class FakeSharedPreferences : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = FakeEditor()

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { removed.add(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean { flush(); return true }
            override fun apply() { flush() }
            private fun flush() {
                for (k in removed) map.remove(k)
                map.putAll(pending)
                pending.clear(); removed.clear()
            }
        }
    }
}
