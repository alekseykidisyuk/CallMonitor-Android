/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Summaries a model wrote about a call. */
@Dao
interface CallSummaryDao {

    /**
     * The summary for a recording, or null while it has none.
     *
     * A [Flow] because the playback screen is open while the summary is being written: the card
     * has to arrive when the worker finishes, without the user leaving the screen and coming back.
     */
    @Query("SELECT * FROM call_summaries WHERE displayName = :displayName")
    fun observe(displayName: String): Flow<CallSummaryEntry?>

    @Query("SELECT * FROM call_summaries WHERE displayName = :displayName")
    suspend fun summary(displayName: String): CallSummaryEntry?

    /**
     * Upsert rather than insert, so redoing a summary replaces the old one.
     *
     * Keeping both would mean deciding which to show, and the answer is always the newer — it was
     * asked for precisely because the earlier one was not good enough.
     */
    @Upsert
    suspend fun upsert(entry: CallSummaryEntry)

    @Query("DELETE FROM call_summaries WHERE displayName = :displayName")
    suspend fun deleteFor(displayName: String)

    /** Which of [displayNames] already have a summary — for showing the state of a list at a glance. */
    @Query("SELECT displayName FROM call_summaries WHERE displayName IN (:displayNames)")
    suspend fun summarised(displayNames: List<String>): List<String>
}
