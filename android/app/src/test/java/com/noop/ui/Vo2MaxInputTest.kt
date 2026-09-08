package com.noop.ui

import android.content.SharedPreferences
import com.noop.analytics.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measured-VO₂max field in Settings → Profile.
 *
 * The value is optional and the stepper is the only way to enter it, so "unset" has to be reachable in
 * both directions: a wearer who taps it by mistake must be able to step back out rather than being left
 * with the lowest number the field allows, which the estimators would then be permanently overruled by.
 *
 * And a value that never leaves SharedPreferences changes nothing — [ProfileStore.toUserProfile] is the
 * single place a profile is built for the engines, so the field has to arrive through it.
 */
class Vo2MaxInputTest {

    private fun step(current: Double, up: Boolean) = vo2maxStep(
        current, up, seed = ProfileStore.VO2MAX_SEED, step = ProfileStore.VO2MAX_STEP,
        max = ProfileStore.VO2MAX_MAX,
    )

    // ── The stepper ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFirstStepUp_seedsATypicalValue() {
        // Not 1 ml/kg/min, which is not a VO₂max anyone has.
        assertEquals(ProfileStore.VO2MAX_SEED, step(0.0, up = true), 0.0)
    }

    @Test
    fun steppingDownFromUnset_staysUnset() {
        assertEquals(0.0, step(0.0, up = false), 0.0)
    }

    @Test
    fun steppingWellBelowTheSeed_returnsToUnset() {
        // The escape hatch: a wearer who entered a value by mistake can withdraw it.
        var v = ProfileStore.VO2MAX_SEED
        repeat(25) { v = step(v, up = false) }
        assertEquals(0.0, v, 0.0)
    }

    @Test
    fun theValue_isCappedAtTheMaximum() {
        var v = ProfileStore.VO2MAX_SEED
        repeat(200) { v = step(v, up = true) }
        assertEquals(ProfileStore.VO2MAX_MAX, v, 0.0)
    }

    @Test
    fun oneStep_movesByOneUnit() {
        assertEquals(ProfileStore.VO2MAX_SEED + ProfileStore.VO2MAX_STEP,
                     step(ProfileStore.VO2MAX_SEED, up = true), 0.0)
    }

    // ── Persistence and the profile the engines see ────────────────────────────────────────────

    @Test
    fun theValue_roundTripsThroughPreferences() {
        val store = ProfileStore(FakeSharedPreferences())
        store.vo2maxOverride = 47.0
        assertEquals(47.0, store.vo2maxOverride, 1e-6)
    }

    @Test
    fun anOutOfRangeStoredValue_isClamped() {
        val store = ProfileStore(FakeSharedPreferences())
        store.vo2maxOverride = 500.0
        assertEquals(ProfileStore.VO2MAX_MAX, store.vo2maxOverride, 1e-6)
    }

    @Test
    fun freshInstall_leavesTheFieldUnset() {
        assertEquals(0.0, ProfileStore(FakeSharedPreferences()).vo2maxOverride, 0.0)
        assertEquals(0.0, ProfileStore(FakeSharedPreferences()).toUserProfile().vo2maxOverride, 0.0)
    }

    @Test
    fun toUserProfile_carriesTheMeasuredValue() {
        val store = ProfileStore(FakeSharedPreferences())
        store.vo2maxOverride = 47.0
        assertEquals(47.0, store.toUserProfile().vo2maxOverride, 1e-6)
    }

    @Test
    fun toUserProfile_carriesTheCalorieKnobs() {
        // A profile built longhand drops whichever field the writer forgot, and the engine then
        // scores with a default nobody chose.
        val store = ProfileStore(FakeSharedPreferences())
        store.calorieActiveAccrualMET = 2.75
        store.calorieModel = com.noop.analytics.calorie.EnergyModel.HYBRID
        assertEquals(store.toHeartRateGates(), store.toUserProfile().heartRateGates)
        assertEquals(store.toHybridModelSetting(), store.toUserProfile().hybridModelSetting)
        assertEquals(2.75, store.toUserProfile().hybridModelSetting.activeAccrualMET, 1e-12)
        // The model choice rides beside the settings rather than inside them, so it is a further field the
        // longhand profile can drop on its own.
        assertEquals(
            com.noop.analytics.calorie.EnergyModel.HYBRID,
            store.toUserProfile().calorieModel,
        )
    }

    @Test
    fun toUserProfile_carriesTheMeasuredBasalSettings() {
        val store = ProfileStore(FakeSharedPreferences())
        store.calorieDhrrBasalSeedOffsetBpm = 7.0
        assertEquals(store.toDynamicHrrModelSetting(), store.toUserProfile().dynamicHrrModelSetting)
        assertEquals(7.0, store.toUserProfile().dynamicHrrModelSetting.basalSeedOffsetBpm, 1e-12)
    }

    @Test
    fun toUserProfile_carriesTheWearersOwnZoneBoundaries() {
        // The measured-basal model anchors its fuel curve on them, so a profile that dropped them
        // would score the wearer against the conventional percentages instead of their own.
        val store = ProfileStore(FakeSharedPreferences())
        store.hrZoneThresholds = listOf(88, 110, 132, 154, 176)
        assertEquals(
            listOf(88.0, 110.0, 132.0, 154.0, 176.0),
            store.toUserProfile().hrZoneThresholds,
        )
    }

    @Test
    fun aMeasuredValue_reachesTheEnergyModelThroughTheProfile() {
        // End to end: the field is only worth having if the number it stores changes the day's energy.
        val store = ProfileStore(FakeSharedPreferences())
        val inferred = store.toUserProfile()
        store.vo2maxOverride = 45.0
        val measured = store.toUserProfile()
        val hr = (0 until 3_600).map { com.noop.data.HrSample("t", it.toLong(), 130) }
        fun active(p: UserProfile) =
            com.noop.analytics.calorie.Calories.estimateDayEnergy(hr, p, hrmax = 184.0, restingHR = 48.0)
                .dayActiveKcal
        assertTrue(active(measured) < active(inferred))
    }

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
