/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Whether Wireless debugging can be switched off once the daemon is running.
 *
 * CallVault's principle is "Wireless debugging only while it is needed" — but switching it off is not
 * always free. **`adbd` shuts down when its last transport is removed**, and the daemon is a child of an
 * `adbd` shell session, so turning off the *only* transport kills the daemon we just launched. Measured
 * on a Galaxy S24 FE: the daemon died 50-90 ms after each disable, the keep-alive relaunched it, the
 * relaunch re-enabled Wireless debugging, and the cycle repeated six times over two minutes with the app
 * never reaching "ready to record". The same code looked fine on a OnePlus only because USB debugging
 * was enabled there — a second transport that kept `adbd` alive and masked the bug entirely.
 *
 * So the rule is: **never remove `adbd`'s last transport while the daemon has to stay alive.** This
 * decision is kept free of `Context` so it can be tested exhaustively, which matters because every
 * branch here was a real device behaviour rather than a guess.
 */
enum class WirelessDebuggingPlan {
    /** Another transport (USB debugging) keeps `adbd` alive — safe to switch Wireless debugging off. */
    DROP_USB_KEEPS_ADBD,

    /** The loopback listener keeps `adbd` alive — safe to switch Wireless debugging off. */
    DROP_LOOPBACK_KEEPS_ADBD,

    /**
     * Wireless debugging is the only transport, so it must stay on for the daemon to survive. The user
     * is told, and pointed at the two settings that would let it be switched off.
     */
    KEEP_ONLY_TRANSPORT,
}

object WirelessDebuggingPolicy {

    /**
     * Decides what to do with Wireless debugging now that the daemon is connected.
     *
     * Loopback is preferred in the reported reason when both are available: it is the one CallVault can
     * rely on when the user is away from a computer, whereas USB debugging is incidental.
     */
    fun plan(isUsbDebuggingEnabled: Boolean, isLoopbackArmed: Boolean): WirelessDebuggingPlan = when {
        isLoopbackArmed -> WirelessDebuggingPlan.DROP_LOOPBACK_KEEPS_ADBD
        isUsbDebuggingEnabled -> WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD
        else -> WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT
    }

    /** Whether Wireless debugging has to stay on — i.e. the user should be told why. */
    fun mustKeepWirelessDebugging(plan: WirelessDebuggingPlan): Boolean =
        plan == WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT
}
