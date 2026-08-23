/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * The words to expect, handed to whisper before it decodes a call.
 *
 * Whisper is general-purpose and knows nothing about the call, so anything unusual comes out as
 * whatever it sounds like. Measured on a real Hebrew call: "one plus nine" — a phone model — was
 * transcribed as `1 + 9`, arithmetic rather than a name. Contact names go the same way, and a name
 * spelled differently in every transcript is also a name that search will never find.
 *
 * whisper's `initial_prompt` biases decoding towards words it has been shown. Two limits shape
 * everything here:
 *
 *  - **It is a bias, not a rule.** It makes the right spelling likelier and guarantees nothing.
 *  - **It must stay short.** The prompt is capped, and an over-long or unrelated one does not simply
 *    fail to help — whisper recites it back inside the transcript. So the contact goes first, terms
 *    follow while they fit, and a term is dropped whole rather than cut.
 */
object TranscriptionPrompt {

    /**
     * Longest prompt handed over.
     *
     * Well under whisper's own limit, deliberately: the ceiling is where recitation starts, and
     * being near it buys nothing. A couple of hundred characters is more terms than a call has.
     */
    const val MAX_CHARS = 220

    /**
     * The prompt for one recording, or null when there is nothing worth saying.
     *
     * @param contactName who the call is with. First in the list, because it is the term most
     *   certain to be spoken. Ignored when it is really a phone number.
     * @param glossary the user's own terms, comma-separated.
     */
    fun build(contactName: String?, glossary: String): String? {
        val terms = LinkedHashSet<String>()

        contactName?.trim()?.takeIf { it.isNotEmpty() && !isPhoneNumber(it) }?.let { terms += it }
        glossary.split(',').forEach { term ->
            term.trim().takeIf { it.isNotEmpty() }?.let { terms += it }
        }
        if (terms.isEmpty()) return null

        val kept = mutableListOf<String>()
        var length = 0
        for (term in terms) {
            // ", " between terms, and the closing full stop.
            val added = term.length + if (kept.isEmpty()) 1 else 3
            if (length + added > MAX_CHARS) break
            kept += term
            length += added
        }
        if (kept.isEmpty()) return null

        // Written as a sentence rather than a bare list: whisper's prompt is read as preceding
        // speech, and text shaped like speech steers the decoder better than a fragment.
        return kept.joinToString(", ") + "."
    }

    /**
     * Whether [name] is a phone number wearing a name's place.
     *
     * An unsaved contact falls back to their number, and feeding digits to whisper immediately
     * before a call in which numbers are spoken invites it to write those digits down.
     */
    private fun isPhoneNumber(name: String): Boolean =
        name.count { it.isDigit() } >= name.count { !it.isWhitespace() } / 2
}
