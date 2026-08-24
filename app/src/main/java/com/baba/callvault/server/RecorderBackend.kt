/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.Context
import android.os.SystemClock
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.utils.AppLogger

/**
 * The one way to get a recorder running, whichever backend the user chose.
 *
 * Everything that needs the daemon — app start, a boot, an incoming call, the offline path, a
 * post-update relaunch — calls [ensureRunning] and does not care how it is served. The mode lives in
 * [AppPreferences.getPrivilegedMode]; the two implementations are [RecorderServerLauncher] (our own
 * ADB daemon) and [ShizukuBackend].
 *
 * Deliberately the same shape as `RecorderServerLauncher.ensureServerRunning`: blocking, returns
 * whether a usable binder is now in [RecorderConnection]. The call sites predate Shizuku and should not
 * have to be restructured to gain it.
 */
object RecorderBackend {

    private const val TAG = "CV:RecorderBackend"

    /** How long to wait for Shizuku to hand back a binder. Its bind is asynchronous. */
    private const val SHIZUKU_BIND_TIMEOUT_MS = 10_000L

    private const val POLL_MS = 100L

    /**
     * How long to wait for a torn-down recorder's binder to actually die before starting the next one.
     * Generous: getting this wrong reports the wrong backend as ready, which is worse than a slow switch.
     */
    private const val TEARDOWN_TIMEOUT_MS = 5_000L

    /**
     * Makes sure a recorder is running and its binder is in [RecorderConnection].
     *
     * @return true when a binder is available, false when it could not be obtained — a normal outcome
     *   for Shizuku mode on a phone where Shizuku is not running, and never an exception.
     */
    fun ensureRunning(context: Context, timeoutMs: Long = 24_000): Boolean {
        val mode = AppPreferences(context).getPrivilegedMode()
        return when (BackendChoice.of(mode)) {
            BackendChoice.ADB -> RecorderServerLauncher.ensureServerRunning(context, timeoutMs).also { up ->
                // Symmetric with the Shizuku branch: our daemon clears any Shizuku-hosted recorder left
                // over from a previous mode. Shizuku's own removal cannot reach a service started by an
                // older app version (its args no longer match), so this is the only thing that can.
                if (up) {
                    runCatching { RecorderConnection.service?.killStaleRecorders() }
                        .onFailure { AppLogger.w(TAG, "Could not clear stale Shizuku recorders: ${it.message}") }
                }
            }
            BackendChoice.SHIZUKU -> ensureShizukuRunning(context)
        }
    }

    /**
     * Moves to [to], stopping whatever the old mode had running first.
     *
     * The order is the point. Two shell-uid recorders would compete for the same audio input and the
     * loser is not predictable, so the old one is stopped and only then is the new one started.
     */
    fun switchTo(context: Context, to: PrivilegedMode) {
        val prefs = AppPreferences(context)
        val from = prefs.getPrivilegedMode()

        when (BackendChoice.toTearDown(from, to)) {
            BackendChoice.ADB -> {
                AppLogger.i(TAG, "Leaving standalone mode; stopping our daemon")
                runCatching { RecorderConnection.service?.destroy() }
                    .onFailure { AppLogger.w(TAG, "Could not stop the daemon: ${it.message}") }
            }
            BackendChoice.SHIZUKU -> {
                AppLogger.i(TAG, "Leaving Shizuku mode; releasing the user service")
                // Ask the service ITSELF to exit first, exactly as the ADB branch above does.
                //
                // `stop(remove = true)` only asks *Shizuku* to destroy the service, and measured on the
                // OP9 it did not: the process was still alive 30s later, so its binder never died, the
                // teardown wait below ran out its full 5s, and ensureServerRunning then "reused" that
                // very binder — leaving the app in STANDALONE mode talking to a Shizuku-hosted recorder
                // while the switch dialog said "Ready — using CallVault". Silently, that costs every
                // standalone-only feature at once: handoff, VoIP arming and speaker attribution all
                // quietly do nothing, because capture is really going through scrcpy.
                //
                // We hold that binder and destroy() exits the process, so this is the one teardown that
                // does not depend on another app acting on our behalf.
                runCatching { RecorderConnection.service?.destroy() }
                    .onFailure { AppLogger.d(TAG, "The user service did not answer destroy(): ${it.message}") }
                runCatching { ShizukuBackend.stop(remove = true) }
                    .onFailure { AppLogger.w(TAG, "Could not stop the Shizuku service: ${it.message}") }
            }
            null -> {
                AppLogger.d(TAG, "Mode unchanged ($to); leaving the running recorder alone")
                return
            }
        }

        // Wait for the old recorder to actually be GONE before anyone asks whether one is running.
        //
        // destroy()/unbind only *ask*; the binder's death arrives asynchronously. Measured on the OP9:
        // the mode switch asked the ADB daemon to die at 16:11:56.731, and 3ms later ensureRunning saw
        // RecorderConnection still connected, reported "already connected; reusing existing binder",
        // and declared the switch ready — about the very daemon it had just killed. Shizuku was never
        // bound. The death landed 17ms after that, far too late to matter.
        val clearedBy = SystemClock.elapsedRealtime() + TEARDOWN_TIMEOUT_MS
        while (RecorderConnection.isConnected && SystemClock.elapsedRealtime() < clearedBy) {
            Thread.sleep(POLL_MS)
        }
        if (RecorderConnection.isConnected) {
            // Do NOT carry this binder into the new mode. It belongs to the host we just tore down, and
            // keeping it is how the app ended up in standalone mode recording through a Shizuku service
            // — reported as "Ready — using CallVault", with handoff, VoIP arming and speaker
            // attribution all silently absent. Dropping it makes the next ensureRunning start the
            // backend the user actually chose, or fail honestly.
            AppLogger.w(TAG, "The previous recorder is still connected after ${TEARDOWN_TIMEOUT_MS}ms")
            RecorderConnection.forceClear("it belongs to $from, and we are switching to $to")
        } else {
            AppLogger.i(TAG, "Previous recorder is gone; starting the $to backend")
        }

        prefs.setPrivilegedMode(to)

        // Entering Shizuku mode starts from a clean slate: drop any existing user-service record so the
        // next bind spawns a FRESH process running current code. A daemon(true) service survives app
        // updates, and an old one cannot clean up after itself — it predates the code that knows how —
        // so asking it to is useless. Done only on entering the mode, never on an ordinary start, which
        // would kill a warm recorder (possibly mid-call) for no reason.
        if (to.needsShizuku) {
            runCatching { ShizukuBackend.stop(remove = true) }
                .onFailure { AppLogger.d(TAG, "No previous Shizuku service to drop: ${it.message}") }

            // Stop the keep-alive outright rather than trusting it to notice. It only checks the mode
            // in onStartCommand, and a service already running when the switch happens never gets
            // another one — so it kept running as a foreground service in Shizuku mode, holding a
            // notification and polling for a daemon that must not exist. Found on the OP9.
            runCatching { DaemonKeepAliveService.stop(context) }
                .onFailure { AppLogger.w(TAG, "Could not stop the keep-alive: ${it.message}") }
        }

        // Turn off what the new mode cannot honour, rather than leaving switches on that promise
        // something they cannot deliver. Two of them (resilient recording, VoIP) previously produced
        // silent EMPTY recordings when the mode could not deliver, which is the worst possible shape
        // for a call recorder to fail in.
        // Put back what a previous switch took away, now that this mode can honour it again. Only ever
        // switches WE turned off, so the round trip is lossless without ever enabling something the user
        // turned off themselves. Before this, trying Shizuku once and coming straight back left resilient
        // recording, VoIP and offline recording off for good, and nothing said so.
        val restored = prefs.restoreWhatModeCanDoAgain(to)
        if (restored.isNotEmpty()) {
            AppLogger.i(TAG, "Turned back on (supported again in $to): ${restored.joinToString()}")
        }

        val turnedOff = prefs.disableWhatModeCannotDo(to)
        if (turnedOff.isNotEmpty()) {
            AppLogger.i(TAG, "Turned off (unsupported in $to): ${turnedOff.joinToString()}")
        }
        AppLogger.i(TAG, "Privileged mode is now $to")
    }

    /**
     * Whether the chosen mode can actually serve a recorder right now, without starting anything.
     *
     * What the status row on Settings reports. Standalone's readiness is a whole wizard's worth of
     * state and is reported elsewhere; this answers only the Shizuku half honestly.
     */
    fun shizukuStatus(context: Context): ShizukuStatus = when {
        !ShizukuBackend.isInstalled(context) -> ShizukuStatus.NOT_INSTALLED
        !ShizukuBackend.isRunning() -> ShizukuStatus.NOT_RUNNING
        !ShizukuBackend.hasPermission() -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }

    private fun ensureShizukuRunning(context: Context): Boolean {
        if (RecorderConnection.isConnected) {
            AppLogger.d(TAG, "Recorder already connected; reusing existing binder")
            return true
        }

        val status = shizukuStatus(context)
        if (status != ShizukuStatus.READY) {
            AppLogger.i(TAG, "Shizuku cannot serve a recorder right now: $status")
            return false
        }

        if (!ShizukuBackend.start()) return false

        // The bind is asynchronous: Shizuku starts the process, then calls back. Poll the holder the
        // callback fills, exactly as the ADB path polls for its pushed binder.
        val deadline = SystemClock.elapsedRealtime() + SHIZUKU_BIND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RecorderConnection.isConnected) {
                // Our own detached ADB daemon survives the app and is not stopped by anything here —
                // so without this, both backends run and either may hold the binder the app talks to.
                // Measured on the OP9: an app in Shizuku mode recorded through the leftover daemon.
                runCatching { RecorderConnection.service?.killStaleRecorders() }
                    .onFailure { AppLogger.w(TAG, "Could not clear stale ADB daemons: ${it.message}") }
                return true
            }
            Thread.sleep(POLL_MS)
        }
        AppLogger.w(TAG, "Shizuku did not hand back a recorder binder within ${SHIZUKU_BIND_TIMEOUT_MS}ms")
        return false
    }
}

/** Why Shizuku mode can or cannot serve a recorder, in the order a user would fix them. */
enum class ShizukuStatus {
    /** No Shizuku app on the phone at all. */
    NOT_INSTALLED,

    /** Installed, but its server is not running — it must be started after every reboot. */
    NOT_RUNNING,

    /** Running, but CallVault has not been allowed to use it. */
    NO_PERMISSION,

    /** Running and permitted. */
    READY,
}
