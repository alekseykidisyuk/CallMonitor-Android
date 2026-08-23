/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baba.callvault.transcription.model.DownloadableModel
import com.baba.callvault.transcription.model.ModelDownloadWorker

/**
 * What a model download is doing, as far as anyone looking at the screen is concerned.
 *
 * The download of the summarisation model takes 3.46 GB over an unmetered network — long enough
 * that a button which simply stops saying "Download" one day is not an acceptable answer. Until
 * now nothing observed the worker's progress at all: `ModelDownloadWorker.percentOf` existed and
 * had no callers, so the transcription model's row flipped straight from Download to Installed with
 * nothing in between.
 */
sealed interface ModelDownloadState {

    /** On the device and complete. */
    data object Installed : ModelDownloadState

    /** Nothing is happening and there is nothing on disk. */
    data object Absent : ModelDownloadState

    /**
     * Stopped part-way, with [downloadedBytes] already fetched and kept.
     *
     * Distinct from [Absent] because it is the opposite of it. The partial file survives a cancel
     * deliberately — the next attempt resumes with a Range request, verified against HuggingFace,
     * so those bytes are banked rather than wasted. Left rendering as "Download, 3.5 GB" that fact
     * is invisible, and a user who stopped a download reasonably assumes they have to start again.
     */
    data class Paused(val downloadedBytes: Long) : ModelDownloadState

    /**
     * Queued but not yet started — usually waiting for an unmetered network.
     *
     * Distinct from [Downloading] because 0% and "waiting for Wi-Fi" look identical on a progress
     * bar and mean very different things to someone wondering why nothing is happening.
     */
    data object Waiting : ModelDownloadState

    /** Running, [percent] of the way through. */
    data class Downloading(val percent: Int) : ModelDownloadState

    /** Stopped and will not resume on its own. [reason] is one of the worker's `ERROR_` values. */
    data class Failed(val reason: String?) : ModelDownloadState

    companion object {

        /**
         * The state to show, given what the catalogue and WorkManager each say.
         *
         * **Installed wins over everything.** A finished download leaves its work in the terminal
         * SUCCEEDED state, and a failed attempt leaves a FAILED one behind that WorkManager keeps
         * for its own bookkeeping — neither should contradict a file that is verifiably on disk.
         *
         * Kept separate from the observation below so it can be reasoned about, and tested, without
         * a WorkManager instance.
         */
        fun from(
            isInstalled: Boolean,
            workState: WorkInfo.State?,
            percent: Int?,
            error: String?,
            partialBytes: Long = 0L
        ): ModelDownloadState =
            when {
                isInstalled -> Installed
                workState == WorkInfo.State.RUNNING ->
                    percent?.let { Downloading(it) } ?: Waiting
                // ENQUEUED covers both the first moment after a tap and a retry waiting on its
                // backoff, which is also the shape an interrupted download comes back in.
                workState == WorkInfo.State.ENQUEUED || workState == WorkInfo.State.BLOCKED -> Waiting
                workState == WorkInfo.State.FAILED -> Failed(error)
                // Nothing running. SUCCEEDED without the file means it was verified then removed;
                // CANCELLED means the user stopped it; null means no attempt this process. What
                // separates them is only whether bytes are banked on disk.
                partialBytes > 0L -> Paused(partialBytes)
                else -> Absent
            }
    }
}

/**
 * Observes the download of [model].
 *
 * `isInstalled` is passed in rather than read here because it is a filesystem check the caller
 * already makes to decide what row to draw, and doing it twice would have the two disagree for a
 * frame after a download lands.
 */
@Composable
fun rememberModelDownloadState(
    model: DownloadableModel,
    isInstalled: Boolean,
    partialBytes: Long
): State<ModelDownloadState> {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val workName = remember(model.id) { ModelDownloadWorker.workNameFor(model) }

    return produceState<ModelDownloadState>(
        initialValue = ModelDownloadState.from(isInstalled, null, null, null, partialBytes),
        workManager, workName, isInstalled, partialBytes
    ) {
        workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
            // Unique work, so there is at most one that matters; a retry replaces rather than joins.
            val info = infos.lastOrNull()
            value = ModelDownloadState.from(
                isInstalled = isInstalled,
                workState = info?.state,
                percent = info?.let { ModelDownloadWorker.percentOf(it.progress) },
                error = info?.outputData?.getString(ModelDownloadWorker.KEY_ERROR),
                partialBytes = partialBytes
            )
        }
    }
}
