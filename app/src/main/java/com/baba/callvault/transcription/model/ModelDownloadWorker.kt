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
 * Downloads a model, resumably, and installs it only once it hashes correctly.
 *
 * Resumable rather than foreground: these run from 190 MB to 3.46 GB and WorkManager stops a worker
 * after roughly ten minutes, so on any ordinary connection the download *will* be interrupted.
 * Rather than holding a foreground service open for however long that takes, an interrupted
 * download keeps its partial file and the next attempt continues from where it stopped with a Range
 * request. The user pays for each byte once.
 *
 * Constrained to unmetered networks by [enqueue]: silently pulling gigabytes over a phone's mobile
 * data would be indefensible.
 *
 * **The work request carries the model's details rather than its name.** Resolving an id would mean
 * this worker knowing every catalogue there is — including the summarisation one, which already
 * depends on this package for [DownloadableModel] — so the two would have to reference each other.
 * Passing the five fields it actually needs keeps the worker ignorant of what kind of model it is
 * fetching, which is the correct amount for it to know.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = requestedModel()
            ?: return@withContext Result.failure() // nothing to fetch: retrying cannot help

        val dir = ModelRepository.modelsDir(applicationContext)
        if (ModelRepository.isInstalled(dir, model)) {
            AppLogger.i(TAG, "${model.id} is already installed")
            return@withContext Result.success()
        }

        val part = ModelRepository.partFileFor(dir, model)

        // Checked before a byte is fetched. At 3.46 GB a download that runs the phone out of space
        // does not merely fail — the system starts shedding processes on the way there. Failing up
        // front with a reason is the kinder outcome, and retrying cannot conjure storage.
        val remaining = model.sizeBytes - (if (part.isFile) part.length() else 0L)
        if (!ModelDownloadPolicy.hasRoomFor(dir.usableSpace, remaining)) {
            AppLogger.w(TAG, "Not enough free space for ${model.id}: needs $remaining bytes")
            return@withContext Result.failure(workDataOf(KEY_ERROR to ERROR_NO_SPACE))
        }

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
                            workDataOf(KEY_ERROR to ERROR_VERIFICATION_FAILED)
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
     * The model this request is for, assembled from the work's own input.
     *
     * Falls back to the whisper catalogue when the details are absent, which happens for exactly one
     * case: a download enqueued by an older version of the app that is still pending when the update
     * lands. Without this it would fail on resume and the user would pay for those bytes twice.
     */
    private fun requestedModel(): DownloadableModel? {
        val id = inputData.getString(KEY_MODEL_ID) ?: return null
        val url = inputData.getString(KEY_URL)
            ?: return TranscriptionModel.fromId(id)

        return RequestedModel(
            id = id,
            fileName = inputData.getString(KEY_FILE_NAME) ?: return null,
            url = url,
            sha256 = inputData.getString(KEY_SHA256) ?: return null,
            sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L).takeIf { it > 0L } ?: return null
        )
    }

    /** A model described entirely by the work request, so the worker needs no catalogue. */
    private data class RequestedModel(
        override val id: String,
        override val fileName: String,
        override val url: String,
        override val sha256: String,
        override val sizeBytes: Long
    ) : DownloadableModel

    /**
     * Streams [model] into [part], continuing from whatever is already there.
     *
     * @return true when the file is complete; false when the worker was stopped part-way.
     */
    private suspend fun download(model: DownloadableModel, part: File): Boolean {
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
            var lastPublished = NOTHING_PUBLISHED

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (isStopped) return false

                        val read = input.read(buffer)
                        if (read <= 0) break

                        output.write(buffer, 0, read)
                        written += read

                        // Only when the whole percent moves. Publishing on every buffer is a
                        // WorkManager database write per 64 KB — about 8,700 of them for a 574 MB
                        // model and 52,800 for a 3.46 GB one, to move a bar with a hundred stops.
                        val percent = ModelDownloadPolicy.percentOf(written, model.sizeBytes)
                        if (ModelDownloadPolicy.shouldPublish(lastPublished, percent)) {
                            lastPublished = percent
                            setProgress(workDataOf(KEY_MODEL_ID to model.id, KEY_PERCENT to percent))
                        }
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

        /** The model's details, so the worker needs no catalogue to resolve them. */
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_URL = "url"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_SIZE_BYTES = "sizeBytes"

        /** The downloaded file did not match its published digest and was discarded. */
        const val ERROR_VERIFICATION_FAILED = "verification-failed"

        /** There was not enough free space, so nothing was fetched. */
        const val ERROR_NO_SPACE = "insufficient-storage"

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_BYTES = 1 shl 16

        /** No figure has reached the progress store yet, so even 0% is news. */
        private const val NOTHING_PUBLISHED = -1

        /** Unique work name for [model], so tapping download twice does not fetch it twice. */
        fun workNameFor(model: DownloadableModel): String = "cv_model_download_${model.id}"

        /** Queues [model] for download over an unmetered network. Idempotent. */
        fun enqueue(context: Context, model: DownloadableModel) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to model.id,
                        KEY_FILE_NAME to model.fileName,
                        KEY_URL to model.url,
                        KEY_SHA256 to model.sha256,
                        KEY_SIZE_BYTES to model.sizeBytes
                    )
                )
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
        fun cancel(context: Context, model: DownloadableModel) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(model))
        }

        /** Progress percent reported by a running download, or null. */
        fun percentOf(progress: Data): Int? =
            progress.getInt(KEY_PERCENT, -1).takeIf { it >= 0 }
    }
}
