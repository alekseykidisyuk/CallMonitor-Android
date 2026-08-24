/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.PrivilegedMode

/**
 * How an app (VoIP) call gets captured, which differs by privileged mode.
 *
 * **Standalone — [POLICY_LOOPBACK].** CallVault's own design: a dynamic audio policy loops the far party
 * back while a plain mic takes the near one, interleaved as separate channels. Proven two-sided on a real
 * WhatsApp call. Its cost is that the policy must be **armed before the call's audio track exists**, so a
 * call that arrives while the daemon is absent is unrecoverable.
 *
 * **Shizuku — [SYSTEM_OUTPUT_MIX].** The policy design cannot work there: it is built from `AudioRecord`s
 * inside the recorder process, and one cannot reach RECORDING state in a Shizuku-hosted process. This is
 * where **Ever-Call-Recorder** (a sibling fork of the same upstream) has the better answer — it forces
 * scrcpy's `output` source for app calls, which runs in scrcpy's own process and therefore works.
 *
 * Their reasoning, which matches what this project learned independently:
 *
 *  - `playback` (AudioPlaybackCaptureConfiguration) **hard-excludes** audio tagged
 *    `USAGE_VOICE_COMMUNICATION` at the platform level — exactly how calling apps tag call audio — so it
 *    records silence no matter how privileged the caller is.
 *  - Any **mic-class** source competes with the calling app's own microphone session, and Android
 *    silences one of them for privacy. CallVault met the same wall from the other side: a second voice
 *    `AudioRecord` during a carrier recording silently drops the user's own side.
 *  - `output` (REMOTE_SUBMIX) is a privileged system-mix tap gated on `CAPTURE_AUDIO_OUTPUT` rather than
 *    a microphone capture, and predates the `USAGE_VOICE_COMMUNICATION` exclusion.
 *
 * **Not yet verified two-sided by us.** Ever-Call-Recorder advertises both sides; CallVault's own VoIP
 * work needed *two* sources to get both, so whether the system mix alone carries the near party is a
 * question for a real call, not for reasoning. Until then this is the difference between recording an app
 * call under Shizuku and not recording it at all.
 */
enum class VoipCapturePlan(
    /** Whether the capture must be set up before the call's audio exists. */
    val needsArmingBeforeCall: Boolean,
    /** The scrcpy `audio_source=` key this plan records with, or null when it does not use scrcpy. */
    val scrcpySourceKey: String?,
) {
    POLICY_LOOPBACK(needsArmingBeforeCall = true, scrcpySourceKey = null),
    SYSTEM_OUTPUT_MIX(needsArmingBeforeCall = false, scrcpySourceKey = "output"),
    ;

    companion object {
        fun forMode(mode: PrivilegedMode): VoipCapturePlan =
            if (mode.needsShizuku) SYSTEM_OUTPUT_MIX else POLICY_LOOPBACK
    }
}
