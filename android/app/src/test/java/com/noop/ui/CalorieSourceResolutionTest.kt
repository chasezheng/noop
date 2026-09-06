package com.noop.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which calorie number the dashboards show when NOOP and the phone both have one.
 *
 * A phone value above zero replaces NOOP's own estimate for the day, which keeps the figure matching
 * what the phone already showed. The gate is a bare `> 0`, so a phone that spent the day in a pocket
 * and reported 9 kcal still wins, and every calorie setting becomes invisible while it does.
 *
 * [ProfileStore.caloriePreferOnDevice] flips the precedence. These pin both directions, and that the
 * flip changes only which number is shown: neither order invents or drops a day.
 */
class CalorieSourceResolutionTest {

    /** The shipped resolution rather than a copy: a restatement here could not fail when the card
     *  changed. */
    private fun resolve(
        onDevice: Map<String, Double>,
        imported: Map<String, Double>,
        preferOnDevice: Boolean,
    ): Map<String, Double> = resolveCaloriesByDay(onDevice, imported, preferOnDevice)

    @Test
    fun byDefault_aTinyImportedValueStillReplacesTheOnDeviceEstimate() {
        // The reported symptom: the tile reads 9 while NOOP computed 1800.
        val resolved = resolve(
            onDevice = mapOf("2026-08-27" to 1800.0),
            imported = mapOf("2026-08-27" to 9.0),
            preferOnDevice = false,
        )
        assertEquals(9.0, resolved["2026-08-27"]!!, 0.0)
    }

    @Test
    fun preferringOnDevice_showsWhatNoopComputed() {
        val resolved = resolve(
            onDevice = mapOf("2026-08-27" to 1800.0),
            imported = mapOf("2026-08-27" to 9.0),
            preferOnDevice = true,
        )
        assertEquals(1800.0, resolved["2026-08-27"]!!, 0.0)
    }

    @Test
    fun preferringOnDevice_stillFallsBackToImportedOnDaysNoopNeverScored() {
        // Days before the strap was owned have no on-device figure; the phone's is better than none.
        val resolved = resolve(
            onDevice = emptyMap(),
            imported = mapOf("2026-08-01" to 420.0),
            preferOnDevice = true,
        )
        assertEquals(420.0, resolved["2026-08-01"]!!, 0.0)
    }

    @Test
    fun theDefault_stillFallsBackToOnDeviceOnDaysThePhoneDidNotCover() {
        val resolved = resolve(
            onDevice = mapOf("2026-08-27" to 1800.0),
            imported = emptyMap(),
            preferOnDevice = false,
        )
        assertEquals(1800.0, resolved["2026-08-27"]!!, 0.0)
    }

    @Test
    fun flippingThePreference_neverChangesWhichDaysAppear() {
        val onDevice = mapOf("2026-08-26" to 1700.0, "2026-08-27" to 1800.0)
        val imported = mapOf("2026-08-27" to 9.0, "2026-08-28" to 500.0)
        assertEquals(
            resolve(onDevice, imported, preferOnDevice = false).keys,
            resolve(onDevice, imported, preferOnDevice = true).keys,
        )
    }

    @Test
    fun freshInstall_keepsTheImportedFirstBehaviour() {
        assertEquals(false, ProfileStore(FakeSharedPreferences()).caloriePreferOnDevice)
    }

    @Test
    fun thePreference_roundTrips() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.caloriePreferOnDevice = true
        assertEquals(true, profile.caloriePreferOnDevice)
    }

    @Test
    fun resettingCalorieSettings_restoresImportedFirst() {
        val profile = ProfileStore(FakeSharedPreferences())
        profile.caloriePreferOnDevice = true
        profile.resetCalorieSettings()
        assertEquals(false, profile.caloriePreferOnDevice)
    }

    @Test
    fun resettingCalorieSettings_leavesNothingForTheBackupToExport() {
        // `backupSnapshot` exports a knob on `prefs.contains`, so "reset to defaults" must CLEAR the
        // keys, not write the defaults into them. Writing them marks seven knobs as deliberately
        // chosen, and the next .noopbak restore stamps them over another device's tuned values.
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieModel = com.noop.analytics.calorie.EnergyModel.HYBRID
        profile.calorieDayActiveHRRFraction = 0.42
        profile.calorieBoutActiveHRRFraction = 0.37
        profile.calorieActiveAccrualMET = 2.75
        profile.calorieDynAccelMETGainPerG = 21.0
        profile.calorieHrFallbackWhenNoMET = false
        profile.caloriePreferOnDevice = true
        assertEquals(7, profile.backupSnapshot().keys.count { it.startsWith("calorie.") })

        profile.resetCalorieSettings()

        assertEquals(
            emptyList<String>(),
            profile.backupSnapshot().keys.filter { it.startsWith("calorie.") },
        )
    }

    @Test
    fun resettingCalorieSettings_stillReadsBackAsTheShippedDefaults() {
        // Clearing the keys must be indistinguishable from the defaults to every reader, or "reset"
        // would move a number the wearer did not touch.
        val profile = ProfileStore(FakeSharedPreferences())
        profile.calorieDayActiveHRRFraction = 0.42
        profile.calorieActiveAccrualMET = 2.75
        profile.resetCalorieSettings()
        assertEquals(com.noop.analytics.calorie.HeartRateGates(), profile.toHeartRateGates())
        assertEquals(com.noop.analytics.calorie.HybridModelSetting(), profile.toHybridModelSetting())
    }

    // ---- Which source a surface is showing ----

    @Test
    fun importedFirst_theImportedFigureIsWhatTheCardShows() {
        assertEquals(9.0, importedWinsKcal(1800.0, 9.0, preferOnDevice = false))
    }

    @Test
    fun preferringOnDevice_theCardIsNotShowingTheImportedFigure() {
        // The row a single-value check cannot see: both sources cover the day and NOOP's wins, so a
        // surface explaining the card must NOT announce the phone's number.
        assertEquals(null, importedWinsKcal(1800.0, 9.0, preferOnDevice = true))
    }

    @Test
    fun preferringOnDevice_theImportedFigureStillWinsADayNoopNeverScored() {
        assertEquals(420.0, importedWinsKcal(null, 420.0, preferOnDevice = true))
    }

    @Test
    fun noImportedFigure_isNeverAnnounced() {
        assertEquals(null, importedWinsKcal(1800.0, null, preferOnDevice = false))
        assertEquals(null, importedWinsKcal(null, null, preferOnDevice = true))
    }

    @Test
    fun theWinner_agreesWithTheNumberTheCardResolves() {
        // The footnote and the card must not answer from two different rules. Every combination of
        // presence and preference: the source named as winner is the source the card's own resolver
        // took its number from.
        val day = "2026-08-27"
        for (onDevice in listOf(null, 1800.0)) {
            for (imported in listOf(null, 9.0)) {
                for (prefer in listOf(false, true)) {
                    val shown = resolveCaloriesByDay(
                        onDevice = onDevice?.let { mapOf(day to it) } ?: emptyMap(),
                        imported = imported?.let { mapOf(day to it) } ?: emptyMap(),
                        preferOnDevice = prefer,
                    )[day]
                    val expected = when (calorieWinnerForDay(onDevice, imported, prefer)) {
                        CalorieSource.ON_DEVICE -> onDevice
                        CalorieSource.IMPORTED -> imported
                        null -> null
                    }
                    assertEquals("onDevice=$onDevice imported=$imported prefer=$prefer", expected, shown)
                }
            }
        }
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
