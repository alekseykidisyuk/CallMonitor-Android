/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stopgap that stops a long recording taking the heap with it.
 *
 * Transcription decodes the whole file into memory before the model sees any of it, so a long call ends
 * in a crash or a silently dead job after a long wait. Refusing up front is strictly better — and the
 * edges matter, because the two ways to get this wrong are refusing recordings that would have worked
 * and letting through the ones that will not.
 */
class TranscriptionLengthLimitTest {

    private val fifteenMinutesMs = 15L * 60 * 1000

    @Test
    fun `a short recording is fine`() {
        assertFalse(TranscriptionLengthLimit.isTooLong(30_000L))
    }

    @Test
    fun `exactly the limit is still allowed`() {
        // The limit is the longest length that works, not the shortest that fails.
        assertFalse(TranscriptionLengthLimit.isTooLong(fifteenMinutesMs))
    }

    @Test
    fun `a millisecond over the limit is refused`() {
        assertTrue(TranscriptionLengthLimit.isTooLong(fifteenMinutesMs + 1))
    }

    @Test
    fun `an unknown length is allowed through`() {
        // The container returns 0 when it declares no duration. Refusing on "unknown" would block
        // ordinary short recordings with missing metadata — a worse failure than letting a rare long
        // one reach the limit that already exists, which is the heap.
        assertFalse(TranscriptionLengthLimit.isTooLong(0L))
    }

    @Test
    fun `the call-log duration in seconds is judged the same way`() {
        assertFalse(TranscriptionLengthLimit.isTooLong(durationSeconds = 15L * 60))
        assertTrue(TranscriptionLengthLimit.isTooLong(durationSeconds = 15L * 60 + 1))
    }

    @Test
    fun `an unknown call-log duration is allowed through`() {
        // Null is routine: VoIP calls are often not in the call log at all.
        assertFalse(TranscriptionLengthLimit.isTooLong(durationSeconds = null))
    }
}
