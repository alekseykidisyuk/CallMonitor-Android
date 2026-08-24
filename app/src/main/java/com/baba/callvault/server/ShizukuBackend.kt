/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.baba.callvault.utils.AppLogger
import rikka.shizuku.Shizuku

/**
 * Starts the recorder through a Shizuku server instead of our own embedded ADB.
 *
 * The app-side mirror of [RecorderBinderProvider]: where that one *receives* a binder our daemon pushes,
 * this one asks Shizuku to start [RecorderUserService] and takes the binder Shizuku hands back. Both end
 * at [RecorderConnection.onBinderReceived], and nothing downstream can tell which ran.
 *
 * **Works with every Shizuku variant.** Stock Shizuku, Sui, and the thedjchi/symbuzzer forks all ship
 * `moe.shizuku.privileged.api` and the same `API_V23` permission, so none of this is variant-specific —
 * and nothing here should ever become so.
 *
 * Nothing in this object throws: a phone without Shizuku, with Shizuku stopped, or with permission
 * denied is an ordinary state for a feature that is off by default, not an error to propagate.
 */
object ShizukuBackend {

    private const val TAG = "CV:ShizukuBackend"

    /** Every Shizuku variant, including Sui and the community forks, uses this package name. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Request code for [requestPermission]; only has to be unique within the app. */
    const val PERMISSION_REQUEST_CODE = 5713

    /** Kept so the same args can unbind what they bound — Shizuku matches services by their args. */
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfigPackage.name, RecorderUserService::class.java.name))
            // Outlives the app process. The recorder must be up when a call arrives, which is often
            // long after anyone last opened CallVault — and Shizuku restarts a daemon service itself,
            // which is precisely the persistence our own daemon has to fight for.
            .daemon(true)
            .processNameSuffix("recorder")
            .version(SERVICE_VERSION)
    }

    /**
     * Bumped whenever [RecorderUserService] or anything it serves changes in a way a running service
     * would get wrong. Shizuku kills and restarts a user service whose version differs from the one
     * already running, which is the only defence against a stale service from the previous app version
     * answering with old code — the same hazard the ADB path handles by killing stale daemons.
     */
    private const val SERVICE_VERSION = 1

    @Volatile private var connection: ServiceConnection? = null

    /** Whether a Shizuku app is installed at all (needs the `<queries>` entry to see it). */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Whether a Shizuku server is running right now and reachable. */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Whether the user has granted CallVault permission to use Shizuku. */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Asks Shizuku for permission. The answer arrives on Shizuku's own listener, not from here. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { AppLogger.w(TAG, "requestPermission failed: ${it.message}") }
    }

    /**
     * Asks Shizuku to start the recorder, if it can.
     *
     * Returns false — without side effects — when Shizuku is absent, stopped, or unpermitted. The
     * binder does not arrive by the time this returns; it lands in [RecorderConnection] when Shizuku
     * answers, exactly as the daemon's own delivery does.
     */
    fun start(): Boolean {
        if (!isRunning()) {
            AppLogger.i(TAG, "Shizuku is not running; nothing to bind to")
            return false
        }
        if (!hasPermission()) {
            AppLogger.i(TAG, "Shizuku permission has not been granted")
            return false
        }
        if (connection != null) {
            AppLogger.d(TAG, "Already bound")
            return true
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) {
                    AppLogger.e(TAG, "Shizuku returned a dead binder for the recorder service")
                    return
                }
                AppLogger.i(TAG, "Shizuku started the recorder service")
                RecorderConnection.onBinderReceived(IRecorderService.Stub.asInterface(binder))
                // Same death handling the daemon path gets: the holder must clear itself, or callers
                // meet a DeadObjectException instead of an honest "not connected".
                runCatching { binder.linkToDeath(RecorderConnection.deathRecipient, 0) }
                    .onFailure { AppLogger.w(TAG, "linkToDeath failed: ${it.message}") }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                AppLogger.w(TAG, "Shizuku's recorder service disconnected")
                RecorderConnection.onBinderDied()
            }
        }

        return runCatching {
            Shizuku.bindUserService(userServiceArgs, conn)
            connection = conn
            true
        }.onFailure { AppLogger.e(TAG, "bindUserService failed: ${it.message}", it) }.getOrDefault(false)
    }

    /**
     * Stops the Shizuku-hosted recorder.
     *
     * [remove] `true` tells Shizuku to forget the daemon service entirely rather than leave it running
     * for the next bind — which is what switching back to standalone mode means, and what must happen
     * before the ADB path starts a second recorder that would fight this one for the audio input.
     */
    fun stop(remove: Boolean = true) {
        val conn = connection ?: return
        runCatching { Shizuku.unbindUserService(userServiceArgs, conn, remove) }
            .onFailure { AppLogger.w(TAG, "unbindUserService failed: ${it.message}") }
        connection = null
        RecorderConnection.onBinderDied()
    }
}

/** Indirection so the args above do not drag a BuildConfig import through every file that reads them. */
private object BuildConfigPackage {
    val name: String get() = com.baba.callvault.BuildConfig.APPLICATION_ID
}
