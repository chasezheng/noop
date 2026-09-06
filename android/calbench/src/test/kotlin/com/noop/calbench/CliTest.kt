package com.noop.calbench

import com.noop.analytics.calorie.CalorieModels
import com.noop.analytics.calorie.EnergyModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.time.LocalDate
import java.time.ZoneId

/** Argument parsing, directory expansion, and the shape of what a run writes. */
class CliTest {

    @Test
    fun `the defaults are every model, the system zone, csv, and the whole extent`() {
        val options = Options.parse(arrayOf("--backups", "a.noopbak"))
        assertEquals(listOf(File("a.noopbak")), options.backups)
        assertEquals(CalorieModels.order, options.models)
        assertEquals(ZoneId.systemDefault(), options.zone)
        assertEquals(false, options.json)
        assertNull(options.from)
        assertNull(options.to)
        assertNull(options.out)
    }

    @Test
    fun `every flag parses, and backups is variadic so a shell glob passes straight through`() {
        val options = Options.parse(
            arrayOf(
                "--backups", "a.noopbak", "b.sqlite", "dir",
                "--from", "2026-01-02", "--to", "2026-01-09",
                "--model", "hybrid", "--tz", "America/Los_Angeles",
                "--format", "json", "--out", "rows.json",
            ),
        )
        assertEquals(3, options.backups.size)
        assertEquals(LocalDate.parse("2026-01-02"), options.from)
        assertEquals(LocalDate.parse("2026-01-09"), options.to)
        assertEquals(listOf(EnergyModel.HYBRID), options.models)
        assertEquals(ZoneId.of("America/Los_Angeles"), options.zone)
        assertEquals(true, options.json)
        assertEquals(File("rows.json"), options.out)
    }

    @Test
    fun `a model is named by its wire id or by its case-insensitive name`() {
        assertEquals(listOf(EnergyModel.HEART_RATE), Options.parse(arrayOf("--backups", "a", "--model", "hr")).models)
        assertEquals(
            listOf(EnergyModel.HEART_RATE),
            Options.parse(arrayOf("--backups", "a", "--model", "heart_rate")).models,
        )
        assertEquals(CalorieModels.order, Options.parse(arrayOf("--backups", "a", "--model", "all")).models)
    }

    @Test
    fun `a bad argument is refused with something the caller can act on`() {
        for (args in listOf(
            arrayOf("--from", "2026-01-01"),
            arrayOf("--backups"),
            arrayOf("--backups", "a", "--from", "yesterday"),
            arrayOf("--backups", "a", "--tz", "Mars/Olympus"),
            arrayOf("--backups", "a", "--format", "parquet"),
            arrayOf("--backups", "a", "--model", "guesswork"),
            arrayOf("--backups", "a", "--from", "2026-02-01", "--to", "2026-01-01"),
            arrayOf("--backups", "a", "--nonsense"),
        )) {
            try {
                Options.parse(args)
                fail("expected ${args.joinToString(" ")} to be refused")
            } catch (e: IllegalArgumentException) {
                assertTrue(args.joinToString(" "), !e.message.isNullOrEmpty())
            }
        }
    }

    @Test
    fun `a directory yields its backups in a stable order and ignores everything else`() = withTempDir { dir ->
        File(dir, "b.noopbak").writeText("")
        File(dir, "a.sqlite").writeText("")
        File(dir, "notes.md").writeText("")
        File(dir, "nested").mkdirs()
        assertEquals(listOf("a.sqlite", "b.noopbak"), expand(dir).map { it.name })
        // An explicit file is taken whatever it is called.
        assertEquals(listOf("notes.md"), expand(File(dir, "notes.md")).map { it.name })
        assertEquals(emptyList<File>(), expand(File(dir, "gone.noopbak")))
    }

    @Test
    fun `a run writes one row per minute per model, with the day totals on every row`() = withTempDir { dir ->
        val csv = StringWriter()
        val writer = CsvWriter(csv)
        val (source, day) = scoredBackup(dir)
        source.use { runBlocking { Runner(ZoneId.of("UTC"), CalorieModels.order, writer, Warn {}).run(it, null, null) } }
        writer.finish()
        val lines = csv.toString().trim().lines()
        assertEquals("header + 1440 minutes x 3 models", 1 + 3 * 1_440, lines.size)

        val header = lines.first().split(",")
        val rows = lines.drop(1).map { header.zip(it.split(",")).toMap() }
        assertEquals(setOf(day), rows.map { it["day"] }.toSet())
        // The stored ids, written out: taking them from the registry would let a renamed one pass.
        assertEquals(setOf("hr", "hybrid", "dynamic_hrr"), rows.map { it["model"] }.toSet())
        assertEquals("UTC", rows.first()["tz"])
        assertEquals("my-whoop", rows.first()["deviceId"])
        assertEquals(TestStore.MANIFEST_SCHEMA_VERSION.toString(), rows.first()["schemaVersion"])
        // The day totals repeat unchanged down every row of their model, so the caller can group
        // without a second file.
        for (model in listOf("hr", "hybrid")) {
            val totals = rows.filter { it["model"] == model }.map { it["dayActiveKcal"] }.toSet()
            assertEquals(1, totals.size)
        }
        // The stored figure rides along so a re-run can be compared with what the app banked.
        assertEquals(setOf("123.5"), rows.map { it["storedActiveKcal"] }.toSet())
    }

    @Test
    fun `the json run carries the same table`() = withTempDir { dir ->
        val json = StringWriter()
        val writer = JsonWriter(json)
        val (source, _) = scoredBackup(dir)
        source.use {
            runBlocking { Runner(ZoneId.of("UTC"), listOf(EnergyModel.HEART_RATE), writer, Warn {}).run(it, null, null) }
        }
        writer.finish()
        val rows = JSONArray(json.toString())
        assertEquals(1_440, rows.length())
        val first = rows.getJSONObject(0)
        assertEquals("hr", first.getString("model"))
        assertTrue(first.has("epochStart") && first.has("activeKcal") && first.has("dayTotalKcal"))
        // The two model-keyed maps are nested here rather than flattened, because JSON has somewhere
        // to put them. The heart-rate model reports neither, so both are present and empty.
        assertEquals(0, first.getJSONObject("series").length())
        assertEquals(0, first.getJSONObject("extras").length())
    }

    @Test
    fun `an empty run still writes a well-formed table`() {
        val json = StringWriter()
        val csv = StringWriter()
        JsonWriter(json).finish()
        CsvWriter(csv).finish()
        assertEquals(0, JSONArray(json.toString()).length())
        assertEquals(1, csv.toString().trim().lines().size)
    }

    @Test
    fun `a day whose window holds no heart rate is left out rather than filled with basal`() =
        withTempDir { dir ->
            val csv = StringWriter()
            val store = TestStore().device("my-whoop")
            val first = "2026-05-04"
            val start = com.noop.analytics.AnalyticsEngine.dayStartUtcSeconds(first)
            for (minute in 0 until 600) store.hr("my-whoop", start + minute * 60L, 60)
            // Two days later, so the range spans a day with nothing in it.
            for (minute in 0 until 600) store.hr("my-whoop", start + 2 * 86_400L + minute * 60L, 60)
            val source = BackupSource.open(store.writeNoopbak(File(dir, "gap.noopbak")))
            val runner = Runner(ZoneId.of("UTC"), listOf(EnergyModel.HEART_RATE), CsvWriter(csv), Warn {})
            source.use { runBlocking { runner.run(it, null, null) } }
            assertEquals(2, runner.daysScored)
            assertEquals(1, runner.daysUnobserved)
        }

    /** A backup with one fully-observed UTC day in it, and the figure the app banked for that day. */
    private fun scoredBackup(dir: File): Pair<BackupSource, String> {
        val day = "2026-05-04"
        val start = com.noop.analytics.AnalyticsEngine.dayStartUtcSeconds(day)
        val store = TestStore().device("my-whoop").activeKcal("my-whoop-noop", day, 123.5)
        for (minute in 0 until 1_440) {
            store.hr("my-whoop", start + minute * 60L, 60 + minute % 40)
            store.gravity("my-whoop", start + minute * 60L, if (minute % 3 == 0) 0.04 else 0.0)
        }
        return BackupSource.open(store.writeNoopbak(File(dir, "one.noopbak"))) to day
    }
}
