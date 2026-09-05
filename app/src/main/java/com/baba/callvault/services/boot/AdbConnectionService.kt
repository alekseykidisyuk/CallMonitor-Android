/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.boot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Post-boot recovery service for the standalone ADB recorder.
 *
 * A reboot clears CallVault's classic tcpip loopback listener. On ROMs that allow
 * WRITE_SECURE_SETTINGS the service can bootstrap Wireless debugging itself and re-arm loopback.
 * Xiaomi/HyperOS can deliberately deny that permission even to shell. In that case there is no
 * programmatic API left that can turn Wireless debugging on, so the service stays alive and watches
 * for the user's one manual Wireless-debugging toggle. The instant that toggle appears it retries the
 * recorder bootstrap automatically; the user no longer has to return to CallMonitor or tap reconnect.
 *
 * The service also watches Wi-Fi availability. This fixes the other common boot ordering: the phone
 * boots before Wi-Fi is associated, the first recovery fails, and previously nothing retried when Wi-Fi
 * appeared later.
 */
class AdbConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recoveryInFlight = AtomicBoolean(false)
    private var observersRegistered = false

    private val wirelessDebuggingObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            if (AdbShell.isWirelessDebuggingEnabled(applicationContext)) {
                AppLogger.i(TAG, "Wireless debugging became available after boot; retrying recorder recovery now")
                attemptRecovery("wireless-debugging-enabled")
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                AppLogger.i(TAG, "Wi-Fi became available after boot; retrying recorder recovery")
                attemptRecovery("wifi-available")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_startup_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
        registerRecoveryObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            startForeground(
                NOTIF_ID,
                buildNotification(getString(R.string.notif_startup_preparing)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            AppLogger.e(TAG, "startForeground failed", it)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(getString(R.string.notif_startup_preparing)))
        }

        attemptRecovery("service-start")
        // Stay alive while recovery is pending. On HyperOS this is what lets a later manual WD toggle
        // recover immediately instead of requiring the user to reopen CallMonitor.
        return START_STICKY
    }

    private fun attemptRecovery(reason: String) {
        if (!recoveryInFlight.compareAndSet(false, true)) {
            AppLogger.d(TAG, "Recovery already in flight; ignoring trigger=$reason")
            return
        }

        scope.launch {
            try {
                AppLogger.i(TAG, "Post-boot recorder recovery attempt: $reason")
                val started = runCatching { RecorderBackend.ensureRunning(applicationContext) }
                    .getOrDefault(false)
                AppLogger.i(TAG, "Post-boot recorder recovery result: connected=$started reason=$reason")

                if (started) {
                    SilentFailureNotifier.clearRecorderUnavailable(applicationContext)
                    SilentFailureNotifier.checkSyncHealth(applicationContext)
                    mainHandler.post { finishRecoveryService() }
                } else {
                    SilentFailureNotifier.warnRecorderUnavailable(applicationContext)

                    val prefs = AppPreferences(applicationContext)
                    val needsManualWd = prefs.isOfflineRecordingEnabled() &&
                        !AdbShell.isLoopbackArmed(applicationContext) &&
                        !AdbShell.isWirelessDebuggingEnabled(applicationContext) &&
                        !AdbShell.hasWriteSecureSettings(applicationContext)
                    if (needsManualWd) {
                        AppLogger.w(
                            TAG,
                            "Recorder cannot bootstrap automatically on this ROM: loopback was cleared by reboot and " +
                                "WRITE_SECURE_SETTINGS is denied. Waiting for one manual Wireless-debugging toggle.",
                        )
                    }
                    // Deliberately do NOT stop. Wi-Fi or the user's WD toggle may arrive later and the
                    // registered observers will retry immediately.
                }
            } finally {
                recoveryInFlight.set(false)
            }
        }
    }

    private fun registerRecoveryObservers() {
        if (observersRegistered) return
        observersRegistered = true
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"),
                false,
                wirelessDebuggingObserver,
            )
        }.onFailure { AppLogger.w(TAG, "Could not watch Wireless debugging after boot: ${it.message}") }

        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm?.registerNetworkCallback(request, networkCallback)
        }.onFailure { AppLogger.w(TAG, "Could not watch Wi-Fi after boot: ${it.message}") }
    }

    private fun unregisterRecoveryObservers() {
        if (!observersRegistered) return
        observersRegistered = false
        runCatching { contentResolver.unregisterContentObserver(wirelessDebuggingObserver) }
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
    }

    private fun finishRecoveryService() {
        unregisterRecoveryObservers()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unregisterRecoveryObservers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(text)
            .build()

    companion object {
        private const val TAG = "CV:AdbConnectionService"
        private const val CHANNEL_ID = "adb_boot"
        private const val NOTIF_ID = 4713

        fun start(context: Context) {
            val intent = Intent(context, AdbConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
