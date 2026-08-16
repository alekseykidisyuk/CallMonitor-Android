/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderTest {

    // ---- PCM-16 to mono float ----

    @Test
    fun `mono pcm16 is scaled into the minus one to one range`() {
        val pcm = shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE)
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 1)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f), out, 0.001f)
    }

    @Test
    fun `stereo pcm16 is averaged down to mono`() {
        // L=+full R=-full must average to silence, not to a doubled or clipped sample.
        val pcm = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 1000, 1000)
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(1000f / 32767f, out[1], 0.001f)
    }

    @Test
    fun `an odd trailing frame in stereo input is dropped rather than read out of bounds`() {
        // Recordings made before the mandatory mono downmix are stereo, and a truncated file can end
        // mid-frame. Reading past the end here would be an out-of-bounds crash on the back-catalogue.
        val pcm = shortArrayOf(100, 200, 300) // 1.5 stereo frames
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 2)
        assertEquals(1, out.size)
    }

    @Test
    fun `empty input produces empty output rather than throwing`() {
        assertEquals(0, AudioDecoder.pcm16ToMonoFloat(shortArrayOf(), channels = 1).size)
    }

    @Test
    fun `every produced sample stays within the range whisper requires`() {
        val pcm = ShortArray(512) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 1)
        assertTrue(out.all { it in -1f..1f })
    }

    // ---- resampling ----

    @Test
    fun `audio already at the target rate is returned untouched`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out = AudioDecoder.resampleTo16k(input, inputRate = 16_000)
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun `48k is resampled to a third of the sample count`() {
        // CallVault records at 48 kHz, so 3:1 is the case that actually runs in production.
        val input = FloatArray(48_000) { 0.5f }
        val out = AudioDecoder.resampleTo16k(input, inputRate = 48_000)
        assertEquals(16_000, out.size)
    }

    @Test
    fun `a constant signal survives resampling as the same constant`() {
        // Interpolation must not introduce ripple on a flat input.
        val input = FloatArray(4_800) { 0.25f }
        val out = AudioDecoder.resampleTo16k(input, inputRate = 48_000)
        assertTrue(out.all { kotlin.math.abs(it - 0.25f) < 0.001f })
    }

    @Test
    fun `resampling empty audio yields empty audio`() {
        assertEquals(0, AudioDecoder.resampleTo16k(FloatArray(0), inputRate = 48_000).size)
    }

    // ---- duration plausibility ----
    //
    // These exist because of a real incident on 2026-08-16: a 45-second clip was handed to whisper
    // malformed and transcribed for over eleven minutes before anything indicated a problem. whisper
    // does not reject an over-long buffer, it just works through it, so the arithmetic has to be
    // checked here instead.

    @Test
    fun `a decode matching the declared duration is plausible`() {
        // 45 s at 48 kHz.
        assertTrue(AudioDecoder.isPlausibleDuration(45 * 48_000, 48_000, 45_000_000L))
    }

    @Test
    fun `the eleven-minute-from-45-seconds case is rejected`() {
        // What the failed on-device run effectively did: ~15x more audio than the file declares.
        assertFalse(AudioDecoder.isPlausibleDuration(45 * 15 * 48_000, 48_000, 45_000_000L))
    }

    @Test
    fun `reading float samples as shorts would double the length and is rejected`() {
        // The concrete mis-decode this guards: 32-bit float PCM read as 16-bit doubles the count.
        assertFalse(AudioDecoder.isPlausibleDuration(45 * 48_000 * 2 + 1, 48_000, 45_000_000L))
    }

    @Test
    fun `a wrong sample rate assumption that triples the audio is rejected`() {
        // Believing the file's 48 kHz when the decoder actually emitted 16 kHz.
        assertFalse(AudioDecoder.isPlausibleDuration(45 * 48_000 * 3, 48_000, 45_000_000L))
    }

    @Test
    fun `an unknown declared duration is not treated as a failure`() {
        // Some containers do not declare one; that is not evidence of a bug.
        assertTrue(AudioDecoder.isPlausibleDuration(1_000, 48_000, 0L))
    }

    @Test
    fun `small imprecision is tolerated rather than policed`() {
        // A few dropped frames must not fail a decode; the guard is for order-of-magnitude errors.
        assertTrue(AudioDecoder.isPlausibleDuration((45 * 48_000 * 0.97).toInt(), 48_000, 45_000_000L))
    }

    @Test
    fun `a nonsensical sample rate is implausible`() {
        assertFalse(AudioDecoder.isPlausibleDuration(1_000, 0, 45_000_000L))
    }
}
