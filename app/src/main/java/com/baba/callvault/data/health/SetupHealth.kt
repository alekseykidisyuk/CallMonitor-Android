/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

/** What the status card has to say about whether recording actually works. */
sealed interface SetupHealth {
    /** No call has proved anything yet — a fresh install, or nothing since the setup changed. */
    data object Unverified : SetupHealth

    data class Verified(val atMillis: Long) : SetupHealth

    /** It worked, but the setup has changed since; the next call will confirm it still does. */
    data object StaleAfterChange : SetupHealth

    data class LastCallFailed(val atMillis: Long, val reason: FailureReason) : SetupHealth

    /** A call happened that CallVault never saw at all. */
    data class CallNotRecorded(val atMillis: Long, val label: String?) : SetupHealth
}

/** True for the states that should flip the card to a warning. */
val SetupHealth.isProblem: Boolean
    get() = this is SetupHealth.StaleAfterChange ||
        this is SetupHealth.LastCallFailed ||
        this is SetupHealth.CallNotRecorded

object SetupHealthDeriver {

    /**
     * First match wins, most urgent first: a call that vanished entirely, then a call that failed,
     * then a setup change that makes an old verification stop speaking for the current configuration.
     *
     * A failure older than the last verification is spent — a later call proved things work again.
     */
    fun derive(facts: HealthFacts, currentFingerprint: String, newestGap: CallGap?): SetupHealth {
        if (newestGap != null) return SetupHealth.CallNotRecorded(newestGap.startedAt, newestGap.label)

        val reason = facts.lastFailureReason
        if (reason != null && facts.lastFailureAt > facts.lastVerifiedAt) {
            return SetupHealth.LastCallFailed(facts.lastFailureAt, reason)
        }
        if (facts.lastVerifiedAt <= 0L) return SetupHealth.Unverified
        if (facts.verifiedFingerprint != currentFingerprint) return SetupHealth.StaleAfterChange
        return SetupHealth.Verified(facts.lastVerifiedAt)
    }
}
