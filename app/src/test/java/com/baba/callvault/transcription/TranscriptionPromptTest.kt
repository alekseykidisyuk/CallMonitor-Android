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
 * The "words to expect" handed to whisper before a call is decoded.
 *
 * Whisper is general-purpose and has no idea what a call is about, so a brand said aloud comes out
 * as what it sounds like: "one plus nine" was transcribed as "1 + 9" on a real Hebrew call, and
 * contact names fare no better. Naming the words in advance biases the decoding towards them.
 *
 * The size limit is the point of most of these tests. whisper's prompt is capped, and a long or
 * unrelated one does not merely fail to help — it gets repeated back inside the transcript.
 */
class TranscriptionPromptTest {

    @Test
    fun `names the contact so their name is spelled rather than guessed`() {
        val prompt = TranscriptionPrompt.build(contactName = "Feroza", glossary = "")

        assertTrue(prompt!!.contains("Feroza"))
    }

    @Test
    fun `carries the user's own terms`() {
        val prompt = TranscriptionPrompt.build(contactName = null, glossary = "OnePlus, Shizuku")

        assertTrue(prompt!!.contains("OnePlus"))
        assertTrue(prompt.contains("Shizuku"))
    }

    @Test
    fun `puts the contact first, since it is the term most certain to be said`() {
        val prompt = TranscriptionPrompt.build(contactName = "Dana", glossary = "OnePlus")

        assertTrue(prompt!!.indexOf("Dana") < prompt.indexOf("OnePlus"))
    }

    @Test
    fun `is null when there is nothing worth saying`() {
        // Not an empty string: an empty prompt and no prompt must reach whisper the same way.
        assertNull(TranscriptionPrompt.build(contactName = null, glossary = ""))
        assertNull(TranscriptionPrompt.build(contactName = "   ", glossary = "  ,  , "))
    }

    @Test
    fun `drops a contact name that is really a phone number`() {
        // Unsaved contacts fall back to the number, and feeding digits to whisper before a call full
        // of digits invites it to repeat them.
        assertNull(TranscriptionPrompt.build(contactName = "+972 50 123 4567", glossary = ""))
    }

    @Test
    fun `keeps the prompt short enough that whisper will not recite it`() {
        val long = (1..200).joinToString(", ") { "term$it" }

        val prompt = TranscriptionPrompt.build(contactName = "Dana", glossary = long)

        assertTrue("was ${prompt!!.length}", prompt.length <= TranscriptionPrompt.MAX_CHARS)
        assertTrue("the contact must survive truncation", prompt.contains("Dana"))
    }

    @Test
    fun `never cuts a term in half when trimming`() {
        val long = (1..200).joinToString(", ") { "term$it" }

        val prompt = TranscriptionPrompt.build(contactName = null, glossary = long)!!

        // Every term that made it in is whole: a half-word teaches whisper a spelling that is wrong.
        val terms = prompt.removeSuffix(".").split(", ").filter { it.isNotBlank() }
        assertTrue(terms.all { it.matches(Regex("term\\d+")) })
    }

    @Test
    fun `ignores duplicates and blank entries`() {
        val prompt = TranscriptionPrompt.build(contactName = "Dana", glossary = "Dana, , OnePlus,,OnePlus")

        assertEquals("Dana, OnePlus.", prompt)
    }
}
