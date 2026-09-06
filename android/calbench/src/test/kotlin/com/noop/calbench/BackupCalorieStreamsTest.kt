package com.noop.calbench

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.calorie.ActivityDay
import com.noop.analytics.calorie.exclusiveEnd
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** The port's four resolutions, each against the rule `RepositoryCalorieStreams` follows. */
class BackupCalorieStreamsTest {

    private val zone = ZoneId.of("UTC")
    private val day = "2026-05-04"
    private val nowUtc = 1_788_045_497L
    private val midnight = AnalyticsEngine.dayStartUtcSeconds(day)

    private fun streams(store: TestStore, dir: File, settings: String? = null): Pair<BackupSource, BackupCalorieStreams> {
        val source = BackupSource.open(store.writeNoopbak(File(dir, "s-${dir.list()!!.size}.noopbak"), settings))
        return source to BackupCalorieStreams(source, zone, nowUtc)
    }

    @Test
    fun `the activity window opens at local midnight`() = withTempDir { dir ->
        val (source, streams) = streams(TestStore().device("my-whoop"), dir)
        source.use {
            runBlocking {
                val window = ActivityDay.atLocalMidnight(streams.localMidnightUtc(streams.activityDay(day))).window()
                assertEquals(midnight, window.first)
                assertEquals(86_400L, window.exclusiveEnd - window.first)
            }
        }
    }

    @Test
    fun `an archived strap is no candidate, and the active one owns the day`() = withTempDir { dir ->
        val store = TestStore()
            .device("my-whoop", status = "archived")
            .device("whoop-new", status = "active")
            .device("strap-other", status = "paired")
            .hr("my-whoop", midnight + 5 * 3_600L, 60)
            .hr("whoop-new", midnight + 6 * 3_600L, 62)
        val (source, streams) = streams(store, dir)
        source.use { runBlocking { assertEquals("whoop-new", streams.owner(streams.activityDay(day))) } }
    }

    @Test
    fun `a stored day-owner override wins outright`() = withTempDir { dir ->
        val store = TestStore()
            .device("whoop-new", status = "active")
            .device("import-src", sourceKind = "fileImport", status = "paired")
            .hr("whoop-new", midnight + 6 * 3_600L, 62)
            .dayOwner(day, "import-src")
        val (source, streams) = streams(store, dir)
        source.use { runBlocking { assertEquals("import-src", streams.owner(streams.activityDay(day))) } }
    }

    @Test
    fun `a day nobody has data for falls back to the active strap`() = withTempDir { dir ->
        val store = TestStore()
            .device("whoop-new", status = "active")
            .device("strap-other", status = "paired")
            .hr("whoop-new", midnight + 400 * 3_600L, 62)
        val (source, streams) = streams(store, dir)
        source.use { runBlocking { assertEquals("whoop-new", streams.owner(streams.activityDay(day))) } }
    }

    @Test
    fun `the streams read under the union of the ids the owner banks through`() = withTempDir { dir ->
        // A re-added strap banks live rows under a fresh id while its import history stays under the
        // canonical one, so a single-id read finds nothing on a day the wearer can see data for.
        val store = TestStore()
            .device("whoop-new", status = "active")
            .device("strap-other", status = "paired")
            .hr("whoop-new", midnight + 6 * 3_600L, 62)
            .hr("my-whoop", midnight + 7 * 3_600L, 58)
            .gravity("my-whoop", midnight + 7 * 3_600L, 0.05)
            .workout("my-whoop", midnight + 8 * 3_600L, midnight + 9 * 3_600L)
            .workout("whoop-new-noop", midnight + 10 * 3_600L, midnight + 11 * 3_600L)
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking {
                val inputs = streams.load(streams.activityDay(day), midnight until midnight + 86_400L)
                assertEquals(listOf(62, 58), inputs.hr.map { s -> s.bpm })
                assertEquals(1, inputs.gravity.size)
                // The detected ("-noop") bout and the recorded one both reach the model.
                assertEquals(2, inputs.workouts.size)
            }
        }
    }

    @Test
    fun `beats are counted per second, over the half-open activity window`() = withTempDir { dir ->
        // The row range is inclusive of its end while the window is half-open, so the second the
        // window shuts belongs to the next day and must not be read.
        val store = TestStore()
            .device("my-whoop")
            .hr("my-whoop", midnight + 3_600L, 60)
            .beat("my-whoop", midnight + 3_600L, rrMs = 880)
            .beat("my-whoop", midnight + 3_600L, rrMs = 920)
            .beat("my-whoop", midnight + 3_601L)
            .beat("my-whoop", midnight + 86_400L)
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking {
                val inputs = streams.load(streams.activityDay(day), midnight until midnight + 86_400L)
                assertEquals(
                    listOf((midnight + 3_600L) to 2, (midnight + 3_601L) to 1),
                    inputs.rr,
                )
            }
        }
    }

    @Test
    fun `a pulse-oximetry beat and a future-stamped one are both left out`() = withTempDir { dir ->
        // Rows on the SpO2 channel count the same heartbeats a second time, and a row stamped in the
        // future carries a corrupt clock. The app filters both at read; this harness restates that
        // filter by hand, and nothing but this test checks the two copies still agree.
        val store = TestStore()
            .device("my-whoop")
            .hr("my-whoop", midnight + 3_600L, 60)
            .beat("my-whoop", midnight + 3_600L, rrMs = 880)
            .beat("my-whoop", midnight + 3_600L, rrMs = 900, srcChannel = 2)
            .beat("my-whoop", midnight + 3_601L, rrMs = 910, tsSuspect = 1)
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking {
                val inputs = streams.load(streams.activityDay(day), midnight until midnight + 86_400L)
                assertEquals(listOf((midnight + 3_600L) to 1), inputs.rr)
            }
        }
    }

    @Test
    fun `a backup with no beat table at all reads as no beats`() = withTempDir { dir ->
        // Every backup written before that table existed, which must read rather than throw.
        val store = TestStore().device("my-whoop").hr("my-whoop", midnight + 3_600L, 60)
        store.omit = setOf("rrInterval")
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking {
                val inputs = streams.load(streams.activityDay(day), midnight until midnight + 86_400L)
                assertEquals(emptyList<Pair<Long, Int>>(), inputs.rr)
            }
        }
    }

    @Test
    fun `the day's own computed resting HR wins over any carry-forward`() = withTempDir { dir ->
        val store = TestStore()
            .device("my-whoop")
            .restingHr("my-whoop-noop", day, 48)
            .restingHr("my-whoop", day, 61)
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking { assertEquals(48.0, streams.vitals(streams.activityDay(day), it.profile, null).restingHR) }
        }
    }

    @Test
    fun `with no computed row the anchor carries forward from the latest measured day`() = withTempDir { dir ->
        val store = TestStore()
            .device("my-whoop")
            .restingHr("my-whoop", "2026-05-01", 55)
            .restingHr("my-whoop", "2026-05-02", 57)
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking { assertEquals(57.0, streams.vitals(streams.activityDay(day), it.profile, null).restingHR) }
        }
    }

    @Test
    fun `a Health Connect row never blends into a day a strap measured`() = withTempDir { dir ->
        // The vitals ids are ordered, but `calorieRestingHR` AVERAGES every row for the winning day, so
        // order alone would fold the phone's aggregate into the strap's figure instead of deferring.
        val store = TestStore()
            .device("my-whoop")
            .restingHr("my-whoop", "2026-05-02", 52)
            .restingHr("health-connect", "2026-05-02", 70)
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking { assertEquals(52.0, streams.vitals(streams.activityDay(day), it.profile, null).restingHR) }
        }
    }

    @Test
    fun `a later Health Connect day still wins, which is why it is read at all`() = withTempDir { dir ->
        val store = TestStore()
            .device("my-whoop")
            .restingHr("my-whoop", "2026-05-01", 52)
            .restingHr("health-connect", "2026-05-03", 66)
        val (source, streams) = streams(store, dir)
        source.use {
            runBlocking { assertEquals(66.0, streams.vitals(streams.activityDay(day), it.profile, null).restingHR) }
        }
    }

    @Test
    fun `no resting HR anywhere leaves the estimator's own default to stand in`() = withTempDir { dir ->
        val (source, streams) = streams(TestStore().device("my-whoop"), dir)
        source.use { runBlocking { assertNull(streams.vitals(streams.activityDay(day), it.profile, null).restingHR) } }
    }
}
