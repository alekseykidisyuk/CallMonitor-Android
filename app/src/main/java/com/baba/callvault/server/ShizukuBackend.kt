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
     * The app's own versionCode, which is what ShizuCallRecorder uses and what this must be.
     *
     * Shizuku kills and restarts a user service whose version differs from the running one, so tying
     * this to the app version makes **every app update restart the service**. A hardcoded constant does
     * not, and that is not a tidiness point — it cost a silent empty recording on a real call:
     *
     * A `daemon(true)` service outlives the app, including across an update. An update moves the APK to
     * a new hashed directory, so the surviving service was still holding the OLD path; when direct
     * capture failed and it reached for the scrcpy jar, `ZipFile` threw on a file that no longer existed,
     * both capture paths were gone, and the call recorded nothing while every layer reported success.
     */
    private val SERVICE_VERSION = com.baba.callvault.BuildConfig.VERSION_CODE

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
    fun start(): Boolean = synchronized(bindLock) { startLocked() }

    /**
     * Guards [start]. Without it two callers — the app-start warmup and the first call, say — can both
     * find `connection == null` and both call `bindUserService`, which leaves TWO user services running
     * and the app bound to whichever answered last. Observed on the OP9: two
     * `com.baba.callvault:recorder` processes and "Shizuku started the recorder service" logged twice
     * within milliseconds.
     */
    private val bindLock = Any()

    private fun startLocked(): Boolean {
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
    fun stop(remove: Boolean = true): Unit = synchronized(bindLock) {
        // NOT `connection ?: return`. A daemon(true) user service outlives the app process, so after any
        // restart this process has no connection to a service that is still very much running — and
        // returning early there left it alive while the ADB daemon started alongside it. Measured on the
        // OP9: two com.baba.callvault:recorder processes AND our own daemon, after a single round trip.
        //
        // Shizuku matches user services by their args, not by the connection object, so a throwaway
        // connection is enough to remove one this process never bound.
        val conn = connection ?: NO_OP_CONNECTION
        runCatching { Shizuku.unbindUserService(userServiceArgs, conn, remove) }
            .onFailure { AppLogger.w(TAG, "unbindUserService failed: ${it.message}") }
        connection = null
        RecorderConnection.onBinderDied()
    }
}

/**
 * Stands in for a connection this process never made, so a service left running by a previous process
 * can still be removed. Its callbacks are never expected to fire.
 */
private val NO_OP_CONNECTION = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
    override fun onServiceDisconnected(name: ComponentName?) = Unit
}

/** Indirection so the args above do not drag a BuildConfig import through every file that reads them. */
private object BuildConfigPackage {
    val name: String get() = com.baba.callvault.BuildConfig.APPLICATION_ID
}
