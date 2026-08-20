/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Transcripts, deliberately in their **own** database rather than in
 * [com.baba.callvault.data.recordings.db.RecordingDatabase].
 *
 * That database uses `fallbackToDestructiveMigration(dropAllTables = true)`, which is right for it
 * and fatal for us. The recordings catalog is a derived cache: it can be re-seeded from the SAF
 * folders, so dropping it on a schema bump costs nothing. A transcript is the opposite — it costs
 * minutes of device CPU and cannot be regenerated from anything cheaper than the audio it came from,
 * at the same cost again. Sharing that database would mean the next unrelated schema change silently
 * destroys every transcript the user has.
 *
 * **This database must therefore NEVER be given a destructive fallback.** Schema changes here get
 * real, hand-written, tested migrations. `exportSchema = true` so those migrations have a schema
 * to diff against, which the recordings catalog deliberately does without.
 *
 * Rows are keyed by `RecordingEntry.displayName`, the catalog's natural key, so transcripts survive
 * the catalog being dropped and re-seeded.
 */
@Database(
    entities = [
        TranscriptEntry::class,
        TranscriptSegmentEntry::class,
        TranscriptSegmentFts::class,
        RecordingNoteEntry::class,
        RecordingWaveformEntry::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(TranscriptStateConverter::class)
abstract class TranscriptDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao

    abstract fun noteDao(): RecordingNoteDao

    companion object {

        private const val DB_NAME = "transcripts.db"

        /**
         * v1 → v2: notes and cached waveforms.
         *
         * Hand-written rather than destructive because of what is in here: a note is the user's own
         * words and cannot be regenerated from anything. `CREATE TABLE IF NOT EXISTS` with the exact
         * column types Room expects, so the schema it validates against on open matches.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recording_notes` (" +
                        "`displayName` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`displayName`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recording_waveforms` (" +
                        "`displayName` TEXT NOT NULL, " +
                        "`peaks` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`displayName`))"
                )
            }
        }

        @Volatile
        private var INSTANCE: TranscriptDatabase? = null

        fun get(context: Context): TranscriptDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranscriptDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it } // no destructive fallback — see the class KDoc
            }

        /**
         * Whether a transcripts database has ever been created.
         *
         * Lets the delete cascade skip its work entirely on a device that has never transcribed
         * anything, instead of calling [get] and materialising an empty database as a side effect of
         * deleting a recording.
         *
         * An already-open [INSTANCE] counts on its own: the database plainly exists once this process
         * has opened it, and Room creates the file lazily on first write, so the file check alone can
         * report "no database" while one is open and holding rows.
         */
        fun exists(context: Context): Boolean =
            INSTANCE != null || context.applicationContext.getDatabasePath(DB_NAME).exists()
    }
}
