/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger

/**
 * Holds a capture track created ahead of time, so a call does not wait for the daemon.
 *
 * The daemon creates a `VOICE_CALL` track while privileged and hands it over **stopped**; the app keeps
 * it and simply starts it when a call begins. Capture permission is checked at creation, not at start,
 * so once the track is held the daemon is no longer needed at call time — measured on a OnePlus 12 at
 * −6.3 dBFS with the creating daemon killed by pid.
 *
 * This exists because a dead daemon costs an 18-second cold start, which is longer than a short call:
 * a real outgoing call was lost that way. Arming happens off the call path, so its cost is invisible.
 *
 * **Held stopped, never started.** A started track would pin the vendor HAL's recording use-case (the
 * call then records silent) and would cost a permanent wakelock and a 24/7 microphone indicator
 * attributed to "Shell". Stopped costs none of that; the price is that the track can be evicted, which
 * is why every caller must treat [isUsable] as the truth rather than assuming.
 */
object HeldTrackStore {

    private const val TAG = "CV:HeldTrack"

    @Volatile private var arming = false
    @Volatile private var binder: IBinder? = null
    @Volatile private var cblk: ParcelFileDescriptor? = null
    @Volatile private var geometry: HandoffGeometry? = null

    /** True while waiting for a delivery, so [HandoffReceiver] routes it here instead of recording. */
    val isArming: Boolean get() = arming

    /**
     * Whether a usable track is held **right now**.
     *
     * Pings the binder rather than trusting a stored flag: the track can be evicted at any time
     * (`VOICE_CALL` has the lowest input priority on this device), and a stale "yes" would send a call
     * down the instant path with nothing behind it.
     */
    fun isUsable(): Boolean {
        val b = binder ?: return false
        if (cblk == null || geometry == null) return false
        return runCatching { b.pingBinder() }.getOrDefault(false)
    }

    /** Called by [HandoffReceiver] when a delivery arrives while [arm] is waiting for one. */
    fun onHandoff(handed: IBinder, cblkFd: ParcelFileDescriptor?, frameCount: Int, rate: Int, channels: Int) {
        if (!AudioHandoffNative.ensureLoaded()) {
            AppLogger.w(TAG, "arm: native library unavailable in the app")
            return
        }
        val g = cblkFd?.let {
            HandoffGeometry.of(frameCount, rate, channels, AudioHandoffNative.nativeAshmemSize(it.fd))
        }
        val invalid = g?.validationError()
        if (g == null || invalid != null) {
            AppLogger.w(TAG, "arm: unusable geometry (${invalid ?: "no cblk"})")
            runCatching { cblkFd?.close() }
            return
        }
        binder = handed
        cblk = cblkFd
        geometry = g
        AppLogger.i(TAG, "armed: track held stopped (${g.wrapFrames} frames, ${g.sampleRate}Hz, ${g.channels}ch)")
    }

    /**
     * Asks the daemon for a track and holds it. Call OFF the call path — it needs the daemon, which is
     * the very dependency this feature removes from call time.
     *
     * @return true when a usable track is held afterwards.
     */
    fun arm(sampleRate: Int, channels: Int): Boolean {
        if (isUsable()) return true
        val service = RecorderConnection.service ?: run {
            AppLogger.i(TAG, "arm: no daemon yet; will arm when it connects")
            return false
        }
        release()
        arming = true
        val delivered = runCatching { service.startHandoffHeld("voice-call", sampleRate, channels) }
            .onFailure { AppLogger.w(TAG, "arm: startHandoffHeld failed: ${it.message}") }
            .getOrDefault(false)
        arming = false
        val ok = isUsable()
        if (!ok) AppLogger.w(TAG, "arm: no usable track (delivered=$delivered)")
        return ok
    }

    /** The held pieces, or null when nothing usable is held. */
    fun held(): Triple<IBinder, Int, HandoffGeometry>? {
        if (!isUsable()) return null
        val b = binder ?: return null
        val fd = cblk?.fd ?: return null
        val g = geometry ?: return null
        return Triple(b, fd, g)
    }

    /** Stops and drops the held track. Safe to call when nothing is held. */
    fun release() {
        binder?.let { runCatching { HeldRecordControl.stop(it) } }
        runCatching { cblk?.close() }
        binder = null
        cblk = null
        geometry = null
    }
}
