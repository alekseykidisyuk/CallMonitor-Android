/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * JNI bridge to whisper.cpp.
 *
 * whisper.cpp forbids touching one context from more than one thread at a time, so nothing may call
 * these functions directly — [TranscriptionEngine] owns that serialisation and is the only supported
 * entry point. The raw `Long` context pointer is likewise not something callers should hold: it must
 * be paired with exactly one [freeContext], which the engine guarantees with `try`/`finally`.
 *
 * This runs in the app process. It must never be used from the privileged recorder daemon:
 * transcription needs no privilege, and sharing a process with the capture path would put the most
 * fragile part of the app behind a CPU-saturating job.
 */
object WhisperNative {
    init { System.loadLibrary("whispercv") }

    /** ggml build/CPU feature string. Used to confirm the native library loaded at all. */
    external fun systemInfo(): String

    /** @return an opaque context pointer, or 0 when the model could not be loaded. */
    external fun initContext(modelPath: String): Long

    /** Releases a context from [initContext]. Safe to call with 0. */
    external fun freeContext(ptr: Long)

    /**
     * Runs recognition over [audio] — 16 kHz mono float in [-1, 1]. Results are read afterwards with
     * [segmentCount] and the segment accessors.
     *
     * @param language ISO code such as "he", or null to auto-detect. Passing the wrong language is
     *   not a soft failure: decoding Hebrew as English yields confident nonsense.
     */
    external fun transcribe(ptr: Long, audio: FloatArray, threads: Int, language: String?)

    /**
     * Asks a run in progress to stop, from any thread.
     *
     * [transcribe] is one blocking call that neither coroutine cancellation nor WorkManager can
     * interrupt, so without this Stop stopped nothing: the phone stayed at ~600% CPU until the run
     * finished by itself. whisper checks before each computation step, so this lands in well under a
     * second. The flag is cleared at the start of every [transcribe], so a stale abort cannot kill
     * the following run.
     */
    external fun requestAbort()

    external fun segmentCount(ptr: Long): Int

    /** Milliseconds from the start of the recording (whisper's centiseconds are converted natively). */
    external fun segmentStartMs(ptr: Long, index: Int): Long

    external fun segmentEndMs(ptr: Long, index: Int): Long

    external fun segmentText(ptr: Long, index: Int): String
}
