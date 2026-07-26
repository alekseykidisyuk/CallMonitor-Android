/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger

/**
 * Notices VoIP calls starting and ending.
 *
 * There is no `PHONE_STATE` broadcast for WhatsApp/Signal/Telegram, so instead we watch for a playback
 * stream tagged `USAGE_VOICE_COMMUNICATION` — the far party's voice — appearing while the device is in
 * `MODE_IN_COMMUNICATION`. Both conditions together are what distinguishes a call from, say, a voice
 * message being played back.
 *
 * This can run late without harm. The capture policy is armed when the feature is switched on (see
 * [VoipCaptureController]), and only the POLICY has to predate the call — the sink can be created on a
 * call already in progress. So detection is free to use ordinary public APIs rather than race the app.
 *
 * Hosted by [DaemonKeepAliveService], which is already a permanent foreground service, so this adds no
 * new background component and no second notification. VoIP detection therefore has exactly the same
 * lifetime as the rest of the recorder: if the app is alive enough to record a carrier call, it is
 * alive enough to record a VoIP one.
 */
class VoipCallDetector(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    /** True while we believe a VoIP call is up, so repeat callbacks don't restart the recording. */
    private var callActive = false

    /**
     * A call's players flicker as routes change (earpiece → speaker → Bluetooth), so a disappearance is
     * only treated as "call ended" once it has persisted. Otherwise a route change would end the
     * recording mid-conversation.
     */
    private val endCallRunnable = Runnable {
        if (callActive) {
            callActive = false
            AppLogger.i(TAG, "VoIP call ended")
            runCatching { VoipRecordingCoordinator.onCallEnded(context) }
                .onFailure { AppLogger.w(TAG, "VoIP stop failed: ${it.message}") }
        }
    }

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val voiceActive = configs.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
            } && audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION

            if (voiceActive && !callActive) {
                handler.removeCallbacks(endCallRunnable)
                callActive = true
                AppLogger.i(TAG, "VoIP call detected")
                runCatching { VoipRecordingCoordinator.onCallStarted(context) }
                    .onFailure { AppLogger.w(TAG, "VoIP start failed: ${it.message}") }
            } else if (!voiceActive && callActive) {
                handler.removeCallbacks(endCallRunnable)
                handler.postDelayed(endCallRunnable, END_DEBOUNCE_MS)
            }
        }
    }

    /** Starts watching, if the feature is on. Idempotent. */
    fun start() {
        if (registered || audioManager == null) return
        if (!AppPreferences(context).isVoipRecordingEnabled()) return
        runCatching {
            audioManager.registerAudioPlaybackCallback(callback, handler)
            registered = true
            AppLogger.i(TAG, "VoIP call detection active")
        }.onFailure { AppLogger.w(TAG, "Could not register playback callback: ${it.message}") }
    }

    /** Stops watching and ends any in-flight recording. Idempotent. */
    fun stop() {
        handler.removeCallbacks(endCallRunnable)
        if (callActive) endCallRunnable.run()
        if (!registered || audioManager == null) return
        runCatching { audioManager.unregisterAudioPlaybackCallback(callback) }
        registered = false
        AppLogger.i(TAG, "VoIP call detection stopped")
    }

    /** Re-reads the preference: starts watching when switched on, stops when switched off. */
    fun sync() {
        if (AppPreferences(context).isVoipRecordingEnabled()) start() else stop()
    }

    private companion object {
        const val TAG = "CV:VoipDetect"
        const val END_DEBOUNCE_MS = 1_500L
    }
}
