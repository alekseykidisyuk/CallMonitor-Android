/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.utils.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns "a VoIP call started/ended" into a recording, writing into the same folder as carrier
 * recordings so both appear together in the app.
 *
 * Naming differs from the carrier path out of necessity: a VoIP call leaves no call-log entry and
 * exposes no phone number, so there is no contact to resolve. Recordings are therefore named by time
 * and marked `voip`, which is honest about what we know rather than guessing at who was on the call.
 *
 * Kept deliberately small and independent of [AudioRecordingEngine]: that engine is built around a
 * carrier call's metadata (number, direction, ignore-rules), none of which exists here.
 */
object VoipRecordingCoordinator {
    private const val TAG = "CV:VoipRec"

    @Volatile private var recording = false
    @Volatile private var pending: SafHelper.SafResult? = null

    /** Starts a VoIP recording. No-op when the feature is off, already recording, or unavailable. */
    @Synchronized
    fun onCallStarted(context: Context) {
        if (recording) return
        val prefs = AppPreferences(context)
        if (!prefs.isVoipRecordingEnabled()) return

        val service = RecorderConnection.service
        if (service == null) {
            AppLogger.w(TAG, "VoIP call detected but the daemon is not connected — not recording")
            return
        }

        val folderUri = prefs.getRecordingFolderUri()
        if (!SafHelper.isFolderValid(context, folderUri)) {
            AppLogger.e(TAG, "VoIP call detected but the recording folder is missing/unwritable")
            return
        }

        val codec = runCatching { ScrcpyAudioCodec.fromKey(prefs.getAudioCodec()) }
            .getOrDefault(ScrcpyAudioCodec.OPUS)
        val bitRate = prefs.getAudioBitRate().takeIf { it > 0 } ?: codec.defaultBitRate
        val fileName = buildFileName(codec)

        val saf = SafHelper.createAudioFile(context, folderUri, fileName, codec.mimeType)
        if (saf == null) {
            AppLogger.e(TAG, "Could not create the VoIP output file")
            return
        }

        val started = runCatching {
            service.startVoipRecording(codec.cliKey, bitRate, saf.descriptor)
        }.onFailure { AppLogger.e(TAG, "startVoipRecording threw: ${it.message}", it) }.getOrDefault(false)

        if (!started) {
            // Most likely the policy was not armed before the call — nothing can be captured now, so
            // remove the empty file rather than leaving a 0-byte recording in the user's folder.
            AppLogger.e(TAG, "VoIP recording refused by the daemon; discarding the empty file")
            runCatching { saf.descriptor.close() }
            runCatching { DocumentFile.fromSingleUri(context, saf.uri)?.delete() }
            return
        }

        recording = true
        pending = saf
        AppLogger.i(TAG, "VoIP recording started -> $fileName")
    }

    /** Stops the in-flight VoIP recording, if any. Idempotent. */
    @Synchronized
    fun onCallEnded(context: Context) {
        if (!recording) return
        recording = false
        val saf = pending
        pending = null
        runCatching { RecorderConnection.service?.stopRecording() }
            .onFailure { AppLogger.w(TAG, "stopRecording failed: ${it.message}") }

        // Providers that cannot hand out a seekable rw fd (Downloads, SD card, some cloud/OEM
        // providers) get a private staging file instead; without this copy the SAF entry stays empty.
        val staging = saf?.stagingFile
        if (staging != null) {
            val copied = runCatching { SafHelper.writeStagedFileToUri(context, staging, saf.uri) }
                .onFailure { AppLogger.e(TAG, "Staged VoIP copy failed: ${it.message}", it) }
                .getOrDefault(false)
            AppLogger.i(TAG, "VoIP staged copy ${if (copied) "ok" else "FAILED"} -> ${saf.uri}")
            runCatching { staging.delete() }
        }
        AppLogger.i(TAG, "VoIP recording stopped (${saf?.uri})")
    }

    /**
     * `<timestamp>_voip.<ext>` — no number and no contact, because a VoIP call provides neither. The
     * `voip` marker keeps these distinguishable from carrier recordings at a glance and in sorting.
     */
    private fun buildFileName(codec: ScrcpyAudioCodec): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.CANADA).format(Date())
        return "${stamp}_voip.${codec.containerExtension}"
    }
}
