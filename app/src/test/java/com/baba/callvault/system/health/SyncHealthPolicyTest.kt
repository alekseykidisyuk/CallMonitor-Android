/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import com.baba.callvault.data.SyncScheduleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHealthPolicyTest {

    private val now = 1_000L * 60 * 60 * 24 * 100 // day 100

    private fun daysAgo(days: Int) = now - days * 24L * 60L * 60L * 1000L

    @Test
    fun `counts a recording that has waited longer than the schedule allows`() {
        assertEquals(1, SyncHealthPolicy.countStalled(listOf(daysAgo(5)), SyncScheduleMode.DAILY, now))
    }

    @Test
    fun `does not count a recording still within its window`() {
        assertEquals(0, SyncHealthPolicy.countStalled(listOf(daysAgo(1)), SyncScheduleMode.DAILY, now))
    }

    @Test
    fun `a weekly schedule tolerates a week of waiting`() {
        // The false positive this threshold exists to prevent: on WEEKLY, six days device-only is
        // correct behaviour, and warning about it would fire every week for ever. A warning that
        // cries wolf is worse than none — it trains the user to swipe away the one that matters.
        assertEquals(0, SyncHealthPolicy.countStalled(listOf(daysAgo(6)), SyncScheduleMode.WEEKLY, now))
    }

    @Test
    fun `a weekly schedule still warns once it is truly overdue`() {
        assertEquals(1, SyncHealthPolicy.countStalled(listOf(daysAgo(12)), SyncScheduleMode.WEEKLY, now))
    }

    @Test
    fun `never counts an undated recording`() {
        // A missing stamp is not evidence of age. Counting it would send the user hunting for a
        // problem that is not there.
        assertEquals(0, SyncHealthPolicy.countStalled(listOf(0L), SyncScheduleMode.IMMEDIATE, now))
    }

    @Test
    fun `counts only the overdue ones in a mixed library`() {
        assertEquals(
            2,
            SyncHealthPolicy.countStalled(
                listOf(daysAgo(30), daysAgo(10), daysAgo(1), 0L), SyncScheduleMode.DAILY, now
            )
        )
    }

    @Test
    fun `every schedule leaves headroom past its own cycle`() {
        // The invariant behind the numbers: a threshold at or below the cycle length would warn
        // about recordings that are simply waiting their turn.
        assertTrue(SyncHealthPolicy.staleAfterDays(SyncScheduleMode.DAILY) > 1)
        assertTrue(SyncHealthPolicy.staleAfterDays(SyncScheduleMode.WEEKLY) > 7)
    }
}
