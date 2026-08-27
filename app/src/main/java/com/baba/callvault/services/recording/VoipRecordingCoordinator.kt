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
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.health.CallOutcome
import com.baba.callvault.data.health.CallOutcomes
import com.baba.callvault.data.health.Prerequisite
import com.baba.callvault.data.health.SetupFingerprint
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.health.record
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.system.storage.StorageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /** Mirrors `VoipAppIdentity.UID_UNKNOWN`, which lives in the daemon-side package. */
    private const val UID_UNKNOWN = -1

    @Volatile private var recording = false

    /** True while a VoIP recording is running, so the UI can say so. */
    val isRecording: Boolean get() = recording
    @Volatile private var pending: SafHelper.SafResult? = null
    @Volatile private var codecMime: String = "audio/ogg"

    /** Starts a VoIP recording. No-op when the feature is off, already recording, or unavailable. */
    @Synchronized
    fun onCallStarted(context: Context) {
        if (recording) return
        val prefs = AppPreferences(context)
        if (!prefs.isVoipRecordingEnabled()) return

        val service = RecorderConnection.service
        if (service == null) {
            // Nothing can be done for THIS call, and that is not a shortcoming of the retry that is
            // missing here — see [reportMissed]. All that is left is to say so.
            reportMissed(context, R.string.voip_missed_not_ready, null)
            AppLogger.w(TAG, "VoIP call detected but the daemon is not connected — not recording")
            return
        }

        val folderUri = prefs.getRecordingFolderUri()
        if (!SafHelper.isFolderValid(context, folderUri)) {
            // User-owned and permanent until they fix it: every call goes the same way until then.
            reportMissed(context, R.string.voip_missed_no_folder, Prerequisite.RECORDING_FOLDER)
            AppLogger.e(TAG, "VoIP call detected but the recording folder is missing/unwritable")
            return
        }

        val codec = runCatching { ScrcpyAudioCodec.fromKey(prefs.getAudioCodec()) }
            .getOrDefault(ScrcpyAudioCodec.OPUS)
        val bitRate = prefs.getAudioBitRate().takeIf { it > 0 } ?: codec.defaultBitRate
        // Best-effort; a missing app or name just drops out of the filename.
        // The app comes from the audio stream we are about to record, so it cannot be the wrong app;
        // the caller is then looked up scoped to THAT package, never across all notifications.
        val callPackage = resolveCallPackage(context, service)
        val appLabel = callPackage?.let { appLabelFor(context, it) }
        val caller = callPackage?.let {
            runCatching { service.voipCallerName(it) }
                .onFailure { AppLogger.d(TAG, "caller lookup failed: ${it.message}") }
                .getOrNull()
        }
        val fileName = buildFileName(codec, appLabel, caller)

        val saf = SafHelper.createAudioFile(context, folderUri, fileName, codec.mimeType)
        if (saf == null) {
            reportMissed(context, R.string.voip_missed_no_folder, Prerequisite.RECORDING_FOLDER)
            AppLogger.e(TAG, "Could not create the VoIP output file")
            return
        }

        val started = runCatching {
            service.startVoipRecording(codec.cliKey, bitRate, saf.descriptor)
        }.onFailure { AppLogger.e(TAG, "startVoipRecording threw: ${it.message}", it) }.getOrDefault(false)

        if (!started) {
            // Most likely the policy was not armed before the call — nothing can be captured now, so
            // remove the empty file rather than leaving a 0-byte recording in the user's folder.
            reportMissed(context, R.string.voip_missed_not_ready, null)
            AppLogger.e(TAG, "VoIP recording refused by the daemon; discarding the empty file")
            runCatching { saf.descriptor.close() }
            runCatching { DocumentFile.fromSingleUri(context, saf.uri)?.delete() }
            return
        }

        recording = true
        pending = saf
        codecMime = codec.mimeType
        AppLogger.i(TAG, "VoIP recording started -> $fileName")
    }

    /**
     * Says out loud that a VoIP call went unrecorded.
     *
     * **Why there is no retry here.** The obvious fix — wait a moment for the daemon and start late —
     * cannot work. Capture depends on a dynamic audio policy the daemon registers, and Android fixes
     * a track's routing when the track is *created*: `startVoipRecording` refuses outright with
     * "policy was not armed before the call" for exactly this reason. By the time we notice the call,
     * its audio is already routed. Arming is re-done on every fresh daemon binder
     * (`RecorderConnection.onDaemonReady`), which is what keeps the window small; a call that lands
     * inside it is lost, and no amount of waiting recovers it.
     *
     * So the only honest thing left is to tell the user, because the alternative is what shipped
     * until now: a call recorded by nobody and mentioned by nobody. That is the worst outcome a call
     * recorder has — worse than a duplicate warning, worse than a bad recording — because the user
     * finds out weeks later, if ever.
     *
     * Both a notification (seen now, while they remember the call) and a status-card entry (still
     * there tomorrow). Reporting only, and wrapped: nothing here may throw into the call path.
     *
     * @param prerequisite the user-owned setting to blame, where there is one. Null for a transient
     *   fault of ours, which is recorded as an unexplained gap instead — the two must never blur,
     *   since one excuses the miss and the other is precisely the failure worth chasing.
     */
    private fun reportMissed(context: Context, messageRes: Int, prerequisite: Prerequisite?) {
        // Reading health can fail; a failure there must not decide to shout. False is the quiet
        // direction, and matches the carrier path's gate in `recordMissedForMissingPrerequisite`.
        val everWorked = runCatching { SetupHealthStore(context).read().lastVerifiedAt > 0L }
            .getOrDefault(false)

        when (VoipMissPolicy.report(everWorked, prerequisite)) {
            MissReport.SILENT -> {
                AppLogger.i(TAG, "An app call went unrecorded, but no call has ever recorded here — staying quiet")
                return
            }

            MissReport.EXCUSED, MissReport.UNEXPLAINED -> Unit
        }

        runCatching {
            RecordingNotificationHelper(context).showErrorNotification(context.getString(messageRes))
        }.onFailure { AppLogger.w(TAG, "Could not warn about the missed VoIP call: ${it.message}") }

        // Separately guarded from the notification: one is seen now, the other is still there
        // tomorrow, and a failure to write the second must not cost the first.
        runCatching {
            val now = System.currentTimeMillis()
            val store = SetupHealthStore(context)
            // Labelled "App call": there is no number and no contact to name it by, and the card
            // saying which kind of call went missing is most of what makes it actionable.
            val label = context.getString(R.string.voip_missed_label)
            if (prerequisite != null) store.recordMissedWhileNotReady(now, label, prerequisite)
            else store.recordGap(now, label)
        }.onFailure { AppLogger.w(TAG, "Could not record the missed VoIP call: ${it.message}") }
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

        // A recording where the far party was never audible is one-sided. Say so now rather than let it
        // be discovered weeks later — the app may have opted out of capture, or this OEM build may not
        // attach calls to our mix; from here the two are indistinguishable.
        val farHeard = runCatching { RecorderConnection.service?.voipFarPartyHeard() ?: true }
            .getOrDefault(true)   // no service, or an error: assume fine; never cry wolf
        if (!farHeard) {
            AppLogger.w(TAG, "VoIP recording captured only your side — the other app blocks capture")
            runCatching {
                RecordingNotificationHelper(context)
                    .showErrorNotification(context.getString(R.string.voip_one_sided_warning))
            }.onFailure { AppLogger.w(TAG, "Could not warn about the one-sided recording: ${it.message}") }
        }

        // Providers that cannot hand out a seekable rw fd (Downloads, SD card, some cloud/OEM
        // providers) get a private staging file instead; without this copy the SAF entry stays empty.
        val staging = saf?.stagingFile
        if (staging != null) {
            val copied = runCatching { SafHelper.writeStagedFileToUri(context, staging, saf.uri) }
                .onFailure { AppLogger.e(TAG, "Staged VoIP copy failed: ${it.message}", it) }
                .getOrDefault(false)
            AppLogger.i(TAG, "VoIP staged copy ${if (copied) "ok" else "FAILED"} -> ${saf.uri}")
            if (!runCatching { staging.delete() }.getOrDefault(false)) {
                AppLogger.w(TAG, "The VoIP staging file ${staging.name} could not be deleted; it stays in the cache")
            }
        }
        // The Home list reads CallVault's own catalog, not the folder — a file that is never recorded
        // here exists on disk but is invisible in the app. The carrier path does this from
        // RecordingForegroundService, which the VoIP path deliberately does not go through.
        if (saf != null) {
            val name = saf.displayName.substringAfterLast('/')
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val size = SafHelper.fileSize(context, saf.uri)
                    RecordingCatalog.recordLocal(context, name, saf.uri, size, System.currentTimeMillis())
                    // Collect the speaker turns the capture just measured, before transcription is
                    // queued — the runner reads them from the database when it labels segments, so
                    // arriving afterwards would mean an unlabelled transcript.
                    //
                    // The capture interleaves L=near, R=far and had that separation all along; it was
                    // simply thrown away at the downmix, so app calls came out with no speaker labels
                    // while carrier calls had them. `outgoing = false`: an app call has no reliable
                    // direction here, and guessing wrong would teach the You/Them mapping the wrong
                    // side, which is worse than leaving the sides neutral.
                    runCatching {
                        SpeakerTurnsRepository.collectAfterCall(context, name, outgoing = false)
                    }.onFailure { AppLogger.w(TAG, "Could not collect VoIP speaker turns: ${it.message}") }
                    // Same order as the carrier path: catalog first, then queue.
                    TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name)
                    // Draw it now rather than when the user opens it: this phone has just come
                    // off a call, and they have not asked to wait for anything yet.
                    RecordingExtrasRepository.precomputeWaveform(context, name, saf.uri)
                    // SafHelper.fileSize() returns -1 specifically for "unknown" (a provider that can't
                    // report a length right now), never for "empty" — that's 0. CallOutcomes.of() cannot
                    // tell the two apart and would judge a negative size as EMPTY_FILE, so an unknowable
                    // size must never reach it: recording nothing is the safe direction, not guessing.
                    if (size < 0L) {
                        AppLogger.i(TAG, "VoIP file size unknown for '$name' (SAF reported $size); skipping the setup-health write")
                    } else {
                        runCatching {
                            val outcome = CallOutcomes.of(size, daemonDied = false, farPartyHeard = farHeard)
                            SetupHealthStore(context).record(
                                outcome, System.currentTimeMillis(), name, SetupFingerprint.of(AppPreferences(context))
                            )
                            // Tell the user the app call was recorded, the same way a carrier call
                            // does. Until now this path said nothing at all: the toast and vibration
                            // hang off RecordingServiceState, which only RecordingForegroundService
                            // drives, and this path deliberately does not go through it. A silent
                            // success is indistinguishable from a silent failure, and the only way to
                            // tell them apart was to go looking for the file days later.
                            //
                            // Only on Verified. Confirming a recording that is empty or one-sided
                            // would be worse than saying nothing — those already raise their own
                            // error notification, and two contradictory signals is not a fix.
                            if (outcome is CallOutcome.Verified) {
                                RecordingNotificationHelper(context).showRecordingEnded()
                            }
                        }.onFailure { AppLogger.w(TAG, "Could not record setup health for '$name': ${it.message}") }
                    }
                    AppLogger.i(TAG, "VoIP recording catalogued: $name ($size bytes)")
                    StorageRouter.route(context, saf.uri, name, codecMime)
                }.onFailure { AppLogger.e(TAG, "Cataloguing the VoIP recording failed: ${it.message}", it) }
            }
        }
        AppLogger.i(TAG, "VoIP recording stopped (${saf?.uri})")
    }

    /**
     * `<timestamp>_voip.<ext>` — no number and no contact, because a VoIP call provides neither. The
     * `voip` marker keeps these distinguishable from carrier recordings at a glance and in sorting.
     */
    /**
     * `<timestamp>_voip[_<App>][_<caller>]`. Both extras are best-effort — a VoIP call provides neither
     * a number nor a call-log entry, so an absent app or name simply drops out of the name rather than
     * being guessed at.
     */
    private fun buildFileName(codec: ScrcpyAudioCodec, appLabel: String?, caller: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.CANADA).format(Date())
        // The app hangs off the marker ("_voip-Signal") rather than sitting in its own underscore slot.
        // With both in underscore slots, a call with an app but no caller was indistinguishable from one
        // with a caller but no app, and Signal calls lost their app badge because of it.
        val app = appLabel?.takeIf { it.isNotBlank() }?.let { "-${sanitiseForFileName(it)}" } ?: ""
        val who = caller?.takeIf { it.isNotBlank() }?.let { "_$it" } ?: ""
        return "${stamp}_voip$app$who${codec.containerExtension}"   // containerExtension has the dot
    }

    /**
     * The package on the call, from the uid that owns the call's audio track.
     *
     * `getPackagesForUid` rather than string matching on a dump: it is the framework's own answer, and
     * it stays correct for shared uids and for work profiles, where the uid encodes the user id.
     * Several packages can share a uid, in which case the launchable one is the app the user is in.
     */
    /**
     * The name of the app whose call is currently up, or null when it cannot be told.
     *
     * Used by the "Ask me" prompt so it can say *which* app is calling. Resolution goes through the
     * daemon (only it can see who owns the call audio), so this returns null whenever the daemon is
     * not connected — the prompt then falls back to generic wording rather than not appearing.
     */
    fun currentCallAppLabel(context: Context): String? {
        val service = RecorderConnection.service ?: return null
        val pkg = runCatching { resolveCallPackage(context, service) }.getOrNull() ?: return null
        return appLabelFor(context, pkg)
    }

    private fun resolveCallPackage(context: Context, service: IRecorderService): String? {
        val uid = runCatching { service.voipCallAppUid() }
            .onFailure { AppLogger.d(TAG, "call-uid lookup failed: ${it.message}") }
            .getOrDefault(UID_UNKNOWN)
        if (uid == UID_UNKNOWN) {
            AppLogger.d(TAG, "No VoIP call audio owner found; naming by time alone")
            return null
        }

        val pm = context.packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.toList().orEmpty()
        if (packages.isEmpty()) {
            AppLogger.d(TAG, "uid $uid resolved to no visible package")
            return null
        }
        return packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: packages.first()
    }

    /** The app's user-visible name, e.g. "com.whatsapp" -> "WhatsApp"; falls back to the package. */
    private fun appLabelFor(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun sanitiseForFileName(s: String) = s.replace(Regex("""[/\\:*?"<>|_\p{Cntrl}]"""), "").trim()
}
