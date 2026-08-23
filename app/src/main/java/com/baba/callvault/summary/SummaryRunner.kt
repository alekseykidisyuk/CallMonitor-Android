/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.first

/** A loaded model, for the length of one run. Substituted in tests. */
fun interface SummarySession {
    fun generate(prompt: String, maxTokens: Int, grammar: String?): String
}

/**
 * Loads a model, runs [block] against it, and frees it.
 *
 * An interface rather than a direct call to [SummaryEngine] so the runner can be tested without a
 * native library, a 3.46 GB file, or ninety seconds per case.
 */
interface SummaryModelHost {
    suspend fun run(modelPath: String, block: suspend (SummarySession) -> CallSummary?): CallSummary?

    companion object {
        /** The real one. Loads through [SummaryEngine], which owns the mutex and the free. */
        val Default = object : SummaryModelHost {
            override suspend fun run(
                modelPath: String,
                block: suspend (SummarySession) -> CallSummary?
            ): CallSummary? = SummaryEngine.withModel(modelPath) { session ->
                block { prompt, maxTokens, grammar -> session.generate(prompt, maxTokens, grammar) }
            }
        }
    }
}

/**
 * Turns a stored transcript into a stored summary.
 *
 * Separate from the worker for the same reason [com.baba.callvault.transcription.TranscriptionRunner]
 * is: everything worth testing lives here, and none of it needs WorkManager or a native library.
 *
 * **The model is loaded once for the whole call, not once per chunk.** Loading is seconds and the
 * memory is gigabytes; a long call is several chunks plus a merge, and paying the load for each
 * would dominate the run.
 */
class SummaryRunner(
    private val context: Context,
    private val host: SummaryModelHost = SummaryModelHost.Default,
    /** Whether the run that just ended was stopped rather than finished. Injected for tests. */
    private val wasAborted: () -> Boolean = { SummaryEngine.wasAborted() }
) {

    private val db get() = TranscriptDatabase.get(context)

    /**
     * Summarises [displayName] and stores the result.
     *
     * @param shouldStop consulted between chunks. A single chunk can take a minute, so this is the
     *   granularity a stop can be honoured at without abandoning the model mid-token; the worker
     *   also calls [SummaryEngine.requestAbort], which reaches inside a running generate.
     * @param onProgress how many chunks are done out of how many, so something on screen can move.
     * @return the stored summary, or null if there was nothing to summarise or the run was stopped.
     */
    suspend fun run(
        displayName: String,
        modelId: String,
        modelPath: String,
        language: String?,
        now: Long,
        shouldStop: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): CallSummary? {
        val segments = segmentsFor(displayName)
        if (segments.isEmpty()) {
            // Guarded by SummaryQueue too, but a summary of nothing costs the same as a real one.
            AppLogger.i(TAG, "Nothing to summarise for a recording with no transcribed words")
            return null
        }

        val chunks = SummaryChunking.chunk(segments)
        onProgress(0, chunks.size)

        val summary = host.run(modelPath) { session ->
            val parts = mutableListOf<String>()

            chunks.forEachIndexed { index, chunk ->
                if (shouldStop()) return@run null
                parts += session.generate(
                    SummaryPrompt.forChunkJson(chunk, language, withTimestamps = true),
                    CHUNK_TOKEN_BUDGET,
                    SummaryGrammar.JSON
                )
                onProgress(index + 1, chunks.size)
            }

            if (shouldStop() || wasAborted()) return@run null

            // One chunk is already the answer. Merging it with itself would compress a summary that
            // has already been compressed once, and measurably loses detail.
            val document = if (parts.size == 1) {
                parts.single()
            } else {
                session.generate(
                    SummaryPrompt.forMergeJson(parts, language),
                    MERGE_TOKEN_BUDGET,
                    SummaryGrammar.JSON
                )
            }

            CallSummary.parse(document)
        }

        if (summary == null) {
            // Never store a half-summary. An aborted run returns whatever was produced before the
            // stop, which parses to a fragment or to nothing; either way it is not an account of
            // the call and showing it as one would be worse than showing nothing.
            AppLogger.i(TAG, "No summary stored: the run was stopped or produced nothing usable")
            return null
        }

        db.summaryDao().upsert(
            CallSummaryEntry(
                displayName = displayName,
                document = summary.toJson(),
                model = modelId,
                createdAt = now
            )
        )
        AppLogger.i(TAG, "Stored a summary of ${chunks.size} chunk(s)")
        return summary
    }

    /** The transcribed words, in order. Empty when there is no transcript. */
    private suspend fun segmentsFor(displayName: String): List<TranscriptSegmentEntry> {
        if (!TranscriptDatabase.exists(context)) return emptyList()
        return db.transcriptDao().observe(displayName).first()?.segments.orEmpty()
    }

    private companion object {
        const val TAG = "CV:SummaryRunner"

        /**
         * Tokens allowed for one chunk's JSON.
         *
         * Measured: 220 cut a real summary off mid-word, and half a sentence reads as a crash
         * rather than as a summary. The grammar makes truncation produce invalid JSON rather than
         * a plausible fragment, so a budget that is too small fails loudly — but it still fails.
         */
        const val CHUNK_TOKEN_BUDGET = 420

        /** The merge carries every part's points forward, so it needs more room than one chunk. */
        const val MERGE_TOKEN_BUDGET = 640
    }
}
