/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What whisper is told before it decodes a call.
 *
 * Whisper knows nothing about the call, so anything unusual comes out as whatever it sounds like:
 * on a real Hebrew call the phone model "one plus nine" was transcribed as `1 + 9`. The prompt is
 * read as text that came just before, and its style is imitated — which is the whole reason a
 * generic hint can work at all, and why it must be built from what the app already knows rather
 * than from a list somebody is expected to type.
 */
class TranscriptionPromptTest {

    @Test
    fun `shows a non-Latin language that foreign words stay in their own alphabet`() {
        val prompt = TranscriptionPrompt.build(contactName = null, language = "he")

        // Hebrew text carrying Latin-script words: the pattern, demonstrated rather than described.
        assertTrue(prompt!!.contains("Android"))
        assertTrue(prompt.any { it in '\u0590'..'\u05FF' })
    }

    @Test
    fun `says nothing extra for a language already written in Latin script`() {
        // English words in a French transcript need no help; they are the same alphabet.
        assertNull(TranscriptionPrompt.build(contactName = null, language = "fr"))
        assertNull(TranscriptionPrompt.build(contactName = null, language = "en"))
    }

    @Test
    fun `says nothing at all while the language is being auto-detected`() {
        // Naming one language would bias the detection it is waiting on.
        assertNull(TranscriptionPrompt.build(contactName = null, language = null))
    }

    @Test
    fun `names the contact so their spelling does not drift between transcripts`() {
        val prompt = TranscriptionPrompt.build(contactName = "Feroza", language = "en")

        assertTrue(prompt!!.contains("Feroza"))
    }

    @Test
    fun `carries both the hint and the contact when there is a reason for each`() {
        val prompt = TranscriptionPrompt.build(contactName = "Feroza", language = "he")!!

        assertTrue(prompt.contains("Android"))
        assertTrue(prompt.contains("Feroza"))
    }

    @Test
    fun `drops a contact name that is really a phone number`() {
        // Unsaved contacts fall back to the number, and feeding digits to whisper before a call full
        // of spoken digits invites it to write them down.
        assertNull(TranscriptionPrompt.build(contactName = "+972 50 123 4567", language = "en"))
    }

    @Test
    fun `stays short enough that whisper will not recite it`() {
        val prompt = TranscriptionPrompt.build(contactName = "x".repeat(400), language = "he")!!

        assertTrue("was ${prompt.length}", prompt.length <= TranscriptionPrompt.MAX_CHARS)
    }
}
