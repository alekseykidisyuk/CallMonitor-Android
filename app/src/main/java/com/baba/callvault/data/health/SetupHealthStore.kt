/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.content.Context
import androidx.core.content.edit

/** Why a call failed to produce a usable recording. Persisted by name; unknown names read as null. */
enum class FailureReason { EMPTY_FILE, DAEMON_DIED, ONE_SIDED }

/**
 * Everything the status card knows about whether recording actually works. All timestamps are epoch
 * millis; 0 means "never".
 */
data class HealthFacts(
    val lastVerifiedAt: Long = 0L,
    val verifiedFingerprint: String? = null,
    val lastFailureAt: Long = 0L,
    val lastFailureReason: FailureReason? = null,
    val lastFailureLabel: String? = null,
    val lastGapAt: Long = 0L,
    val lastGapLabel: String? = null,
    val sweepWatermark: Long = 0L,
    val observedCallEnds: List<Long> = emptyList(),
    /** Epoch millis this install first became ABLE to record, or 0 if never established. */
    val observingSince: Long = 0L
)

/**
 * Persists what real calls proved. Its own preference file rather than more keys on AppPreferences,
 * which is already oversized (see the agreed split in docs/dev-notes/backlog.md).
 *
 * [observeCall] records that a call was seen AT ALL, whatever the outcome — it answers the sweep's
 * "did we observe this call", never "did it work". A call that failed loudly is still one the sweep
 * must not report as unseen.
 */
class SetupHealthStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): HealthFacts = HealthFacts(
        lastVerifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L),
        verifiedFingerprint = prefs.getString(KEY_VERIFIED_FINGERPRINT, null),
        lastFailureAt = prefs.getLong(KEY_FAILURE_AT, 0L),
        lastFailureReason = prefs.getString(KEY_FAILURE_REASON, null)?.let { name ->
            FailureReason.entries.firstOrNull { it.name == name }
        },
        lastFailureLabel = prefs.getString(KEY_FAILURE_LABEL, null),
        lastGapAt = prefs.getLong(KEY_GAP_AT, 0L),
        lastGapLabel = prefs.getString(KEY_GAP_LABEL, null),
        sweepWatermark = prefs.getLong(KEY_SWEEP_WATERMARK, 0L),
        observedCallEnds = readRing(),
        observingSince = prefs.getLong(KEY_OBSERVING_SINCE, 0L)
    )

    /**
     * A call produced a usable recording: the setup is proven as of [atMillis], clearing any failure
     * AND any gap — a later call proving things work again is exactly what retires either warning.
     */
    fun recordVerified(atMillis: Long, fingerprint: String) = prefs.edit {
        putLong(KEY_VERIFIED_AT, atMillis)
        putString(KEY_VERIFIED_FINGERPRINT, fingerprint)
        remove(KEY_FAILURE_AT); remove(KEY_FAILURE_REASON); remove(KEY_FAILURE_LABEL)
        remove(KEY_GAP_AT); remove(KEY_GAP_LABEL)
    }

    /** A call was handled but produced nothing usable. The last verification date is left intact. */
    fun recordFailure(atMillis: Long, reason: FailureReason, label: String?) = prefs.edit {
        putLong(KEY_FAILURE_AT, atMillis)
        putString(KEY_FAILURE_REASON, reason.name)
        putString(KEY_FAILURE_LABEL, label)
    }

    /**
     * Persists that a call CallVault never observed happened at [atMillis], surviving past the sweep
     * pass that found it. Without this, the gap lived only in that pass's transient result: the sweep's
     * own watermark advance would put the same entry below the watermark on the very next resume, so it
     * would never be found again and the warning would silently vanish. This is the only way the gap
     * outlives the sweep — it clears solely via [recordVerified].
     */
    fun recordGap(atMillis: Long, label: String?) = prefs.edit {
        putLong(KEY_GAP_AT, atMillis)
        putString(KEY_GAP_LABEL, label)
    }

    /**
     * Remembers that a call ended at [endedAtMillis], whatever came of it. Keeps the newest [RING_SIZE].
     * Synchronized: this reads the current ring before writing it back, and two concurrent callers
     * racing here could drop an entry — which reads as a false CallNotRecorded alarm rather than a
     * merely-missed one, exactly what this feature must never do.
     */
    @Synchronized
    fun observeCall(endedAtMillis: Long) {
        val ends = (listOf(endedAtMillis) + readRing()).distinct().sortedDescending().take(RING_SIZE)
        prefs.edit { putString(KEY_OBSERVED_ENDS, ends.joinToString(",")) }
    }

    fun setSweepWatermark(millis: Long) = prefs.edit { putLong(KEY_SWEEP_WATERMARK, millis) }

    /**
     * Returns the moment this install first became ABLE to record, establishing it now if it is
     * unset AND [isReady] is true. Once set, it never moves — a later loss of readiness (e.g. dev
     * options toggled off) must not erase the fact that recording once worked, or the gap sweep's
     * floor would silently retreat.
     *
     * Synchronized for the same reason as [observeCall]: this is a read-then-maybe-write, and two
     * racing callers could otherwise commit two different "first ready" moments.
     */
    @Synchronized
    fun observingSinceOrSet(isReady: Boolean, nowMillis: Long): Long {
        val existing = prefs.getLong(KEY_OBSERVING_SINCE, 0L)
        if (existing != 0L) return existing
        if (!isReady) return 0L
        prefs.edit { putLong(KEY_OBSERVING_SINCE, nowMillis) }
        return nowMillis
    }

    private fun readRing(): List<Long> =
        prefs.getString(KEY_OBSERVED_ENDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.sortedDescending()
            .orEmpty()

    companion object {
        /**
         * How many of the newest observed call ends [observeCall] keeps; the sweep's eviction
         * horizon. Public so callers of [CallGapDetector.sweep] can pass it as `ringCapacity`.
         */
        const val RING_SIZE = 20

        private const val FILE_NAME = "cv_setup_health"
        private const val KEY_VERIFIED_AT = "last_verified_at"
        private const val KEY_VERIFIED_FINGERPRINT = "verified_fingerprint"
        private const val KEY_FAILURE_AT = "last_failure_at"
        private const val KEY_FAILURE_REASON = "last_failure_reason"
        private const val KEY_FAILURE_LABEL = "last_failure_label"
        private const val KEY_GAP_AT = "last_gap_at"
        private const val KEY_GAP_LABEL = "last_gap_label"
        private const val KEY_SWEEP_WATERMARK = "sweep_watermark"
        private const val KEY_OBSERVED_ENDS = "observed_call_ends"
        private const val KEY_OBSERVING_SINCE = "observing_since"
    }
}
