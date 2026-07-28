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

/**
 * True for the states that should flip the card to a warning.
 *
 * [StaleAfterChange] is deliberately excluded: a setup change (e.g. an app update bumping the build's
 * version code) is informational, not proof that anything is broken. It just means the next call
 * hasn't re-confirmed the new configuration yet. Painting that as a warning would raise a false alarm
 * on every update, exactly the "cried wolf" outcome this whole feature exists to avoid.
 */
val SetupHealth.isProblem: Boolean
    get() = this is SetupHealth.LastCallFailed ||
        this is SetupHealth.CallNotRecorded

object SetupHealthDeriver {

    /**
     * First match wins, most urgent first: a call that vanished entirely, then a call that failed,
     * then a setup change that makes an old verification stop speaking for the current configuration.
     *
     * Both the gap and the failure are read from [facts] rather than passed in transiently: a gap or a
     * failure at or older than [HealthFacts.lastVerifiedAt] is spent — a later call proved things work
     * again — but it must still be POSSIBLE for either to outlive the sweep pass that found it. Passing
     * a transient "newest gap this pass" made the sweep's own watermark advance erase the warning it had
     * just raised, the moment the next resume re-swept past it.
     */
    fun derive(facts: HealthFacts, currentFingerprint: String): SetupHealth {
        if (facts.lastGapAt > facts.lastVerifiedAt) {
            return SetupHealth.CallNotRecorded(facts.lastGapAt, facts.lastGapLabel)
        }

        val reason = facts.lastFailureReason
        if (reason != null && facts.lastFailureAt > facts.lastVerifiedAt) {
            return SetupHealth.LastCallFailed(facts.lastFailureAt, reason)
        }
        if (facts.lastVerifiedAt <= 0L) return SetupHealth.Unverified
        if (facts.verifiedFingerprint != currentFingerprint) return SetupHealth.StaleAfterChange
        return SetupHealth.Verified(facts.lastVerifiedAt)
    }
}
