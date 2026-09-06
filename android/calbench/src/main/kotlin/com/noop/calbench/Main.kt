package com.noop.calbench

import com.noop.analytics.calorie.CalorieModels
import com.noop.analytics.calorie.EnergyModel
import kotlinx.coroutines.runBlocking
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.LocalDate
import java.time.ZoneId

private const val USAGE = """
calbench — run NOOP's shipped calorie models over a range of backups.

  calbench --backups <dir-or-file>... [--from YYYY-MM-DD] [--to YYYY-MM-DD]
           [--model hybrid|hr|all] [--tz America/Los_Angeles]
           [--set key=value]... [--format csv|json] [--out PATH]

A directory argument takes every *.noopbak and *.sqlite inside it. One row per
(backup, day, model, minute) goes to stdout, or to --out; progress and warnings
go to stderr. Days overlap between snapshots of the same device on purpose —
every row is tagged with its source backup, and the caller picks.

--set overrides one of the backup's own settings for the run, so a parameter can
be swept without editing the file. It is read back through the app's own clamps,
and the file on disk is never written to.

Defaults: --model all, --tz the system zone, --format csv, the full heart-rate
extent of each backup.
"""

/** Parsed by hand: a harness with six flags does not earn a parser dependency. */
data class Options(
    val backups: List<File>,
    val from: LocalDate?,
    val to: LocalDate?,
    val models: List<EnergyModel>,
    val zone: ZoneId,
    val json: Boolean,
    val out: File?,
    /** Settings to apply over each backup's own, already coerced to the type the key holds. */
    val settings: Map<String, Any>,
) {

    companion object {

        /** Throws [IllegalArgumentException] with a message a user can act on; the caller prints the usage. */
        fun parse(args: Array<String>): Options {
            val backups = ArrayList<File>()
            var from: LocalDate? = null
            var to: LocalDate? = null
            var models = CalorieModels.order
            var zone = ZoneId.systemDefault()
            var json = false
            var out: File? = null
            val settings = LinkedHashMap<String, Any>()
            var i = 0
            fun next(flag: String): String {
                require(i + 1 < args.size) { "$flag needs a value" }
                return args[++i]
            }
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--backups" -> {
                        // Variadic, so a shell glob can be passed straight through.
                        while (i + 1 < args.size && !args[i + 1].startsWith("--")) backups += File(args[++i])
                        require(backups.isNotEmpty()) { "--backups needs at least one path" }
                    }
                    "--from" -> from = day(next(arg))
                    "--to" -> to = day(next(arg))
                    "--model" -> models = models(next(arg))
                    "--tz" -> zone = zone(next(arg))
                    "--set" -> setting(next(arg)).let { (key, value) -> settings[key] = value }
                    "--format" -> json = format(next(arg))
                    "--out" -> out = File(next(arg))
                    "--help", "-h" -> throw IllegalArgumentException("")
                    else -> throw IllegalArgumentException("unknown argument: $arg")
                }
                i++
            }
            require(backups.isNotEmpty()) { "--backups is required" }
            require(from == null || to == null || !from.isAfter(to)) { "--from is after --to" }
            return Options(backups, from, to, models, zone, json, out, settings)
        }

        /**
         * One `--set key=value`, refused unless the key is a setting a backup could itself carry.
         *
         * An unknown or wrong-typed key is a mistake worth stopping for: silently dropping it would
         * report a whole run as a sweep of something it never changed.
         */
        private fun setting(value: String): Pair<String, Any> {
            val key = value.substringBefore('=')
            require(key.isNotEmpty() && '=' in value) { "--set wants key=value, not $value" }
            val coerced = BackupSource.coerceSetting(key, value.substringAfter('='))
                ?: throw IllegalArgumentException(
                    if (key in BackupSource.settingKeys) {
                        "--set $key does not hold ${value.substringAfter('=')}"
                    } else {
                        "unknown setting: $key (known: ${BackupSource.settingKeys.joinToString(", ")})"
                    },
                )
            return key to coerced
        }

        private fun day(value: String): LocalDate =
            runCatching { LocalDate.parse(value) }
                .getOrElse { throw IllegalArgumentException("not a YYYY-MM-DD day: $value") }

        private fun zone(value: String): ZoneId =
            runCatching { ZoneId.of(value) }
                .getOrElse { throw IllegalArgumentException("not a time-zone id: $value") }

        private fun format(value: String): Boolean = when (value.lowercase()) {
            "csv" -> false
            "json" -> true
            else -> throw IllegalArgumentException("--format is csv or json, not $value")
        }

        /** Accepts a model's stored id or its name, so a backup's token can be typed verbatim. */
        private fun models(value: String): List<EnergyModel> {
            if (value.equals("all", ignoreCase = true)) return CalorieModels.order
            val model = EnergyModel.forId(value.lowercase())
                ?: EnergyModel.values().firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "unknown model: $value (known: all, ${EnergyModel.values().joinToString(", ") { it.id }})",
                )
            return listOf(model)
        }
    }
}

/**
 * Expand a path into the backups under it.
 *
 * A directory is taken one level deep and sorted, so a run over a folder is reproducible. An
 * explicit file is taken whatever it is called.
 */
internal fun expand(path: File): List<File> = when {
    path.isDirectory -> path.listFiles()
        .orEmpty()
        .filter { it.isFile && (it.name.endsWith(".noopbak") || it.name.endsWith(".sqlite")) }
        .sortedBy { it.name }
    path.isFile -> listOf(path)
    else -> emptyList()
}

fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        if (!e.message.isNullOrEmpty()) System.err.println("calbench: ${e.message}")
        System.err.println(USAGE.trim())
        kotlin.system.exitProcess(if (e.message.isNullOrEmpty()) 0 else 2)
    }

    val warn = Warn { System.err.println("calbench: $it") }
    val sink: Writer = options.out
        ?.let { BufferedWriter(it.writer(), 1 shl 20) }
        ?: BufferedWriter(OutputStreamWriter(System.out), 1 shl 20)
    val writer: RowWriter = if (options.json) JsonWriter(sink) else CsvWriter(sink)
    val runner = Runner(options.zone, options.models, writer, warn)

    var skipped = 0
    sink.use {
        for (path in options.backups) {
            val files = expand(path)
            if (files.isEmpty()) warn.warn("$path: no backup found here")
            for (file in files) {
                val source = try {
                    BackupSource.open(file, options.settings)
                } catch (e: UnreadableBackup) {
                    // Named with its schema version and then skipped, so one unreadable file does not
                    // cost the rest of the folder.
                    warn.warn("skipping $file (schemaVersion=${e.schemaVersion ?: "?"}): ${e.message}")
                    skipped++
                    continue
                }
                source.use { runBlocking { runner.run(it, options.from, options.to) } }
            }
        }
        writer.finish()
    }
    warn.warn(
        "done: ${runner.daysScored} day(s) scored, ${runner.daysUnobserved} unobserved, $skipped backup(s) skipped",
    )
}
