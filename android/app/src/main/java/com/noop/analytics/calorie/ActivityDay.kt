package com.noop.analytics.calorie

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One activity day: a calendar date, and the zone whose midnight opens it.
 *
 * A bare "yyyy-MM-dd" key cannot say which midnight it means, so a day resolved under the wrong zone
 * is scored over the wrong twenty-four hours. Build one through a factory below rather than by hand.
 */
data class ActivityDay(val date: LocalDate, val zone: ZoneId) {

    /** The "yyyy-MM-dd" key rows are bucketed under. */
    val key: String get() = date.toString()

    /**
     * Local midnight, in epoch seconds.
     *
     * Every factory supplies a fixed [ZoneOffset] rather than a region zone. A region zone makes this
     * daylight-saving aware, which shifts a past day by an hour and so moves a stored value.
     */
    fun localMidnightUtc(): Long = date.atStartOfDay(zone).toEpochSecond()

    /**
     * The `[start, end)` UTC window this day is scored over.
     *
     * The activity day is the calendar day: it opens at [localMidnightUtc] and runs a fixed 86 400 s,
     * so energy buckets on the same boundary as every other daily figure.
     */
    fun window(): LongRange = localMidnightUtc().let { it until it + 86_400L }

    companion object {

        // Not LocalDate.EPOCH, which is Java 9 and absent on API 26.
        private val EPOCH_DAY: LocalDate = LocalDate.of(1970, 1, 1)

        /**
         * [key] parsed under [zone], or the epoch day when it is malformed.
         *
         * A 1970 window matches no real sample, so one bad key cannot fail a whole scoring pass.
         */
        fun forKey(key: String, zone: ZoneId): ActivityDay =
            ActivityDay(runCatching { LocalDate.parse(key) }.getOrDefault(EPOCH_DAY), zone)

        /** [key] at a fixed offset of [tzOffsetSeconds] east of UTC. */
        fun forKey(key: String, tzOffsetSeconds: Long): ActivityDay =
            forKey(key, offsetOf(tzOffsetSeconds))

        /** [date] at a fixed offset of [tzOffsetSeconds] east of UTC. */
        fun atOffset(date: LocalDate, tzOffsetSeconds: Long): ActivityDay =
            ActivityDay(date, offsetOf(tzOffsetSeconds))

        /**
         * The day whose local midnight is [localMidnightUtc].
         *
         * The date is the nearest UTC day, not the one below: a midnight an hour east of UTC would
         * otherwise need a −23 h offset, which a zone offset cannot express.
         */
        fun atLocalMidnight(localMidnightUtc: Long): ActivityDay {
            val date = LocalDate.ofEpochDay(Math.floorDiv(localMidnightUtc + 43_200L, 86_400L))
            return atOffset(date, date.toEpochDay() * 86_400L - localMidnightUtc)
        }

        private fun offsetOf(tzOffsetSeconds: Long): ZoneOffset =
            ZoneOffset.ofTotalSeconds(tzOffsetSeconds.toInt())
    }
}
