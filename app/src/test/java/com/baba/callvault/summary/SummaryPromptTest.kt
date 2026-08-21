/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPromptTest {

    private fun segment(text: String, speaker: String? = null) =
        TranscriptSegmentEntry(
            id = 1, displayName = "call", startMs = 0, endMs = 1000, text = text, speaker = speaker
        )

    @Test
    fun `names the output language so a Hebrew call is summarised in Hebrew`() {
        assertTrue(SummaryPrompt.forChunk(listOf(segment("שלום")), "he").contains("Hebrew"))
    }

    @Test
    fun `names every language the app can transcribe`() {
        // A language the transcriber offers but the prompt cannot name would silently produce an
        // English summary of a foreign call — the failure this whole feature must not have.
        val offered = listOf("he", "en", "ar", "zh", "fr", "de", "hu", "it", "pl", "pt", "ru", "es", "vi")
        offered.forEach { code ->
            assertTrue("no name for $code", SummaryPrompt.languageName(code) != null)
        }
    }

    @Test
    fun `falls back to the transcript's own words when the language is unknown`() {
        // Never guess English. An unrecognised code means "match the transcript", which is right
        // whatever the language turns out to be.
        val prompt = SummaryPrompt.forChunk(listOf(segment("hello")), language = null)
        assertTrue(prompt.contains("same language as the conversation"))
        assertFalse(prompt.contains("in English"))
    }

    @Test
    fun `forbids inventing anything`() {
        assertTrue(SummaryPrompt.forChunk(listOf(segment("hi")), "en").contains("Do not invent"))
    }

    @Test
    fun `carries the speaker when the transcript has one`() {
        val prompt = SummaryPrompt.forChunk(listOf(segment("hi there", speaker = "Caller")), "en")
        assertTrue(prompt.contains("Caller: hi there"))
    }

    @Test
    fun `omits the speaker column entirely when unknown`() {
        val prompt = SummaryPrompt.forChunk(listOf(segment("hi there")), "en")
        assertTrue(prompt.contains("hi there"))
        assertFalse(prompt.contains(": hi there"))
    }

    @Test
    fun `the merge prompt asks for one summary without repetition`() {
        val merged = SummaryPrompt.forMerge(listOf("part one", "part two"), "en")
        assertTrue(merged.contains("part one"))
        assertTrue(merged.contains("part two"))
        assertTrue(merged.contains("English"))
        assertTrue(merged.contains("Do not invent"))
    }
}
