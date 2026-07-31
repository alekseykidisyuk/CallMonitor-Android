/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource

/** Which copies of a recording a bulk delete should remove. */
enum class DeleteScope { DEVICE, DRIVE, BOTH }

/**
 * Turning a multi-selection into the exact list of files to act on.
 *
 * Kept away from the UI and free of Compose because getting it wrong deletes the wrong recordings.
 * The awkward case is that one row does not mean one file: a recording present both on the device
 * and in a Drive folder is shown as a single row carrying two URIs, so "delete these four" is
 * ambiguous until the user says which copies they mean.
 */
object RecordingSelection {

    /**
     * Whether the delete needs to ask which copies to remove.
     *
     * Only true when the selection actually contains a recording held in both places — asking
     * otherwise would be a pointless dialog in front of an unambiguous action.
     */
    fun needsScopeChoice(selected: List<RecordingItem>): Boolean =
        selected.any { it.source == RecordingSource.BOTH }

    /**
     * The files a delete should remove, given the user's [scope] choice.
     *
     * [scope] governs the two-copy recordings; single-copy ones always contribute their one file, so
     * choosing "Drive only" over a selection that also holds device-only recordings does **not**
     * silently delete those. Anything else would destroy files the user did not point at.
     */
    fun urisToDelete(selected: List<RecordingItem>, scope: DeleteScope): List<Uri> =
        selected.flatMap { item ->
            when (item.source) {
                RecordingSource.BOTH -> when (scope) {
                    DeleteScope.DEVICE -> listOfNotNull(item.localUri)
                    DeleteScope.DRIVE -> listOfNotNull(item.driveUri)
                    DeleteScope.BOTH -> listOfNotNull(item.localUri, item.driveUri)
                }
                // A recording that exists in one place only: the scope question was never about it.
                RecordingSource.LOCAL, RecordingSource.DRIVE -> listOf(item.uri)
            }
        }.distinct()

    /**
     * One URI per selected recording, for sharing.
     *
     * The device copy wins where there is a choice: it needs no network to read, and the two copies
     * are the same audio. Sharing both would attach the same call twice.
     */
    fun urisToShare(selected: List<RecordingItem>): List<Uri> =
        selected.map { it.localUri ?: it.driveUri ?: it.uri }.distinct()
}
