/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a transcription model, resumably, and installs it only once it hashes correctly.
 *
 * Resumable rather than foreground: these are 190–574 MB files and WorkManager stops a worker after
 * roughly ten minutes, so on a slow connection the download *will* be interrupted. Rather than
 * holding a foreground service open for however long that takes, an interrupted download keeps its
 * partial file and the next attempt continues from where it stopped with a Range request. The user
 * pays for each byte once.
 *
 * Constrained to unmetered networks by [enqueue]: silently pulling half a gigabyte over a phone's
 * mobile data would be indefensible.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = TranscriptionModel.fromId(inputData.getString(KEY_MODEL_ID))
            ?: return@withContext Result.failure() // unknown id: retrying cannot help

        val dir = ModelRepository.modelsDir(applicationContext)
        if (ModelRepository.isInstalled(dir, model)) {
            AppLogger.i(TAG, "${model.id} is already installed")
            return@withContext Result.success()
        }

        val part = ModelRepository.partFileFor(dir, model)

        runCatching { download(model, part) }
            .fold(
                onSuccess = { completed ->
                    when {
                        // Stopped mid-flight. The partial file stays, so the retry resumes.
                        !completed -> Result.retry()
                        ModelRepository.finalizeDownload(dir, model) -> Result.success()
                        // Digest mismatch: finalizeDownload has already discarded the file. Retrying
                        // would re-download from the same source, so surface it instead of looping.
                        else -> Result.failure(
                            workDataOf(KEY_ERROR to "verification-failed")
                        )
                    }
                },
                onFailure = { error ->
                    AppLogger.w(TAG, "Download of ${model.id} failed: ${error.message}")
                    Result.retry()
                }
            )
    }

    /**
     * Streams [model] into [part], continuing from whatever is already there.
     *
     * @return true when the file is complete; false when the worker was stopped part-way.
     */
    private suspend fun download(model: TranscriptionModel, part: File): Boolean {
        val alreadyHave = if (part.isFile) part.length() else 0L
        if (alreadyHave > model.sizeBytes) {
            // Longer than the published size means this is not the file we think it is.
            AppLogger.w(TAG, "Discarding oversized partial download of ${model.id}")
            part.delete()
        }

        val resumeFrom = if (part.isFile) part.length() else 0L
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
        }

        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $status")
            }

            // A server that ignores the Range header replies 200 with the whole file, so anything
            // already written has to go or the two would be concatenated into garbage.
            val append = status == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0L
            if (!append && part.exists()) part.delete()

            var written = if (append) resumeFrom else 0L

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (isStopped) return false

                        val read = input.read(buffer)
                        if (read <= 0) break

                        output.write(buffer, 0, read)
                        written += read

                        val percent = (written * PERCENT * 1.0 / model.sizeBytes).toInt()
                        setProgress(workDataOf(KEY_MODEL_ID to model.id, KEY_PERCENT to percent))
                    }
                }
            }

            return written >= model.sizeBytes
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "CV:ModelDownload"

        const val KEY_MODEL_ID = "modelId"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_BYTES = 1 shl 16
        private const val PERCENT = 100

        /** Unique work name for [model], so tapping download twice does not fetch it twice. */
        fun workNameFor(model: TranscriptionModel): String = "cv_model_download_${model.id}"

        /** Queues [model] for download over an unmetered network. Idempotent. */
        fun enqueue(context: Context, model: TranscriptionModel) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to model.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(workNameFor(model), ExistingWorkPolicy.KEEP, request)
            AppLogger.i(TAG, "Queued download of ${model.id}")
        }

        /** Cancels an in-progress download. The partial file is kept so a later retry resumes. */
        fun cancel(context: Context, model: TranscriptionModel) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(model))
        }

        /** Progress percent reported by a running download, or null. */
        fun percentOf(progress: Data): Int? =
            progress.getInt(KEY_PERCENT, -1).takeIf { it >= 0 }
    }
}
