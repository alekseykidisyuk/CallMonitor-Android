/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState

/**
 * Which recordings still need transcribing.
 *
 * The answer spans two databases — the recordings catalog and the transcripts store — which cannot be
 * joined in SQL, so it is composed here.
 *
 * The exclusions matter more than the inclusions. Transcribing costs roughly the call's own duration
 * in CPU, so a queue that keeps re-offering work that is finished, in flight, or hopeless would burn
 * a night of battery to achieve nothing.
 */
object TranscriptionQueue {

    /** How many recordings one scheduled run will take on. */
    const val DEFAULT_LIMIT = 25

    /** Passed as the limit to mean "take the whole backlog". */
    const val NO_LIMIT = 0

    /**
     * Display names that should be transcribed, **oldest first** so a backlog drains in call order.
     *
     * A recording is offered when it has a local file to decode and either has no transcript row at
     * all or was left [TranscriptState.QUEUED] by an interrupted run.
     *
     * Deliberately excluded:
     * - [TranscriptState.DONE] — already transcribed.
     * - [TranscriptState.RUNNING] — in flight; offering it again would duplicate the work.
     * - [TranscriptState.FAILED] — a file that cannot be decoded fails identically every time, so a
     *   scheduled run must not retry it nightly forever. Only an explicit tap retries.
     * - No local copy — a Drive-only recording has nothing to decode. Queuing it would fail and then
     *   mark it FAILED, which would also hide it from a later run made after it syncs back down.
     */
    suspend fun pending(context: Context, limit: Int = DEFAULT_LIMIT): List<String> {
        val transcribable = RecordingCatalog.all(context).filter { it.localUri != null }
        if (transcribable.isEmpty()) return emptyList()

        val states = if (TranscriptDatabase.exists(context)) {
            TranscriptDatabase.get(context).transcriptDao()
                .allTranscripts()
                .associate { it.displayName to it.state }
        } else {
            emptyMap()
        }

        return transcribable
            .filter { entry ->
                when (states[entry.displayName]) {
                    null, TranscriptState.QUEUED -> true
                    TranscriptState.DONE, TranscriptState.RUNNING, TranscriptState.FAILED -> false
                }
            }
            .sortedBy { it.lastModified }
            .let { if (limit <= NO_LIMIT) it else it.take(limit) }
            .map { it.displayName }
    }

    /**
     * Clears rows left [TranscriptState.RUNNING] by a run that is no longer happening.
     *
     * A worker that is cancelled — or killed outright — cannot be relied on to tidy up after itself:
     * whisper runs inside a blocking native call, so the interruption may never reach the Kotlin that
     * would reset the row. Observed on the OP12: after Stop the row sat on the busy spinner for ever,
     * untappable, while nothing was running; and the queue skips RUNNING, so an automatic sweep would
     * not have rescued it either.
     *
     * Called from the app process by whoever does the stopping, which is still alive to finish the job.
     *
     * @return how many rows were released.
     */
    suspend fun releaseStaleRunning(context: Context): Int {
        if (!TranscriptDatabase.exists(context)) return 0

        val dao = TranscriptDatabase.get(context).transcriptDao()
        val stale = dao.displayNamesWithState(TranscriptState.RUNNING)
        stale.forEach { dao.deleteFor(it) }
        return stale.size
    }
}
