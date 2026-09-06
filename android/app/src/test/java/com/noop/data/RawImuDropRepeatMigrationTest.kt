package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v37 -> v38 repeat of the `rawImuSample` drop.
 *
 * A database that reached version 35 on this branch before it was rebased never ran the drop, and no
 * later step could reach it, so the table survives as dead bytes that Room does not map and does not
 * complain about. This migration is that drop again, written to be a no-op everywhere else.
 */
class RawImuDropRepeatMigrationTest {

    @Test
    fun migrationDropsOnlyTheRetiredTable() {
        val sql = WhoopDatabase.RAW_IMU_DROP_MIGRATION_SQL
        assertEquals("one statement", 1, sql.size)
        assertEquals("DROP TABLE IF EXISTS `rawImuSample`", sql.single())
    }

    /** `IF EXISTS`, because the table is already gone for every install that came through 34 -> 35. */
    @Test
    fun migrationIsHarmlessWhereTheTableIsAlreadyGone() {
        assertTrue(WhoopDatabase.RAW_IMU_DROP_MIGRATION_SQL.single().contains("IF EXISTS"))
    }

    /**
     * The drop must not reach a table something still maps. `rawImuSample` is named here as a literal
     * so that re-adding an entity of that name cannot quietly restore a table this deletes.
     */
    @Test
    fun theDroppedTableIsNotOneTheSchemaStillDeclares() {
        val oracle = javaClass.classLoader!!.getResource("schema_oracle.json")!!.readText()
        assertTrue("the oracle must not declare rawImuSample", !oracle.contains("\"rawImuSample\""))
    }

    @Test
    fun migrationSpansTheRightVersions() {
        assertEquals(37, WhoopDatabase.MIGRATION_37_38.startVersion)
        assertEquals(38, WhoopDatabase.MIGRATION_37_38.endVersion)
    }

    @Test
    fun migrationIsRegisteredInTheChain() {
        assertTrue(
            "MIGRATION_37_38 must be in ALL_MIGRATIONS or Room throws on upgrade",
            WhoopDatabase.ALL_MIGRATIONS.any { it.startVersion == 37 && it.endVersion == 38 },
        )
    }

    /** The chain's end must be the version the database declares, or Room has nowhere to stop. */
    @Test
    fun theChainEndsAtTheDeclaredSchemaVersion() {
        assertEquals(41, WhoopDatabase.SCHEMA_VERSION)
        assertEquals(
            WhoopDatabase.SCHEMA_VERSION,
            WhoopDatabase.ALL_MIGRATIONS.maxOf { it.endVersion },
        )
    }
}
