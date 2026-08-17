/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A transcript together with its segments. */
data class TranscriptWithSegments(
    @Embedded val transcript: TranscriptEntry,
    @Relation(parentColumn = "displayName", entityColumn = "displayName")
    val segmentsUnordered: List<TranscriptSegmentEntry>
) {
    /**
     * Room cannot express ORDER BY inside a @Relation, so the ordering is applied once here rather
     * than being left to every caller to remember.
     */
    val segments: List<TranscriptSegmentEntry>
        get() = segmentsUnordered.sortedBy { it.startMs }
}

/** One search result per recording: which call, where in it, and the text that matched. */
data class TranscriptSearchHit(
    val displayName: String,
    val startMs: Long,
    val snippet: String
)

@Dao
interface TranscriptDao {

    @Upsert
    suspend fun upsertTranscript(entry: TranscriptEntry)

    @Transaction
    @Query("SELECT * FROM transcripts WHERE displayName = :displayName")
    fun observe(displayName: String): Flow<TranscriptWithSegments?>

    @Query("SELECT displayName FROM transcripts WHERE state = :state")
    suspend fun displayNamesWithState(state: TranscriptState): List<String>

    @Query("SELECT * FROM transcripts WHERE displayName IN (:displayNames)")
    fun observeAll(displayNames: List<String>): Flow<List<TranscriptEntry>>

    /**
     * Overwrites a recording's segments in one transaction, so re-transcribing replaces rather than
     * appends. Without the delete, a second run would silently double every line.
     */
    @Transaction
    suspend fun replaceSegments(displayName: String, segments: List<TranscriptSegmentEntry>) {
        deleteSegments(displayName)
        insertSegments(segments)
    }

    @Query("DELETE FROM transcript_segments WHERE displayName = :displayName")
    suspend fun deleteSegments(displayName: String)

    @Insert
    suspend fun insertSegments(segments: List<TranscriptSegmentEntry>)

    /**
     * Removes a transcript and its segments. Search rows follow automatically because the FTS table
     * is declared over the segments table as its content.
     *
     * This is the cascade: a cross-database foreign key to the recordings catalog is impossible, so
     * the deletion path must call this explicitly. If it does not, a deleted recording keeps a
     * searchable text of the conversation — a privacy defect, not an untidiness.
     */
    @Transaction
    suspend fun deleteFor(displayName: String) {
        deleteSegments(displayName)
        deleteTranscript(displayName)
    }

    @Query("DELETE FROM transcripts WHERE displayName = :displayName")
    suspend fun deleteTranscript(displayName: String)

    /**
     * Full-text search across every stored transcript, collapsed to one hit per recording at its
     * earliest match.
     *
     * [query] is an FTS MATCH expression, not a literal: callers must quote user input before it
     * reaches here, or a stray quote typed naturally becomes a syntax error rather than a no-match.
     */
    @Query(
        """
        SELECT s.displayName AS displayName, MIN(s.startMs) AS startMs, s.text AS snippet
        FROM transcript_segments AS s
        JOIN transcript_segments_fts AS f ON f.rowid = s.id
        WHERE transcript_segments_fts MATCH :query
        GROUP BY s.displayName
        """
    )
    suspend fun search(query: String): List<TranscriptSearchHit>
}
