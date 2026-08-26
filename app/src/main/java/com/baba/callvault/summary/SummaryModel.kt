/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.transcription.model.DownloadableModel

/**
 * The language models CallVault will summarise with. Currently exactly one.
 *
 * Chosen by measurement on 2026-08-21, not by reputation. Gemma 4 E2B was the only candidate that
 * **invented nothing** across four controlled runs — it kept a delivery window at nine-to-eleven
 * where Qwen3.5-4B narrowed it to nine-to-ten, and got every figure in the sample right. For a
 * record of a real conversation a plausible fabrication is the worst possible output, so
 * faithfulness is the one property that cannot be traded away for speed or size. Qwen was also
 * roughly twice as slow on identical work and spent a third of its budget thinking out loud.
 *
 * **Never bundled, always downloaded.** Two independent reasons point the same way. It is 3.46 GB,
 * which is not something to put in an APK. (An earlier version of this comment also said it ships
 * under Google's *Gemma Terms of Use*; that is **wrong** for Gemma 4, which is **Apache-2.0 and
 * ungated** — checked against the model card on 2026-08-26. The licence was never the objection; the
 * 3.46 GB was, and still is.) Downloading keeps the app's
 * own licensing clean and leaves the user accepting Google's terms directly, from Google's own
 * distribution.
 */
enum class SummaryModel(
    override val id: String,
    override val fileName: String,
    override val url: String,
    override val sha256: String,
    override val sizeBytes: Long,
    val peakMemoryBytes: Long
) : DownloadableModel {

    /**
     * Gemma 4 E2B instruction-tuned, Q4_K_M.
     *
     * Quantisation-aware trained for phones, which is why an effective-2B model holds up at int4
     * where a post-hoc quantised one would not.
     */
    GEMMA_4_E2B_Q4_K_M(
        id = "gemma-4-e2b-it-q4_k_m",
        fileName = "google_gemma-4-E2B-it-Q4_K_M.gguf",
        url = "https://huggingface.co/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/" +
            "google_gemma-4-E2B-it-Q4_K_M.gguf",
        sha256 = "923c4c86177d2ee173a7f5b4fa3d0ac65f5962ab15e6d6a5bc250aec4fd7bf7e",
        sizeBytes = 3_462_680_032L,
        peakMemoryBytes = 3_500_000_000L
    );

    companion object {

        /** The model used when the user has expressed no preference. There is only one. */
        val DEFAULT = GEMMA_4_E2B_Q4_K_M

        fun fromId(id: String?): SummaryModel? = entries.firstOrNull { it.id == id }
    }
}
