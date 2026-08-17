/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

/**
 * When calls get transcribed.
 *
 * MANUAL (the default) transcribes only what the user taps; AUTOMATIC sweeps everything not yet
 * transcribed at a chosen time. Manual is the default deliberately: transcription costs roughly the
 * call's own duration in CPU, so it is not something to start doing to someone's phone unasked.
 *
 * Shaped like [SyncScheduleMode] so both can drive the same dropdown pattern in Settings.
 */
enum class TranscriptionMode(val key: String) {
    MANUAL("manual"),
    AUTOMATIC("automatic");

    companion object {
        fun fromKey(k: String?): TranscriptionMode = entries.firstOrNull { it.key == k } ?: MANUAL
    }
}
