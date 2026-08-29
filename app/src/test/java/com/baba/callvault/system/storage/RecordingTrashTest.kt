/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trash's whole state lives in a file name, so these are the rules that decide whether a deleted
 * recording can be brought back — and whether one gets destroyed early.
 *
 * Every failure here is silent and permanent, which is the reason for the density: a name that will
 * not parse back to the original means a recording that cannot be restored, and an expiry that
 * mis-reads a stamp means one deleted before its thirty days are up.
 */
class RecordingTrashTest {

    private val name = "20260829_101500_in_Dana.ogg"
    private val deletedAt = 1_756_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun `a trashed name round-trips back to exactly the original`() {
        // Our own names contain underscores, so a naive split would return "101500_in_Dana.ogg".
        val trashed = RecordingTrash.trashedName(name, deletedAt)

        assertEquals(name, RecordingTrash.originalName(trashed))
    }

    @Test
    fun `a trashed name is recognisable and a normal one is not`() {
        assertTrue(RecordingTrash.isTrashed(RecordingTrash.trashedName(name, deletedAt)))
        assertFalse(RecordingTrash.isTrashed(name))
        assertFalse(RecordingTrash.isTrashed(null))
    }

    @Test
    fun `the moment of deletion survives in the name`() {
        val trashed = RecordingTrash.trashedName(name, deletedAt)

        assertEquals(deletedAt, RecordingTrash.deletedAtMillis(trashed))
    }

    @Test
    fun `trashing something already trashed does not nest a second prefix`() {
        // Two passes would otherwise bury the original name one level deeper and make it
        // unrecoverable, which is the one thing this whole file exists to prevent.
        val once = RecordingTrash.trashedName(name, deletedAt)

        val twice = RecordingTrash.trashedName(once, deletedAt + 5_000)

        assertEquals(once, twice)
        assertEquals(name, RecordingTrash.originalName(twice))
    }

    @Test
    fun `a name that is not trashed has no original and no stamp`() {
        assertNull(RecordingTrash.originalName(name))
        assertNull(RecordingTrash.deletedAtMillis(name))
    }

    @Test
    fun `a malformed trashed name yields no original rather than a wrong one`() {
        // Better to leave it sitting in the trash for someone to rename by hand than to restore it
        // under a name that is not its own.
        assertNull(RecordingTrash.originalName("cvtrash_"))
        assertNull(RecordingTrash.originalName("cvtrash_123456"))
        assertNull(RecordingTrash.originalName("cvtrash_123456_"))
    }

    @Test
    fun `a recording is kept for the full retention period`() {
        val trashed = RecordingTrash.trashedName(name, deletedAt)

        assertFalse(RecordingTrash.isExpired(trashed, deletedAt))
        assertFalse(RecordingTrash.isExpired(trashed, deletedAt + 29 * day))
        assertTrue(RecordingTrash.isExpired(trashed, deletedAt + 30 * day))
    }

    @Test
    fun `a name with no readable stamp is never expired`() {
        // The stamp is the only evidence of when something was deleted. Treating its absence as "old"
        // would destroy a recording on the strength of a parsing failure.
        assertFalse(RecordingTrash.isExpired("cvtrash_notanumber_call.ogg", Long.MAX_VALUE))
        assertFalse(RecordingTrash.isExpired("cvtrash_", Long.MAX_VALUE))
    }

    @Test
    fun `a stamp in the future is a clock change, not an ancient file`() {
        val trashed = RecordingTrash.trashedName(name, deletedAt)

        assertFalse(RecordingTrash.isExpired(trashed, deletedAt - 100 * day))
    }

    @Test
    fun `days remaining counts down and stops at zero`() {
        val trashed = RecordingTrash.trashedName(name, deletedAt)

        assertEquals(30, RecordingTrash.daysRemaining(trashed, deletedAt))
        assertEquals(29, RecordingTrash.daysRemaining(trashed, deletedAt + day))
        assertEquals(1, RecordingTrash.daysRemaining(trashed, deletedAt + 29 * day))
        assertEquals(0, RecordingTrash.daysRemaining(trashed, deletedAt + 30 * day))
        assertEquals(0, RecordingTrash.daysRemaining(trashed, deletedAt + 900 * day))
    }

    @Test
    fun `a name containing the prefix later on is not mistaken for a trashed one`() {
        assertFalse(RecordingTrash.isTrashed("20260829_cvtrash_notes.ogg"))
    }
}
