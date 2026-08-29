/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.telecom.Call
import android.telecom.InCallService
import com.baba.callvault.utils.AppLogger

/**
 * A **spike**. It observes and records nothing; it exists to answer one question: will Telecom bind
 * us at all on these phones?
 *
 * **What it would be worth if the answer is yes.** Telecom binds a non-UI `InCallService` at
 * [onCallAdded] — the earliest moment a call exists — and hands over the number, the direction, the
 * state, and whether the call is self-managed (VoIP) or not. Today all four are *inferred*: from
 * `READ_PHONE_STATE` broadcasts, from the call log after the fact, and from guesswork about which
 * app is in the foreground. The VoIP/carrier confusion that inference produces has already caused a
 * shipped bug. BCR calls this "much more reliable than `READ_PHONE_STATE` + broadcasts", and adds
 * that the bind lifts the process to foreground — which would also attack the post-reboot cold-start
 * miss, for free.
 *
 * **Why a spike rather than the feature.** The permission this needs, `MANAGE_ONGOING_CALLS`, is
 * `signature|appop`, and the appop grant that our own upstream documents is **silently ignored by
 * both OnePlus ROMs** — `appops set` returns exit 0 and changes nothing. The only remaining route is
 * the companion-device role, which confers the role on Android 16 and does not on ColorOS 14. So
 * whether Telecom will bind us is genuinely unknown, and the cheapest way to find out is to declare
 * a service that does nothing and look.
 *
 * 🚨 **It must stay inert.** Nothing here may start, stop or influence a recording while the question
 * is open: a half-wired second source of call state, racing the existing one, is exactly how a
 * recording gets missed. Every callback logs and returns.
 *
 * Numbers are logged **redacted** — this is a diagnostic on a real person's phone, and the point of
 * the exercise is whether the callback arrives, not who was called.
 */
class CallVaultInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        AppLogger.i(TAG, "onCallAdded: ${describe(call)}")
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        AppLogger.i(TAG, "onCallRemoved: ${describe(call)}")
    }

    /**
     * What the bind actually gives us, in the terms we care about.
     *
     * `selfManaged` is the whole VoIP/carrier distinction, stated by the platform rather than
     * guessed — the single most valuable field here.
     */
    private fun describe(call: Call): String {
        val details = call.details
        val selfManaged = details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)
        val direction = when (details.callDirection) {
            Call.Details.DIRECTION_INCOMING -> "incoming"
            Call.Details.DIRECTION_OUTGOING -> "outgoing"
            else -> "unknown"
        }
        // Length only. A number is the one thing in a call record that identifies a person, and a
        // spike has no business writing it to a log that gets shared in bug reports.
        val handleDigits = details.handle?.schemeSpecificPart?.length ?: 0
        return "direction=$direction selfManaged=$selfManaged state=${call.details.state} " +
            "handleLength=$handleDigits"
    }

    private companion object {
        const val TAG = "CV:InCallSpike"
    }
}
