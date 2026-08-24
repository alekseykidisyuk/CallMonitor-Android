/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.app.Application
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.services.debug.DebugNotificationHelper
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.system.storage.SyncScheduler
import com.baba.callvault.transcription.TranscriptionQueue
import com.baba.callvault.system.updates.UpdateScheduler
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.VoipCaptureController
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.runBlocking

/**
 * CallVaultApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
class CallVaultApplication : Application() {
    private companion object {
        const val TAG = "CV:CallVaultApplication"

        /** Old CallMonitorService notification id (pre-consolidation) — now shares 4720; cancel the stale one. */
        const val LEGACY_READINESS_NOTIF_ID = 4714

        /** Old RecorderReadinessNotifier notification id (pre-consolidation, transient launch notice). */
        const val LEGACY_LAUNCH_NOTIF_ID = 4715
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)

        // Migration: pre-consolidation builds showed readiness from THREE sources (the transient launch
        // notifier id 4715 + the post-boot CallMonitorService id 4714), duplicating the single permanent
        // keep-alive notification (id 4720). Cancel those stale ids once on startup so an updated install
        // immediately drops to ONE readiness notification instead of waiting for a reboot to clear them.
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)?.apply {
                cancel(LEGACY_READINESS_NOTIF_ID); cancel(LEGACY_LAUNCH_NOTIF_ID)
            }
        }

        // The VoIP capture policy lives in the daemon and dies with it, and it must be armed BEFORE a
        // call starts — there is no arming it once a call is under way. Re-arm on every fresh daemon
        // binder. Off the binder thread: arming is a blocking IPC.
        RecorderConnection.onDaemonReady = {
            if (AppPreferences(applicationContext).isVoipRecordingEnabled()) {
                Thread {
                    runCatching { VoipCaptureController.sync(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "VoIP re-arm failed: ${it.message}") }
                }.apply { isDaemon = true; name = "cv-voip-rearm" }.start()
            }
        }

        // Re-assert the "debug logging is on" reminder if the user left logging enabled across an
        // app restart, so the nudge to turn it back off survives process death.
        runCatching { DebugNotificationHelper.sync(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Debug notification sync failed: ${it.message}") }

        // Reconcile the daily retention sweep with the saved prefs (schedules it when retention is on,
        // cancels it when off). Idempotent; ensures the sweep persists across reinstalls/reboots.
        runCatching { RetentionScheduler.apply(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Retention scheduler apply failed: ${it.message}") }

        // Reconcile the daily update check with the saved prefs (same idempotent pattern), then run
        // an immediate throttled check so a new release surfaces on app open, not just once a day.
        runCatching {
            UpdateScheduler.apply(applicationContext)
            UpdateScheduler.checkNowIfDue(applicationContext)
        }.onFailure { AppLogger.w(TAG, "Update scheduler apply failed: ${it.message}") }

        // Reconcile the periodic cloud sweep too. It was only ever applied when the wizard was
        // *finished*, while the cadence pref is written the moment it is tapped — so backing out of the
        // wizard (or being killed in it) could leave a scheduled sweep behind for a cadence the app no
        // longer uses, batch-uploading alongside the per-recording copy.
        runCatching { SyncScheduler.apply(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Sync scheduler apply failed: ${it.message}") }

        // Release any transcript row left RUNNING by a run that no longer exists. A cancelled or
        // killed worker cannot tidy up after itself — whisper sits inside a blocking native call, so
        // the interruption may never reach the Kotlin that would reset the row. Left alone the row
        // shows an untappable spinner for ever, and the queue skips RUNNING so no automatic sweep
        // would rescue it either. A fresh process means nothing from before is still transcribing.
        Thread {
            runCatching { runBlocking { TranscriptionQueue.releaseStaleWork(applicationContext) } }
                .onSuccess { if (it > 0) AppLogger.i(TAG, "Released $it stale transcription row(s)") }
                .onFailure { AppLogger.w(TAG, "Releasing stale transcription rows failed: ${it.message}") }
        }.start()

        // If ADB was already paired, proactively bring up the persistent recorder daemon in the
        // background: this (transiently) enables Wireless debugging if needed, launches the daemon,
        // waits for its binder, then turns WD back OFF — so the app is recording-ready over binder and
        // WD is off when idle, with no user action. Best-effort; the call path also ensures it on demand.
        // isPrivilegedTransportSetUp, not isAdbPaired: a Shizuku user never pairs, and gating on
        // pairing meant the app never bound Shizuku at startup — so the first call after a cold
        // start raced an unbound recorder.
        if (AppPreferences(applicationContext).isPrivilegedTransportSetUp()) {
            Thread {
                // Warm the persistent daemon in the background so the app is recording-ready over binder.
                // Readiness ("starting up… → ready to record") is surfaced by the SINGLE persistent
                // DaemonKeepAliveService notification — no separate notifier here, which previously
                // produced a DUPLICATE readiness notification alongside the keep-alive one.
                runCatching { RecorderBackend.ensureRunning(applicationContext) }
                    .onFailure { AppLogger.w(TAG, "Startup recorder-daemon warmup failed: ${it.message}") }
            }.apply { isDaemon = true }.start()
        }
    }
}