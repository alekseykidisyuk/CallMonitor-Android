/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.net.Uri
import androidx.core.net.toUri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A bulk delete removes files the user cannot get back, and one row is not always one file — a
 * recording kept both on the device and in Drive is a single row carrying two URIs. Every case here
 * is one a wrong answer would destroy something.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14 tops out at SDK 35 while the app targets 36, same as CallLogReaderTest.
@Config(sdk = [35])
class RecordingSelectionTest {

    private fun device(name: String) = item(name, RecordingSource.LOCAL, local = "content://dev/$name")
    private fun drive(name: String) = item(name, RecordingSource.DRIVE, drive = "content://drv/$name")
    private fun both(name: String) =
        item(name, RecordingSource.BOTH, local = "content://dev/$name", drive = "content://drv/$name")

    private fun item(name: String, source: RecordingSource, local: String? = null, drive: String? = null) =
        RecordingItem(
            uri = (local ?: drive)!!.toUri(),
            displayName = name,
            sizeBytes = 1,
            lastModified = 0,
            direction = null,
            displayDate = null,
            startedAtMillis = null,
            number = null,
            source = source,
            localUri = local?.toUri(),
            driveUri = drive?.toUri(),
        )

    // ---- When to ask ----

    @Test
    fun `asks which copies only when a two-copy recording is selected`() {
        assertTrue(RecordingSelection.needsScopeChoice(listOf(device("a"), both("b"))))
    }

    @Test
    fun `does not ask when every selected recording exists in one place`() {
        // A dialog in front of an unambiguous action is just an extra tap.
        assertFalse(RecordingSelection.needsScopeChoice(listOf(device("a"), drive("b"))))
        assertFalse(RecordingSelection.needsScopeChoice(emptyList()))
    }

    // ---- What gets deleted ----

    @Test
    fun `device-only scope removes just the device copy of a two-copy recording`() {
        val uris = RecordingSelection.urisToDelete(listOf(both("a")), DeleteScope.DEVICE)
        assertEquals(listOf("content://dev/a".toUri()), uris)
    }

    @Test
    fun `drive-only scope removes just the Drive copy`() {
        val uris = RecordingSelection.urisToDelete(listOf(both("a")), DeleteScope.DRIVE)
        assertEquals(listOf("content://drv/a".toUri()), uris)
    }

    @Test
    fun `both scope removes each copy exactly once`() {
        val uris = RecordingSelection.urisToDelete(listOf(both("a")), DeleteScope.BOTH)
        assertEquals(listOf("content://dev/a".toUri(), "content://drv/a".toUri()), uris)
    }

    @Test
    fun `a single-copy recording is deleted whatever the scope says`() {
        // THE DANGEROUS CASE: choosing "Drive only" over a mixed selection must not spare, or
        // silently destroy, the recordings that only exist in one place. Each contributes its one
        // file, because the scope question was never about them.
        val selection = listOf(device("a"), drive("b"))
        for (scope in DeleteScope.entries) {
            assertEquals(
                "scope $scope",
                listOf("content://dev/a".toUri(), "content://drv/b".toUri()),
                RecordingSelection.urisToDelete(selection, scope),
            )
        }
    }

    @Test
    fun `a mixed selection deletes the right files per recording`() {
        val uris = RecordingSelection.urisToDelete(listOf(device("a"), both("b")), DeleteScope.DRIVE)
        assertEquals(listOf("content://dev/a".toUri(), "content://drv/b".toUri()), uris)
    }

    @Test
    fun `deleting nothing is not an error`() {
        assertEquals(emptyList<Uri>(), RecordingSelection.urisToDelete(emptyList(), DeleteScope.BOTH))
    }

    // ---- What gets shared ----

    @Test
    fun `shares one file per recording, preferring the device copy`() {
        // Attaching both copies would send the same call twice.
        val uris = RecordingSelection.urisToShare(listOf(both("a"), drive("b")))
        assertEquals(listOf("content://dev/a".toUri(), "content://drv/b".toUri()), uris)
    }
}
