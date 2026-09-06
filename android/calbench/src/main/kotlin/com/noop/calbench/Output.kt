package com.noop.calbench

import java.io.Writer

/**
 * One minute of one day under one model, from one backup.
 *
 * The day totals ride every row rather than going to a second file, so a reader can group without a
 * join. They are taken from the model and never re-folded from the minutes: the model adds its terms
 * in a different order, and re-adding a day's doubles left to right moves the last bit.
 */
data class EpochRow(
    val backup: String,
    val exportedAt: Long,
    val appVersion: String,
    val appBuild: String,
    val schemaVersion: Int,
    val day: String,
    val deviceId: String,
    val model: String,
    val tz: String,
    val windowStart: Long,
    val windowEnd: Long,
    val epochStart: Long,
    val activeKcal: Double,
    /**
     * This minute's non-energy series, keyed by the producing model's own label constants.
     *
     * A key the model left null for this minute is absent rather than zero, so an empty map is a
     * minute the model described only by its energy.
     */
    val series: Map<String, Double>,
    val dayTotalKcal: Double,
    val dayBasalKcal: Double,
    val dayActiveKcal: Double,
    /** What the app stored for this day under this owner, or null when it scored none. */
    val storedActiveKcal: Double?,
    /** The day figures the producing model reports about its own working, keyed by its constants. */
    val extras: Map<String, Double>,
)

/**
 * The column order, shared by both writers so either output describes the same table.
 *
 * `series` and `extras` are one column each rather than one per key. Which keys a model emits varies
 * by day — a declined day names fewer of them, and some are set only when the model measured
 * something — so the full set is not known until the last day is scored, which is far too late to
 * write a header. Each writer renders the two maps in whatever its format expresses naturally.
 */
private val COLUMNS = listOf(
    "backup", "exportedAt", "appVersion", "appBuild", "schemaVersion",
    "day", "deviceId", "model", "tz",
    "windowStart", "windowEnd",
    "epochStart", "activeKcal", "series",
    "dayTotalKcal", "dayBasalKcal", "dayActiveKcal",
    "storedActiveKcal", "extras",
)

private fun EpochRow.values(): List<Any?> = listOf(
    backup, exportedAt, appVersion, appBuild, schemaVersion,
    day, deviceId, model, tz,
    windowStart, windowEnd,
    epochStart, activeKcal, series,
    dayTotalKcal, dayBasalKcal, dayActiveKcal,
    storedActiveKcal, extras,
)

/** Writes rows as they are scored: a year of days across two models is millions of them. */
interface RowWriter {
    fun row(row: EpochRow)
    fun finish()
}

class CsvWriter(private val out: Writer) : RowWriter {

    init {
        out.write(COLUMNS.joinToString(","))
        out.write("\n")
    }

    override fun row(row: EpochRow) {
        out.write(row.values().joinToString(",") { field(it) })
        out.write("\n")
    }

    override fun finish() = out.flush()

    /**
     * Renders a Double as the shortest decimal that round-trips to the same bits.
     *
     * A formatted number would reach the reader as a different value from the one the model produced.
     * This is also locale-independent, which the formatting alternatives are not.
     */
    private fun field(value: Any?): String = when (value) {
        null -> ""
        is Map<*, *> -> quoteIfNeeded(map(value))
        is String -> quoteIfNeeded(value)
        else -> value.toString()
    }

    /**
     * A map as `key=value;key=value`, in the order the model built it.
     *
     * CSV has no nesting, and a column per key is not available here (see [COLUMNS]). Label constants
     * are identifiers, so neither separator can occur in a key; a value is always a number.
     */
    private fun map(value: Map<*, *>): String =
        value.entries.joinToString(";") { (k, v) -> "$k=$v" }

    private fun quoteIfNeeded(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

/** A JSON array of objects, written incrementally like [CsvWriter]. */
class JsonWriter(private val out: Writer) : RowWriter {

    private var first = true

    init {
        out.write("[")
    }

    override fun row(row: EpochRow) {
        if (!first) out.write(",")
        first = false
        out.write("\n  {")
        out.write(
            COLUMNS.zip(row.values()).joinToString(", ") { (name, value) ->
                "${quote(name)}:${literal(value)}"
            },
        )
        out.write("}")
    }

    override fun finish() {
        out.write(if (first) "]\n" else "\n]\n")
        out.flush()
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "null"
        // Nested, because JSON has somewhere to put a map and a reader of it expects one.
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "${quote("$k")}:${literal(v)}" }
        is String -> quote(value)
        is Double -> if (value.isFinite()) value.toString() else "null"
        else -> value.toString()
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
