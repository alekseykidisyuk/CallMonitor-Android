/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import java.util.Locale

/** Formats a position within a call, for the timestamp beside each transcript line. */
object TranscriptTimestamp {

    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L

    /**
     * Renders [millis] as `m:ss`, or `h:mm:ss` once a call passes an hour.
     *
     * Truncates rather than rounds: a timestamp is a seek target, and rounding up would point past the
     * start of the very line it labels. Hours are kept rather than wrapped, so a ninety-minute call
     * does not show "0:30" at its midpoint.
     */
    fun format(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L)) / MILLIS_PER_SECOND
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        val minutes = totalMinutes % MINUTES_PER_HOUR
        val hours = totalMinutes / MINUTES_PER_HOUR

        // Locale.ROOT: this is a digital clock reading, not localised prose, and must not pick up
        // locale-specific digits that would not line up in a column.
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
