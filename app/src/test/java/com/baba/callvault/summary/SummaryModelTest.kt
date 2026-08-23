/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue entry, checked for the things that are silently fatal if wrong.
 *
 * ggml's failure mode on a truncated or corrupt model is a crash inside native code with nothing
 * useful in the log, so the digest and the length are load-bearing. Neither can be verified from a
 * unit test — but a typo in either is caught here, and the two together were checked against the
 * real file and the published `content-length` when the entry was written.
 */
class SummaryModelTest {

    private val gemma = SummaryModel.GEMMA_4_E2B_Q4_K_M

    @Test
    fun `the download url ends with the file it is meant to fetch`() {
        // The two are written separately and a mismatch downloads the right bytes under the wrong
        // name, so the model is never found and the app quietly offers to download it again.
        assertTrue(gemma.url.endsWith(gemma.fileName))
    }

    @Test
    fun `the digest is a lower-case hex sha-256`() {
        // ModelRepository compares case-insensitively, but a wrong length means a typo.
        assertEquals(64, gemma.sha256.length)
        assertTrue(gemma.sha256.all { it in "0123456789abcdef" })
    }

    @Test
    fun `the published length matches what was measured`() {
        // Verified twice: against the local copy used for every benchmark, and against the
        // content-length HuggingFace serves for this exact URL.
        assertEquals(3_462_680_032L, gemma.sizeBytes)
    }

    @Test
    fun `ids are stable and unique`() {
        // Stored beside a summary so a redo with a different model is distinguishable.
        assertEquals(SummaryModel.entries.size, SummaryModel.entries.map { it.id }.toSet().size)
        assertEquals(gemma, SummaryModel.fromId("gemma-4-e2b-it-q4_k_m"))
    }

    @Test
    fun `an unknown id resolves to nothing rather than a default`() {
        // A stored preference naming a model that has been withdrawn must not silently become a
        // different one — the caller decides whether to fall back.
        assertNull(SummaryModel.fromId("some-model-we-dropped"))
        assertNull(SummaryModel.fromId(null))
    }

    @Test
    fun `the model is far too big to be shipped in the apk`() {
        // Guards the decision rather than the number: anything of this size is downloaded, and the
        // requirements modal has to say so before a user starts it on mobile data.
        assertTrue("Bundling this would be a 3 GB APK", gemma.sizeBytes > 3_000_000_000L)
    }

    @Test
    fun `the measured memory cost is recorded honestly`() {
        // Peak PSS measured on the OP12 while the model was loaded — 3.0 to 3.5 GB across four
        // runs. An earlier figure of 1.9 GB was read AFTER the model was freed and was simply
        // wrong. This number decides what the requirements modal promises, so it is the real one.
        assertTrue(gemma.peakMemoryBytes >= 3_000_000_000L)
    }
}
