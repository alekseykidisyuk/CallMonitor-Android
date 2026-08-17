/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptSearchHit
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.data.transcripts.db.TranscriptWithSegments
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What a recording row shows about its transcript.
 *
 * Distinct from [TranscriptState], which is what gets *persisted*. This adds [NONE] — the common case
 * of a recording nobody has transcribed — so the UI renders a state rather than special-casing a null
 * at every use. [NONE] is never stored; a row that does not exist is what produces it.
 */
enum class TranscriptStatus {
    NONE,
    QUEUED,
    RUNNING,
    DONE,
    FAILED;

    companion object {
        fun of(state: TranscriptState?): TranscriptStatus = when (state) {
            null -> NONE
            TranscriptState.QUEUED -> QUEUED
            TranscriptState.RUNNING -> RUNNING
            TranscriptState.DONE -> DONE
            TranscriptState.FAILED -> FAILED
        }
    }
}

/**
 * The transcript side of the recordings list: what each row shows, and the actions it offers.
 *
 * Reads are scoped to the names asked for, because Home lists years of calls and observing the whole
 * table to draw a handful of icons would re-read everything on every change.
 */
object TranscriptRepository {

    private const val TAG = "CV:TranscriptRepository"

    private fun dao(context: Context) = TranscriptDatabase.get(context).transcriptDao()

    /**
     * Status for each of [displayNames], including those with no transcript at all.
     *
     * Every requested name appears in the map. A missing key would leave its row rendering nothing,
     * which is a worse failure than rendering "not transcribed".
     */
    fun statusesFor(context: Context, displayNames: List<String>): Flow<Map<String, TranscriptStatus>> {
        if (displayNames.isEmpty()) return flowOf(emptyMap())

        // Skip opening the database at all before the first transcription — most installs will never
        // have one, and a Home screen should not create a database just to draw a list.
        if (!TranscriptDatabase.exists(context)) {
            return flowOf(displayNames.associateWith { TranscriptStatus.NONE })
        }

        return dao(context).observeAll(displayNames).map { rows ->
            val stored = rows.associate { it.displayName to TranscriptStatus.of(it.state) }
            displayNames.associateWith { stored[it] ?: TranscriptStatus.NONE }
        }
    }

    /** The full transcript for one recording, or null when there is none. */
    fun transcript(context: Context, displayName: String): Flow<TranscriptWithSegments?> =
        dao(context).observe(displayName)

    /** Transcribes [displayName] now, at the user's request — no charging or schedule constraints. */
    fun transcribeNow(context: Context, displayName: String) {
        TranscriptionScheduler.runNow(context, displayName)
    }

    /**
     * Retries a recording whose last attempt failed.
     *
     * Clearing the row is the point, not a side effect: [com.baba.callvault.transcription.TranscriptionQueue]
     * deliberately skips FAILED so an undecodable file is not retried nightly forever, which also means
     * a retry that left the row in place would do nothing at all.
     */
    suspend fun retry(context: Context, displayName: String) {
        dao(context).deleteFor(displayName)
        transcribeNow(context, displayName)
    }

    /** Removes a transcript, leaving the recording. Someone may want the audio but not the text. */
    suspend fun delete(context: Context, displayName: String) {
        dao(context).deleteFor(displayName)
    }

    /**
     * Full-text search across every transcript.
     *
     * [query] is whatever the user typed, and is quoted before it reaches SQLite: `MATCH` takes an
     * expression, so an apostrophe or asterisk typed naturally would otherwise be a syntax error —
     * surfacing as a crash rather than as "no results".
     */
    suspend fun search(context: Context, query: String): List<TranscriptSearchHit> {
        val prepared = quoteForFts(query)
        if (prepared.isEmpty()) return emptyList()
        if (!TranscriptDatabase.exists(context)) return emptyList()

        return runCatching { dao(context).search(prepared) }
            .getOrElse {
                // Never let a search term take the screen down; report nothing found.
                AppLogger.w(TAG, "Search failed: ${it.message}")
                emptyList()
            }
    }

    /**
     * Turns free text into a safe FTS MATCH expression.
     *
     * Each whitespace-separated word becomes a quoted phrase, with embedded quotes doubled per SQLite's
     * escaping rule. That treats the input as words to find rather than as operators, which is what
     * someone typing into a search box means.
     */
    private fun quoteForFts(query: String): String =
        query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }
}
