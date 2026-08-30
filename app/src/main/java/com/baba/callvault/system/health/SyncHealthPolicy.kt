/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import com.baba.callvault.data.SyncScheduleMode

/**
 * Whether recordings have stopped reaching Drive.
 *
 * This exists because of one specific way people lose everything: sync dies quietly — a revoked
 * grant, a folder deleted on the other side, a periodic job that stopped being scheduled — and
 * nothing says so. The recordings keep being made, the app keeps looking healthy, and it is only
 * discovered when the phone is replaced and the cloud copy everyone assumed existed does not.
 *
 * It is deliberately **symptom-based**: it asks "are there recordings that should be in Drive and are
 * not, and have they been that way too long", never "did the last upload fail". A cause-based check
 * only fires when an upload is actually attempted, which is exactly what does not happen in the case
 * worth catching. This one notices a sync that has stopped happening at all.
 */
object SyncHealthPolicy {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * How long a recording may sit with no Drive copy before that is evidence of a problem.
     *
     * Derived from the schedule, not a single constant. On WEEKLY it is entirely correct for a
     * recording to be device-only for six days, and a fixed three-day threshold would have warned
     * every single week — a warning that cries wolf is worse than none, because it trains the user
     * to swipe away the one that matters. Each value leaves a full cycle of headroom past the point
     * the copy should have happened.
     */
    fun staleAfterDays(mode: SyncScheduleMode): Int = when (mode) {
        SyncScheduleMode.IMMEDIATE -> 2
        SyncScheduleMode.DAILY -> 3
        SyncScheduleMode.WEEKLY -> 10
    }

    /**
     * How many of [unsyncedLastModified] have been waiting longer than [mode] allows.
     *
     * @param unsyncedLastModified last-modified stamps of recordings that have a device copy and no
     *                             Drive copy. A recording already in Drive is not passed in.
     */
    fun countStalled(unsyncedLastModified: List<Long>, mode: SyncScheduleMode, now: Long): Int {
        val cutoff = now - staleAfterDays(mode) * DAY_MS
        // An undated recording (0) is never counted, for the same reason the retention sweep never
        // deletes one: a stamp we do not have is not evidence of age, and manufacturing a warning
        // out of a metadata gap would send the user hunting for a problem that is not there.
        return unsyncedLastModified.count { it > 0L && it < cutoff }
    }
}
