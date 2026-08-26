/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Where downloaded models live on disk, and whether one can be trusted.
 *
 * Serves any [DownloadableModel] — whisper models for transcription, and the language model for
 * summaries. Only [installedModels] is still whisper-specific, because it enumerates a catalogue
 * rather than acting on a model it was handed.
 *
 * Models are large — 190 MB to 874 MB for whisper, and about 3.5 GB for the summariser — and are
 * deliberately kept in the app's private files directory, never in the recordings folders: they must
 * not be swept by retention, synced to Drive, or shown to the user as if they were a recording.
 *
 * Two different checks, used for two different purposes:
 * - [verify] hashes the whole file. Correct but slow, so it runs **once**, straight after a download
 *   and before the file is given its real name.
 * - [isInstalled] only compares the length. Cheap enough to call from the UI, and sufficient to catch
 *   the realistic failure — a killed download leaving a short file. Hashing 574 MB every time the
 *   settings screen drew would be unusable.
 */
object ModelRepository {

    private const val TAG = "CV:ModelRepository"
    private const val MODELS_DIR = "models"

    /** Suffix for an in-progress download, so a partial file can never be mistaken for a real one. */
    const val PART_SUFFIX = ".part"

    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val DIGEST_BUFFER_BYTES = 1 shl 16

    /** The directory holding downloaded models, created on demand. */
    fun modelsDir(context: Context): File =
        File(context.applicationContext.filesDir, MODELS_DIR).apply { mkdirs() }

    /**
     * Whether [model] is present and complete in [dir].
     *
     * [expectedSize] exists so tests can use a stand-in file instead of writing hundreds of
     * megabytes; production callers leave it at the model's published length.
     */
    fun isInstalled(
        dir: File,
        model: DownloadableModel,
        expectedSize: Long = model.sizeBytes
    ): Boolean {
        val file = File(dir, model.fileName)
        return file.isFile && file.length() == expectedSize
    }

    fun isInstalled(context: Context, model: DownloadableModel): Boolean =
        isInstalled(modelsDir(context), model)

    /** The model's file, or null when it is absent or incomplete. */
    fun pathFor(
        dir: File,
        model: DownloadableModel,
        expectedSize: Long = model.sizeBytes
    ): File? = File(dir, model.fileName).takeIf { isInstalled(dir, model, expectedSize) }

    fun pathFor(context: Context, model: DownloadableModel): File? =
        pathFor(modelsDir(context), model)

    /** Every model currently installed and complete. */
    fun installedModels(context: Context): List<TranscriptionModel> {
        val dir = modelsDir(context)
        return TranscriptionModel.entries.filter { isInstalled(dir, it) }
    }

    /**
     * Whether [file]'s SHA-256 equals [expectedSha256], comparing case-insensitively.
     *
     * Streams the file rather than reading it, because these are hundreds of megabytes. Returns false
     * rather than throwing for a missing or unreadable file: every caller's response to "cannot be
     * trusted" is the same regardless of the reason.
     */
    fun verify(file: File, expectedSha256: String): Boolean {
        if (!file.isFile) return false

        return runCatching {
            val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(DIGEST_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.map { actual ->
            actual.equals(expectedSha256, ignoreCase = true)
        }.getOrElse {
            AppLogger.w(TAG, "Could not hash ${file.name}: ${it.message}")
            false
        }
    }

    /** The in-progress download file for [model]. */
    fun partFileFor(dir: File, model: DownloadableModel): File =
        File(dir, model.fileName + PART_SUFFIX)

    /**
     * How much of [model] is already on disk from an interrupted download, or 0.
     *
     * Exists so the UI can say so. A cancelled 3.46 GB download keeps its partial file on purpose —
     * the next attempt resumes with a Range request and the user pays for each byte once — but a
     * row that still reads "Download, 3.5 GB" hides that entirely, and looks exactly like starting
     * again from nothing.
     */
    fun partialBytes(context: Context, model: DownloadableModel): Long {
        val part = partFileFor(modelsDir(context), model)
        return if (part.isFile) part.length() else 0L
    }

    /**
     * Promotes a finished download to the real file, but only if it hashes correctly.
     *
     * This is the gate the whole download path exists to reach: a model is renamed into place only
     * after its digest matches, so a truncated or corrupted file can never be handed to ggml — whose
     * failure mode is a crash inside native code, not an error we could catch and report.
     *
     * A failed check deletes the partial file rather than leaving it: keeping it would only mean the
     * next attempt resumes from bytes already known to be wrong.
     *
     * @return true when the model is now installed.
     */
    fun finalizeDownload(
        dir: File,
        model: DownloadableModel,
        expectedSha256: String = model.sha256
    ): Boolean {
        val part = partFileFor(dir, model)
        if (!part.isFile) return false

        if (!verify(part, expectedSha256)) {
            AppLogger.w(TAG, "Discarding ${model.id}: downloaded file failed its digest check")
            part.delete()
            return false
        }

        val target = File(dir, model.fileName)
        if (target.exists()) target.delete()

        val renamed = part.renameTo(target)
        if (renamed) AppLogger.i(TAG, "Installed model ${model.id}")
        else AppLogger.w(TAG, "Verified ${model.id} but could not move it into place")
        return renamed
    }

    /** Deletes [model] and any partial download of it. Safe to call when nothing is installed. */
    fun delete(context: Context, model: DownloadableModel) {
        val dir = modelsDir(context)
        listOf(File(dir, model.fileName), File(dir, model.fileName + PART_SUFFIX))
            .filter { it.exists() }
            .forEach { file ->
                if (file.delete()) AppLogger.i(TAG, "Deleted ${file.name}")
                else AppLogger.w(TAG, "Failed to delete ${file.name}")
            }
    }
}
