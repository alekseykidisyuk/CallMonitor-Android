/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one place a llama.cpp model is loaded, used and freed.
 *
 * Everything here exists because the native side has two rules that are easy to break from Kotlin:
 * one model may not be driven from two threads at once, and every load must be paired with exactly
 * one free. A [Mutex] enforces the first; `try`/`finally` the second.
 *
 * Neither prompts nor generated text are ever logged — they are the substance of a private call.
 */
object SummaryEngine {

    private const val TAG = "CV:SummaryEngine"

    /**
     * The same thread policy transcription uses, and for the same measured reason.
     *
     * Both drive ggml, which synchronises its threads at every layer, so a thread on one of the
     * phone's efficiency cores does not add its share — it makes the others wait for it at each
     * barrier. Sharing the policy also means one place to change when it is measured again, rather
     * than two that quietly disagree.
     */
    private val threads: Int get() = TranscriptionEngine.preferredThreadCount()

    private val mutex = Mutex()

    /**
     * Whether a model is loaded and working right now.
     *
     * Mirrors [TranscriptionEngine.isRunning] and exists for a sharper reason. This object
     * serialises on [mutex], so a second run does not fail — it waits, in silence, behind a button
     * the user has already pressed and which appears to have done nothing. Asking here lets the
     * caller refuse up front and say why, rather than queueing invisibly.
     */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Whether the last run was stopped rather than finishing.
     *
     * The authority on *how* a run ended. An aborted generate returns normally with a short answer,
     * so without this a stopped run is indistinguishable from a very terse summary — the same trap
     * that made a stopped transcription look like a failed one.
     */
    private val aborted = AtomicBoolean(false)

    /**
     * Loads [modelPath], runs [block] against it, and frees it — whatever happens.
     *
     * The model stays loaded only for the duration of [block] rather than being cached. A 4B model
     * at Q4 is well over a gigabyte of resident memory, and holding that for a screen the user may
     * never open again is not a trade worth making on a phone.
     */
    suspend fun <T> withModel(modelPath: String, block: suspend (Session) -> T): T =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val ptr = LlamaNative.initContext(modelPath)
                if (ptr == 0L) error("Could not load the summarisation model")
                isRunning = true
                try {
                    block(Session(ptr))
                } finally {
                    // Cleared before the free rather than after, so a free that throws cannot leave
                    // the app believing a run is still in progress for the rest of the process.
                    isRunning = false
                    LlamaNative.freeContext(ptr)
                }
            }
        }

    /** True when [requestAbort] landed during the most recent run. Cleared by the next one. */
    fun wasAborted(): Boolean = aborted.get()

    /** Asks a run in progress to stop, from any thread. Safe to call when nothing is running. */
    fun requestAbort() {
        aborted.set(true)
        LlamaNative.requestAbort()
    }

    /** A loaded model, valid only inside [withModel]. */
    class Session internal constructor(private val ptr: Long) {

        /** How many tokens [text] actually costs this model's tokeniser. */
        fun countTokens(text: String): Int = LlamaNative.countTokens(ptr, text)

        /**
         * Completes [prompt]. Returns whatever was produced before the stop when aborted.
         *
         * @param grammar GBNF the output must satisfy, or null for free text.
         */
        fun generate(prompt: String, maxTokens: Int, grammar: String? = null): String {
            aborted.set(false)
            val raw = LlamaNative.generate(ptr, prompt, maxTokens, threads, grammar)
            if (aborted.get()) AppLogger.i(TAG, "A summary run was stopped before it finished")
            // Reasoning models think out loud first. That is theirs, not the user's.
            return SummaryText.stripReasoning(raw)
        }
    }
}
