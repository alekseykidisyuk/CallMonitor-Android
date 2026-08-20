/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem

/**
 * How a recording is named to the user: contact, else number, else the file name.
 *
 * One rule in one place. It was written out by hand at each call site, and the transcribing sheet got
 * it wrong — showing `20260819_201239.877+0300_in_<name>.ogg` where every other surface said who the
 * call was with.
 */
object RecordingLabel {

    /** The best available name for [item], or null when there is no item. */
    fun of(item: RecordingItem?): String? =
        item?.let { it.contactName ?: it.number ?: it.displayName }

    /**
     * The best available name for [displayName], looked up in [recordings].
     *
     * Falls back to [displayName] itself when the recording is not there: the transcribing sheet
     * learns the name from WorkManager progress, which can outlive the recording, and a raw file name
     * is still better than a blank sheet.
     */
    fun forDisplayName(recordings: List<RecordingItem>, displayName: String): String =
        of(recordings.firstOrNull { it.displayName == displayName }) ?: displayName
}
