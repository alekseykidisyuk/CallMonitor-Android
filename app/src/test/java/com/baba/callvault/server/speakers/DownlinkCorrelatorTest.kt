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
import kotlin.random.Random

/**
 * Identifies the far-party channel by comparing the combined capture against a downlink-only one.
 *
 * The combined `VOICE_CALL` capture puts the two directions on two channels but never says which is
 * which — Android leaves the order to the OEM. `VOICE_DOWNLINK` has no such ambiguity: the platform
 * documents it as "voice call downlink (Rx)", the far party and nothing else. Recording a few
 * seconds of it alongside the call answers the question by observation rather than by inference.
 *
 * Loudness envelopes are compared rather than samples. The two captures are separate `AudioRecord`s
 * started microseconds apart with their own gain, so they are never sample-aligned; but when the far
 * party speaks, *both* get louder together, and that is all the comparison needs.
 */
class DownlinkCorrelatorTest {

    private val sampleRate = 48_000
    private val random = Random(20260823)

    @Test
    fun `names the channel that rises and falls with the downlink as the far party`() {
        // Arrange: channel A carries the far party — it is loud exactly when the downlink probe is.
        val correlator = DownlinkCorrelator(sampleRate)
        val speech = listOf(true, false, true, true, false, false, true, false, true, true, false, true)

        // Act
        speech.forEach { loud ->
            correlator.acceptCall(stereo(a = if (loud) 6000 else 30, b = if (loud) 40 else 5000))
            correlator.acceptDownlink(mono(if (loud) 5200 else 25))
        }

        // Assert
        assertEquals(ChannelMap.A_IS_FAR, correlator.result())
    }

    @Test
    fun `names the other channel just as readily`() {
        val correlator = DownlinkCorrelator(sampleRate)
        val speech = listOf(true, false, true, true, false, false, true, false, true, true, false, true)

        speech.forEach { loud ->
            correlator.acceptCall(stereo(a = if (loud) 40 else 5000, b = if (loud) 6000 else 30))
            correlator.acceptDownlink(mono(if (loud) 5200 else 25))
        }

        assertEquals(ChannelMap.B_IS_FAR, correlator.result())
    }

    @Test
    fun `refuses to choose when both channels track the downlink equally`() {
        // Leakage, or a HAL that mirrors the same mix onto both channels. A confident wrong answer
        // here shows the user's own words as the other person's.
        val correlator = DownlinkCorrelator(sampleRate)

        repeat(12) { i ->
            val loud = i % 2 == 0
            correlator.acceptCall(stereo(a = if (loud) 6000 else 30, b = if (loud) 6000 else 30))
            correlator.acceptDownlink(mono(if (loud) 5200 else 25))
        }

        assertEquals(ChannelMap.UNKNOWN, correlator.result())
    }

    @Test
    fun `refuses to choose from too few windows`() {
        val correlator = DownlinkCorrelator(sampleRate)

        repeat(3) {
            correlator.acceptCall(stereo(a = 6000, b = 30))
            correlator.acceptDownlink(mono(5200))
        }

        assertEquals(ChannelMap.UNKNOWN, correlator.result())
    }

    @Test
    fun `refuses to choose when nobody speaks`() {
        // A call held in silence says nothing about which channel is whose.
        val correlator = DownlinkCorrelator(sampleRate)

        repeat(20) {
            correlator.acceptCall(stereo(a = 3, b = 4))
            correlator.acceptDownlink(mono(2))
        }

        assertEquals(ChannelMap.UNKNOWN, correlator.result())
    }

    @Test
    fun `refuses to choose when the downlink capture yielded nothing`() {
        // The probe opened but the device fed it silence — the case that must not be read as an
        // answer, because silence correlates with a silent channel.
        val correlator = DownlinkCorrelator(sampleRate)

        repeat(20) { i ->
            val loud = i % 3 == 0
            correlator.acceptCall(stereo(a = if (loud) 6000 else 30, b = if (loud) 40 else 5000))
            correlator.acceptDownlink(mono(0))
        }

        assertEquals(ChannelMap.UNKNOWN, correlator.result())
    }

    @Test
    fun `survives a small delay between the two captures`() {
        // They are separate AudioRecords started microseconds apart, and the HAL may buffer one more
        // deeply than the other. A fixed one-window skew must not defeat the comparison.
        val correlator = DownlinkCorrelator(sampleRate)
        val speech = listOf(true, false, true, true, false, false, true, false, true, true, false, true, false, true)

        correlator.acceptDownlink(mono(25))   // the probe starts one window early — the lag search must absorb it
        speech.forEach { loud ->
            correlator.acceptCall(stereo(a = if (loud) 6000 else 30, b = if (loud) 40 else 5000))
            correlator.acceptDownlink(mono(if (loud) 5200 else 25))
        }

        assertEquals(ChannelMap.A_IS_FAR, correlator.result())
    }

    @Test
    fun `ignores a trailing partial frame instead of throwing`() {
        val correlator = DownlinkCorrelator(sampleRate)
        correlator.acceptCall(ByteArray(5))
        correlator.acceptDownlink(ByteArray(3))
        correlator.result()   // must not throw
    }

    /** One window of interleaved stereo PCM-16 at the given per-channel amplitude. */
    private fun stereo(a: Int, b: Int): ByteArray {
        val frames = sampleRate * DownlinkCorrelator.WINDOW_MS / 1000
        val out = ByteArray(frames * 4)
        for (f in 0 until frames) {
            putLe(out, f * 4, noisy(a))
            putLe(out, f * 4 + 2, noisy(b))
        }
        return out
    }

    /** One window of mono PCM-16 at the given amplitude. */
    private fun mono(amplitude: Int): ByteArray {
        val frames = sampleRate * DownlinkCorrelator.WINDOW_MS / 1000
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) putLe(out, f * 2, noisy(amplitude))
        return out
    }

    /** Amplitude with a little jitter, so no two envelopes are trivially identical. */
    private fun noisy(amplitude: Int): Int =
        if (amplitude == 0) 0 else (amplitude + random.nextInt(-amplitude / 10 - 1, amplitude / 10 + 1))

    private fun putLe(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
