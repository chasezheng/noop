package com.noop.calbench

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A synthetic store, built row by row.
 *
 * Written out with the same CREATE TABLE columns the app's schema carries for the six tables the
 * calorie path reads, so a test exercises the harness's real SQL rather than a shape invented to
 * suit it. No real backup is ever checked in: this project keeps health data out of the repo, and a
 * fixture that has to be regenerated from someone's strap is not a fixture.
 */
class TestStore {

    private val hr = ArrayList<Triple<String, Long, Int>>()
    private val gravity = ArrayList<Pair<String, Pair<Long, Double>>>()
    private val workouts = ArrayList<Triple<String, Pair<Long, Long>, String>>()
    private val beats = ArrayList<BeatRow>()
    private val daily = ArrayList<Triple<String, String, Int?>>()
    private val dailyKcal = HashMap<Pair<String, String>, Double>()
    private val devices = ArrayList<Triple<String, String, String>>()
    private val owners = ArrayList<Pair<String, String>>()

    /** Tables to leave out entirely, for the "too old to read" case. */
    var omit: Set<String> = emptySet()

    fun hr(deviceId: String, ts: Long, bpm: Int) = apply { hr += Triple(deviceId, ts, bpm) }

    fun gravity(deviceId: String, ts: Long, dynAccel: Double) =
        apply { gravity += deviceId to (ts to dynAccel) }

    fun beat(deviceId: String, ts: Long, rrMs: Int = 900, srcChannel: Int? = null, tsSuspect: Int? = null) =
        apply { beats += BeatRow(deviceId, ts, rrMs, srcChannel, tsSuspect) }

    fun workout(deviceId: String, start: Long, end: Long, sport: String = "run") =
        apply { workouts += Triple(deviceId, start to end, sport) }

    fun restingHr(deviceId: String, day: String, bpm: Int?) = apply { daily += Triple(deviceId, day, bpm) }

    fun activeKcal(deviceId: String, day: String, kcal: Double) = apply { dailyKcal[deviceId to day] = kcal }

    fun device(id: String, sourceKind: String = "liveBLE", status: String = "active") =
        apply { devices += Triple(id, sourceKind, status) }

    fun dayOwner(day: String, deviceId: String) = apply { owners += day to deviceId }

    /** Write the store as a bare `.sqlite`. */
    fun writeSqlite(into: File): File {
        DriverManager.getConnection("jdbc:sqlite:${into.absolutePath}").use { c ->
            // One transaction for the whole store: a dense 1 Hz day is tens of thousands of rows, and
            // SQLite commits each of them separately without this.
            c.autoCommit = false
            c.createStatement().use { st ->
                st.executeUpdate("PRAGMA user_version = 35")
                if ("hrSample" !in omit) {
                    st.executeUpdate(
                        "CREATE TABLE hrSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, " +
                            "bpm INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY (deviceId, ts))",
                    )
                }
                if ("gravitySample" !in omit) {
                    st.executeUpdate(
                        "CREATE TABLE gravitySample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, " +
                            "x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, " +
                            "synced INTEGER NOT NULL DEFAULT 0, dynAccel REAL, PRIMARY KEY (deviceId, ts))",
                    )
                }
                if ("rrInterval" !in omit) {
                    st.executeUpdate(
                        "CREATE TABLE rrInterval (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, " +
                            "rrMs INTEGER NOT NULL, seq INTEGER NOT NULL DEFAULT 0, " +
                            "synced INTEGER NOT NULL DEFAULT 0, ord INTEGER, srcChannel INTEGER, " +
                            "tsSuspect INTEGER, PRIMARY KEY (deviceId, ts, rrMs, seq))",
                    )
                }
                if ("workout" !in omit) {
                    st.executeUpdate(
                        "CREATE TABLE workout (deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, " +
                            "endTs INTEGER NOT NULL, sport TEXT NOT NULL, source TEXT NOT NULL, " +
                            "PRIMARY KEY (deviceId, startTs))",
                    )
                }
                if ("dailyMetric" !in omit) {
                    st.executeUpdate(
                        "CREATE TABLE dailyMetric (deviceId TEXT NOT NULL, day TEXT NOT NULL, " +
                            "restingHr INTEGER, activeKcalEst REAL, PRIMARY KEY (deviceId, day))",
                    )
                }
                if ("pairedDevice" !in omit) {
                    st.executeUpdate(
                        "CREATE TABLE pairedDevice (id TEXT NOT NULL PRIMARY KEY, sourceKind TEXT NOT NULL, " +
                            "status TEXT NOT NULL, addedAt INTEGER NOT NULL)",
                    )
                }
                if ("dayOwnership" !in omit) {
                    st.executeUpdate(
                        "CREATE TABLE dayOwnership (day TEXT NOT NULL PRIMARY KEY, deviceId TEXT NOT NULL, " +
                            "locked INTEGER NOT NULL DEFAULT 0)",
                    )
                }
            }
            fun exec(sql: String, bind: (java.sql.PreparedStatement) -> Unit) =
                c.prepareStatement(sql).use { st -> bind(st); st.executeUpdate() }

            for ((deviceId, ts, bpm) in hr) {
                exec("INSERT OR REPLACE INTO hrSample (deviceId, ts, bpm) VALUES (?, ?, ?)") {
                    it.setString(1, deviceId); it.setLong(2, ts); it.setInt(3, bpm)
                }
            }
            for ((deviceId, sample) in gravity) {
                exec(
                    "INSERT OR REPLACE INTO gravitySample (deviceId, ts, x, y, z, dynAccel) " +
                        "VALUES (?, ?, 0, 0, 1, ?)",
                ) {
                    it.setString(1, deviceId); it.setLong(2, sample.first); it.setDouble(3, sample.second)
                }
            }
            for (beat in beats) {
                exec(
                    "INSERT OR REPLACE INTO rrInterval (deviceId, ts, rrMs, seq, ord, srcChannel, tsSuspect) " +
                        "VALUES (?, ?, ?, 0, 0, ?, ?)",
                ) {
                    it.setString(1, beat.deviceId); it.setLong(2, beat.ts); it.setInt(3, beat.rrMs)
                    if (beat.srcChannel == null) it.setNull(4, java.sql.Types.INTEGER)
                    else it.setInt(4, beat.srcChannel)
                    if (beat.tsSuspect == null) it.setNull(5, java.sql.Types.INTEGER)
                    else it.setInt(5, beat.tsSuspect)
                }
            }
            for ((deviceId, span, sport) in workouts) {
                exec(
                    "INSERT OR REPLACE INTO workout (deviceId, startTs, endTs, sport, source) " +
                        "VALUES (?, ?, ?, ?, 'test')",
                ) {
                    it.setString(1, deviceId); it.setLong(2, span.first); it.setLong(3, span.second)
                    it.setString(4, sport)
                }
            }
            for ((deviceId, day, bpm) in daily) {
                exec("INSERT OR REPLACE INTO dailyMetric (deviceId, day, restingHr) VALUES (?, ?, ?)") {
                    it.setString(1, deviceId); it.setString(2, day)
                    if (bpm == null) it.setNull(3, java.sql.Types.INTEGER) else it.setInt(3, bpm)
                }
            }
            for ((key, kcal) in dailyKcal) {
                exec(
                    "INSERT INTO dailyMetric (deviceId, day, activeKcalEst) VALUES (?, ?, ?) " +
                        "ON CONFLICT (deviceId, day) DO UPDATE SET activeKcalEst = excluded.activeKcalEst",
                ) {
                    it.setString(1, key.first); it.setString(2, key.second); it.setDouble(3, kcal)
                }
            }
            for ((index, d) in devices.withIndex()) {
                exec("INSERT OR REPLACE INTO pairedDevice (id, sourceKind, status, addedAt) VALUES (?, ?, ?, ?)") {
                    it.setString(1, d.first); it.setString(2, d.second); it.setString(3, d.third)
                    it.setLong(4, index.toLong())
                }
            }
            for ((day, deviceId) in owners) {
                exec("INSERT OR REPLACE INTO dayOwnership (day, deviceId) VALUES (?, ?)") {
                    it.setString(1, day); it.setString(2, deviceId)
                }
            }
            c.commit()
        }
        return into
    }

    /** Write the store as a `.noopbak` — the ZIP the app's exporter produces. */
    fun writeNoopbak(into: File, settings: String? = null, manifest: String? = MANIFEST): File {
        val sqlite = Files.createTempFile("calbench-test-", ".sqlite").toFile()
        sqlite.delete()
        writeSqlite(sqlite)
        ZipOutputStream(into.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("noop-backup.sqlite"))
            sqlite.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            if (settings != null) {
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(settings.toByteArray())
                zip.closeEntry()
            }
            if (manifest != null) {
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
            }
        }
        sqlite.delete()
        return into
    }

    companion object {
        const val MANIFEST_SCHEMA_VERSION = 35
        const val MANIFEST =
            """{"appBuild":"373","appVersion":"10.7.0","exportedAt":1788045497009,"platform":"android","schemaVersion":35}"""
    }
}

/** One `rrInterval` row, as the two filtered columns make it. */
private data class BeatRow(
    val deviceId: String,
    val ts: Long,
    val rrMs: Int,
    val srcChannel: Int?,
    val tsSuspect: Int?,
)

/** A scratch directory that cleans up after itself. */
fun withTempDir(body: (File) -> Unit) {
    val dir = Files.createTempDirectory("calbench-test").toFile()
    try {
        body(dir)
    } finally {
        dir.deleteRecursively()
    }
}
