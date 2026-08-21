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

    /**
     * One chunk of a call, rendered for the model.
     *
     * Measured on a real Hebrew call: handed 176 separate lines, Gemma returned the **first four,
     * copied word for word**, and ignored the rest. whisper cuts at every pause, so a real
     * transcript is fragments — "yes", "okay", half a sentence — and a wall of them does not read as
     * a conversation to anything. Lines with no speaker are therefore joined into flowing text
     * before the model sees them, which is what they were before whisper cut them up.
     *
     * A transcript that *does* name its speakers keeps its line breaks: there the breaks carry the
     * turn-taking, which is information rather than noise.
     */
    fun forChunk(segments: List<TranscriptSegmentEntry>, language: String?): String {
        val hasSpeakers = segments.any { it.speaker != null }
        val body = if (hasSpeakers) {
            segments.joinToString("\n") { segment ->
                segment.speaker?.let { "$it: ${segment.text}" } ?: segment.text
            }
        } else {
            segments.joinToString(" ") { it.text.trim() }
        }
        return buildString {
            appendLine("Below is part of a recorded phone call, transcribed automatically.")
            appendLine("The transcription is imperfect and some words may be wrong.")
            appendLine()
            appendLine("Write a short summary of this part of the call, in a few flowing sentences.")
            // Naming what a summary is FOR, rather than only its length.
            //
            // "A short summary" produced an inventory — "the call covered X, and also Y, and
            // mentioned Z" — which is true, useless, and not what anyone opens a summary to find.
            // Someone returning to a call months later wants what it was about, what was settled,
            // and what they promised to do. Asking for those three turns a list into an account.
            appendLine("Say what the conversation was about, what was decided or agreed, and " +
                "anything either person said they would do.")
            // Said outright, because the failure was not a misunderstanding of the task but a
            // literal answer to it: it copied. "In your own words" is the instruction that was
            // missing, and it costs nothing to be explicit about the rest as well.
            appendLine("Write it in your own words. Do not copy sentences from the transcript.")
            appendLine("Cover the whole of the text below, not only its beginning.")
            // The transcript is machine-made and sometimes wrong. Left unsaid, the model treats a
            // mangled phrase as a topic and solemnly reports that the call "mentioned" it.
            appendLine("Ignore words that are clearly mis-transcribed rather than reporting them.")
            appendLine("Do not use headings, labels or bullet points. Do not repeat yourself.")
            appendLine(languageClause(language))
            appendLine(NO_INVENTION)
            appendLine()
            appendLine("TRANSCRIPT:")
            appendLine(body)
            appendLine()
            append("SUMMARY:")
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
