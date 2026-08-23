/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.json.JSONArray
import org.json.JSONObject

/**
 * A model's account of a call, once it has stopped being text and become data.
 *
 * The fields are the ones [SummaryGrammar] can physically produce, and no others. Two from the
 * original design are absent on purpose — `participants`, because it read a ringtone marker as a
 * person and named them, and `sentiment`, because being confidently wrong about the tone of
 * someone's private conversation is a bad trade. [parse] ignores both if an older prompt asks for
 * them, so their return is a change to the grammar rather than a migration.
 *
 * **Parsed with `org.json`, not a serialization library.** The shape is six known keys produced
 * under a grammar; that does not justify adding a compiler plugin to the build. Room stores the
 * document [toJson] returns, so encoding and decoding are the same code path the model's own output
 * takes — one parser, exercised by every summary that has ever been read.
 */
data class CallSummary(
    /** One line on why the call happened, used as the card's headline. */
    val intent: String,
    /** The account itself, in prose. Never blank — see [parse]. */
    val summary: String,
    val keyPoints: List<String>,
    val decisions: List<String>,
    val actionItems: List<String>,
    val keyFacts: List<String>
) {

    /**
     * The summary as a JSON document, in the shape [parse] accepts.
     *
     * `org.json` escapes quotation marks, commas and newlines, all of which occur in ordinary
     * speech. A list joined on a delimiter would corrupt the first time someone was quoted.
     */
    fun toJson(): String = JSONObject()
        .put(KEY_INTENT, intent)
        .put(KEY_SUMMARY, summary)
        .put(KEY_POINTS, JSONArray(keyPoints))
        .put(KEY_DECISIONS, JSONArray(decisions))
        .put(KEY_ACTIONS, JSONArray(actionItems))
        .put(KEY_FACTS, JSONArray(keyFacts))
        .toString()

    companion object {

        private const val KEY_INTENT = "intent"
        private const val KEY_SUMMARY = "summary"
        private const val KEY_POINTS = "keyPoints"
        private const val KEY_DECISIONS = "decisions"
        private const val KEY_ACTIONS = "actionItems"
        private const val KEY_FACTS = "keyFacts"

        private val REQUIRED = listOf(KEY_INTENT, KEY_SUMMARY, KEY_POINTS, KEY_DECISIONS, KEY_ACTIONS, KEY_FACTS)

        /**
         * Combines already-parsed parts into one summary, without asking the model again.
         *
         * The floor under the merge pass. That pass is a second generation and so a second chance to
         * fail — it can truncate against its token budget, and the result is then *no summary at
         * all* after minutes of work the user sat and watched. Concatenating what already parsed is
         * worse than a written merge and enormously better than nothing.
         *
         * The first part names the call: the opening is where someone says why they rang, so the
         * earliest non-empty intent is the call's. Identical lines across parts are dropped, because
         * a summary that says the same thing twice reads as padding.
         */
        fun concatenate(parts: List<CallSummary>): CallSummary? {
            if (parts.isEmpty()) return null
            parts.singleOrNull()?.let { return it }

            return CallSummary(
                intent = parts.firstOrNull { it.intent.isNotBlank() }?.intent.orEmpty(),
                summary = parts.map { it.summary }.filter { it.isNotBlank() }.distinct().joinToString(" "),
                keyPoints = parts.flatMap { it.keyPoints }.distinct(),
                decisions = parts.flatMap { it.decisions }.distinct(),
                actionItems = parts.flatMap { it.actionItems }.distinct(),
                keyFacts = parts.flatMap { it.keyFacts }.distinct()
            )
        }

        /**
         * The summary in [raw], or `null` if there isn't a whole one.
         *
         * Returning `null` rather than a partially filled object is the whole point. Under a token
         * cap a small model drops its closing quote, and a card built from the fragment would show
         * a sentence that stops mid-word as though the call had been summarised. The grammar makes
         * that unsampleable while it is applied — but a row written before the grammar existed, or
         * by a run where it failed to compile, still has to be refused on the way out.
         *
         * Tolerant about everything that isn't the shape: a reasoning block, prose around the
         * object, a code fence, and keys we don't know are all fine. Strict about exactly two
         * things — every expected key present, and prose actually in it.
         */
        fun parse(raw: String): CallSummary? {
            val json = objectIn(SummaryText.stripReasoning(raw)) ?: return null
            if (REQUIRED.any { !json.has(it) }) return null

            val summary = json.optString(KEY_SUMMARY).trim()
            if (summary.isEmpty()) return null

            return CallSummary(
                intent = json.optString(KEY_INTENT).trim(),
                summary = summary,
                keyPoints = stringsIn(json, KEY_POINTS),
                decisions = stringsIn(json, KEY_DECISIONS),
                actionItems = stringsIn(json, KEY_ACTIONS),
                keyFacts = stringsIn(json, KEY_FACTS)
            )
        }

        /**
         * The outermost JSON object in [text], or `null`.
         *
         * Spans the first `{` to the last `}` so a code fence or a closing pleasantry is simply not
         * included, rather than having to be recognised and stripped.
         */
        private fun objectIn(text: String): JSONObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
        }

        /**
         * The non-blank strings at [key].
         *
         * Blanks are dropped rather than kept: an empty string renders as a bullet with nothing
         * beside it, which reads as a fact the app has lost rather than one the model never had.
         */
        private fun stringsIn(json: JSONObject, key: String): List<String> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length())
                .mapNotNull { array.optString(it).trim().ifEmpty { null } }
        }
    }
}
