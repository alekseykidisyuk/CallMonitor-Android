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

    /**
     * Whether a recording is being transcribed right now.
     *
     * Exists so background work can stand aside. Transcription saturates the CPU for minutes, and
     * anything else decoding audio at the same time both slows it down and poisons the timing the
     * estimate calibrates from — a factor learned while something else was stealing cores is wrong
     * for every run afterwards.
     */
    @Volatile
    var isRunning: Boolean = false
        private set


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
    /**
     * How many threads to give whisper, from a core count.
     *
     * **Not every core.** Phone CPUs are heterogeneous — the OP12's eight are six performance cores
     * and two efficiency cores — and ggml synchronises its threads at every layer. A thread pinned
     * to a little core therefore does not add its share of work; it makes the other five wait for it
     * at each barrier. Using all eight measured slower than using the big ones alone, and burned the
     * little cores' power for the privilege.
     *
     * Two are left out, which lands on exactly the performance cores of the common 6+2 phones, and
     * degrades sensibly elsewhere: a 4-core phone gets 2, a single-core one still gets 1. Capped at
     * six because past that the gains flatten while contention with the rest of the phone does not.
     *
     * Changing this changes how long a run takes, so it invalidates the speed this device has
     * already calibrated — see [TranscriptionEstimate].
     */
    fun threadCountFor(availableProcessors: Int): Int =
        (availableProcessors - RESERVED_CORES).coerceIn(1, MAX_THREADS)

    /** Left for the OS, the UI and whatever else the phone is doing while this runs. */
    private const val RESERVED_CORES = 2

    /** Past this, ggml's gains flatten while contention with the rest of the phone does not. */
    private const val MAX_THREADS = 6

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

    /**
     * How far through the current recording whisper is, 0-100. Zero when nothing is running.
     *
     * Gated on [whisperActive] rather than read straight from native, because the native counter is
     * a global that survives the run that filled it. It is zeroed when `whisper_full` is entered —
     * but loading the model and decoding the audio happen first and take seconds, and a poller
     * asking during that window used to get the PREVIOUS run's figure. Re-transcribing therefore
     * opened at the last run's percentage rather than at nothing, which reads as a job already
     * nearly done. The doc above promised zero; only this keeps the promise.
     */
    fun progressPercent(): Int = if (whisperActive) WhisperNative.progressPercent() else 0

    /** True only while native decoding is actually under way — see [progressPercent]. */
    @Volatile
    private var whisperActive: Boolean = false

    /** Set by [requestAbort], cleared at the start of every run so a stale abort cannot kill the next. */
    private val abortRequested = AtomicBoolean(false)

    /**
     * Whether the run that just ended was stopped rather than finished.
     *
     * The authority on that question, because the two obvious alternatives are both wrong:
     *  - the exception does not say. An aborted `whisper_full` returns **normally**, so a stop looks
     *    exactly like success — and storing that would leave a half transcript.
     *  - `isStopped` may not be true yet. Stop aborts the engine before WorkManager marks the worker
     *    stopped, so a run unwinding in that window looks like a genuine failure.
     */
    fun wasAborted(): Boolean = abortRequested.get()

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
        // Set before the decode, not after: decoding the audio is itself minutes of CPU on a long
        // call, and that is exactly when other background work must stand aside.
        isRunning = true
        try {
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
            // Bracketed as tightly as possible around the native call: outside it, the counter
            // still holds whatever the previous run left behind.
            whisperActive = true
            try {
                WhisperNative.transcribe(ptr, audio, threads, language)
            } finally {
                whisperActive = false
            }

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
        } finally {
            isRunning = false
        }
    }
}
