/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * Turns audio into stored segments. Substituted in tests, because the real one loads a native
 * library a JVM test cannot.
 */
fun interface Transcriber {
    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelPath: String,
        language: String?
    ): List<TranscriptSegment>
}

/**
 * Runs transcription over a list of recordings, one at a time, recording state as it goes.
 *
 * Everything here exists because the work is *slow*: a 30-minute call costs about 30 minutes of CPU
 * with the small model and over an hour with turbo. That single fact drives the design — progress is
 * committed per recording so an interrupted run resumes rather than restarts, one bad file cannot
 * stall the rest, and a stop request is honoured between recordings instead of an hour later.
 */
class TranscriptionRunner(
    private val context: Context,
    private val transcriber: Transcriber = Transcriber(TranscriptionEngine::transcribe)
) {

    private val dao get() = TranscriptDatabase.get(context).transcriptDao()

    /**
     * Transcribes each of [displayNames] in turn.
     *
     * @param shouldStop consulted between recordings; when it returns true the batch ends early and
     *   whatever finished stays finished.
     * @param onProgress announced before each recording is started, so something on screen can say
     *   what is happening during a run that may last hours.
     * @return how many recordings were transcribed.
     */
    suspend fun runBatch(
        modelId: String,
        modelPath: String,
        language: String?,
        displayNames: List<String>,
        shouldStop: () -> Boolean = { false },
        onProgress: suspend (completed: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): Int {
        var transcribed = 0
        var reached = 0

        for (displayName in displayNames) {
            if (shouldStop()) {
                AppLogger.i(TAG, "Stopping after $transcribed recording(s); the rest stay queued")
                break
            }
            // Count recordings reached rather than transcribed: a skipped or failed one still moves
            // the run forward, and a counter that stalls on a bad file looks like a hang.
            onProgress(reached, displayNames.size, displayName)
            reached++
            if (runOne(modelId, modelPath, language, displayName)) transcribed++
        }

        return transcribed
    }

    /** Transcribes one recording. Returns true when it finished and was stored. */
    suspend fun runOne(
        modelId: String,
        modelPath: String,
        language: String?,
        displayName: String
    ): Boolean {
        // The checkpoint. A resumed run re-offers everything it was given, so finished work must be
        // recognised and skipped rather than paid for twice.
        if (dao.findTranscript(displayName)?.state == TranscriptState.DONE) {
            AppLogger.i(TAG, "$displayName is already transcribed; skipping")
            return false
        }

        val uri = localUriFor(displayName) ?: run {
            // Deleted between being queued and being reached. Nothing to decode and nothing to say.
            AppLogger.i(TAG, "$displayName is no longer in the catalog; skipping")
            return false
        }

        mark(displayName, TranscriptState.RUNNING, modelId, language)

        return runCatching { transcriber.transcribe(context, uri, modelPath, language) }
            .onFailure { error ->
                // runCatching swallows CancellationException like any other, and a stop is not a
                // failure of this recording. Recording it as FAILED is the worst outcome —
                // TranscriptionQueue never re-offers a FAILED recording, so one tap on Stop would
                // exclude that call from every future automatic run until someone retried it by hand.
                // QUEUED is no better: the row renders that as a spinner, which in Manual mode would
                // spin for ever with nothing behind it. Removing the row restores exactly the state
                // before the run: the row offers "Transcribe", and the queue counts it as pending.
                // The cancellation is then rethrown so the worker can report that it was stopped.
                if (error is CancellationException) {
                    dao.deleteFor(displayName)
                    throw error
                }
            }
            .fold(
                onSuccess = { segments ->
                    dao.replaceSegments(displayName, segments.map { it.toEntry(displayName) })
                    mark(displayName, TranscriptState.DONE, modelId, language)
                    // Count, never content: a transcript is the substance of a private call.
                    AppLogger.i(TAG, "Transcribed $displayName (${segments.size} segment(s))")
                    true
                },
                onFailure = { error ->
                    AppLogger.w(TAG, "Failed to transcribe $displayName: ${error.message}")
                    mark(displayName, TranscriptState.FAILED, modelId, language, error.message)
                    false
                }
            )
    }

    private suspend fun localUriFor(displayName: String): Uri? =
        RecordingCatalog.all(context)
            .firstOrNull { it.displayName == displayName }
            ?.localUri
            ?.toUri()

    private suspend fun mark(
        displayName: String,
        state: TranscriptState,
        modelId: String,
        language: String?,
        errorMessage: String? = null
    ) {
        dao.upsertTranscript(
            TranscriptEntry(
                displayName = displayName,
                state = state,
                modelId = modelId,
                language = language,
                updatedAt = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        )
    }

    private fun TranscriptSegment.toEntry(displayName: String) = TranscriptSegmentEntry(
        displayName = displayName,
        startMs = startMs,
        endMs = endMs,
        text = text,
        speaker = null
    )

    private companion object {
        const val TAG = "CV:TranscriptionRunner"
    }
}
