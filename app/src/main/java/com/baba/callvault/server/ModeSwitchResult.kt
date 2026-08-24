/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import androidx.annotation.StringRes
import com.baba.callvault.R
import com.baba.callvault.data.PrivilegedMode

/**
 * How a mode switch ended, in terms the dialog can show.
 *
 * **"Ready" means a live binder, nothing less.** Switching used to be silent — the toggle flipped, work
 * happened on a background thread, and nothing said whether a recorder was actually running afterwards.
 * On a phone that had both backends alive at once, that silence hid a real bug for hours.
 */
enum class ModeSwitchResult(@param:StringRes val messageRes: Int, val isReady: Boolean = false) {

    /** A recorder is running and its binder is in [RecorderConnection]. */
    Ready(R.string.mode_switch_ready, isReady = true),

    /** Shizuku mode was chosen on a phone with no Shizuku app. */
    ShizukuNotInstalled(R.string.mode_switch_shizuku_not_installed),

    /** Shizuku is installed but its server is not running — it stops on every reboot. */
    ShizukuNotRunning(R.string.mode_switch_shizuku_not_running),

    /** Shizuku is running but has not allowed CallVault to use it. */
    ShizukuNotPermitted(R.string.mode_switch_shizuku_not_permitted),

    /**
     * Everything the user controls is in place and the recorder still did not come up.
     *
     * Deliberately distinct from the three above: telling someone to start Shizuku when Shizuku is
     * already running sends them to fix something that is not broken.
     */
    RecorderDidNotStart(R.string.mode_switch_recorder_failed),

    ;

    companion object {
        /**
         * Judges the switch from what is true afterwards.
         *
         * @param connected whether a recorder binder is live now — the only thing that means success.
         * @param shizuku Shizuku's state, consulted **only** in Shizuku mode; in standalone it is a red
         *   herring and naming it would send the user somewhere irrelevant.
         */
        fun of(mode: PrivilegedMode, connected: Boolean, shizuku: ShizukuStatus): ModeSwitchResult {
            if (connected) return Ready
            if (!mode.needsShizuku) return RecorderDidNotStart
            return when (shizuku) {
                ShizukuStatus.NOT_INSTALLED -> ShizukuNotInstalled
                ShizukuStatus.NOT_RUNNING -> ShizukuNotRunning
                ShizukuStatus.NO_PERMISSION -> ShizukuNotPermitted
                ShizukuStatus.READY -> RecorderDidNotStart
            }
        }
    }
}
