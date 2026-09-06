package com.noop.analytics

import com.noop.analytics.calorie.Calories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sex-specific coefficient tables behind every calorie figure.
 *
 * Nothing in the suite scored a female or nonbinary subject, so all 26 of those coefficients could be
 * transposed or mistyped and every test would still pass — including through a file move. Pinning the
 * values themselves would only restate the table, so these assert the STRUCTURE instead: the
 * male/female/nonbinary relationships hold, and each field reaches the arithmetic it belongs to.
 *
 * Standard body: 80 kg / 180 cm / 35 y.
 */
class CalorieCoefficientsTest {

    private val weightKg = 80.0
    private val heightCm = 180.0
    private val age = 35.0

    private fun basal(sex: String): Double =
        Calories.basalKcalForSpan(
            UserProfile(weightKg = weightKg, heightCm = heightCm, age = age, sex = sex),
            86_400.0,
        )

    // ---- The tables themselves ----

    @Test
    fun everySexResolvesToItsOwnTable() {
        assertEquals(Calories.male, Calories.resolveCoeffs("male"))
        assertEquals(Calories.female, Calories.resolveCoeffs("female"))
        assertEquals(Calories.nonbinary, Calories.resolveCoeffs("nonbinary"))
    }

    @Test
    fun sexIsMatchedCaseInsensitively() {
        assertEquals(Calories.female, Calories.resolveCoeffs("Female"))
        assertEquals(Calories.female, Calories.resolveCoeffs("FEMALE"))
    }

    @Test
    fun nonbinaryIsExactlyTheMaleFemaleMidpointInAllThirteenCoefficients() {
        // The documented convention, and the one relationship that catches a single mistyped digit in
        // any of the three tables: the midpoint stops holding for that field alone.
        val m = Calories.male
        val f = Calories.female
        val n = Calories.nonbinary
        val fields = listOf<Pair<String, (Calories.Coeffs) -> Double>>(
            "restingAlpha" to { it.restingAlpha },
            "restingWeight" to { it.restingWeight },
            "restingHeight" to { it.restingHeight },
            "restingAge" to { it.restingAge },
            "workoutHR" to { it.workoutHR },
            "workoutWeight" to { it.workoutWeight },
            "workoutAge" to { it.workoutAge },
            "workoutAlpha" to { it.workoutAlpha },
            "fitHR" to { it.fitHR },
            "fitVO2" to { it.fitVO2 },
            "fitWeight" to { it.fitWeight },
            "fitAge" to { it.fitAge },
            "fitAlpha" to { it.fitAlpha },
        )
        for ((name, get) in fields) {
            assertEquals(name, (get(m) + get(f)) / 2.0, get(n), 1e-12)
        }
    }

    // ---- Basal: the revised Harris-Benedict split ----

    @Test
    fun femaleRestsLowerThanMaleForTheSameBody() {
        // Harris-Benedict's whole point. If the two tables were swapped this is what would flip.
        assertTrue(basal("female") < basal("male"))
    }

    @Test
    fun nonbinaryRestsBetweenTheTwo() {
        assertTrue(basal("female") < basal("nonbinary"))
        assertTrue(basal("nonbinary") < basal("male"))
    }

    @Test
    fun anUnknownSexFallsBackRatherThanCrashing() {
        assertTrue(basal("") > 0.0)
        assertTrue(basal("unspecified") > 0.0)
    }

    @Test
    fun everyBasalCoefficientReachesTheArithmetic() {
        // Each input moves the answer in its published direction, for a female subject specifically —
        // the table that had no coverage at all. A coefficient dropped from the expression, or wired
        // to the wrong input, breaks one of these without touching the others.
        fun b(w: Double = weightKg, h: Double = heightCm, a: Double = age): Double =
            Calories.basalKcalForSpan(
                UserProfile(weightKg = w, heightCm = h, age = a, sex = "female"), 86_400.0,
            )
        assertTrue("weight raises BMR", b(w = weightKg + 10) > b())
        assertTrue("height raises BMR", b(h = heightCm + 10) > b())
        assertTrue("age lowers BMR", b(a = age + 10) < b())
    }

    @Test
    fun heightIsAppliedInMetresNotCentimetres() {
        // `restingHeight` is per METRE (309.8 for female), so 10 cm is worth ~31 kcal/day. Feeding it
        // centimetres would be ~100x that and swamp every other term.
        val perTenCm = Calories.basalKcalForSpan(
            UserProfile(weightKg = weightKg, heightCm = heightCm + 10, age = age, sex = "female"),
            86_400.0,
        ) - basal("female")
        assertEquals(Calories.female.restingHeight * 0.10, perTenCm, 1e-6)
    }

    // ---- Active: the Keytel path ----

    @Test
    fun femaleBurnsLessThanMaleAtTheSameHeartRate() {
        fun rate(sex: String): Double = Calories.activeKcalPerS(
            Calories.resolveCoeffs(sex), hr = 140.0, hrmax = 185.0, weightKg = weightKg, age = age,
        )
        assertTrue(rate("female") < rate("male"))
        assertTrue(rate("female") < rate("nonbinary"))
        assertTrue(rate("nonbinary") < rate("male"))
    }

    @Test
    fun theFitnessAdjustedFormIsUsedOnlyWhenVo2maxIsKnown() {
        val c = Calories.resolveCoeffs("female")
        val base = Calories.activeKcalPerS(c, hr = 140.0, hrmax = 185.0, weightKg = weightKg, age = age)
        val fit = Calories.activeKcalPerS(
            c, hr = 140.0, hrmax = 185.0, weightKg = weightKg, age = age, vo2max = 45.0,
        )
        assertTrue("the two Keytel forms must not coincide", kotlin.math.abs(fit - base) > 1e-9)
    }

    @Test
    fun heartRateIsCappedAtHrmaxInBothForms() {
        val c = Calories.resolveCoeffs("female")
        fun rate(hr: Double, vo2max: Double?) =
            Calories.activeKcalPerS(c, hr = hr, hrmax = 185.0, weightKg = weightKg, age = age, vo2max = vo2max)
        assertEquals(rate(185.0, null), rate(230.0, null), 1e-12)
        assertEquals(rate(185.0, 45.0), rate(230.0, 45.0), 1e-12)
    }
}
