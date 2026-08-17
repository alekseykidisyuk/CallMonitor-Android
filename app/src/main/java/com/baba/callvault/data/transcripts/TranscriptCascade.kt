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
import com.baba.callvault.utils.AppLogger

/**
 * Deletes transcripts when their recording goes away.
 *
 * Transcripts live in their own database ([TranscriptDatabase]) because the recordings catalog is a
 * destructible cache, which means a cross-database `ON DELETE CASCADE` is not available. This object
 * *is* the cascade, and it is called from [com.baba.callvault.data.recordings.RecordingCatalog] at
 * every point a catalog row is dropped.
 *
 * Getting this wrong is a privacy defect rather than an untidiness: the app would report a recording
 * as deleted while keeping a searchable transcript of the conversation, which is more exposing than
 * the audio it replaced.
 */
object TranscriptCascade {

    private const val TAG = "CV:TranscriptCascade"

    /**
     * Removes the transcripts (and their search rows) for [displayNames].
     *
     * Never throws: a failure here must not turn a working delete into a failed one. It is also a
     * no-op before the first transcription, so deleting a recording on a device that has never used
     * the feature does not create a database.
     */
    suspend fun deleteFor(context: Context, displayNames: Collection<String>) {
        if (displayNames.isEmpty()) return
        if (!TranscriptDatabase.exists(context)) return

        runCatching {
            val dao = TranscriptDatabase.get(context).transcriptDao()
            displayNames.forEach { dao.deleteFor(it) }
        }.onFailure {
            // Names are logged, transcript text never is.
            AppLogger.w(TAG, "Failed to delete transcripts for ${displayNames.size} recording(s): ${it.message}")
        }
    }
}
