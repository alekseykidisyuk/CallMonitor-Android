/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

/** One call as the system call log reports it. Android types deliberately absent so this stays testable. */
data class CallLogEntry(
    val startedAt: Long,
    val durationSeconds: Long,
    val isIncoming: Boolean,
    val label: String?
)

/** A call that should have been recorded and that CallVault never observed. */
data class CallGap(val startedAt: Long, val label: String?)

data class SweepResult(val gaps: List<CallGap>, val newWatermark: Long)

/**
 * Finds calls CallVault never saw — the daemon-dead case, which produces no end-of-call event and so
 * cannot be caught by outcome recording alone.
 *
 * The join is on "did we observe this call", never "does a file exist": a user who deletes recordings
 * must not manufacture failures. Two refusals matter more than the matching itself — a call the ring
 * is too short to remember is not judged, and neither is one below the answer floor.
 */
object CallGapDetector {

    /** Half-width of the window in which an observed end counts as the same call. */
    private const val TOLERANCE_MILLIS = 90_000L

    /** Answered calls shorter than this are ignored: the largest false-alarm source, and least useful. */
    private const val MIN_ANSWERED_SECONDS = 5L

    private const val MILLIS_PER_SECOND = 1_000L

    fun sweep(
        entries: List<CallLogEntry>,
        observedCallEnds: List<Long>,
        autoRecordIncoming: Boolean,
        autoRecordOutgoing: Boolean,
        watermark: Long
    ): SweepResult {
        val newWatermark = entries.maxOfOrNull { it.startedAt }?.coerceAtLeast(watermark) ?: watermark
        val oldestRemembered = observedCallEnds.minOrNull()
            ?: return SweepResult(emptyList(), newWatermark) // nothing remembered → judge nothing

        val gaps = entries
            .filter { it.startedAt > watermark }
            .filter { it.startedAt >= oldestRemembered }
            .filter { it.durationSeconds >= MIN_ANSWERED_SECONDS }
            .filter { if (it.isIncoming) autoRecordIncoming else autoRecordOutgoing }
            .filterNot { entry ->
                val expectedEnd = entry.startedAt + entry.durationSeconds * MILLIS_PER_SECOND
                observedCallEnds.any { end -> kotlin.math.abs(end - expectedEnd) <= TOLERANCE_MILLIS }
            }
            .map { CallGap(it.startedAt, it.label) }

        return SweepResult(gaps, newWatermark)
    }
}
