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
 * whatever it sounds like. Measured on a real Hebrew call: "OnePlus 9" — a phone model — was
 * transcribed as `1 + 9`, arithmetic rather than a name. Contact names go the same way, and a name
 * spelled differently in every transcript is also a name that search will never find.
 *
 * whisper's `initial_prompt` biases decoding towards words it has been shown. Three limits shape
 * everything here:
 *
 *  - **Only the words that are in it.** Measured: a prompt describing the *shape* of the problem
 *    changed nothing at all, while one naming `OnePlus` fixed it outright. See [styleHintFor].
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
     * The names a call is likely to contain, written the way they should appear.
     *
     * **whisper's prompt biases towards the words that are in it, and nothing else.** An earlier
     * version of this tried to *demonstrate a style* — a Hebrew sentence carrying two English words,
     * on the theory that whisper imitates what it is shown. Measured against the failing call on
     * 2026-08-24, that produced output byte-identical to sending no prompt at all:
     *
     * | prompt | "OnePlus 9" came out as |
     * |---|---|
     * | none | `1 plus 10` |
     * | a Hebrew sentence containing `Android` and `Google` | `1 plus 10` — identical |
     * | the same sentence, with `OnePlus` among the names | **`OnePlus 9`** |
     * | brand names *without* `OnePlus` | `1 + תשע` — back to arithmetic |
     *
     * So the payload is the names themselves, and the sentence around them only keeps whisper
     * writing in the right language. The honest limit follows directly: **this helps a word only if
     * that word is here.** It is a short, curated list of names common enough to earn their place,
     * not a solution to foreign words in general.
     *
     * It stays built-in for the same reason it is short. Asking the user to type the words their
     * calls contain was rejected, and rightly — the words that need help are exactly the ones you do
     * not think to list, so a box nobody fills in helps nobody. The one name the app *does* know is
     * the contact's, and [build] adds it.
     *
     * Offered only for languages written in a non-Latin script, because that is where the damage is:
     * a French transcript already writes English words in the same alphabet, while Hebrew sounds
     * them out or, as above, does arithmetic with them. Deliberately dull entries — whisper recites
     * its prompt into the transcript when the audio is near-silent, so it should be unremarkable
     * when it surfaces.
     */
    private fun styleHintFor(language: String?): String? = when (language) {
        "he" -> "שיחה בעברית ובתוכה שמות באנגלית: $NAMES."
        "ar" -> "مكالمة بالعربية وفيها أسماء إنجليزية: $NAMES."
        "ru" -> "Разговор по-русски, в нём английские названия: $NAMES."
        "zh" -> "中文通话，其中夹杂英文名称：$NAMES。"
        else -> null
    }

    /**
     * The names themselves, shared by every language above.
     *
     * Phone makers and the apps calls are actually about — the class of word that gets sounded out.
     * Kept to a handful: every name costs prompt budget that the contact's own name needs, and a
     * long prompt is the one that gets recited back.
     */
    private const val NAMES = "OnePlus, Samsung, iPhone, Android, Google, WhatsApp"

    /**
     * Whether [name] is a phone number wearing a name's place.
     *
     * An unsaved contact falls back to their number, and feeding digits to whisper immediately
     * before a call in which numbers are spoken invites it to write those digits down.
     */
    private fun isPhoneNumber(name: String): Boolean =
        name.count { it.isDigit() } >= name.count { !it.isWhitespace() } / 2
}
