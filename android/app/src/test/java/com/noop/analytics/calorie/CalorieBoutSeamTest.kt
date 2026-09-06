package com.noop.analytics.calorie

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-workout energy stays off the model registry.
 *
 * `Calories.estimateBoutCalories` answers a different question: GROSS energy for a bout, not the
 * surplus above resting a day is scored in, under a deliberately looser continuity cap. Routing it
 * through a [CalorieModel] would move every stored `WorkoutRow.energyKcal` — a different table, with
 * no rescore path behind it — and the three call sites hold a sample list and a profile and nothing
 * else: no day, no window, no clock. If it ever wants a seam it wants its own.
 *
 * A source-text guard rather than a type-level one because the mistake is a call, not a signature.
 */
class CalorieBoutSeamTest {

    /** The files that price a bout today. Named so the guard cannot pass by finding nothing. */
    private val boutCallSites = listOf(
        "analytics/WorkoutDetector.kt",
        "analytics/ManualWorkoutRescore.kt",
        "ui/AppViewModel.kt",
    )

    @Test
    fun theKnownBoutCallSitesStillPriceBouts() {
        for (site in boutCallSites) {
            assertTrue(
                "$site no longer calls estimateBoutCalories; this guard is now vacuous",
                read(sourceRoot().resolve(site)).contains("estimateBoutCalories"),
            )
        }
    }

    @Test
    fun nothingThatPricesABoutReachesTheModelRegistry() {
        val root = sourceRoot()
        val offenders = kotlinSources(root)
            .filter { read(it).contains("estimateBoutCalories") }
            .filter { read(it).contains("CalorieModel") }
            .map { root.relativize(it).toString() }
        assertEquals("bout energy must not be routed through the model registry", emptyList<String>(), offenders)
    }

    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .toList()
        }

    private fun read(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    /** `app/src/main/java/com/noop`, located fail-closed from the working directory upwards. */
    private fun sourceRoot(): Path {
        val suffixes = listOf(
            Path.of("app/src/main/java/com/noop"),
            Path.of("src/main/java/com/noop"),
        )
        val matches = LinkedHashSet<Path>()
        var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (directory != null) {
            for (suffix in suffixes) {
                val candidate = directory.resolve(suffix).normalize()
                if (Files.isDirectory(candidate)) matches.add(candidate.toRealPath())
            }
            directory = directory.parent
        }
        assertEquals(
            "Could not locate the app sources from user.dir=${System.getProperty("user.dir")}: $matches",
            1,
            matches.size,
        )
        return matches.single()
    }
}
