/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
 * chunked, VAD-segmented decoding, which the pipeline needs anyway for resumability — see the design
 * note. Until then callers must treat a very long recording as a real failure mode.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000

    private const val DEQUEUE_TIMEOUT_US = 10_000L

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
     * Decodes [uri] to 16 kHz mono float. Blocking and CPU-bound — [TranscriptionEngine] is what moves
     * it off the main thread; do not call it directly from UI code.
     */
    fun decodeToMono16k(context: Context, uri: Uri): FloatArray {
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
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val pcm = decodePcm(extractor, format, mime)
            val mono = pcm16ToMonoFloat(pcm, channels)
            return resampleTo16k(mono, sampleRate)
        } finally {
            extractor.release()
        }
    }

    /**
     * Drains the decoder into raw little-endian PCM-16.
     *
     * Bytes are accumulated rather than boxed `Short`s on purpose: a ten-minute call is ~29 million
     * samples, and a `List<Short>` of that would cost hundreds of megabytes in object headers alone.
     */
    private fun decodePcm(extractor: MediaExtractor, format: MediaFormat, mime: String): ShortArray {
        val codec = MediaCodec.createDecoderByType(mime)
        val sink = ByteArrayOutputStream()
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
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
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(chunk, 0, info.size)
                        sink.write(chunk)
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
        return shorts
    }
}
