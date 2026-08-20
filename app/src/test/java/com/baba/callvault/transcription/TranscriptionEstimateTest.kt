/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a transcription will take, and how that answer improves with use.
 *
 * The estimate itself is arithmetic — duration times a real-time factor — so the only thing worth
 * testing is the factor: where it comes from, how a measurement changes it, and which measurements are
 * too absurd to believe.
 */
class TranscriptionEstimateTest {

    private val published = 3.0

    @Test
    fun before_any_run_it_falls_back_to_the_published_factor() {
        // A phone that has never transcribed anything still has to answer the question.
        assertEquals(published, TranscriptionEstimate.blend(stored = null, measured = null, fallback = published), 0.001)
    }

    @Test
    fun the_first_real_measurement_replaces_the_published_guess_outright() {
        // The published figure came from one developer's phone. The moment this phone reports its own
        // speed, that is better evidence than a number measured on different hardware.
        assertEquals(1.4, TranscriptionEstimate.blend(stored = null, measured = 1.4, fallback = published), 0.001)
    }

    @Test
    fun later_measurements_move_the_estimate_without_letting_one_run_dominate() {
        // Runs vary: thermal throttling, a busy phone, a short clip with a long model load. Smoothing
        // keeps one unusual run from making every future estimate wrong.
        val next = TranscriptionEstimate.blend(stored = 2.0, measured = 4.0, fallback = published)

        assertTrue("must move toward the new measurement", next > 2.0)
        assertTrue("must not jump all the way to it", next < 4.0)
    }

    @Test
    fun an_impossible_measurement_is_ignored() {
        // A run that reports transcribing an hour of audio in a second is a bug in the measurement, not
        // a fast phone. Believing it would promise instant transcripts for ever after.
        assertEquals(2.0, TranscriptionEstimate.blend(stored = 2.0, measured = 0.0001, fallback = published), 0.001)
        assertEquals(2.0, TranscriptionEstimate.blend(stored = 2.0, measured = 5000.0, fallback = published), 0.001)
    }

    @Test
    fun measuring_needs_a_real_length_to_divide_by() {
        // Guards against dividing by zero when the container declares no duration.
        assertEquals(null, TranscriptionEstimate.measure(audioMs = 0L, elapsedMs = 5_000L))
        assertEquals(null, TranscriptionEstimate.measure(audioMs = -1L, elapsedMs = 5_000L))
    }

    @Test
    fun measuring_divides_the_time_taken_by_the_length_of_the_audio() {
        assertEquals(3.0, TranscriptionEstimate.measure(audioMs = 60_000L, elapsedMs = 180_000L)!!, 0.001)
    }

    @Test
    fun the_estimate_is_the_length_of_the_call_times_the_factor() {
        assertEquals(180_000L, TranscriptionEstimate.estimateMs(audioMs = 60_000L, rtf = 3.0))
    }
}
