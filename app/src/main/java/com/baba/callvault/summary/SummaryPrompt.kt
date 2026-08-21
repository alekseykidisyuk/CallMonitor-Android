/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry

/**
 * What the model is actually asked.
 *
 * Two instructions carry the whole feature. **Answer in the language of the call** — a Hebrew
 * conversation summarised into English is not a degraded result, it is a broken one. And **invent
 * nothing** — this is a record of something a real person said, so a fluent fabrication is worse
 * than a clumsy summary and far worse than no summary at all.
 *
 * The instructions are written in English regardless of the transcript's language. Small models
 * follow English instructions more reliably than instructions in the target language, and the
 * language of the *answer* is stated explicitly rather than left to be inferred.
 *
 * Pure and Android-free so the wording is under test rather than under review.
 */
object SummaryPrompt {

    /**
     * English names for every language the transcriber offers, keyed by the same codes
     * `TranscriptionLabels` uses.
     *
     * By name, not by code: "Hebrew" is a word a model has seen in instructions a great many times;
     * "he" is a pronoun.
     */
    private val LANGUAGE_NAMES = mapOf(
        "ar" to "Arabic",
        "de" to "German",
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "he" to "Hebrew",
        "hu" to "Hungarian",
        "it" to "Italian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "vi" to "Vietnamese",
        "zh" to "Chinese"
    )

    /** The English name for [code], or null when it is not one the app offers. */
    fun languageName(code: String?): String? = code?.let { LANGUAGE_NAMES[it.lowercase()] }

    /**
     * How to word the language instruction.
     *
     * An unknown or absent code never falls back to English — that is precisely the failure being
     * guarded against. It falls back to "the same language as the conversation", which is correct
     * whatever that language turns out to be.
     */
    private fun languageClause(code: String?): String =
        languageName(code)?.let { "Write the summary in $it." }
            ?: "Write the summary in the same language as the conversation."

    private const val NO_INVENTION =
        "Use only what is said in the text below. Do not invent names, numbers, dates or " +
            "commitments, and do not guess at anything that is unclear."

    /** One chunk of a call, rendered for the model with its speakers where they are known. */
    fun forChunk(segments: List<TranscriptSegmentEntry>, language: String?): String {
        val body = segments.joinToString("\n") { segment ->
            // The speaker column is omitted rather than left blank when unknown, so a transcript
            // with no speaker data reads as ordinary dialogue instead of looking damaged.
            segment.speaker?.let { "$it: ${segment.text}" } ?: segment.text
        }
        return buildString {
            appendLine("Summarise this part of a recorded phone call in a few short sentences.")
            appendLine(languageClause(language))
            appendLine(NO_INVENTION)
            appendLine()
            appendLine(body)
            appendLine()
            append("Summary:")
        }
    }

    /**
     * Folds the per-chunk summaries into one.
     *
     * A separate prompt rather than the same one applied twice: the input here is already summary
     * prose, and asking to "summarise this part of a call" would make the model treat a summary as
     * a transcript and compress what is already compressed.
     */
    fun forMerge(summaries: List<String>, language: String?): String = buildString {
        appendLine("These are summaries of consecutive parts of one recorded phone call.")
        appendLine("Combine them into a single summary of the whole call, without repeating points.")
        appendLine(languageClause(language))
        appendLine(NO_INVENTION)
        appendLine()
        summaries.forEachIndexed { index, summary ->
            appendLine("Part ${index + 1}: $summary")
        }
        appendLine()
        append("Summary of the whole call:")
    }
}
