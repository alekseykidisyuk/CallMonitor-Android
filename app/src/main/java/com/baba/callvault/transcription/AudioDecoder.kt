/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.baba.callvault.utils.AppLogger
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Turns a stored recording into the audio whisper.cpp requires: 16 kHz mono float in [-1, 1].
 *
 * CallVault writes 48 kHz mono Opus, but stereo is handled too — recordings made before the mandatory
 * mono downmix (v1.4.4) are stereo and the back-catalogue still contains them.
 *
 * **Known limit, deliberately not solved here.** [decodeToMono16k] decodes the whole file into memory:
 * roughly 210 MB of transient buffers for a 10-minute call, and proportionally more beyond that. That
 * is fine for typical calls but not for very long ones, especially alongside a ~1 GB model. The fix is
 * chunked, VAD-segmented decoding, which the pipeline needs anyway for resumability.
 */
object AudioDecoder {

    private const val TAG = "CV:AudioDecoder"

    const val TARGET_SAMPLE_RATE = 16_000

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * How far the decoded length may stray from the container's declared duration before it is
     * treated as a bug rather than as ordinary imprecision. Generous on purpose: the point is to catch
     * an order-of-magnitude error, not to police a few dropped frames.
     */
    private const val DURATION_TOLERANCE = 2.0

    /** PCM plus the format it is *actually* in, which is the decoder's output, not the file's input. */
    private data class DecodedPcm(val pcm: ShortArray, val sampleRate: Int, val channels: Int)

    /**
     * Interleaved PCM-16 to mono float. Extra channels are averaged in rather than dropped, so a
     * stereo call where each party sits on one side keeps both voices.
     *
     * A trailing partial frame is ignored: integer division bounds the loop, which is what stops a
     * truncated file reading past the end of the array.
     */
    fun pcm16ToMonoFloat(pcm: ShortArray, channels: Int): FloatArray {
        require(channels >= 1) { "channels must be >= 1, was $channels" }
        val frames = pcm.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += pcm[f * channels + c] / 32767f
            out[f] = (acc / channels).coerceIn(-1f, 1f)
        }
        return out
    }

    /**
     * Linear resample to [TARGET_SAMPLE_RATE].
     *
     * Linear interpolation is adequate precisely because of what these recordings are: measured
     * telephony audio carries no energy above ~3.4 kHz, far below the 8 kHz Nyquist limit of the
     * target rate, so there is nothing up there for a cruder filter to alias down.
     */
    fun resampleTo16k(input: FloatArray, inputRate: Int): FloatArray {
        if (inputRate == TARGET_SAMPLE_RATE || input.isEmpty()) return input
        val ratio = inputRate.toDouble() / TARGET_SAMPLE_RATE
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i * ratio
            val a = src.toInt()
            val b = (a + 1).coerceAtMost(input.size - 1)
            val frac = (src - a).toFloat()
            out[i] = input[a] * (1 - frac) + input[b] * frac
        }
        return out
    }

    /**
     * Whether [frames] at [sampleRate] is a believable decode of a file declaring [expectedDurationUs].
     *
     * This exists because of a real failure: handing whisper an over-long or malformed buffer does not
     * throw — it simply runs for as long as the buffer implies. A 45-second clip once transcribed for
     * over eleven minutes before anyone could tell anything was wrong. Checking the arithmetic costs
     * nothing and converts that into an immediate, legible error.
     *
     * Returns true when the duration is unknown ([expectedDurationUs] <= 0), because some containers
     * genuinely do not declare one and that is not evidence of a bug.
     */
    fun isPlausibleDuration(frames: Int, sampleRate: Int, expectedDurationUs: Long): Boolean {
        if (expectedDurationUs <= 0L) return true
        if (sampleRate <= 0) return false
        val decodedMs = frames * 1000.0 / sampleRate
        val expectedMs = expectedDurationUs / 1000.0
        if (decodedMs == 0.0) return expectedMs == 0.0
        val ratio = decodedMs / expectedMs
        return ratio in (1.0 / DURATION_TOLERANCE)..DURATION_TOLERANCE
    }

    /**
     * Decodes [uri] to 16 kHz mono float. Blocking and CPU-bound — [TranscriptionEngine] is what moves
     * it off the main thread; do not call it directly from UI code.
     *
     * @param shouldStop consulted while decoding so a stop is honoured here too. Decoding a long
     *   recording is otherwise uninterruptible — a 1.5-hour call kept burning CPU for ~12 seconds
     *   after Stop was tapped, because it had not reached whisper yet.
     * @throws IllegalStateException if the decode produces an implausible amount of audio, rather than
     *   letting a malformed buffer reach whisper where it costs minutes of silent CPU burn.
     * @throws InterruptedException if [shouldStop] becomes true while decoding.
     */
    fun decodeToMono16k(
        context: Context,
        uri: Uri,
        shouldStop: () -> Boolean = { false }
    ): FloatArray {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                requireNotNull(pfd) { "Cannot open $uri" }
                extractor.setDataSource(pfd.fileDescriptor)
            }
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in $uri")

            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val expectedDurationUs =
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            val decoded = decodePcm(extractor, inputFormat, mime, shouldStop)
            val mono = pcm16ToMonoFloat(decoded.pcm, decoded.channels)

            check(isPlausibleDuration(mono.size, decoded.sampleRate, expectedDurationUs)) {
                "Decoded ${mono.size} frames at ${decoded.sampleRate} Hz " +
                    "(${mono.size * 1000L / decoded.sampleRate.coerceAtLeast(1)} ms) but the container " +
                    "declares ${expectedDurationUs / 1000} ms — refusing to transcribe a malformed decode"
            }

            AppLogger.i(
                TAG,
                "Decoded $uri: ${mono.size} frames @ ${decoded.sampleRate} Hz, ${decoded.channels} ch " +
                    "(${mono.size * 1000L / decoded.sampleRate.coerceAtLeast(1)} ms)",
            )
            return resampleTo16k(mono, decoded.sampleRate)
        } finally {
            extractor.release()
        }
    }

    /**
     * Drains the decoder into raw little-endian PCM-16, reporting the format the decoder **actually
     * produced**.
     *
     * Taking the sample rate and channel count from the extractor's input format is a bug: a decoder
     * may emit a different layout, and believing the wrong one silently rescales the whole recording.
     * `INFO_OUTPUT_FORMAT_CHANGED` is therefore honoured rather than ignored.
     *
     * Bytes are accumulated rather than boxed `Short`s on purpose: a ten-minute call is ~29 million
     * samples, and a `List<Short>` of that would cost hundreds of megabytes in object headers alone.
     */
    private fun decodePcm(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mime: String,
        shouldStop: () -> Boolean
    ): DecodedPcm {
        val codec = MediaCodec.createDecoderByType(mime)
        val sink = ByteArrayOutputStream()

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                // Decoding a long recording takes a while and is otherwise uninterruptible: a
                // 1.5-hour call spent ~12 seconds here after Stop was tapped, still burning CPU. The
                // check is per output buffer, so it costs nothing and reacts within milliseconds.
                if (shouldStop()) throw InterruptedException("decode stopped")

                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        // The decoder is the authority on what it emits; the extractor only describes
                        // the file. Where they disagree, believing the file rescales the recording.
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)

                        val encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        check(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                            "Decoder emitted PCM encoding $encoding; this reader only understands 16-bit. " +
                                "Reading float samples as shorts would double the sample count and produce noise."
                        }
                        AppLogger.i(TAG, "Decoder output format: ${sampleRate} Hz, $channels ch")
                    }

                    else -> if (outIndex >= 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(chunk, 0, info.size)
                            sink.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        val bytes = sink.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return DecodedPcm(shorts, sampleRate, channels)
    }
}
