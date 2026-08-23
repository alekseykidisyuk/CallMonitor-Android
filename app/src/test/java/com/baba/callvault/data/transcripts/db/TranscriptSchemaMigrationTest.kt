/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the hand-written migrations against the schema drifting out from under them.
 *
 * [TranscriptDatabase] has no destructive fallback, deliberately: a transcript costs minutes of the
 * user's CPU and a summary costs ninety seconds more, so dropping the tables on a schema bump would
 * spend their battery twice over. The price of that decision is that a migration which does not
 * produce exactly what Room expects is not a lost cache — it is a crash on open, for every user who
 * has ever transcribed a call, with no way back.
 *
 * **What this proves.** Room exports the schema it will validate against into `app/schemas/`. This
 * compares each migration's `CREATE TABLE` against that exported truth, character for character.
 * The realistic failure it catches is drift rather than a typo today: someone adds a column to
 * [CallSummaryEntry] months from now, Room's expectation moves, and the migration silently does not.
 *
 * **What it does not prove.** It does not open a v2 database and upgrade it. Doing that faithfully
 * needs Room's own `MigrationTestHelper` reading these same files, which under Robolectric means
 * merging the schema directory into test assets. That is worth adding the day a migration does more
 * than add a table — one that transforms existing rows can be perfectly well-formed and still lose
 * data, and nothing here would notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSchemaMigrationTest {

    @Test
    fun `the summaries migration creates exactly the table Room expects at v3`() {
        val expected = createSqlFor(version = 3, table = "call_summaries")

        assertEquals(expected, sqlIn(TranscriptDatabase.MIGRATION_2_3_SQL.single()))
    }

    @Test
    fun `the notes migration still creates exactly the tables Room expects`() {
        // MIGRATION_1_2 shipped untested. It is right — this is what says so, and keeps saying so.
        val statements = TranscriptDatabase.MIGRATION_1_2_SQL.map(::sqlIn)

        assertTrue(createSqlFor(version = 2, table = "recording_notes") in statements)
        assertTrue(createSqlFor(version = 2, table = "recording_waveforms") in statements)
    }

    @Test
    fun `the speaker turns migration creates exactly the table Room expects at v4`() {
        val expected = createSqlFor(version = 4, table = "speaker_turns")

        assertEquals(expected, sqlIn(TranscriptDatabase.MIGRATION_3_4_SQL.single()))
    }

    @Test
    fun `every table added since v1 is created by a migration`() {
        // The check that survives someone adding an entity: if a table exists at the current version
        // but was not in v1 and no migration creates it, upgrading users never get it.
        val migrated = (
            TranscriptDatabase.MIGRATION_1_2_SQL +
                TranscriptDatabase.MIGRATION_2_3_SQL +
                TranscriptDatabase.MIGRATION_3_4_SQL
            ).map(::sqlIn)
        val original = tablesAt(version = 1)

        tablesAt(version = LATEST_VERSION).forEach { table ->
            if (table in original) return@forEach
            assertTrue(
                "No migration creates `$table`, so anyone upgrading never gets it",
                migrated.any { createSqlFor(LATEST_VERSION, table) == it }
            )
        }
    }

    /** Room's own `CREATE TABLE` for [table] at [version], with its table-name placeholder resolved. */
    private fun createSqlFor(version: Int, table: String): String {
        val entities = schema(version).getJSONObject("database").getJSONArray("entities")
        val entity = (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .firstOrNull { it.getString("tableName") == table }
            ?: error("v$version has no table `$table`; the exported schema and this test disagree")
        return sqlIn(entity.getString("createSql").replace("\${TABLE_NAME}", table))
    }

    private fun tablesAt(version: Int): Set<String> {
        val entities = schema(version).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
    }

    private fun schema(version: Int): JSONObject {
        val file = File(SCHEMA_DIR, "$version.json")
        check(file.exists()) {
            "Missing ${file.path}. Room exports schemas on build; run the tests from the app module."
        }
        return JSONObject(file.readText())
    }

    /** Collapses whitespace so a reformatted string concatenation is not a failing test. */
    private fun sqlIn(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val LATEST_VERSION = 4
        const val SCHEMA_DIR = "schemas/com.baba.callvault.data.transcripts.db.TranscriptDatabase"
    }
}
