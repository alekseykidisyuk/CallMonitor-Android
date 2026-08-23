/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import com.baba.callvault.data.ChannelMap
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Working out which captured channel is the far party, from the ringback before they answer.
 *
 * It cannot be hardcoded: Android documents `VOICE_CALL` as "uplink + downlink" and never specifies
 * which channel is which, leaving it an OEM decision. But it can be *learned*. On an outgoing call,
 * between the start of recording and the moment the far side picks up, the ringback tone comes from
 * the network — present on the far channel, absent from the near one, which carries only room noise.
 *
 * **Refusing to answer is a first-class result.** A confident wrong mapping shows the user's own
 * words as the other person's, which is far worse than showing neither name. Every ambiguous case
 * here asserts `UNKNOWN`.
 *
 * Synthetic PCM throughout — no device, no call, no recording.
 */
class ChannelMapDetectorTest {

    private val sampleRate = 48_000

    @Test
    fun `identifies the channel carrying a sustained tone as the far party`() {
        // Israel's ringback is ~400 Hz; the detector must not depend on that.
        val detector = feed(seconds = 3.0, toneHz = 400.0, toneOnChannel = 1, noiseOnOther = true)

        assertEquals(ChannelMap.B_IS_FAR, detector.result())
    }

    @Test
    fun `works whichever channel the tone is on`() {
        val detector = feed(seconds = 3.0, toneHz = 400.0, toneOnChannel = 0, noiseOnOther = true)

        assertEquals(ChannelMap.A_IS_FAR, detector.result())
    }

    @Test
    fun `does not depend on the ringback frequency`() {
        // 440 Hz in the UK, 425 in much of Europe, 440+480 in the US. Periodicity is the signal,
        // not a number.
        val detector = feed(seconds = 3.0, toneHz = 440.0, toneOnChannel = 1, noiseOnOther = true)

        assertEquals(ChannelMap.B_IS_FAR, detector.result())
    }

    @Test
    fun `reports unknown when neither channel carries a tone`() {
        // Some carriers send no ringback at all, and an answered-immediately call has no window.
        val detector = feed(seconds = 3.0, toneHz = null, toneOnChannel = 0, noiseOnOther = true)

        assertEquals(ChannelMap.UNKNOWN, detector.result())
    }

    @Test
    fun `reports unknown when both channels carry a tone`() {
        // Leakage between channels, or a speakerphone picking the ringback back up. Two candidates
        // is not a weaker answer, it is no answer.
        val detector = ChannelMapDetector(sampleRate)
        detector.accept(stereo(seconds = 3.0, left = tone(400.0), right = tone(400.0)))

        assertEquals(ChannelMap.UNKNOWN, detector.result())
    }

    @Test
    fun `reports unknown for an incoming call`() {
        // There is no ringback phase to learn from: the caller hears it, not us.
        val detector = ChannelMapDetector(sampleRate, isOutgoing = false)
        detector.accept(stereo(seconds = 3.0, left = silence(), right = tone(400.0)))

        assertEquals(ChannelMap.UNKNOWN, detector.result())
    }

    @Test
    fun `ignores a tone too short to be ringback`() {
        // A DTMF blip or a dial click is not ringback. Ringback holds for seconds.
        val detector = feed(seconds = 0.4, toneHz = 400.0, toneOnChannel = 1, noiseOnOther = true)

        assertEquals(ChannelMap.UNKNOWN, detector.result())
    }

    @Test
    fun `silence on both channels is unknown, not a coin toss`() {
        val detector = ChannelMapDetector(sampleRate)
        detector.accept(stereo(seconds = 3.0, left = silence(), right = silence()))

        assertEquals(ChannelMap.UNKNOWN, detector.result())
    }

    @Test
    fun `speech on one channel is not ringback`() {
        // The strongest false positive to avoid. If the far party answers instantly and talks, their
        // channel is loud and busy — but speech is broadband and shifting, where ringback holds one
        // narrow band steady. Mistaking speech for ringback would still give the right answer here,
        // but for the wrong reason, and would generalise to the near party talking first.
        val detector = ChannelMapDetector(sampleRate)
        detector.accept(stereo(seconds = 3.0, left = speechLike(), right = silence()))

        assertEquals(ChannelMap.UNKNOWN, detector.result())
    }

    @Test
    fun `nothing fed at all is unknown`() {
        assertEquals(ChannelMap.UNKNOWN, ChannelMapDetector(sampleRate).result())
    }

    // ---- synthetic audio ----

    private fun feed(seconds: Double, toneHz: Double?, toneOnChannel: Int, noiseOnOther: Boolean):
        ChannelMapDetector {
        val detector = ChannelMapDetector(sampleRate)
        val toneGen = toneHz?.let { tone(it) } ?: silence()
        val other = if (noiseOnOther) quietNoise() else silence()
        detector.accept(
            if (toneOnChannel == 0) stereo(seconds, left = toneGen, right = other)
            else stereo(seconds, left = other, right = toneGen)
        )
        return detector
    }

    /** A pure tone: one narrow band, held steady — what ringback looks like. */
    private fun tone(hz: Double): (Int) -> Short = { i ->
        (8000 * sin(2 * PI * hz * i / sampleRate)).toInt().toShort()
    }

    /** Room noise on the near channel: audible, but with no band holding the energy. */
    private fun quietNoise(): (Int) -> Short {
        var seed = 12345L
        return { _ ->
            seed = seed * 1103515245 + 12345
            ((seed shr 16) % 400).toShort()
        }
    }

    private fun silence(): (Int) -> Short = { 0 }

    /** Broadband and shifting, unlike a ringback tone. */
    private fun speechLike(): (Int) -> Short {
        var seed = 99L
        return { i ->
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val envelope = sin(2 * PI * 3.0 * i / sampleRate)
            val noise = ((seed shr 24) % 6000).toDouble()
            (noise * envelope).toInt().toShort()
        }
    }

    private fun stereo(seconds: Double, left: (Int) -> Short, right: (Int) -> Short): ByteArray {
        val frames = (seconds * sampleRate).toInt()
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = left(i)
            val r = right(i)
            out[i * 4] = (l.toInt() and 0xFF).toByte()
            out[i * 4 + 1] = (l.toInt() shr 8).toByte()
            out[i * 4 + 2] = (r.toInt() and 0xFF).toByte()
            out[i * 4 + 3] = (r.toInt() shr 8).toByte()
        }
        return out
    }
}
