/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

/**
 * The whisper.cpp models CallVault offers, and nothing else.
 *
 * The spec settled on exactly two tiers after measuring on the OP12: `tiny` and `base` are unusable
 * for Hebrew, and `medium` is dominated by turbo on both quality and speed, so offering them would
 * only invite a bad choice.
 *
 * Digests and sizes are the published Git-LFS values from the model repository, and the SMALL_Q5_1
 * digest was additionally checked against the exact file that was verified on-device. They are
 * load-bearing: ggml's failure mode on a corrupt model is a crash in native code, so a model is only
 * ever used after its digest matches.
 *
 * @param id            Stable key stored on a transcript, so a re-run with a better model is
 *                      distinguishable from a re-run of the same one.
 * @param fileName      Name on disk and in the download URL.
 * @param url           Direct download URL.
 * @param sha256        Lower-case hex SHA-256 of the complete file.
 * @param sizeBytes     Exact published length, used as the cheap completeness check.
 * @param realTimeFactor Measured on the OP12: seconds of processing per second of audio. Used to
 *                      warn honestly about how long a long call will take.
 */
enum class TranscriptionModel(
    val id: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val realTimeFactor: Double
) {
    /** Fast tier. Hebrew comes out as gist rather than clean prose, at roughly real time. */
    SMALL_Q5_1(
        id = "small-q5_1",
        fileName = "ggml-small-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        sizeBytes = 190_085_487L,
        realTimeFactor = 0.99
    ),

    /** Best tier. Clean Hebrew, at roughly twice real time and about three times the download. */
    LARGE_V3_TURBO_Q5_0(
        id = "large-v3-turbo-q5_0",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        sizeBytes = 574_041_195L,
        realTimeFactor = 2.16
    );

    companion object {
        /**
         * The tier chosen when the user has expressed no preference.
         *
         * Best rather than Fast, decided 2026-08-17. Fast produces Hebrew *gist*; Best produces clean
         * Hebrew, and a transcript you cannot trust is not worth the battery it cost. The price is a
         * 574 MB download and roughly 2.2x real time instead of 1x, which the user accepted — a
         * 30-minute call takes about 65 minutes rather than 30.
         */
        val DEFAULT = LARGE_V3_TURBO_Q5_0

        fun fromId(id: String?): TranscriptionModel? = entries.firstOrNull { it.id == id }
    }
}
