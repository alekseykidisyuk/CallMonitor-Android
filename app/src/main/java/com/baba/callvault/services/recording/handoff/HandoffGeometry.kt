/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

/**
 * Shape of a delivered capture control block: where the audio ring sits inside the cblk ashmem region
 * and how the drain must walk it.
 *
 * Every field arrives over IPC and is fed straight into native pointer arithmetic, so this type exists
 * to keep that arithmetic — and the validation that must precede it — in one pure, testable place.
 * Nothing may mmap or drain a cblk before [validationError] has returned null for it.
 *
 * @param frameCount frames the track was allocated (`AudioRecord.bufferSizeInFrames`).
 * @param sampleRate capture rate in Hz.
 * @param channels   captured channels (1 mono, 2 interleaved stereo).
 * @param cblkSize   true byte size of the ashmem region, from `ASHMEM_GET_SIZE`.
 */
data class HandoffGeometry(
    val frameCount: Int,
    val sampleRate: Int,
    val channels: Int,
    val cblkSize: Int,
) {
    /** Bytes per frame across all captured channels (PCM-16). */
    val frameSize: Int get() = channels * BYTES_PER_SAMPLE

    /**
     * The frame count the ring PHYSICALLY wraps at: positions are masked with `wrapFrames - 1`, so the
     * ring rounds up to the next power of two rather than wrapping at [frameCount].
     *
     * This mirrors `AudioRecordClientProxy::obtainBuffer` (`front &= mFrameCountP2 - 1`). Wrapping at
     * [frameCount] instead reads the untouched region between [frameCount] and the rounded-up size,
     * which is exactly the periodic-gap bug this constant was introduced to fix — every 48 kHz
     * configuration has a non-power-of-two frame count.
     */
    val wrapFrames: Int
        get() {
            var p2 = 1
            while (p2 < frameCount) p2 = p2 shl 1
            return p2
        }

    /** Last byte of the ring, exclusive — must fit inside [cblkSize]. */
    val ringEndOffset: Long get() = DATA_OFF + wrapFrames.toLong() * frameSize

    /**
     * Why this geometry must not be used, or null if it is safe to drain.
     *
     * The delivery arrives over an exported provider. Callers already reject untrusted UIDs, but the
     * values still get turned into native offsets, so a corrupt or hostile payload must be refused here
     * rather than becoming an out-of-bounds read.
     */
    fun validationError(): String? = when {
        channels !in MIN_CHANNELS..MAX_CHANNELS -> "bad channels=$channels"
        sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE -> "bad rate=$sampleRate"
        frameCount <= 0 -> "bad frameCount=$frameCount"
        cblkSize <= 0 -> "fd is not a sized ashmem region (size=$cblkSize)"
        ringEndOffset > cblkSize ->
            "geometry doesn't fit ashmem (dataOff=$DATA_OFF wrapFrames=$wrapFrames frameSize=$frameSize > $cblkSize)"
        else -> null
    }

    companion object {
        /** Byte offset of the audio buffer within `audio_track_cblk_t` (Android 16). */
        const val DATA_OFF = 232

        /** Freshest frames held back each drain cycle — they may still be mid-write by the server. */
        const val GUARD_FRAMES = 32

        private const val BYTES_PER_SAMPLE = 2 // PCM-16
        private const val MIN_CHANNELS = 1
        private const val MAX_CHANNELS = 2
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 192_000

        /** Frame count assumed when the daemon could not report one. */
        const val FRAME_COUNT_FALLBACK = 2048

        /** Builds a geometry, substituting [FRAME_COUNT_FALLBACK] for a missing frame count. */
        fun of(frameCount: Int, sampleRate: Int, channels: Int, cblkSize: Int) = HandoffGeometry(
            frameCount = if (frameCount > 0) frameCount else FRAME_COUNT_FALLBACK,
            sampleRate = sampleRate,
            channels = channels,
            cblkSize = cblkSize,
        )
    }
}
