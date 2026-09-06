package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v34 -> v35: re-home the Health Connect daily backfill off `my-whoop` onto `health-connect`.
 *
 * This is the BLOCKING half of the change. Narrowing [WhoopDao.PURGE_HC_SHADOWED_DAILY_SQL] to spare rows
 * that carry vitals, while the legacy rows still sit under the strap's own id, would leave them permanent
 * AND arbitrated as strap-grade data — strictly worse than deleting them. So the migration must move them
 * first, and its predicate must match the purge's shape fingerprint exactly, or the two disagree about
 * what "Health-Connect-shaped" means and a row falls between them.
 *
 * As with the other migrations here, this environment has no Robolectric / in-memory Room (see
 * [AppleStepHourMigrationTest]), so the statement is pinned to shape rather than executed. Nothing in this
 * file proves the UPDATE moves a row on a real database — only that the statement says what it must.
 */
class HealthConnectDailyRehomeMigrationTest {

    private val sql = WhoopDatabase.HEALTH_CONNECT_DAILY_REHOME_MIGRATION_SQL.single()

    @Test
    fun migrationSpansTheRightVersions() {
        assertEquals(36, WhoopDatabase.MIGRATION_36_37.startVersion)
        assertEquals(37, WhoopDatabase.MIGRATION_36_37.endVersion)
    }

    @Test
    fun migrationIsRegisteredInTheChain() {
        assertTrue(
            "MIGRATION_36_37 must be in ALL_MIGRATIONS or Room throws on upgrade",
            WhoopDatabase.ALL_MIGRATIONS.any { it.startVersion == 36 && it.endVersion == 37 },
        )
    }

    /** `UPDATE OR IGNORE`, because `(deviceId, day)` is the primary key: a day that somehow already has a
     *  `health-connect` row must keep it rather than abort the whole migration. */
    @Test
    fun migrationMovesRowsAndSurvivesAPrimaryKeyCollision() {
        assertTrue(sql.uppercase().startsWith("UPDATE OR IGNORE"))
        assertTrue(sql.contains("SET `deviceId` = 'health-connect'"))
        assertTrue(sql.contains("WHERE `deviceId` = 'my-whoop'"))
        // Re-homing, never deleting: a legacy row's vitals are the only reading anyone has for that day.
        for (banned in listOf("DROP ", "DELETE ", "INSERT ", "RENAME ")) {
            assertTrue("migration must not contain $banned", !sql.uppercase().contains(banned))
        }
    }

    /**
     * The shape fingerprint is the purge's, column for column. A WHOOP CSV import or a strap-scored night
     * always carries efficiency and stage minutes, so neither can match — which is what stops the migration
     * moving real strap history onto a phone source.
     */
    @Test
    fun migrationMatchesTheSamePurgeShapeFingerprint() {
        val shapeColumns = listOf(
            "efficiency", "deepMin", "remMin", "lightMin",
            "disturbances", "recovery", "strain", "steps", "activeKcalEst",
        )
        for (column in shapeColumns) {
            assertTrue("re-home predicate must require $column IS NULL", sql.contains("`$column` IS NULL"))
            assertTrue(
                "purge predicate must require $column IS NULL",
                WhoopDao.PURGE_HC_SHADOWED_DAILY_SQL.contains("$column IS NULL"),
            )
        }
    }

    /**
     * The migration must NOT require the vitals columns to be null — those are exactly the rows worth
     * moving. The purge, conversely, must require them, so a row carrying a reading is never deleted. The
     * two predicates are deliberately asymmetric on these four columns and identical everywhere else.
     */
    @Test
    fun vitalsAreCarriedByTheMigrationAndSparedByThePurge() {
        val vitals = listOf("restingHr", "avgHrv", "spo2Pct", "respRateBpm")
        for (column in vitals) {
            assertTrue(
                "$column must not be part of the re-home predicate — a row carrying one is the point",
                !sql.contains("`$column` IS NULL"),
            )
            assertTrue(
                "purge must spare a row carrying $column",
                WhoopDao.PURGE_HC_SHADOWED_DAILY_SQL.contains("$column IS NULL"),
            )
        }
    }

    /**
     * The purge still deletes an empty row on a day a computed source covers, which is the job
     * narrowing it must not turn off. Idempotent: it matches on column state, so a re-run after the
     * rows are gone matches nothing.
     */
    @Test
    fun purgeStillTargetsShadowedEmptyRowsOnly() {
        val purge = WhoopDao.PURGE_HC_SHADOWED_DAILY_SQL
        assertTrue(purge.startsWith("DELETE FROM dailyMetric WHERE deviceId = 'my-whoop'"))
        assertTrue(
            "the purge must stay scoped to days a computed source covers",
            purge.contains("day IN (SELECT day FROM dailyMetric d WHERE d.deviceId LIKE '%-noop')"),
        )
    }
}
