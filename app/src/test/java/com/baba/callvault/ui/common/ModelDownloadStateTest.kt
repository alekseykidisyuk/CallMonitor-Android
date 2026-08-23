/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.work.WorkInfo
import com.baba.callvault.transcription.model.ModelDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the download row shows, for every combination that can actually occur.
 *
 * The interesting cases are the contradictions: WorkManager keeps terminal work around for its own
 * bookkeeping, so "the last download failed" and "the model is on the device" are both true after a
 * failed attempt followed by a successful one.
 */
class ModelDownloadStateTest {

    @Test
    fun `an installed model is installed whatever the work says`() {
        // A finished download leaves SUCCEEDED behind; a previously failed one leaves FAILED. Neither
        // may contradict a file that is verifiably on disk.
        listOf(null, WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.RUNNING).forEach {
            assertEquals(
                "work state $it",
                ModelDownloadState.Installed,
                ModelDownloadState.from(isInstalled = true, workState = it, percent = 40, error = null)
            )
        }
    }

    @Test
    fun `no work and no file is simply absent`() {
        assertEquals(
            ModelDownloadState.Absent,
            ModelDownloadState.from(isInstalled = false, workState = null, percent = null, error = null)
        )
    }

    @Test
    fun `a running download reports its percentage`() {
        assertEquals(
            ModelDownloadState.Downloading(37),
            ModelDownloadState.from(isInstalled = false, workState = WorkInfo.State.RUNNING, percent = 37, error = null)
        )
    }

    @Test
    fun `running with no figure yet is waiting, not zero percent`() {
        // 0% and "waiting for Wi-Fi" look identical on a bar and mean very different things to
        // somebody wondering why nothing is happening.
        assertEquals(
            ModelDownloadState.Waiting,
            ModelDownloadState.from(isInstalled = false, workState = WorkInfo.State.RUNNING, percent = null, error = null)
        )
    }

    @Test
    fun `queued work is waiting`() {
        // Covers the moment after a tap, and a retry sitting on its backoff after an interruption —
        // which is the shape a 3.46 GB download spends a good deal of its life in.
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED).forEach {
            assertEquals(
                "work state $it",
                ModelDownloadState.Waiting,
                ModelDownloadState.from(isInstalled = false, workState = it, percent = null, error = null)
            )
        }
    }

    @Test
    fun `a failure carries its reason so the row can explain itself`() {
        assertEquals(
            ModelDownloadState.Failed(ModelDownloadWorker.ERROR_NO_SPACE),
            ModelDownloadState.from(
                isInstalled = false,
                workState = WorkInfo.State.FAILED,
                percent = null,
                error = ModelDownloadWorker.ERROR_NO_SPACE
            )
        )
    }

    @Test
    fun `a cancelled download leaves the model absent rather than failed`() {
        // The user stopped it. Telling them it failed would be blaming the app for their decision.
        assertEquals(
            ModelDownloadState.Absent,
            ModelDownloadState.from(isInstalled = false, workState = WorkInfo.State.CANCELLED, percent = 60, error = null)
        )
    }

    @Test
    fun `succeeded but no file means the model was deleted afterwards`() {
        assertEquals(
            ModelDownloadState.Absent,
            ModelDownloadState.from(isInstalled = false, workState = WorkInfo.State.SUCCEEDED, percent = 100, error = null)
        )
    }
}
