/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.os.Bundle
import android.os.IBinder
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger

/**
 * Answers the one question Track A turns on: **can the app start a capture track it was handed, itself?**
 *
 * Capture permission is checked when a track is CREATED, not when it is started, and a track created
 * before a call and started at call-connect has been measured delivering real audio (−6.7 dBFS against
 * a −5.6 dBFS control). So if AudioFlinger accepts a `start` from the app's uid on a track the daemon
 * created, the daemon becomes a once-per-boot track factory and leaves the call path entirely — no ADB,
 * no debugging switch, and no 18-second cold start when a call arrives.
 *
 * If it refuses, Track A cannot remove the daemon from the call path and we stop there. Either answer
 * is worth having; neither is worth guessing at.
 *
 * Deliberately a probe rather than a feature: it runs only when invoked explicitly through the
 * provider, does no recording, and holds nothing afterwards.
 */
object TrackAProbe {

    private const val TAG = "CV:TrackAProbe"

    @Volatile private var armed = false
    @Volatile private var handedBinder: IBinder? = null

    /** True while a probe is waiting for its handoff, so the receiver routes it here instead of recording. */
    val isArmed: Boolean get() = armed

    /** Called by [HandoffReceiver] when a delivery arrives during a probe. */
    fun onHandoff(binder: IBinder) {
        handedBinder = binder
        AppLogger.i(TAG, "probe received the handed-over track")
    }

    /**
     * Asks the daemon for a **stopped** track, then tries to start it from here — the app's process,
     * the app's uid. Returns a bundle the caller can read straight from `adb shell content call`.
     */
    fun run(): Bundle {
        val result = Bundle()
        val service = RecorderConnection.service
        if (service == null) {
            result.putString("result", "NO_DAEMON — the daemon must be connected to create the track")
            return result
        }

        armed = true
        handedBinder = null
        val delivered = runCatching { service.startHandoffHeld("voice-call", 48_000, 2) }
            .onFailure { AppLogger.w(TAG, "startHandoffHeld threw: ${it.message}") }
            .getOrDefault(false)
        armed = false

        val binder = handedBinder
        if (!delivered || binder == null) {
            result.putString("result", "NO_HANDOFF — daemon did not deliver a track (delivered=$delivered)")
            return result
        }

        // The measurement. A refusal here means AudioFlinger validates the CALLING uid rather than the
        // one registered when the track was created — which would end Track A.
        val started = HeldRecordControl.start(binder)
        val verdict = if (started) {
            "POSITIVE — the app started a track the daemon created. Track A can take the daemon off " +
                "the call path; next step is proving audio flows with the daemon killed."
        } else {
            "NEGATIVE — AudioFlinger refused a start from the app's uid, so the daemon is still needed " +
                "at call time and Track A cannot remove it. See the log for the exception."
        }
        AppLogger.i(TAG, verdict)

        // Leave nothing running: this is a permission probe, not a recording.
        runCatching { HeldRecordControl.stop(binder) }
        runCatching { RecorderConnection.service?.stopHandoff() }

        result.putBoolean("started", started)
        result.putString("result", verdict)
        return result
    }
}
