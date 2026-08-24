/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What whisper is told before it decodes a call: the contact's name, and nothing else.
 *
 * Two richer versions were tried and removed — a glossary the user types, and a built-in list of
 * common product names. The second one *worked*, and was still wrong: a long prompt reads to whisper
 * as text the call is continuing from, and it answers with a few enormous segments instead of many
 * small ones. Segments are what speaker labels are made of, so it bought correct spelling by taking
 * the speaker names off the transcript. Measured on a real 45s call: 19 segments unprompted, 21 with
 * the contact's name, 4 with the list.
 *
 * Which is why these tests care about what is *absent* as much as what is present.
 */
class TranscriptionPromptTest {

    @Test
    fun `names the contact so their spelling does not drift between transcripts`() {
        val prompt = TranscriptionPrompt.build(contactName = "Feroza")

        assertEquals("Feroza.", prompt)
    }

    @Test
    fun `says nothing at all when there is no name to give`() {
        // Silence beats filler. Every prompt costs segmentation, and an empty one buys nothing.
        assertNull(TranscriptionPrompt.build(contactName = null))
        assertNull(TranscriptionPrompt.build(contactName = "   "))
    }

    @Test
    fun `drops a contact name that is really a phone number`() {
        // Unsaved contacts fall back to the number, and feeding digits to whisper before a call full
        // of spoken digits invites it to write them down.
        assertNull(TranscriptionPrompt.build(contactName = "+972 50 123 4567"))
    }

    @Test
    fun `stays short enough that whisper will not recite it`() {
        val prompt = TranscriptionPrompt.build(contactName = "x".repeat(400))!!

        assertTrue("was ${prompt.length}", prompt.length <= TranscriptionPrompt.MAX_CHARS)
    }
}
