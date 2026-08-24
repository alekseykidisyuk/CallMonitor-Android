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
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        val names = if (single != null) {
            listOf(single)
        } else {
            TranscriptionQueue.pending(applicationContext, prefs.getTranscriptionBatchLimit())
        }

        if (names.isEmpty()) {
            AppLogger.i(TAG, "Nothing to transcribe")
            return@withContext Result.success()
        }

        AppLogger.i(TAG, "Transcribing ${names.size} recording(s) with ${model.id}")

        // Watch for a stop and pass it to whisper. `onStopped` is final on CoroutineWorker, and the
        // batch only consults isStopped *between* recordings — so without this a stop mid-recording
        // would leave the phone at ~600% CPU until that recording finished on its own. Covers the
        // routes stopNow cannot: a lost constraint, or a system-imposed cancellation.
        // Progress has two halves that arrive from different places: which recording of how many
        // (from the runner, between files) and how far through this one (from whisper, continuously).
        // The UI needs both at once, so they are held here and always published together.
        var latestCompleted = 0
        var latestTotal = names.size
        var latestCurrent = ""

        suspend fun publishProgress(percent: Int) {
            setProgress(
                workDataOf(
                    KEY_COMPLETED to latestCompleted,
                    KEY_TOTAL to latestTotal,
                    KEY_CURRENT to latestCurrent,
                    KEY_PERCENT to percent
                )
            )
        }

        val abortWatcher = launch {
            while (isActive) {
                if (isStopped) {
                    AppLogger.i(TAG, "Stopped; asking whisper to abort")
                    TranscriptionEngine.requestAbort()
                    break
                }
                // Republish on the same tick. The percentage lives in native memory and changes
                // continuously, so it can only reach the UI by being sampled — and this loop was
                // already running, so it costs nothing to add.
                publishProgress(TranscriptionEngine.progressPercent())
                delay(ABORT_POLL_MS)
            }
        }

        val transcribed = TranscriptionRunner(applicationContext).runBatch(
            modelId = model.id,
            modelPath = modelPath,
            language = TranscriptionLanguageChoice.resolve(
                chosen = inputData.getString(KEY_LANGUAGE),
                setting = prefs.getTranscriptionLanguage()
            ),
            displayNames = names,
            shouldStop = { isStopped },
            onProgress = { completed, total, current ->
                // Remembered as well as published, because the abort watcher republishes the whole
                // set every tick and setProgress replaces rather than merges — dropping these would
                // blank the "2 of 5" the moment the first percentage arrived.
                latestCompleted = completed
                latestTotal = total
                latestCurrent = current
                publishProgress(percent = 0)
            }
        )

        abortWatcher.cancel()

        // Anything left unfinished stays queued, so a later run resumes rather than restarts.
        if (isStopped && transcribed < names.size) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "CV:TranscriptionWorker"

        /** How often the abort watcher checks for a stop. Cheap, and a stop should feel instant. */
        private const val ABORT_POLL_MS = 400L

        /** Input key naming a single recording, for the manual button. Absent means "drain the queue". */
        const val KEY_DISPLAY_NAME = "displayName"

        /**
         * Input key carrying the language picked for this one recording, encoded by
         * [TranscriptionLanguageChoice.encode]. Absent means "use the setting".
         */
        const val KEY_LANGUAGE = "language"

        /**
         * Progress keys read by Home's transcribing pill.
         *
         * Published rather than logged: a run can last hours, and the only alternative to showing it
         * is a phone that is inexplicably warm. The name is the recording's, which the runner already
         * logs — the transcript *text* is never put anywhere outside the database.
         */
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"

        /** How far through the current recording, 0-100. Absent or 0 means "not known yet". */
        const val KEY_PERCENT = "percent"
    }
}
