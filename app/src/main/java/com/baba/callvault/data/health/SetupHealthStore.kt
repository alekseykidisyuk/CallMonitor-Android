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
    val sweepWatermark: Long = 0L,
    val observedCallEnds: List<Long> = emptyList()
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
        sweepWatermark = prefs.getLong(KEY_SWEEP_WATERMARK, 0L),
        observedCallEnds = readRing()
    )

    /** A call produced a usable recording: the setup is proven as of [atMillis], clearing any failure. */
    fun recordVerified(atMillis: Long, fingerprint: String) = prefs.edit {
        putLong(KEY_VERIFIED_AT, atMillis)
        putString(KEY_VERIFIED_FINGERPRINT, fingerprint)
        remove(KEY_FAILURE_AT); remove(KEY_FAILURE_REASON); remove(KEY_FAILURE_LABEL)
    }

    /** A call was handled but produced nothing usable. The last verification date is left intact. */
    fun recordFailure(atMillis: Long, reason: FailureReason, label: String?) = prefs.edit {
        putLong(KEY_FAILURE_AT, atMillis)
        putString(KEY_FAILURE_REASON, reason.name)
        putString(KEY_FAILURE_LABEL, label)
    }

    /** Remembers that a call ended at [endedAtMillis], whatever came of it. Keeps the newest [RING_SIZE]. */
    fun observeCall(endedAtMillis: Long) {
        val ends = (listOf(endedAtMillis) + readRing()).distinct().sortedDescending().take(RING_SIZE)
        prefs.edit { putString(KEY_OBSERVED_ENDS, ends.joinToString(",")) }
    }

    fun setSweepWatermark(millis: Long) = prefs.edit { putLong(KEY_SWEEP_WATERMARK, millis) }

    private fun readRing(): List<Long> =
        prefs.getString(KEY_OBSERVED_ENDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.sortedDescending()
            .orEmpty()

    private companion object {
        const val FILE_NAME = "cv_setup_health"
        const val RING_SIZE = 20
        const val KEY_VERIFIED_AT = "last_verified_at"
        const val KEY_VERIFIED_FINGERPRINT = "verified_fingerprint"
        const val KEY_FAILURE_AT = "last_failure_at"
        const val KEY_FAILURE_REASON = "last_failure_reason"
        const val KEY_FAILURE_LABEL = "last_failure_label"
        const val KEY_SWEEP_WATERMARK = "sweep_watermark"
        const val KEY_OBSERVED_ENDS = "observed_call_ends"
    }
}
