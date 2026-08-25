/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * How long a recording may be before on-device transcription refuses to start.
 *
 * **Why a refusal rather than a best effort.** Transcription decodes the whole recording into memory as
 * 16 kHz mono floats before the model sees any of it, so cost grows with length until the heap runs out
 * — and what the user gets is a crash or a job that dies silently, after a long wait, having produced
 * nothing. Refusing immediately with a reason is a strictly better outcome than trying and dying.
 *
 * **This is a stopgap and is meant to be deleted.** The real fix is to stop decoding the whole file at
 * once — streaming or chunked decode — at which point the limit can go. Agreed 2026-08-25: ship the
 * refusal now, do the decode work later.
 *
 * The threshold is a duration rather than a file size because size depends on codec and bit rate, while
 * the decoded buffer — the thing that actually exhausts the heap — depends only on length.
 */
object TranscriptionLengthLimit {

    /** Recordings longer than this are refused. */
    const val MAX_MINUTES: Int = 15

    private const val MAX_MS: Long = MAX_MINUTES * 60L * 1000L

    /**
     * Whether a recording of [durationMs] is too long to transcribe.
     *
     * **Unknown length is allowed through.** `AudioDecoder.durationMs` returns 0 when the container
     * declares no duration, and a VoIP recording's length is not always in the call log either. Refusing
     * on "unknown" would block ordinary short recordings whose metadata happens to be missing, which is
     * a worse failure than letting a rare long one through to the limit that already exists — the heap.
     */
    fun isTooLong(durationMs: Long): Boolean = durationMs > MAX_MS

    /** As [isTooLong], for the call-log duration in seconds (null when it is not known). */
    fun isTooLong(durationSeconds: Long?): Boolean =
        durationSeconds != null && isTooLong(durationSeconds * 1000L)
}
