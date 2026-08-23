/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import com.baba.callvault.data.ChannelMap

import kotlin.math.sqrt

/**
 * Learns which captured channel is the far party, from the ringback before they answer.
 *
 * Android documents `VOICE_CALL` as "uplink + downlink" and never specifies the channel order, so
 * the mapping is an OEM detail that cannot be assumed. On an **outgoing** call there is a window
 * between recording starting and the far side picking up where the network's ringback plays: present
 * on the far channel, absent from the near one, which carries only room noise. Whichever channel
 * holds a sustained tone through that window is the far party.
 *
 * **Periodicity, not a frequency.** Ringback is ~400 Hz in Israel, 425 across much of Europe, 440
 * and 480 together in the United States. Matching a number would work in one country. What is
 * common to all of them is a single narrow band holding the energy, steadily, for seconds — which
 * is also what separates ringback from speech, which is broadband and constantly shifting.
 *
 * **Refusing is a result.** Every ambiguous case returns [ChannelMap.UNKNOWN] and the transcript
 * shows neutral labels. A confident wrong mapping puts the user's own words in the other person's
 * mouth, on a record of a real conversation.
 *
 * Pure and Android-free, so it is tested with synthetic PCM rather than a phone call.
 */
class ChannelMapDetector(
    private val sampleRate: Int,
    /** Only an outgoing call has a ringback phase; on an incoming one we hear none. */
    private val isOutgoing: Boolean = true
) {

    /** Per-window periodicity scores for each channel, while the window is still open. */
    private val toneWindows = intArrayOf(0, 0)
    private var windows = 0

    private val samplesPerWindow = sampleRate * WINDOW_MS / MILLIS_PER_SECOND

    /**
     * Feeds interleaved stereo PCM-16 captured before the call was answered.
     *
     * Anything after the answer is not ringback and must not be fed here — see
     * [com.baba.callvault.server.DirectAudioRecorderSession], which stops feeding at OFFHOOK.
     */
    fun accept(pcm: ByteArray, len: Int = pcm.size) {
        if (!isOutgoing || windows >= MAX_WINDOWS) return

        val frames = len / BYTES_PER_FRAME
        var frame = 0
        while (frame + samplesPerWindow <= frames && windows < MAX_WINDOWS) {
            scoreWindow(pcm, frame)
            frame += samplesPerWindow
            windows++
        }
    }

    /**
     * The mapping, or [ChannelMap.UNKNOWN].
     *
     * Demands three things at once, and any of them failing means no answer: enough audio to have
     * been ringback rather than a blip, one channel tonal for most of it, and the *other* channel
     * not. Two tonal channels is leakage, and two candidates is no candidate.
     */
    fun result(): ChannelMap {
        if (!isOutgoing) return ChannelMap.UNKNOWN
        // A dial click or a DTMF blip can be tonal. Ringback lasts seconds.
        if (windows < MIN_WINDOWS) return ChannelMap.UNKNOWN

        val tonalA = toneWindows[0] >= windows * SUSTAINED_FRACTION
        val tonalB = toneWindows[1] >= windows * SUSTAINED_FRACTION

        return when {
            tonalA && !tonalB -> ChannelMap.A_IS_FAR
            tonalB && !tonalA -> ChannelMap.B_IS_FAR
            else -> ChannelMap.UNKNOWN
        }
    }

    /** Marks each channel tonal or not for one window of [WINDOW_MS]. */
    private fun scoreWindow(pcm: ByteArray, startFrame: Int) {
        for (channel in 0..1) {
            if (isTonal(pcm, startFrame, channel)) toneWindows[channel]++
        }
    }

    /**
     * Is this channel's window a sustained tone?
     *
     * Measured by autocorrelation: a periodic signal correlates strongly with itself shifted by one
     * period, and noise or speech does not. Lag is searched across the range ringback tones fall in
     * rather than at one frequency, so the country does not matter.
     *
     * Quiet windows are never tonal — silence correlates with itself perfectly and would otherwise
     * look like the strongest tone in the room.
     */
    private fun isTonal(pcm: ByteArray, startFrame: Int, channel: Int): Boolean {
        val n = samplesPerWindow
        val samples = DoubleArray(n)
        var energy = 0.0

        for (i in 0 until n) {
            val v = sampleAt(pcm, (startFrame + i) * BYTES_PER_FRAME + channel * BYTES_PER_SAMPLE).toDouble()
            samples[i] = v
            energy += v * v
        }

        val rms = sqrt(energy / n)
        if (rms < QUIET_FLOOR) return false

        val minLag = sampleRate / MAX_TONE_HZ
        val maxLag = sampleRate / MIN_TONE_HZ
        var best = 0.0

        var lag = minLag
        while (lag <= maxLag && lag < n / 2) {
            var correlation = 0.0
            for (i in 0 until n - lag) correlation += samples[i] * samples[i + lag]
            // Normalised against the window's own energy, so loudness cannot masquerade as pitch.
            val normalised = correlation / (energy * (n - lag).toDouble() / n)
            if (normalised > best) best = normalised
            lag += LAG_STEP
        }

        return best >= PERIODICITY
    }

    /** Reads a little-endian PCM-16 sample at [offset]. */
    private fun sampleAt(pcm: ByteArray, offset: Int): Short {
        if (offset + 1 >= pcm.size) return 0
        val low = pcm[offset].toInt() and 0xFF
        val high = pcm[offset + 1].toInt()
        return ((high shl 8) or low).toShort()
    }

    private companion object {
        const val WINDOW_MS = 100
        const val MILLIS_PER_SECOND = 1000
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_FRAME = 4 // PCM-16 stereo

        /** About a second of audio. Below this a tone could be a dial blip rather than ringback. */
        const val MIN_WINDOWS = 10

        /** Ringback is decided within a few seconds; more audio cannot make the answer better. */
        const val MAX_WINDOWS = 100

        /** How much of the listening window a channel must be tonal for to count as ringback. */
        const val SUSTAINED_FRACTION = 0.6

        /**
         * The band ringback tones live in, worldwide.
         *
         * Wide on purpose: 400 Hz in Israel, 425 in much of Europe, 440 and 480 in the United
         * States, 400 in the UK. Searching a range rather than matching a number is what makes this
         * work outside the country it was written in.
         */
        const val MIN_TONE_HZ = 300
        const val MAX_TONE_HZ = 600

        /** Coarse enough to be cheap; ringback tones are far apart relative to this. */
        const val LAG_STEP = 2

        /** Below this the window is room tone, and silence autocorrelates perfectly. */
        const val QUIET_FLOOR = 200.0

        /** How self-similar a window must be, one period apart, to be called a tone. */
        const val PERIODICITY = 0.7
    }
}
