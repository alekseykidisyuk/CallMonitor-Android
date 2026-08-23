/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.integrations.scrcpy.androidAudioSource
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.data.ChannelMap
import com.baba.callvault.server.speakers.ChannelMapDetector
import com.baba.callvault.server.speakers.DownlinkCorrelator
import com.baba.callvault.server.speakers.SpeakerTurnCodec
import com.baba.callvault.server.speakers.SpeakerTurnDetector
import com.baba.callvault.utils.PcmDownmix
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FAST capture pipeline: a direct `AudioRecord` → `MediaCodec` (encode) → `MediaMuxer` (mux) chain,
 * running IN the privileged daemon process. Replaces the scrcpy-server child for the common case.
 *
 * **Why it's faster.** The scrcpy path spawns a second `app_process`, extracts+verifies the scrcpy jar,
 * and does an abstract-socket handshake before a single sample is captured — ~1–2 s that clips the front
 * of the call, and it also adds the jar extraction to every daemon boot. Here the daemon (already a warm,
 * shell-uid process that holds `CAPTURE_AUDIO_OUTPUT`) opens `AudioRecord` directly: `startRecording()`
 * is ~milliseconds, so capture begins at the first frame with no child process and no jar.
 *
 * **Format parity.** The app creates the SAF output file from the chosen [ScrcpyAudioCodec] (Opus→.ogg,
 * AAC→.m4a), so this encodes to that exact codec/container — no app-side change. [supports] gates use to
 * a mic-type source AND a device that actually has the needed encoder; anything else falls back to scrcpy.
 */
internal class DirectAudioRecorderSession(
    private val source: ScrcpyAudioSource,
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    /** The daemon's received fd copy. The muxer writes through it; [stop] closes it after finalising. */
    private val outFd: ParcelFileDescriptor,
) : RecordingSession {

    private val stopRequested = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var readThread: Thread? = null

    /**
     * Speaker turns, published by the capture loop as it ends and read after [stop] joins it.
     *
     * Stays empty when the capture was mono, or if the loop never reached its end — degrading to "no
     * speaker data" rather than to a broken recording is the rule for everything in this file.
     */
    @Volatile private var speakerTurnsEncoded: String = ""

    /** What this call's ringback suggested, as a [ChannelMap] key. One observation, not the answer. */
    @Volatile private var channelMapObserved: String = ChannelMap.UNKNOWN.key

    /** The downlink-only probe, and the thread draining it. Both null when it could not be opened. */
    @Volatile private var downlinkProbe: AudioRecord? = null
    @Volatile private var probeThread: Thread? = null

    override fun speakerTurns(): String = speakerTurnsEncoded

    override fun observedChannelMap(): String = channelMapObserved

    override fun start() {
        try {
            startInternal()
        } catch (t: Throwable) {
            // Release our OWN resources but do NOT close outFd — the caller may retry over scrcpy with it.
            // MediaMuxer(FileDescriptor) does not own the fd, so release() leaves it open for the fallback.
            cleanupPartial()
            throw t
        }
    }

    private fun startInternal() {
        val androidSource = source.androidAudioSource
            ?: throw UnsupportedOperationException("source ${source.cliKey} is not a mic-type source")
        val mime = encoderMimeFor(codec)

        // Capture stereo when the route allows it — that reliably gets BOTH directions (uplink on one
        // channel, the remote party's downlink on the other); mono routes fall back to 1 channel.
        val (record, captureChannels) = openAudioRecord(androidSource)
        audioRecord = record

        // ...but always ENCODE MONO. A phone call is mono content, and encoding the captured stereo as
        // stereo Opus splits the bitrate across the two channels — at the default 24 kbps that leaves
        // ~12 kbps per side and audibly degrades the FAR party (their downlink channel gets starved).
        // Downmixing to one channel gives the whole bitrate to the (mono) call, restoring quality at the
        // same setting. See [captureLoop]'s downmix.
        // Ask the encoder what it will accept before handing it a bit rate. An out-of-range value is
        // not reliably rejected — MediaCodec can clamp it, or emit frames that decode to nothing,
        // which is a full-length recording that plays silent. Also logs the encoder and its limits,
        // so a future bug report can answer in one line what issue #18 never could.
        val effectiveBitRate = EncoderLimits.resolveBitRate(mime, bitRate, SAMPLE_RATE, ENCODE_CHANNELS)
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, ENCODE_CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val enc = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoder = enc

        // Create the muxer LAST — the risky AudioRecord/encoder setup above has succeeded, so if we get
        // here the output fd is only now consumed (keeps a clean fd for the scrcpy fallback if we'd thrown).
        val mux = MediaMuxer(outFd.fileDescriptor, codec.outputFormat)
        muxer = mux

        enc.start()
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord failed to enter RECORDING state")
        }
        AppLogger.i(TAG, "Direct capture started: source=${source.cliKey} codec=${codec.cliKey} captureCh=$captureChannels encodeCh=$ENCODE_CHANNELS rate=$SAMPLE_RATE")

        readThread = Thread { runCatching { captureLoop(record, enc, mux, captureChannels) }
            .onFailure { AppLogger.w(TAG, "Direct capture loop ended: ${it.message}") } }
            .apply { isDaemon = true; name = "direct-capture" }
            .also { it.start() }
    }

    /**
     * Reads PCM from [record], feeds it to [enc], and muxes the encoded output into [mux] until [stop]
     * signals EOS. Standard synchronous MediaCodec drive: queue input with a monotonic sample-count PTS,
     * drain output, add the track on INFO_OUTPUT_FORMAT_CHANGED (its format carries the Opus/AAC CSD).
     */
    private fun captureLoop(record: AudioRecord, enc: MediaCodec, mux: MediaMuxer, captureChannels: Int) {
        val pcm = ByteArray(READ_CHUNK_BYTES)
        val mono = ByteArray(READ_CHUNK_BYTES / 2)   // downmix target (half the samples of stereo input)
        val downmix = captureChannels == 2
        // Speaker turns come free from the stereo buffer we already hold: the two directions are on
        // separate channels here, and that information is destroyed by the downmix below. Only a
        // stereo capture carries it — a mono route has nothing to compare.
        val speakers = if (downmix) SpeakerTurnDetector(SAMPLE_RATE) else null
        // Which channel is the far party is an OEM detail Android never specifies, so it is learned
        // from the ringback at the start of an outgoing call — present on the far channel, absent
        // from the near one. Reads the same stereo buffer, and caps itself after a few seconds.
        val channelMap = if (downmix) ChannelMapDetector(SAMPLE_RATE) else null
        // The measurement that does not depend on hearing ringback — which the OP12 never delivers to
        // this capture, and which an incoming call cannot produce at all.
        val correlator = if (downmix && PROBE_ENABLED) DownlinkCorrelator(SAMPLE_RATE) else null
        if (correlator != null) {
            downlinkProbe = openDownlinkProbe()?.also { startProbeDrain(it, correlator) }
        }
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        val bytesPerFrame = 2 * ENCODE_CHANNELS // PCM-16, mono → 2 bytes/frame (matches what we feed the encoder)

        while (!stopRequested.get()) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) continue

            // Read the channels BEFORE the downmix averages them away. Guarded: a recording that works
            // is worth more than a label, so a fault here must cost the turns and nothing else.
            if (speakers != null) {
                runCatching { speakers.accept(pcm, read) }
                    .onFailure { AppLogger.w(TAG, "Speaker detection failed; continuing without turns: ${it.message}") }
            }
            if (channelMap != null) {
                runCatching { channelMap.accept(pcm, read) }
                    .onFailure { AppLogger.w(TAG, "Channel-map detection failed; continuing unmapped: ${it.message}") }
            }
            if (correlator != null) {
                runCatching { correlator.acceptCall(pcm, read) }
                    .onFailure { AppLogger.w(TAG, "Downlink comparison failed; continuing unmapped: ${it.message}") }
            }

            // Feed MONO to the encoder: downmix a stereo capture (average L+R), or pass a mono capture through.
            val (buf, len) = if (downmix) mono to PcmDownmix.stereoToMono(pcm, read, mono) else pcm to read

            val inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx)!!
                inBuf.clear(); inBuf.put(buf, 0, len)
                val ptsUs = totalFrames * 1_000_000L / SAMPLE_RATE
                enc.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                totalFrames += len / bytesPerFrame
            }
            muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
        }

        // Publish the turns before finalising, so stop() finds them once it has joined this thread.
        if (speakers != null) {
            runCatching { speakerTurnsEncoded = SpeakerTurnCodec.encode(speakers.finish()) }
                .onFailure { AppLogger.w(TAG, "Could not encode speaker turns: ${it.message}") }
        }
        if (channelMap != null) {
            // What this ONE call suggested. The app decides whether to believe it: it knows the
            // call's direction, and it will not trust any mapping until two calls agree.
            //
            // The downlink comparison is asked FIRST and outranks the ringback. It is a measurement
            // against a stream the platform defines as the far party, where ringback is an inference
            // from a tone that many devices never put into this capture at all — the OP12 among
            // them, measured 2026-08-23: silence throughout the ringing phase.
            val fromProbe = correlator?.let {
                runCatching { it.result() }
                    .onFailure { e -> AppLogger.w(TAG, "Could not compare with the downlink: ${e.message}") }
                    .getOrDefault(ChannelMap.UNKNOWN)
            } ?: ChannelMap.UNKNOWN
            val fromRingback = runCatching { channelMap.result() }
                .onFailure { AppLogger.w(TAG, "Could not read the channel map: ${it.message}") }
                .getOrDefault(ChannelMap.UNKNOWN)

            // ONLY the probe is reported. The ringback detector cannot tell an incoming call from an
            // outgoing one — it assumes outgoing — so on an incoming call its answer is a coin flip
            // dressed as evidence, and the app has no way to tell the two sources apart in a single
            // string. It stays for the log, where it costs nothing and may yet prove useful on a
            // device that does deliver ringback into this capture.
            channelMapObserved = fromProbe.key
            AppLogger.i(
                TAG,
                "Channel map observed: $channelMapObserved (downlink=${fromProbe.key} ringback=${fromRingback.key})"
            )
            // Levels and correlations, because "unknown" has several causes that need different
            // answers: a silent probe means the device does not really implement the source, while
            // two equal correlations mean it does and the channels are not distinguishable this way.
            correlator?.let { AppLogger.i(TAG, "Downlink comparison: ${it.diagnostics()}") }
        }

        // Signal end-of-stream so the encoder flushes its tail, then drain what's left.
        val inIdx = enc.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
        if (inIdx >= 0) enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainEncoder(enc, mux, info, muxerStarted, drainToEos = true)
    }

    /** Drains available encoder output into the muxer. Returns whether the muxer is (now) started. */
    private fun drainEncoder(
        enc: MediaCodec, mux: MediaMuxer, info: MediaCodec.BufferInfo,
        muxerStartedIn: Boolean, drainToEos: Boolean = false,
    ): Boolean {
        var muxerStarted = muxerStartedIn
        var track = if (muxerStarted) 0 else -1
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, if (drainToEos) END_OF_STREAM_TIMEOUT_US else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = mux.addTrack(enc.outputFormat) // format carries codec-specific data (CSD)
                    mux.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (drainToEos) continue else return muxerStarted // no output ready right now
                }
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        mux.writeSampleData(track, outBuf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return muxerStarted
                }
            }
        }
    }

    override fun stop() {
        AppLogger.i(TAG, "Stopping direct capture session")
        stopRequested.set(true)
        // Let the capture loop notice the stop flag, flush EOS, and finalise the muxer.
        runCatching { readThread?.join(READ_JOIN_MS) }
        // The probe usually released itself long before this; this is for a call shorter than it.
        runCatching { probeThread?.join(READ_JOIN_MS) }
        runCatching { downlinkProbe?.stop() }
        runCatching { downlinkProbe?.release() }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        // Muxer LAST among writers — stop() writes the container trailer (without it the file won't play).
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { outFd.close() }
        AppLogger.i(TAG, "Direct capture session stopped")
    }

    /** Releases capture resources on a failed [start] WITHOUT closing [outFd] (the caller retries scrcpy). */
    private fun cleanupPartial() {
        runCatching { audioRecord?.release() }
        runCatching { encoder?.release() }
        runCatching { muxer?.release() } // MediaMuxer.release() does NOT close the fd — outFd stays usable
        audioRecord = null; encoder = null; muxer = null
    }

    /**
     * Opens a short `VOICE_DOWNLINK` capture beside the call, purely to identify the far channel.
     *
     * The combined `VOICE_CALL` capture carries both directions but never says which channel is
     * which — Android documents the source as "uplink + downlink" and leaves the order to the OEM.
     * `VOICE_DOWNLINK` is defined as the far party and nothing else, so a few seconds of it beside
     * the call answers by measurement what no amount of inspecting one stream can.
     *
     * **It is a diagnostic, never part of the recording.** Nothing it captures is written anywhere;
     * only the comparison survives. Every failure path — a device that refuses a second capture, a
     * source it does not implement, a probe fed silence — ends in no observation and a log line, and
     * the recording continues exactly as it would have. It also stops itself after
     * [PROBE_WINDOWS] windows so it holds no audio input for longer than it needs.
     *
     * @return the probe, already recording, or null if it could not be had.
     */
    private fun openDownlinkProbe(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null

        val rec = runCatching {
            @Suppress("MissingPermission") // shell uid holds CAPTURE_AUDIO_OUTPUT; the daemon is not an app.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_DOWNLINK, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR
            )
        }.onFailure {
            AppLogger.i(TAG, "Downlink probe unavailable on this device: ${it.message}")
        }.getOrNull() ?: return null

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.i(TAG, "Downlink probe would not initialise; channel mapping stays unlearned")
            runCatching { rec.release() }
            return null
        }
        // A second capture can be refused at start() rather than at construction.
        if (runCatching { rec.startRecording() }.isFailure ||
            rec.recordingState != AudioRecord.RECORDSTATE_RECORDING
        ) {
            AppLogger.i(TAG, "Downlink probe refused to start; channel mapping stays unlearned")
            runCatching { rec.release() }
            return null
        }
        AppLogger.i(TAG, "Downlink probe open on VOICE_DOWNLINK (mono ${SAMPLE_RATE}Hz)")
        return rec
    }

    /**
     * Drains the probe into [correlator] on its own thread.
     *
     * Its own thread because the capture loop must never wait on it: that loop is the recording, and
     * a probe read that blocks would starve the encoder of the audio the user actually asked for.
     */
    private fun startProbeDrain(probe: AudioRecord, correlator: DownlinkCorrelator) {
        probeThread = Thread {
            val buf = ByteArray(READ_CHUNK_BYTES)
            var windows = 0
            runCatching {
                while (!stopRequested.get() && windows < PROBE_WINDOWS) {
                    val read = probe.read(buf, 0, buf.size)
                    if (read <= 0) continue
                    correlator.acceptDownlink(buf, read)
                    windows += read / (SAMPLE_RATE * DownlinkCorrelator.WINDOW_MS / 1000 * 2)
                }
                AppLogger.i(TAG, "Downlink probe drained $windows window(s)")
            }.onFailure { AppLogger.w(TAG, "Downlink probe read failed: ${it.message}") }
            // Released as soon as it has what it needs, so nothing holds a voice input needlessly.
            runCatching { probe.stop() }
            runCatching { probe.release() }
            downlinkProbe = null
        }.apply { isDaemon = true; start() }
    }

    private fun openAudioRecord(androidSource: Int): Pair<AudioRecord, Int> {
        for (channelMask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val channels = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val rec = runCatching {
                @Suppress("MissingPermission") // shell uid holds CAPTURE_AUDIO_OUTPUT; the daemon is not an app.
                AudioRecord(androidSource, SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR)
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec to channels
            runCatching { rec?.release() }
        }
        throw IllegalStateException("AudioRecord would not initialise for source $androidSource (stereo or mono)")
    }

    companion object {
        private const val TAG = "CV:DirectCapture"

        /** Match scrcpy's output so the muxed file is equivalent (48 kHz). */
        private const val SAMPLE_RATE = 48_000

        /** Always encode mono — a call is mono content, so this gives the full bitrate to the voice. */
        private const val ENCODE_CHANNELS = 1
        private const val READ_CHUNK_BYTES = 4096

        /**
         * How long the downlink probe listens, in [DownlinkCorrelator.WINDOW_MS] windows.
         *
         * 400 windows is twenty seconds — long enough for both sides to have spoken on almost any
         * call, and short enough that a second voice input is not held for the whole conversation.
         */
        private const val PROBE_WINDOWS = 400

        /**
         * OFF. The probe is suspected of costing the recording its near side.
         *
         * Measured on the OP12, 2026-08-23: the first call recorded with the probe running captured
         * the far party and **nothing of the user** — one burst of speech where there had been two.
         * The likely mechanism is that opening a second voice capture makes the HAL re-route the
         * combined VOICE_CALL stream to downlink only, so the diagnostic silently took half the
         * conversation away.
         *
         * Unproven, but it does not need to be proven to be switched off. Half a recording is a lost
         * call, and this exists only to put a name on a label. It stays off until a run with it off
         * shows whole recordings and a run with it on reproduces the loss.
         */
        private const val PROBE_ENABLED = false
        private const val MAX_INPUT_SIZE = 16_384
        private const val BUFFER_FACTOR = 4
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val READ_JOIN_MS = 2_000L

        /**
         * True if the direct pipeline can handle this [source]+[codec] on THIS device: the source must be
         * a mic-type `AudioSource` (not output/playback capture) AND the device must have an encoder for
         * the codec's MIME. Otherwise [RecorderServer] uses the scrcpy fallback.
         */
        fun supports(source: ScrcpyAudioSource, codec: ScrcpyAudioCodec): Boolean {
            if (source.androidAudioSource == null) return false
            val mime = encoderMimeFor(codec)
            return runCatching {
                // An encoder EXISTING is not the same as it accepting our format. Recording 48 kHz
                // mono into an encoder that advertises neither is how a full-length silent file is
                // produced; better to fall back to scrcpy, which brings its own pipeline.
                hasEncoder(mime) && EncoderLimits.supportsFormat(mime, SAMPLE_RATE, ENCODE_CHANNELS)
            }.getOrDefault(false)
        }

        private fun encoderMimeFor(codec: ScrcpyAudioCodec): String = when (codec) {
            ScrcpyAudioCodec.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
            ScrcpyAudioCodec.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
        }

        private fun hasEncoder(mime: String): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
    }
}
