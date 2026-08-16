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
 * these functions directly — `TranscriptionEngine` owns that serialisation and is the only supported
 * entry point.
 *
 * This runs in the app process. It must never be used from the privileged recorder daemon:
 * transcription needs no privilege, and sharing a process with the capture path would put the most
 * fragile part of the app behind a CPU-saturating job.
 */
object WhisperNative {
    init { System.loadLibrary("whispercv") }

    /** ggml build/CPU feature string. Used to confirm the native library loaded at all. */
    external fun systemInfo(): String
}
