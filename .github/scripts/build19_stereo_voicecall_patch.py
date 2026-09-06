from pathlib import Path

p = Path("app/src/main/java/com/baba/callvault/server/DirectAudioRecorderSession.kt")
s = p.read_text(encoding="utf-8")

old_block = '''        // ...but always ENCODE MONO. A phone call is mono content, and encoding the captured stereo as
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
'''

new_block = '''        // CallMonitor server transcription benefits more from deterministic speaker channels than from
        // the old mono downmix. VOICE_CALL on the tested Redmi Note 12 exposes stereo as two directions
        // (uplink/downlink). Preserve that stereo when the device encoder supports it; otherwise retain
        // the upstream mono fallback. For Opus stereo, never use less than 48 kbps total so each side has
        // roughly the quality the old 24 kbps mono recording had.
        val preserveStereo = source == ScrcpyAudioSource.VOICE_CALL &&
            captureChannels == 2 &&
            EncoderLimits.supportsFormat(mime, SAMPLE_RATE, 2)
        val encodeChannels = if (preserveStereo) 2 else 1
        val requestedBitRate = if (encodeChannels == 2 && codec == ScrcpyAudioCodec.OPUS)
            maxOf(bitRate, STEREO_OPUS_MIN_BIT_RATE)
        else bitRate

        // Ask the encoder what it will accept before handing it a bit rate. An out-of-range value is
        // not reliably rejected — MediaCodec can clamp it, or emit frames that decode to nothing.
        val effectiveBitRate = EncoderLimits.resolveBitRate(mime, requestedBitRate, SAMPLE_RATE, encodeChannels)
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, encodeChannels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitRate)
'''

assert old_block in s, "direct capture codec block not found"
s = s.replace(old_block, new_block, 1)

old_log = '''        AppLogger.i(TAG, "Direct capture started: source=${source.cliKey} codec=${codec.cliKey} captureCh=$captureChannels encodeCh=$ENCODE_CHANNELS rate=$SAMPLE_RATE")

        readThread = Thread { runCatching { captureLoop(record, enc, mux, captureChannels) }
'''
new_log = '''        AppLogger.i(TAG, "Direct capture started: source=${source.cliKey} codec=${codec.cliKey} captureCh=$captureChannels encodeCh=$encodeChannels rate=$SAMPLE_RATE bitrate=$effectiveBitRate")

        readThread = Thread { runCatching { captureLoop(record, enc, mux, captureChannels, encodeChannels) }
'''
assert old_log in s, "direct capture start/captureLoop call not found"
s = s.replace(old_log, new_log, 1)

old_sig = '''    private fun captureLoop(record: AudioRecord, enc: MediaCodec, mux: MediaMuxer, captureChannels: Int) {
        val pcm = ByteArray(READ_CHUNK_BYTES)
        val mono = ByteArray(READ_CHUNK_BYTES / 2)   // downmix target (half the samples of stereo input)
        val downmix = captureChannels == 2
        // Speaker turns come free from the stereo buffer we already hold: the two directions are on
        // separate channels here, and that information is destroyed by the downmix below. Only a
        // stereo capture carries it — a mono route has nothing to compare.
        val speakers = if (downmix) SpeakerTurnDetector(SAMPLE_RATE) else null
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        val bytesPerFrame = 2 * ENCODE_CHANNELS // PCM-16, mono → 2 bytes/frame (matches what we feed the encoder)
'''
new_sig = '''    private fun captureLoop(
        record: AudioRecord,
        enc: MediaCodec,
        mux: MediaMuxer,
        captureChannels: Int,
        encodeChannels: Int,
    ) {
        val pcm = ByteArray(READ_CHUNK_BYTES)
        val mono = ByteArray(READ_CHUNK_BYTES / 2)   // used only when stereo capture must fall back to mono encode
        val downmix = captureChannels == 2 && encodeChannels == 1
        // Keep speaker-turn detection whenever capture is stereo, whether or not we preserve the
        // channels in the output file. This remains useful as redundant metadata for server analysis.
        val speakers = if (captureChannels == 2) SpeakerTurnDetector(SAMPLE_RATE) else null
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        val bytesPerFrame = 2 * encodeChannels // PCM-16 bytes per encoded frame
'''
assert old_sig in s, "captureLoop signature/body not found"
s = s.replace(old_sig, new_sig, 1)

old_comment = '''            // Feed MONO to the encoder: downmix a stereo capture (average L+R), or pass a mono capture through.
            val (buf, len) = if (downmix) mono to PcmDownmix.stereoToMono(pcm, read, mono) else pcm to read
'''
new_comment = '''            // Preserve raw interleaved stereo for VOICE_CALL when possible. Only downmix when the
            // encoder/device cannot accept stereo; mono captures pass through unchanged.
            val (buf, len) = if (downmix) mono to PcmDownmix.stereoToMono(pcm, read, mono) else pcm to read
'''
assert old_comment in s, "PCM feed block not found"
s = s.replace(old_comment, new_comment, 1)

old_const = '''        /** Always encode mono — a call is mono content, so this gives the full bitrate to the voice. */
        private const val ENCODE_CHANNELS = 1
        private const val READ_CHUNK_BYTES = 4096
'''
new_const = '''        /** Upstream capability gate remains conservative: every supported device must encode mono. */
        private const val ENCODE_CHANNELS = 1
        /** Minimum total Opus bitrate when preserving the two VOICE_CALL directions as stereo. */
        private const val STEREO_OPUS_MIN_BIT_RATE = 48_000
        private const val READ_CHUNK_BYTES = 4096
'''
assert old_const in s, "encode constants block not found"
s = s.replace(old_const, new_const, 1)

p.write_text(s, encoding="utf-8")
