/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.net.Uri
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single entry point for turning a recording into text.
 *
 * Everything is serialised onto one dedicated thread, for two independent reasons: whisper.cpp
 * forbids concurrent access to a context at all, and a second concurrent job would only fight the
 * first for cores it is already saturating — two jobs would take roughly twice as long each rather
 * than finishing any sooner.
 *
 * Runs in the app process. It must never be invoked from the privileged recorder daemon.
 */
object TranscriptionEngine {

    private const val TAG = "CV:Transcribe"

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cv-transcribe").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Threads to hand whisper.cpp: every core.
     *
     * Measured on the OP12 (SM8650, 6 performance + 2 efficiency cores) with `large-v3-turbo-q5_0` —
     * 4 threads 140.4 s, 6 threads 99.2 s, 8 threads 95.9 s. Using the efficiency cluster too was
     * fastest, so the "prefer performance cores" rule this was originally designed with is
     * deliberately NOT implemented. Do not reintroduce it without fresh measurements.
     *
     * Clamped to at least 1: `availableProcessors()` may change between calls and has been observed
     * returning nonsense, and whisper.cpp divides work by whatever it is handed.
     */
    fun threadCountFor(availableProcessors: Int): Int = availableProcessors.coerceAtLeast(1)

    fun preferredThreadCount(): Int = threadCountFor(Runtime.getRuntime().availableProcessors())

    /**
     * Asks a run in progress to stop, from any thread.
     *
     * Best-effort by design: it returns immediately, and the run ends shortly afterwards with
     * whatever it had. Safe to call when nothing is running — the flag is cleared before each run.
     * Wrapped because it touches the native library, which may not have loaded on a device where the
     * ABI is unsupported, and failing to stop must never crash the caller.
     */
    fun requestAbort() {
        // Two halves, because a run has two uninterruptible phases: decoding (Kotlin, reads this
        // flag) and whisper itself (native, reads its own).
        abortRequested.set(true)
        runCatching { WhisperNative.requestAbort() }
            .onFailure { AppLogger.w(TAG, "Abort request failed: ${it.message}") }
    }

    /** Set by [requestAbort], cleared at the start of every run so a stale abort cannot kill the next. */
    private val abortRequested = AtomicBoolean(false)

    /**
     * Transcribes [uri] using the model at [modelPath].
     *
     * @param language ISO code such as "he", or null to auto-detect. Passing the wrong language is
     *   not a soft failure — decoding Hebrew as English yields confident nonsense.
     * @return segments in order, or **empty when nothing was recognised**. Callers must surface that
     *   as "no speech detected" rather than as a successful empty transcript; reporting only what was
     *   actually observed is the same rule the VoIP capture path follows.
     */
    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelPath: String,
        language: String?,
    ): List<TranscriptSegment> = withContext(dispatcher) {
        abortRequested.set(false)
        val audio = AudioDecoder.decodeToMono16k(context, uri) { abortRequested.get() }
        if (audio.isEmpty()) {
            AppLogger.w(TAG, "Decoded no audio from $uri")
            return@withContext emptyList()
        }

        val ptr = WhisperNative.initContext(modelPath)
        if (ptr == 0L) error("Could not load whisper model at $modelPath")
        try {
            val threads = preferredThreadCount()
            AppLogger.i(TAG, "Transcribing ${audio.size / AudioDecoder.TARGET_SAMPLE_RATE}s with $threads threads, lang=${language ?: "auto"}")
            WhisperNative.transcribe(ptr, audio, threads, language)

            val count = WhisperNative.segmentCount(ptr)
            (0 until count)
                .map { i ->
                    TranscriptSegment(
                        startMs = WhisperNative.segmentStartMs(ptr, i),
                        endMs = WhisperNative.segmentEndMs(ptr, i),
                        text = WhisperNative.segmentText(ptr, i).trim(),
                    )
                }
                .filter { it.text.isNotEmpty() }
                .also { AppLogger.i(TAG, "Produced ${it.size} segments from $count raw") }
        } finally {
            WhisperNative.freeContext(ptr)
        }
    }
}
