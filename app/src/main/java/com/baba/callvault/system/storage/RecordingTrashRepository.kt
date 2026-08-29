/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One recording sitting in the trash, in one folder. */
data class TrashedRecording(
    val uri: Uri,
    val trashedName: String,
    val originalName: String,
    val deletedAtMillis: Long?,
    val sizeBytes: Long
)

/**
 * Deleting a recording without losing it, and getting it back.
 *
 * **Why this exists.** Deleting was irreversible on two separate paths, on a screen where a mis-tap
 * costs the whole recording and the audio cannot be regenerated at any price. A recycle bin is the
 * best-evidenced protection and it costs nothing but a rename.
 *
 * 🚨 **Trashing must never run [com.baba.callvault.data.transcripts.TranscriptCascade].** The
 * transcript, summary, note and tags belong to a recording that can still come back, and destroying
 * them on the way into the trash would make a restore return silent audio and nothing else. They are
 * removed only when the recording is deleted for good — from here, and from the retention purge.
 */
object RecordingTrashRepository {

    private const val TAG = "CV:Trash"

    /**
     * Renames every copy of [displayName] so it is no longer a live recording.
     *
     * Both folders are swept by name rather than by the row's own uri, because a recording can have a
     * copy on the device and another on Drive and leaving one of them live would put the recording
     * straight back in the list on the next refresh.
     *
     * The catalog row is dropped afterwards. That is safe for the transcript: nothing purges
     * transcripts on the strength of a name being absent from the catalog — only an explicit cascade
     * does, and this deliberately does not call one.
     */
    suspend fun trash(context: Context, displayName: String): Boolean = withContext(Dispatchers.IO) {
        if (RecordingTrash.isTrashed(displayName)) return@withContext false

        val trashedName = RecordingTrash.trashedName(displayName, System.currentTimeMillis())
        var renamedAny = false

        forEachFolder(context) { tree ->
            for (doc in tree.listFiles()) {
                if (doc.isFile && doc.name == displayName) {
                    // renameTo, not a move: no provider needs FLAG_SUPPORTS_MOVE for it and no bytes
                    // travel, which on the Drive folder is the difference between instant and a
                    // hundred-megabyte round trip over mobile data.
                    val renamed = runCatching { doc.renameTo(trashedName) }.getOrElse {
                        AppLogger.w(TAG, "Could not trash a copy: ${it.message}")
                        false
                    }
                    if (renamed) renamedAny = true
                }
            }
        }

        if (renamedAny) {
            // Only once something actually moved. Dropping the row while the file is still live would
            // hide a recording that is not in the trash either — invisible and un-restorable.
            RecordingCatalog.removeName(context, displayName, cascade = false)
        }
        renamedAny
    }

    /** Everything currently in the trash, across both folders, newest deletion first. */
    suspend fun list(context: Context): List<TrashedRecording> = withContext(Dispatchers.IO) {
        val found = mutableListOf<TrashedRecording>()
        forEachFolder(context) { tree ->
            for (doc in tree.listFiles()) {
                val name = doc.name ?: continue
                if (!doc.isFile || !RecordingTrash.isTrashed(name)) continue
                found += TrashedRecording(
                    uri = doc.uri,
                    trashedName = name,
                    // A name that will not parse still appears, under its stored name, so the user can
                    // act on it. Hiding it would be the one way to lose a recording silently here.
                    originalName = RecordingTrash.originalName(name) ?: name,
                    deletedAtMillis = RecordingTrash.deletedAtMillis(name),
                    sizeBytes = runCatching { doc.length() }.getOrDefault(0L)
                )
            }
        }
        found.sortedByDescending { it.deletedAtMillis ?: 0L }
    }

    /**
     * Puts every copy of [trashedName] back under its original name.
     *
     * The catalog is refreshed by the caller's normal reload — the file reappearing in the folder is
     * all the listing needs, and the transcript was never removed, so it is simply there again.
     */
    suspend fun restore(context: Context, trashedName: String): Boolean = withContext(Dispatchers.IO) {
        val original = RecordingTrash.originalName(trashedName) ?: return@withContext false
        var restoredAny = false

        forEachFolder(context) { tree ->
            for (doc in tree.listFiles()) {
                if (doc.isFile && doc.name == trashedName) {
                    val renamed = runCatching { doc.renameTo(original) }.getOrElse {
                        AppLogger.w(TAG, "Could not restore a copy: ${it.message}")
                        false
                    }
                    if (renamed) restoredAny = true
                }
            }
        }
        restoredAny
    }

    /**
     * Deletes every copy of [trashedName] for good, and everything attached to it.
     *
     * This is the only place in the trash that runs the transcript cascade, and it has to: past this
     * point the recording cannot come back, so a transcript left behind would be a readable record of
     * a call the user has now deleted twice.
     */
    suspend fun deleteForever(context: Context, trashedName: String): Boolean =
        withContext(Dispatchers.IO) {
            var deletedAny = false
            forEachFolder(context) { tree ->
                for (doc in tree.listFiles()) {
                    if (doc.isFile && doc.name == trashedName) {
                        val deleted = runCatching { doc.delete() }.getOrDefault(false)
                        if (deleted) deletedAny = true
                    }
                }
            }
            if (deletedAny) {
                RecordingTrash.originalName(trashedName)?.let { original ->
                    com.baba.callvault.data.transcripts.TranscriptCascade
                        .deleteFor(context, listOf(original))
                }
            }
            deletedAny
        }

    /**
     * Removes everything whose thirty days are up. Returns how many recordings went.
     *
     * Called from the retention sweep, beside the sweep of live recordings, so the trash cannot grow
     * without bound on a phone nobody empties by hand.
     */
    suspend fun purgeExpired(context: Context): Int {
        val now = System.currentTimeMillis()
        val expired = list(context).filter { RecordingTrash.isExpired(it.trashedName, now) }
        var purged = 0
        expired.forEach { if (deleteForever(context, it.trashedName)) purged++ }
        if (purged > 0) AppLogger.i(TAG, "Purged $purged expired recording(s) from the trash")
        return purged
    }

    private inline fun forEachFolder(context: Context, block: (DocumentFile) -> Unit) {
        val prefs = AppPreferences(context)
        listOfNotNull(prefs.getRecordingFolderUri(), prefs.getDriveFolderUri()).forEach { folderUri ->
            runCatching {
                DocumentFile.fromTreeUri(context, folderUri)?.let(block)
            }.onFailure {
                AppLogger.w(TAG, "Could not read a storage folder: ${it.message}")
            }
        }
    }
}
