/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs transcription in the background, either over the whole backlog or for one recording the user
 * tapped.
 *
 * Deliberately thin: everything worth testing lives in [TranscriptionRunner], which does not need
 * WorkManager or the native library to exercise.
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(applicationContext)

        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId())
            ?: TranscriptionModel.DEFAULT

        // Retry rather than fail: the model may still be downloading, and the work should pick up
        // once it lands instead of the user having to ask again.
        val modelPath = ModelRepository.pathFor(applicationContext, model)?.absolutePath
            ?: run {
                AppLogger.i(TAG, "Model ${model.id} is not installed yet; will retry")
                return@withContext Result.retry()
            }

        val single = inputData.getString(KEY_DISPLAY_NAME)
        val names = if (single != null) listOf(single) else TranscriptionQueue.pending(applicationContext)

        if (names.isEmpty()) {
            AppLogger.i(TAG, "Nothing to transcribe")
            return@withContext Result.success()
        }

        AppLogger.i(TAG, "Transcribing ${names.size} recording(s) with ${model.id}")

        val transcribed = TranscriptionRunner(applicationContext).runBatch(
            modelId = model.id,
            modelPath = modelPath,
            language = prefs.getTranscriptionLanguage(),
            displayNames = names,
            shouldStop = { isStopped }
        )

        // Anything left unfinished stays queued, so a later run resumes rather than restarts.
        if (isStopped && transcribed < names.size) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "CV:TranscriptionWorker"

        /** Input key naming a single recording, for the manual button. Absent means "drain the queue". */
        const val KEY_DISPLAY_NAME = "displayName"
    }
}
