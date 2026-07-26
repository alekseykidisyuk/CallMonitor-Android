/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the geometry the native drain is driven with. Two things are load-bearing here: the ring wraps
 * at a power of two (getting this wrong produced periodic audio gaps), and every delivered value is
 * bounds-checked before it becomes a native offset.
 */
class HandoffGeometryTest {

    // A realistic 48 kHz stereo delivery: non-power-of-two frame count, comfortably sized ashmem.
    private fun realistic(
        frameCount: Int = 3840,
        sampleRate: Int = 48_000,
        channels: Int = 2,
        cblkSize: Int = 64 * 1024,
    ) = HandoffGeometry(frameCount, sampleRate, channels, cblkSize)

    // --- frame size ---

    @Test
    fun `frame size is two bytes per channel for PCM-16`() {
        assertEquals(2, realistic(channels = 1).frameSize)
        assertEquals(4, realistic(channels = 2).frameSize)
    }

    // --- ring wrap ---

    @Test
    fun `ring wraps at the next power of two above a non-power-of-two frame count`() {
        // The bug this guards: wrapping at 3840 reads the untouched 3840..4096 region every lap.
        assertEquals(4096, realistic(frameCount = 3840).wrapFrames)
        assertEquals(8192, realistic(frameCount = 4097).wrapFrames)
    }

    @Test
    fun `ring wraps at the frame count itself when it is already a power of two`() {
        assertEquals(2048, realistic(frameCount = 2048).wrapFrames)
        assertEquals(1, realistic(frameCount = 1).wrapFrames)
    }

    @Test
    fun `ring end offset accounts for the data offset and the rounded-up ring`() {
        // Arrange
        val geometry = realistic(frameCount = 3840, channels = 2)

        // Act
        val end = geometry.ringEndOffset

        // Assert
        assertEquals(HandoffGeometry.DATA_OFF + 4096L * 4, end)
    }

    // --- validation: accepted ---

    @Test
    fun `accepts a realistic delivery`() {
        assertNull(realistic().validationError())
    }

    @Test
    fun `accepts a ring that exactly fills the ashmem region`() {
        // Arrange: size the region to the last byte the drain will touch.
        val exact = HandoffGeometry.DATA_OFF + 4096 * 4

        // Act + Assert
        assertNull(realistic(frameCount = 4096, channels = 2, cblkSize = exact).validationError())
    }

    // --- validation: rejected ---

    @Test
    fun `rejects a ring one byte larger than the ashmem region`() {
        // Arrange
        val oneByteShort = HandoffGeometry.DATA_OFF + 4096 * 4 - 1

        // Act
        val error = realistic(frameCount = 4096, channels = 2, cblkSize = oneByteShort).validationError()

        // Assert
        assertNotNull("an over-long ring must be refused before it becomes a native offset", error)
        assertTrue(error!!, error.contains("doesn't fit"))
    }

    @Test
    fun `rejects a frame count whose rounded-up ring overflows the region`() {
        // frameCount 2049 rounds up to 4096 frames — the unrounded 2049 would have fit.
        val sizedForUnrounded = HandoffGeometry.DATA_OFF + 2049 * 4

        assertNotNull(realistic(frameCount = 2049, channels = 2, cblkSize = sizedForUnrounded).validationError())
    }

    @Test
    fun `rejects channel counts outside mono and stereo`() {
        assertNotNull(realistic(channels = 0).validationError())
        assertNotNull(realistic(channels = 3).validationError())
        assertNotNull(realistic(channels = -1).validationError())
    }

    @Test
    fun `rejects sample rates outside the supported range`() {
        assertNotNull(realistic(sampleRate = 0).validationError())
        assertNotNull(realistic(sampleRate = 7_999).validationError())
        assertNotNull(realistic(sampleRate = 192_001).validationError())
    }

    @Test
    fun `accepts sample rates at the range boundaries`() {
        assertNull(realistic(sampleRate = 8_000).validationError())
        assertNull(realistic(sampleRate = 192_000).validationError())
    }

    @Test
    fun `rejects an unsized or unreadable ashmem region`() {
        assertNotNull(realistic(cblkSize = 0).validationError())
        assertNotNull(realistic(cblkSize = -1).validationError())
    }

    @Test
    fun `rejects a non-positive frame count`() {
        assertNotNull(HandoffGeometry(0, 48_000, 2, 64 * 1024).validationError())
        assertNotNull(HandoffGeometry(-8, 48_000, 2, 64 * 1024).validationError())
    }

    // --- construction ---

    @Test
    fun `substitutes the fallback when the daemon reported no frame count`() {
        // Arrange + Act
        val geometry = HandoffGeometry.of(frameCount = 0, sampleRate = 48_000, channels = 1, cblkSize = 64 * 1024)

        // Assert
        assertEquals(HandoffGeometry.FRAME_COUNT_FALLBACK, geometry.frameCount)
        assertNull(geometry.validationError())
    }

    @Test
    fun `keeps a reported frame count`() {
        assertEquals(
            3840,
            HandoffGeometry.of(frameCount = 3840, sampleRate = 48_000, channels = 2, cblkSize = 64 * 1024).frameCount,
        )
    }
}
