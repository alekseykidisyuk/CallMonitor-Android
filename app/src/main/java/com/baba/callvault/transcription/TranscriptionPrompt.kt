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
     * Built from what the app already knows. An earlier version asked the user to type a list of
     * words their calls contain; that was rejected as a feature nobody would ever fill in, and the
     * judgement was right — the words that need help are exactly the ones you do not think to list.
     *
     * @param contactName who the call is with. The one term certain to be spoken, and the one whose
     *   spelling should not drift between transcripts. Ignored when it is really a phone number.
     * @param language the pinned transcription language, or null when auto-detecting.
     */
    fun build(contactName: String?, language: String?): String? {
        val name = contactName?.trim()?.takeIf { it.isNotEmpty() && !isPhoneNumber(it) }
        val style = styleHintFor(language)

        val parts = listOfNotNull(style, name?.let { "$it." })
        if (parts.isEmpty()) return null

        val prompt = parts.joinToString(" ")
        return if (prompt.length <= MAX_CHARS) prompt else prompt.take(MAX_CHARS)
    }

    /**
     * A sentence showing what this language's speech looks like written down.
     *
     * whisper reads its prompt as text that came just before, and imitates its style. That is the
     * whole mechanism, and it is why a hint can be generic: a Hebrew sentence carrying an English
     * word in Latin letters demonstrates that foreign words stay foreign, rather than being sounded
     * out into the local script or — as measured on a real call — turned into arithmetic, where the
     * phone model "one plus nine" was written `1 + 9`.
     *
     * Offered only for languages written in a non-Latin script, because that is where the problem
     * lives. A French or German transcript already writes English words in the same alphabet.
     *
     * The words chosen are deliberately dull and universal. A prompt can be echoed into a transcript
     * if the audio is near-silent, so whatever is in it should be unremarkable when it appears.
     */
    private fun styleHintFor(language: String?): String? = when (language) {
        "he" -> "שיחה בעברית, ובתוכה מילים באנגלית כמו Android ו-Google."
        "ar" -> "مكالمة بالعربية، وفيها كلمات إنجليزية مثل Android و Google."
        "ru" -> "Разговор по-русски, в нём есть английские слова, например Android и Google."
        "zh" -> "中文通话，其中夹杂英文词，例如 Android 和 Google。"
        else -> null
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
