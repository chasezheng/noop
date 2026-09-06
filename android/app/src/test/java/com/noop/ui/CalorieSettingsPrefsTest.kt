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

        assertEquals(DynamicHrrModelSettingRanges.BASAL_STILL_FRAC.endInclusive, profile.calorieDhrrBasalStillFrac, 0.0)
        assertEquals(DynamicHrrModelSettingRanges.HAMPEL_RADIUS_S.last, profile.calorieDhrrHampelRadiusS)
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
        calorieDhrrSessionGapS = 900
        calorieDhrrMinHrCoverageFrac = 0.61
        calorieDhrrHampelRadiusS = 7
        calorieDhrrHampelSigmas = 2.5
        calorieDhrrSuppressPeaks = false
        calorieDhrrPeakBlockS = 240
        calorieDhrrPeakPercentile = 0.93
        calorieDhrrMotionStillG = 0.015
        calorieDhrrMotionSmoothS = 12
        calorieDhrrBasalMinWindowS = 420
        calorieDhrrBasalHrRangeBpm = 8.0
        calorieDhrrBasalStillFrac = 0.95
        calorieDhrrBasalBeatCoverageFrac = 0.41
        calorieDhrrRestSmoothS = 45
        calorieDhrrRestSmoothMinSamples = 25
        calorieDhrrBasalHrSeedOffsetBpm = 4.0
        calorieDhrrReserveRampBandBpm = 12.0
        calorieDhrrMeasuredBasalKcalDay = 1_577.0
        calorieDhrrBasalFatNight = 0.75
        calorieDhrrBasalFatDay = 0.35
        calorieDhrrBasalFatDayStartHour = 9.0
        calorieDhrrBasalFatDayEndHour = 21.0
        calorieDhrrActiveFatAtZone1 = 0.95
        calorieDhrrActiveFatAtZone2Top = 0.6
    }

    private val chosenMeasuredBasal = DynamicHrrModelSetting(
        sessionGapS = 900,
        minHrCoverageFrac = 0.61,
        hampelRadiusS = 7,
        hampelSigmas = 2.5,
        suppressPeaks = false,
        peakBlockS = 240,
        peakPercentile = 0.93,
        motionStillG = 0.015,
        motionSmoothS = 12,
        basalMinWindowS = 420,
        basalHrRangeBpm = 8.0,
        basalStillFrac = 0.95,
        basalBeatCoverageFrac = 0.41,
        restSmoothS = 45,
        restSmoothMinSamples = 25,
        basalHrSeedOffsetBpm = 4.0,
        reserveRampBandBpm = 12.0,
        measuredBasalKcalDay = 1_577.0,
        basalFatNight = 0.75,
        basalFatDay = 0.35,
        basalFatDayStartHour = 9.0,
        basalFatDayEndHour = 21.0,
        activeFatAtZone1 = 0.95,
        activeFatAtZone2Top = 0.6,
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
        profile.calorieDhrrBasalStillFrac = 4.0
        profile.calorieDhrrHampelRadiusS = 9_000

        assertEquals(1.0, profile.calorieDhrrBasalStillFrac, 0.0)
        assertEquals(30, profile.calorieDhrrHampelRadiusS)
    }

    @Test
    fun aMeasuredBasalSettingBelowItsRange_readsBackAsTheFloor() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieDhrrBasalStillFrac = -1.0
        profile.calorieDhrrHampelRadiusS = 0

        assertEquals(0.5, profile.calorieDhrrBasalStillFrac, 0.0)
        assertEquals(1, profile.calorieDhrrHampelRadiusS)
    }

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
