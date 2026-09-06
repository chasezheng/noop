package com.noop.calbench

import com.noop.analytics.HrZones
import com.noop.analytics.UserProfile
import com.noop.analytics.calorie.DynamicHrrModelSetting
import com.noop.analytics.calorie.DynamicHrrModelSettingRanges
import com.noop.analytics.calorie.HeartRateGates
import com.noop.analytics.calorie.HeartRateGatesRanges
import com.noop.analytics.calorie.HybridModelSetting
import com.noop.analytics.calorie.HybridModelSettingRanges
import com.noop.analytics.calorie.EnergyModel
import com.noop.data.DailyMetric
import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipInputStream

/** A backup this run cannot score, named so the CLI can say which file it skipped and why. */
class UnreadableBackup(val file: File, val schemaVersion: Int?, message: String) : Exception(message)

/** What a backup's manifest records about the build that wrote it. */
data class BackupManifestInfo(
    val appVersion: String = "",
    val appBuild: String = "",
    val platform: String = "",
    val schemaVersion: Int = 0,
    val exportedAtMs: Long = 0L,
)

/**
 * One backup, opened read-only: its SQLite, its manifest, and the profile its `settings.json` carries.
 *
 * Opened read-only and without migration, because a harness must read a file written at any schema
 * version and a migration is a write. The file on disk is never touched.
 *
 * A backup is self-describing for the calorie models: its settings carry the wearer's body and every
 * calorie setting, and its database carries the streams and the device registry. Only the time zone
 * comes from outside.
 */
class BackupSource private constructor(
    /** The path the run was given, for the `backup` output column. */
    val label: String,
    val manifest: BackupManifestInfo,
    val profile: UserProfile,
    /** The wearer's manual maximum heart rate in bpm, or null to derive one. */
    val maxHROverride: Double?,
    val db: BackupDb,
    private val scratch: File?,
) : Closeable {

    override fun close() {
        db.close()
        scratch?.delete()
    }

    companion object {

        /** The leading bytes that identify a ZIP and a SQLite 3 file. */
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val SQLITE_MAGIC = byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

        private const val SQLITE_ENTRY = "noop-backup.sqlite"
        private const val SETTINGS_ENTRY = "settings.json"
        private const val MANIFEST_ENTRY = "manifest.json"

        /**
         * Open [file], which may be a backup archive or a bare database.
         *
         * A bare database is read where it lies and its settings and manifest are looked for beside
         * it, which is what an unpacked archive leaves on disk. Absent, the profile falls back to the
         * app's own defaults and the schema version is read from the database.
         */
        @Throws(UnreadableBackup::class)
        fun open(file: File, overrides: Map<String, Any> = emptyMap()): BackupSource {
            val magic = ByteArray(16)
            file.inputStream().use { readFully(it, magic) }
            return when {
                magic.copyOf(4).contentEquals(ZIP_MAGIC) -> openZip(file, overrides)
                magic.contentEquals(SQLITE_MAGIC) -> openBare(file, overrides)
                else -> throw UnreadableBackup(file, null, "not a .noopbak ZIP and not a SQLite file")
            }
        }

        private fun openZip(file: File, overrides: Map<String, Any>): BackupSource {
            var settings: String? = null
            var manifestJson: String? = null
            val scratch = Files.createTempFile("calbench-", ".sqlite").toFile()
            var sawDb = false
            try {
                file.inputStream().use { raw ->
                    ZipInputStream(raw).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            when (entry.name.substringAfterLast('/')) {
                                SQLITE_ENTRY -> {
                                    scratch.outputStream().use { zip.copyTo(it) }
                                    sawDb = true
                                }
                                SETTINGS_ENTRY -> settings = zip.readBytes().toString(Charsets.UTF_8)
                                MANIFEST_ENTRY -> manifestJson = zip.readBytes().toString(Charsets.UTF_8)
                            }
                            entry = zip.nextEntry
                        }
                    }
                }
                if (!sawDb) throw UnreadableBackup(file, null, "no $SQLITE_ENTRY entry")
                return assemble(file, scratch, settings, manifestJson, scratch, overrides)
            } catch (e: Throwable) {
                scratch.delete()
                throw e
            }
        }

        private fun openBare(file: File, overrides: Map<String, Any>): BackupSource {
            val sibling = { name: String -> File(file.parentFile ?: File("."), name).takeIf { it.isFile }?.readText() }
            return assemble(file, file, sibling(SETTINGS_ENTRY), sibling(MANIFEST_ENTRY), null, overrides)
        }

        private fun assemble(
            file: File,
            sqlite: File,
            settings: String?,
            manifestJson: String?,
            scratch: File?,
            overrides: Map<String, Any>,
        ): BackupSource {
            val db = try {
                BackupDb(sqlite)
            } catch (e: Exception) {
                throw UnreadableBackup(file, null, "cannot open the SQLite: ${e.message}")
            }
            try {
                val declared = manifestJson?.let(::parseManifest)
                val manifest = declared ?: BackupManifestInfo(schemaVersion = db.userVersion())
                db.requireReadableSchema()?.let { throw UnreadableBackup(file, manifest.schemaVersion, it) }
                // Applied over the file's own values and then read back through the same builders, so
                // an override is clamped by the settings screen's rules exactly as a stored one is.
                val values = settings?.let(::decodeSettings).orEmpty() + overrides
                return BackupSource(
                    label = file.path,
                    manifest = manifest,
                    profile = profileFrom(values),
                    maxHROverride = (values["profile.hrMax"] as? Int)?.takeIf { it > 0 }?.toDouble(),
                    db = db,
                    scratch = scratch,
                )
            } catch (e: Throwable) {
                db.close()
                throw e
            }
        }

        private fun parseManifest(json: String): BackupManifestInfo? = runCatching {
            val o = JSONObject(json)
            BackupManifestInfo(
                appVersion = o.optString("appVersion"),
                appBuild = o.optString("appBuild"),
                platform = o.optString("platform"),
                schemaVersion = o.optInt("schemaVersion"),
                exportedAtMs = o.optLong("exportedAt"),
            )
        }.getOrNull()

        /**
         * The whitelisted settings this harness reads, by the app's own Int, Double and String rule: a
         * wrong-typed or unknown value is dropped rather than guessed at, and a malformed file yields
         * no keys rather than an error.
         *
         * Restated rather than reused because the app's decoder reaches Android types. Only the
         * profile and calorie keys are read; the display keys move no number in either model.
         */
        internal fun decodeSettings(json: String): Map<String, Any> {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            val out = LinkedHashMap<String, Any>()
            for ((key, kind) in READ_KEYS) {
                if (!o.has(key)) continue
                val raw = o.opt(key)
                val coerced = when (kind) {
                    // A JSON boolean is not a Number, so `true` cannot become an age of 1.
                    Kind.INT -> (raw as? Number)?.toInt()
                    Kind.DOUBLE -> (raw as? Number)?.toDouble()
                    Kind.STRING -> raw as? String
                }
                if (coerced != null) out[key] = coerced
            }
            return out
        }

        /** Every settings key an override may name, for a caller that wants to report the typo. */
        val settingKeys: Set<String> get() = READ_KEYS.keys

        /**
         * One `key=value` override as the type that key holds, or null when it is neither.
         *
         * The value arrives as text because it came from a command line; the whitelist decides what it
         * becomes, so an override cannot introduce a type the stored settings could not have held.
         */
        fun coerceSetting(key: String, raw: String): Any? = when (READ_KEYS[key]) {
            Kind.INT -> raw.toIntOrNull()
            Kind.DOUBLE -> raw.toDoubleOrNull()
            Kind.STRING -> raw
            null -> null
        }

        private enum class Kind { INT, DOUBLE, STRING }

        private val READ_KEYS: Map<String, Kind> = linkedMapOf(
            "profile.age" to Kind.INT,
            "profile.sex" to Kind.STRING,
            "profile.weightKg" to Kind.DOUBLE,
            "profile.heightCm" to Kind.DOUBLE,
            "profile.waistCm" to Kind.DOUBLE,
            "profile.hrMax" to Kind.INT,
            "profile.hrZoneThresholds" to Kind.STRING,
            "profile.vo2max" to Kind.DOUBLE,
            "calorie.model" to Kind.STRING,
            "calorie.dayActiveHRRFraction" to Kind.DOUBLE,
            "calorie.boutActiveHRRFraction" to Kind.DOUBLE,
            "calorie.activeAccrualMET" to Kind.DOUBLE,
            "calorie.dynAccelMETGainPerG" to Kind.DOUBLE,
            "calorie.hrFallbackWhenNoMET" to Kind.INT,
            "calorie.sessionGapS" to Kind.INT,
            "calorie.minHrCoverageFrac" to Kind.DOUBLE,
            "calorie.hampelRadiusS" to Kind.INT,
            "calorie.hampelSigmas" to Kind.DOUBLE,
            "calorie.suppressPeaks" to Kind.INT,
            "calorie.peakBlockS" to Kind.INT,
            "calorie.peakPercentile" to Kind.DOUBLE,
            "calorie.motionStillG" to Kind.DOUBLE,
            "calorie.motionSmoothS" to Kind.INT,
            "calorie.basalMinWindowS" to Kind.INT,
            "calorie.basalHrRangeBpm" to Kind.DOUBLE,
            "calorie.basalStillFrac" to Kind.DOUBLE,
            "calorie.basalBeatCoverageFrac" to Kind.DOUBLE,
            "calorie.restSmoothS" to Kind.INT,
            "calorie.restSmoothMinSamples" to Kind.INT,
            "calorie.basalHrSeedOffsetBpm" to Kind.DOUBLE,
            "calorie.reserveRampBandBpm" to Kind.DOUBLE,
            "calorie.measuredBasalKcalDay" to Kind.DOUBLE,
            "calorie.basalFatNight" to Kind.DOUBLE,
            "calorie.basalFatDay" to Kind.DOUBLE,
            "calorie.basalFatDayStartHour" to Kind.DOUBLE,
            "calorie.basalFatDayEndHour" to Kind.DOUBLE,
            "calorie.activeFatAtZone1" to Kind.DOUBLE,
            "calorie.activeFatAtZone2Top" to Kind.DOUBLE,
        )

        /**
         * The profile the app would have built from these values.
         *
         * An absent key falls back to the settings store's default rather than [UserProfile]'s, which
         * differ: a key is omitted from a backup precisely when the wearer never moved it, so the
         * store's default is what the app was scoring with. Each clamp is the settings screen's own,
         * so a hand-edited file can only produce a profile the app itself could hold.
         */
        internal fun profileFrom(values: Map<String, Any>): UserProfile {
            fun int(key: String) = values[key] as? Int
            fun double(key: String) = values[key] as? Double
            return UserProfile(
                weightKg = (double("profile.weightKg") ?: 75.0).coerceIn(30.0, 250.0),
                heightCm = (double("profile.heightCm") ?: 178.0).coerceIn(120.0, 230.0),
                age = (int("profile.age") ?: 30).coerceIn(13, 100).toDouble(),
                sex = (values["profile.sex"] as? String) ?: "male",
                vo2maxOverride = (double("profile.vo2max") ?: 0.0).coerceIn(0.0, 90.0),
                waistCm = (double("profile.waistCm") ?: 0.0).coerceIn(0.0, 200.0),
                heartRateGates = gatesFrom(values),
                hybridModelSetting = hybridSettingFrom(values),
                dynamicHrrModelSetting = dynamicHrrSettingFrom(values),
                hrZoneThresholds = zoneThresholdsFrom(values["profile.hrZoneThresholds"] as? String),
                calorieModel = EnergyModel.forId(values["calorie.model"] as? String)
                    ?: UserProfile().calorieModel,
            )
        }

        /** Five strictly increasing zone starts, or null when the stored value is not that. */
        private fun zoneThresholdsFrom(joined: String?): List<Double>? {
            val values = joined?.split(",")?.mapNotNull { it.trim().toIntOrNull()?.toDouble() } ?: return null
            return HrZones.validCustomLowerBounds(values)
        }

        private fun gatesFrom(values: Map<String, Any>): HeartRateGates {
            val defaults = HeartRateGates()
            fun double(key: String) = values[key] as? Double
            return HeartRateGates(
                dayActiveHRRFraction = (double("calorie.dayActiveHRRFraction") ?: defaults.dayActiveHRRFraction)
                    .coerceIn(HeartRateGatesRanges.HRR_MIN, HeartRateGatesRanges.HRR_MAX),
                boutActiveHRRFraction = (double("calorie.boutActiveHRRFraction") ?: defaults.boutActiveHRRFraction)
                    .coerceIn(HeartRateGatesRanges.HRR_MIN, HeartRateGatesRanges.HRR_MAX),
            )
        }

        private fun hybridSettingFrom(values: Map<String, Any>): HybridModelSetting {
            val defaults = HybridModelSetting()
            fun int(key: String) = values[key] as? Int
            fun double(key: String) = values[key] as? Double
            return HybridModelSetting(
                activeAccrualMET = (double("calorie.activeAccrualMET") ?: defaults.activeAccrualMET)
                    .coerceIn(HybridModelSettingRanges.ACCRUAL_MET_MIN, HybridModelSettingRanges.ACCRUAL_MET_MAX),
                dynAccelMETGainPerG = (double("calorie.dynAccelMETGainPerG") ?: defaults.dynAccelMETGainPerG)
                    .coerceIn(HybridModelSettingRanges.MET_GAIN_MIN, HybridModelSettingRanges.MET_GAIN_MAX),
                hrFallbackWhenNoMET = int("calorie.hrFallbackWhenNoMET")?.let { it != 0 } ?: defaults.hrFallbackWhenNoMET,
            )
        }

        private fun dynamicHrrSettingFrom(values: Map<String, Any>): DynamicHrrModelSetting {
            val defaults = DynamicHrrModelSetting()
            val ranges = DynamicHrrModelSettingRanges
            fun int(key: String, fallback: Int, range: IntRange) =
                (values[key] as? Int ?: fallback).coerceIn(range)
            fun double(key: String, fallback: Double, range: ClosedFloatingPointRange<Double>) =
                (values[key] as? Double ?: fallback).coerceIn(range)
            return DynamicHrrModelSetting(
                sessionGapS = int("calorie.sessionGapS", defaults.sessionGapS, ranges.SESSION_GAP_S),
                minHrCoverageFrac = double(
                    "calorie.minHrCoverageFrac", defaults.minHrCoverageFrac, ranges.MIN_HR_COVERAGE_FRAC),
                hampelRadiusS = int("calorie.hampelRadiusS", defaults.hampelRadiusS, ranges.HAMPEL_RADIUS_S),
                hampelSigmas = double("calorie.hampelSigmas", defaults.hampelSigmas, ranges.HAMPEL_SIGMAS),
                suppressPeaks = (values["calorie.suppressPeaks"] as? Int)?.let { it != 0 } ?: defaults.suppressPeaks,
                peakBlockS = int("calorie.peakBlockS", defaults.peakBlockS, ranges.PEAK_BLOCK_S),
                peakPercentile = double(
                    "calorie.peakPercentile", defaults.peakPercentile, ranges.PEAK_PERCENTILE),
                motionStillG = double("calorie.motionStillG", defaults.motionStillG, ranges.MOTION_STILL_G),
                motionSmoothS = int("calorie.motionSmoothS", defaults.motionSmoothS, ranges.MOTION_SMOOTH_S),
                basalMinWindowS = int(
                    "calorie.basalMinWindowS", defaults.basalMinWindowS, ranges.BASAL_MIN_WINDOW_S),
                basalHrRangeBpm = double(
                    "calorie.basalHrRangeBpm", defaults.basalHrRangeBpm, ranges.BASAL_HR_RANGE_BPM),
                basalStillFrac = double(
                    "calorie.basalStillFrac", defaults.basalStillFrac, ranges.BASAL_STILL_FRAC),
                basalBeatCoverageFrac = double(
                    "calorie.basalBeatCoverageFrac", defaults.basalBeatCoverageFrac, ranges.BASAL_BEAT_COVERAGE_FRAC),
                restSmoothS = int("calorie.restSmoothS", defaults.restSmoothS, ranges.REST_SMOOTH_S),
                restSmoothMinSamples = int(
                    "calorie.restSmoothMinSamples", defaults.restSmoothMinSamples, ranges.REST_SMOOTH_MIN_SAMPLES),
                basalHrSeedOffsetBpm = double(
                    "calorie.basalHrSeedOffsetBpm", defaults.basalHrSeedOffsetBpm, ranges.BASAL_HR_SEED_OFFSET_BPM),
                reserveRampBandBpm = double(
                    "calorie.reserveRampBandBpm", defaults.reserveRampBandBpm, ranges.RESERVE_RAMP_BAND_BPM),
                measuredBasalKcalDay = double(
                    "calorie.measuredBasalKcalDay", defaults.measuredBasalKcalDay, ranges.MEASURED_BASAL_KCAL_DAY),
                basalFatNight = double("calorie.basalFatNight", defaults.basalFatNight, ranges.BASAL_FAT_NIGHT),
                basalFatDay = double("calorie.basalFatDay", defaults.basalFatDay, ranges.BASAL_FAT_DAY),
                basalFatDayStartHour = double(
                    "calorie.basalFatDayStartHour", defaults.basalFatDayStartHour, ranges.BASAL_FAT_DAY_START_HOUR),
                basalFatDayEndHour = double(
                    "calorie.basalFatDayEndHour", defaults.basalFatDayEndHour, ranges.BASAL_FAT_DAY_END_HOUR),
                activeFatAtZone1 = double(
                    "calorie.activeFatAtZone1", defaults.activeFatAtZone1, ranges.ACTIVE_FAT_AT_ZONE1),
                activeFatAtZone2Top = double(
                    "calorie.activeFatAtZone2Top", defaults.activeFatAtZone2Top, ranges.ACTIVE_FAT_AT_ZONE2_TOP),
            )
        }

        private fun readFully(stream: InputStream, into: ByteArray) {
            var got = 0
            while (got < into.size) {
                val r = stream.read(into, got, into.size - got)
                if (r < 0) return
                got += r
            }
        }
    }
}

/**
 * The read-only database behind one backup, as the queries the calorie path makes.
 *
 * Each restates the app's own query: same columns, same ordering, same bounds. The generated
 * data-access code is Android and cannot be imported, so the SQL is written out literally and can be
 * compared against the app's line for line.
 */
class BackupDb(sqlite: File) : Closeable {

    private val conn: Connection =
        DriverManager.getConnection("jdbc:sqlite:file:${sqlite.absolutePath}?immutable=1")

    private val tables: Set<String> = buildSet {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                while (rs.next()) add(rs.getString(1))
            }
        }
    }

    private fun columns(table: String): Set<String> = buildSet {
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) add(rs.getString("name"))
            }
        }
    }

    fun userVersion(): Int =
        conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.getInt(1) } }

    /**
     * Why this backup cannot be scored, or null when it can.
     *
     * A table check rather than a schema-version floor: the version says which migration the file
     * stopped at, not which columns these queries need, so a floor would refuse a readable backup as
     * soon as an unrelated migration lands.
     */
    fun requireReadableSchema(): String? {
        val missing = listOf("hrSample", "gravitySample", "workout", "dailyMetric", "pairedDevice")
            .filterNot { it in tables }
        return if (missing.isEmpty()) null else "missing table(s): ${missing.joinToString(", ")}"
    }

    /**
     * The measured heart rate, unioned with the derived estimate that fills only seconds the strap
     * never reported a bpm for.
     *
     * A backup written before that table existed reads as the plain measured stream.
     */
    fun hrSamples(deviceId: String, from: Long, to: Long, limit: Int): List<HrSample> {
        val sql = if ("ppgHrSample" in tables) {
            "SELECT ts, bpm FROM (" +
                "SELECT ts, bpm FROM hrSample WHERE deviceId = ? AND ts >= ? AND ts <= ? " +
                "UNION ALL " +
                "SELECT p.ts AS ts, p.bpm AS bpm FROM ppgHrSample p " +
                "WHERE p.deviceId = ? AND p.ts >= ? AND p.ts <= ? " +
                "AND NOT EXISTS (SELECT 1 FROM hrSample h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)" +
                ") ORDER BY ts ASC LIMIT ?"
        } else {
            "SELECT ts, bpm FROM hrSample WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        }
        return conn.prepareStatement(sql).use { st ->
            var i = 0
            st.setString(++i, deviceId); st.setLong(++i, from); st.setLong(++i, to)
            if ("ppgHrSample" in tables) {
                st.setString(++i, deviceId); st.setLong(++i, from); st.setLong(++i, to)
            }
            st.setInt(++i, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(HrSample(deviceId, rs.getLong(1), rs.getInt(2))) }
            }
        }
    }

    /** The motion stream. A backup written before that column existed reads null for every row. */
    fun gravitySamples(deviceId: String, from: Long, to: Long, limit: Int): List<GravitySample> {
        val hasDyn = "dynAccel" in columns("gravitySample")
        val sql = "SELECT ts, x, y, z${if (hasDyn) ", dynAccel" else ""} FROM gravitySample " +
            "WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        return conn.prepareStatement(sql).use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to); st.setInt(4, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val dyn = if (hasDyn) rs.getDouble(5).takeUnless { rs.wasNull() } else null
                        add(
                            GravitySample(
                                deviceId = deviceId,
                                ts = rs.getLong(1),
                                x = rs.getDouble(2),
                                y = rs.getDouble(3),
                                z = rs.getDouble(4),
                                dynAccel = dyn,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Beat-to-beat intervals, counted per second.
     *
     * The two filters and the ordering are the app's own, restated here: rows on the SpO2 channel
     * double-count the same heartbeats, and a row stamped in the future carries a corrupt time. A
     * backup written before either column existed carries neither, and reads unfiltered.
     */
    fun rrBeatsPerSecond(deviceId: String, from: Long, to: Long, limit: Int): List<Pair<Long, Int>> {
        if ("rrInterval" !in tables) return emptyList()
        val present = columns("rrInterval")
        val filters = StringBuilder()
        if ("srcChannel" in present) filters.append(" AND (srcChannel IS NULL OR srcChannel <> 2)")
        if ("tsSuspect" in present) filters.append(" AND (tsSuspect IS NULL OR tsSuspect <> 1)")
        val sql = "SELECT ts FROM rrInterval WHERE deviceId = ? AND ts >= ? AND ts <= ?$filters " +
            "ORDER BY ts ASC, ord ASC, rrMs ASC, seq ASC LIMIT ?"
        return conn.prepareStatement(sql).use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to); st.setInt(4, limit)
            st.executeQuery().use { rs ->
                val counts = LinkedHashMap<Long, Int>()
                while (rs.next()) counts.merge(rs.getLong(1), 1, Int::plus)
                counts.toList()
            }
        }
    }

    /** The workout rows. The sport rides along because it is half the deduplication key. */
    fun workouts(deviceId: String, from: Long, to: Long, limit: Int): List<WorkoutRowLite> =
        conn.prepareStatement(
            "SELECT startTs, endTs, sport FROM workout WHERE deviceId = ? AND startTs >= ? AND startTs <= ? " +
                "ORDER BY startTs ASC LIMIT ?",
        ).use { st ->
            st.setString(1, deviceId); st.setLong(2, from); st.setLong(3, to); st.setInt(4, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(WorkoutRowLite(rs.getLong(1), rs.getLong(2), rs.getString(3))) }
            }
        }

    /** The daily rows, narrowed to the one column the calorie anchors read. */
    fun restingHrRows(deviceId: String, from: String, to: String): List<DailyMetric> =
        conn.prepareStatement(
            "SELECT day, restingHr FROM dailyMetric WHERE deviceId = ? AND day >= ? AND day <= ? ORDER BY day ASC",
        ).use { st ->
            st.setString(1, deviceId); st.setString(2, from); st.setString(3, to)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DailyMetric(
                                deviceId = deviceId,
                                day = rs.getString(1),
                                restingHr = rs.getInt(2).takeUnless { rs.wasNull() },
                            ),
                        )
                    }
                }
            }
        }

    /** The most recent day under a source that carries a resting HR. */
    fun latestRestingHrDay(deviceId: String, day: String): String? =
        conn.prepareStatement(
            "SELECT day FROM dailyMetric WHERE deviceId = ? AND day <= ? AND restingHr IS NOT NULL " +
                "ORDER BY day DESC LIMIT 1",
        ).use { st ->
            st.setString(1, deviceId); st.setString(2, day)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** The day's stored active-energy figure, for comparing a re-run against what the app banked. */
    fun storedActiveKcal(deviceId: String, day: String): Double? =
        if ("activeKcalEst" !in columns("dailyMetric")) {
            null
        } else {
            conn.prepareStatement("SELECT activeKcalEst FROM dailyMetric WHERE deviceId = ? AND day = ?").use { st ->
                st.setString(1, deviceId); st.setString(2, day)
                st.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1).takeUnless { rs.wasNull() } else null }
            }
        }

    /** Every registry row, oldest first. */
    fun pairedDevices(): List<PairedDeviceLite> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id, sourceKind, status FROM pairedDevice ORDER BY addedAt ASC").use { rs ->
                buildList { while (rs.next()) add(PairedDeviceLite(rs.getString(1), rs.getString(2), rs.getString(3))) }
            }
        }

    /** The registry's active device. */
    fun activeDeviceId(): String? =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM pairedDevice WHERE status = 'active' LIMIT 1")
                .use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** The stored owner override for a day, authoritative whether or not it is locked. */
    fun dayOwner(day: String): String? =
        if ("dayOwnership" !in tables) {
            null
        } else {
            conn.prepareStatement("SELECT deviceId FROM dayOwnership WHERE day = ?").use { st ->
                st.setString(1, day)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** Every day key that carries heart rate, so a run with no `--from`/`--to` knows what to score. */
    fun hrDayRange(): Pair<Long, Long>? =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT MIN(ts), MAX(ts) FROM hrSample").use { rs ->
                if (!rs.next()) return null
                val lo = rs.getLong(1)
                if (rs.wasNull()) return null
                lo to rs.getLong(2)
            }
        }

    override fun close() = conn.close()
}

data class WorkoutRowLite(val startTs: Long, val endTs: Long, val sport: String)
data class PairedDeviceLite(val id: String, val sourceKind: String, val status: String)
