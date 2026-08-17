/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.StringRes
import com.baba.callvault.R
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.transcription.model.TranscriptionModel

/**
 * Labels and option ranges for transcription settings.
 *
 * Kept beside [SyncScheduleLabels] and for the same reason: the wording and the offered values are
 * referenced from more than one place, and a renamed mode or a changed minute step must not be able
 * to drift between them.
 */
object TranscriptionLabels {

    /** Key standing in for "no language", since a dropdown option cannot carry a null key. */
    const val AUTO_DETECT_KEY = "auto"

    /** Minutes offered for the automatic run — quarter hours, matching the upload schedule. */
    val MINUTE_OPTIONS = listOf(0, 15, 30, 45)

    /**
     * How many recordings one automatic run may take on. 0 is offered as "No limit".
     *
     * Configurable because the right answer depends entirely on how long the calls are: ten short
     * calls is a quick sweep, ten hour-long ones is most of a night at the Best tier.
     */
    val BATCH_LIMIT_OPTIONS = listOf(5, 10, 25, 50, 0)

    /**
     * Languages offered, null meaning auto-detect.
     *
     * Hebrew is first because it is what this feature was measured against. Auto-detect is offered
     * but is not the default: whisper decodes an unspecified language as English, which for a Hebrew
     * call produces fluent nonsense rather than an error the user could recognise.
     */
    val LANGUAGE_OPTIONS: List<String?> = listOf("he", "en", "ar", "ru", "fr", "es", null)

    @StringRes
    fun titleOf(mode: TranscriptionMode): Int = when (mode) {
        TranscriptionMode.MANUAL -> R.string.transcription_mode_manual
        TranscriptionMode.AUTOMATIC -> R.string.transcription_mode_automatic
    }

    @StringRes
    fun titleOf(model: TranscriptionModel): Int = when (model) {
        TranscriptionModel.SMALL_Q5_1 -> R.string.transcription_model_small
        TranscriptionModel.LARGE_V3_TURBO_Q5_0 -> R.string.transcription_model_best
    }

    /** Maps a language code (or null for auto-detect) to its label. */
    @StringRes
    fun languageOf(code: String?): Int = when (code) {
        "he" -> R.string.transcription_language_hebrew
        "en" -> R.string.transcription_language_english
        "ar" -> R.string.transcription_language_arabic
        "ru" -> R.string.transcription_language_russian
        "fr" -> R.string.transcription_language_french
        "es" -> R.string.transcription_language_spanish
        else -> R.string.transcription_language_auto
    }
}
