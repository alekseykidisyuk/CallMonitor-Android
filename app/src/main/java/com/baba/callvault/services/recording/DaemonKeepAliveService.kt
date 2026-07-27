/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.WirelessDebuggingPolicy
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderServerLauncher
import com.baba.callvault.utils.AppLogger

/**
 * Persistent foreground service that keeps CallVault's privileged recorder daemon WARM — our
 * equivalent of what Shizuku's manager app does for its server: a durable foreground presence that
 * anchors the app process and actively re-warms the daemon the moment it dies, so a call is captured
 * instantly (over binder) instead of paying a cold-start that outlasts a short call.
 *
 * **Why a foreground service.** OnePlus/ColorOS reaps our detached shell-uid daemon on idle and on
 * screen transitions (it restarts adbd, which the daemon dies with). Holding a foreground service (a)
 * keeps our own process important, and (b) lets us notice the daemon died and relaunch it — ideally
 * BEFORE the next call. Recording itself flows over the daemon's binder, so once warm, calls record
 * even with Wi-Fi off (over the opt-in loopback transport).
 *
 * **Fast recovery.** A confirmed binder-death ([onDaemonDiedImmediate]) relaunches immediately, and the
 * notification flips to "ready" the instant the relaunch succeeds. A cheap 60s binder-liveness watchdog
 * is the backup for a death we somehow missed.
 */
class DaemonKeepAliveService : Service() {

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val voipDetector by lazy {
        VoipCallDetector(applicationContext).apply {
            // Reuse the permanent keep-alive notification to show that a VoIP call is being recorded,
            // rather than adding a second one. Without this the user has no sign it is working.
            onRecordingStateChanged = { updateNotification(RecorderConnection.isConnected) }
        }
    }
    private var lastReady: Boolean? = null

    @Volatile private var rewarming = false
    private var lastRewarmAtMs = 0L
    /** Consecutive "daemon down" readings — we only relaunch after a couple, to not churn on a blip. */
    private var downStreak = 0

    private val watchdog = object : Runnable {
        override fun run() {
            val alive = isDaemonAlive()
            if (alive != (lastReady == true)) {
                lastReady = alive
                updateNotification(alive)
                AppLogger.d(TAG, "keep-alive: daemon alive=$alive")
            }
            if (alive) {
                downStreak = 0
            } else {
                downStreak++
                // Debounce: act only after DOWN_STREAK_THRESHOLD consecutive down reads, so a transient
                // binder blip doesn't trigger a relaunch (which would kill+respawn a daemon that was fine).
                if (downStreak >= DOWN_STREAK_THRESHOLD) maybeRewarm()
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * True only if the daemon process actually answers a binder ping — more reliable than
     * [RecorderConnection.isConnected] (`service != null`), which can stay stale-true if linkToDeath
     * missed, or read false on a transient. Matches the recording liveness watch's probe.
     */
    private fun isDaemonAlive(): Boolean = runCatching {
        RecorderConnection.service?.asBinder()?.pingBinder() == true
    }.getOrDefault(false)

    /**
     * Watches "USB debugging" so the daemon never silently loses its last way in.
     *
     * `adbd` only runs while USB debugging or Wireless debugging is enabled. When USB debugging is on,
     * CallVault switches Wireless debugging off — correctly, since `adbd` stays up. But if the user then
     * turns USB debugging off, BOTH are off: `adbd` stops, the daemon dies with it, and nothing notices
     * until a call is missed. That is not hypothetical — it cost a real outgoing call, where the cold
     * start took 18.3 s against a 15 s call and the recording never began. Shizuku's own wiki describes
     * users doing exactly this, because some banking apps demand USB debugging be off.
     *
     * So the policy is re-evaluated when the switches change, not only when the daemon launches.
     */
    private val usbDebuggingObserver = object : ContentObserver(watchdogHandler) {
        override fun onChange(selfChange: Boolean) {
            val usbOn = AdbShell.isUsbDebuggingEnabled(applicationContext)
            val wdOn = AdbShell.isWirelessDebuggingEnabled(applicationContext)

            // Both directions matter, and both are immediate. Waiting for the next daemon launch to
            // re-evaluate means the user flips a switch and nothing visibly happens, which reads as
            // broken even when it eventually corrects itself.
            val reason = when {
                // Last way in just disappeared — adbd is going down and the daemon with it.
                !usbOn && !wdOn -> "USB debugging switched off and Wireless debugging is off — adbd has no transport; restoring"
                // USB debugging now holds adbd up, so Wireless debugging is no longer needed. Dropping
                // it here is safe: with USB debugging enabled, toggling Wireless debugging does not
                // restart adbd (measured — its pid is unchanged), so the daemon is never at risk.
                usbOn && wdOn -> "USB debugging switched on — Wireless debugging is no longer needed"
                else -> return
            }

            AppLogger.i(TAG, reason)
            Thread {
                runCatching {
                    if (!usbOn && !wdOn) AdbShell.enableWirelessDebugging(applicationContext)
                    // Re-runs the transport policy: with the daemon already connected this is just the
                    // decision, no relaunch — and it is what switches Wireless debugging off.
                    RecorderServerLauncher.ensureServerRunning(applicationContext)
                }.onFailure { AppLogger.w(TAG, "Could not apply the transport change: ${it.message}") }
            }.apply { isDaemon = true; name = "cv-transport-change" }.start()
        }
    }

    /**
     * Reacts when the **user** switches Wireless debugging on while it is not needed.
     *
     * Only the user's changes count. CallVault turns Wireless debugging on itself during startup, so
     * acting on every change here would switch off the very thing the bootstrap just switched on —
     * [AdbShell.didWeJustSetWirelessDebugging] is what tells the two apart.
     *
     * Even then it acts only when Wireless debugging is genuinely redundant: USB debugging is holding
     * `adbd` up **and** the daemon is already answering. Dropping it in any other state would be taking
     * away the only way in.
     */
    private val wirelessDebuggingObserver = object : ContentObserver(watchdogHandler) {
        override fun onChange(selfChange: Boolean) {
            if (!AdbShell.isWirelessDebuggingEnabled(applicationContext)) return
            if (AdbShell.didWeJustSetWirelessDebugging(enabled = true)) return
            if (!AdbShell.isUsbDebuggingEnabled(applicationContext)) return
            if (!isDaemonAlive()) return

            AppLogger.i(TAG, "Wireless debugging switched on by hand but USB debugging covers adbd — switching it back off")
            Thread {
                runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }
                    .onFailure { AppLogger.w(TAG, "Could not re-apply the transport policy: ${it.message}") }
            }.apply { isDaemon = true; name = "cv-wd-change" }.start()
            // Say so. Undoing a switch the user just flipped, silently, reads as the app fighting them —
            // even when it is right. One dismissible note explaining why is the honest minimum.
            notifyWirelessDebuggingTurnedBackOff()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_readiness_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )

        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_enabled"), false, usbDebuggingObserver,
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false, wirelessDebuggingObserver,
            )
        }.onFailure { AppLogger.w(TAG, "Could not watch the debugging switches: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedForeground = runCatching {
            startForeground(NOTIF_ID, buildNotification(RecorderConnection.isConnected), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { AppLogger.e(TAG, "keep-alive startForeground failed", it) }.isSuccess
        if (!startedForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Recover the INSTANT the daemon dies (binder linkToDeath) — don't wait for the next poll.
        // On a real incoming call this is what races (and hopefully beats) the call after a long idle.
        RecorderConnection.onDeath = { onDaemonDiedImmediate() }
        // VoIP detection lives here rather than in its own component: this service is already a
        // permanent foreground presence, so watching for VoIP calls costs no extra process and no
        // second notification, and VoIP gets exactly the same lifetime as carrier recording.
        voipDetector.sync()

        // Kick the watchdog now (first tick warms the daemon immediately if it's cold).
        lastReady = null
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.post(watchdog)
        // START_STICKY: if the OS kills us under pressure, restart so the daemon anchor comes back.
        return START_STICKY
    }

    /**
     * Called from [RecorderConnection.onDeath] the moment the daemon binder dies — an authoritative
     * "process gone" signal (linkToDeath only fires on real death). Relaunch immediately, skipping the
     * poll interval AND the debounce. Posted to the handler so we're off the binder thread.
     */
    private fun onDaemonDiedImmediate() {
        watchdogHandler.post {
            AppLogger.i(TAG, "keep-alive: binder-death signal — relaunching immediately")
            lastReady = false
            updateNotification(false)
            downStreak = DOWN_STREAK_THRESHOLD // death confirmed — no debounce needed
            // Confirmed death (linkToDeath) is authoritative — relaunch NOW, bypassing the throttle.
            // The throttle exists to avoid HAMMERING a failed relaunch, NOT to delay a genuine recovery:
            // on OnePlus the daemon dies on every screen transition, so a death shortly after a prior
            // relaunch was being throttled — the cause of a long "starting up" window a call would race.
            maybeRewarm(force = true)
        }
    }

    /**
     * Relaunch the daemon if it's down. [force] (a confirmed binder-death) bypasses the throttle so a
     * genuine recovery isn't delayed; the un-forced watchdog path still throttles to avoid hammering a
     * relaunch that keeps failing. Loopback records off-Wi-Fi, so only the non-offline (Wireless
     * Debugging) relaunch path requires Wi-Fi. When the daemon is already connected the [watchdog] never
     * calls this, so an active recording is implicitly skipped and never churned.
     */
    private fun maybeRewarm(force: Boolean = false) {
        if (rewarming) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRewarmAtMs < REWARM_THROTTLE_MS) return
        val offline = runCatching { AppPreferences(this).isOfflineRecordingEnabled() }.getOrDefault(false)
        if (!offline && !isWifiConnected()) return // the WD relaunch path needs Wi-Fi; loopback doesn't
        lastRewarmAtMs = now
        rewarming = true
        Thread {
            AppLogger.i(TAG, "keep-alive: daemon down — relaunching (force=$force offline=$offline)")
            // Restore a transport FIRST, explicitly. `adbd` only runs while USB debugging or Wireless
            // debugging is enabled; with neither there is nothing to launch the daemon over, and the
            // connect path only rediscovers that after ~12 s of failing loopback probes. Turning
            // Wireless debugging back on here is the difference between recovering in seconds and
            // sitting on "starting up" indefinitely — which is what happened on a OnePlus 12 after USB
            // debugging was switched off: twenty minutes down, and it only came back when the app was
            // opened by hand.
            if (!AdbShell.isUsbDebuggingEnabled(applicationContext) &&
                !AdbShell.isWirelessDebuggingEnabled(applicationContext)
            ) {
                AppLogger.w(TAG, "keep-alive: adbd has no transport — switching Wireless debugging back on")
                runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                    .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
            }
            val ok = runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }
                .onFailure { AppLogger.w(TAG, "keep-alive relaunch failed: ${it.message}") }
                .getOrDefault(false)
            rewarming = false
            // Flip the notification to "ready" the INSTANT the relaunch succeeds — don't wait for the next
            // 60s watchdog tick. Without this the daemon reconnects in seconds but the user would still see
            // "starting up" for up to a minute (a perceived-but-false slow recovery).
            if (ok) watchdogHandler.post { lastReady = true; updateNotification(true) }
        }.apply { isDaemon = true; name = "cv-keepalive-rewarm" }.start()
    }

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    /** Tells the user CallVault undid their Wireless-debugging change, and why. Dismissible, silent. */
    private fun notifyWirelessDebuggingTurnedBackOff() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    INFO_CHANNEL_ID,
                    getString(R.string.notif_info_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false); enableVibration(false); setSound(null, null) },
            )
            val notification = NotificationCompat.Builder(this, INFO_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.notif_wd_reverted_title))
                .setContentText(getString(R.string.notif_wd_reverted_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_wd_reverted_text)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(INFO_NOTIF_ID, notification)
        }.onFailure { AppLogger.d(TAG, "Could not post the Wireless-debugging note: ${it.message}") }
    }

    private fun updateNotification(ready: Boolean) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ready))
        }
    }

    private fun buildNotification(ready: Boolean): Notification {
        val voipRecording = runCatching { voipDetector.isRecording }.getOrDefault(false)
        val baseText = when {
            voipRecording -> getString(R.string.notif_voip_recording_text)
            ready -> getString(R.string.notif_readiness_ready_text)
            else -> getString(R.string.notif_readiness_starting_text)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                when {
                    voipRecording -> getString(R.string.notif_voip_recording_title)
                    ready -> getString(R.string.notif_readiness_ready_title)
                    else -> getString(R.string.notif_readiness_starting_title)
                },
            )
            .setContentText(baseText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // When recording is ready BUT the USB default is a data mode, locking the screen mid-call can kill
        // the recorder — surface that right on the readiness notification so the user can act.
        if (ready && UsbDefaultConfig.isScreenLockRisk(this)) {
            val warning = getString(R.string.notif_usb_lock_warning)
            builder.setContentText(warning)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$baseText\n$warning"))
            return builder.build()
        }

        // Wireless debugging has to stay on when it is adbd's ONLY transport, because switching it off
        // would stop adbd and take the daemon with it. Say so rather than leaving the user to notice that
        // a debugging switch they did not turn on is staying on — and name the two settings that free it.
        if (ready && WirelessDebuggingPolicy.mustKeepWirelessDebugging(AdbShell.wirelessDebuggingPlan(this))) {
            val notice = getString(R.string.notif_wd_required)
            builder.setContentText(notice)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$baseText\n$notice"))
        }
        return builder.build()
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(usbDebuggingObserver) }
        runCatching { contentResolver.unregisterContentObserver(wirelessDebuggingObserver) }
        runCatching { voipDetector.stop() }
        watchdogHandler.removeCallbacks(watchdog)
        RecorderConnection.onDeath = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CV:DaemonKeepAlive"
        private const val CHANNEL_ID = "recorder_keepalive"

        /** Low-importance channel for one-off explanations, kept apart from the permanent status note. */
        private const val INFO_CHANNEL_ID = "callvault_info"
        private const val INFO_NOTIF_ID = 4721
        private const val NOTIF_ID = 4720

        /** How often the watchdog checks the daemon is alive. Cheap (a binder ping). */
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        /**
         * Minimum gap between UN-FORCED (watchdog) relaunch attempts, so a persistently-failing relaunch
         * isn't hammered. A confirmed binder-death relaunches immediately via `maybeRewarm(force = true)`
         * and ignores this. Kept modest (was 90s — which delayed real recoveries when the daemon died
         * shortly after a prior relaunch, as it does on every OnePlus screen transition).
         */
        private const val REWARM_THROTTLE_MS = 20_000L

        /** Consecutive down reads before relaunching — debounces a transient binder blip into no action. */
        private const val DOWN_STREAK_THRESHOLD = 2

        /** Start (or no-op if already running) the persistent keep-alive anchor. Safe to call repeatedly. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DaemonKeepAliveService::class.java))
            }.onFailure { AppLogger.w(TAG, "Failed to start keep-alive service: ${it.message}") }
        }

        /** Stop the keep-alive anchor (e.g. if the user disables persistence). */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DaemonKeepAliveService::class.java)) }
        }
    }
}
