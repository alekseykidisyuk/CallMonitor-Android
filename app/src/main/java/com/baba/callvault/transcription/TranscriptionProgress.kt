/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import kotlin.math.exp

/**
 * The number to show while a recording is being transcribed.
 *
 * whisper reports progress only at chunk boundaries — thirty seconds of audio at a time — so a short
 * call produces about three updates in total: 0, then 39, then 78. Shown raw, that means a figure
 * that sits at zero for a third of the run and then leaps. A number that does not move is not more
 * honest than a spinner; it is a spinner that also looks broken, because the reasonable reading of
 * "0%, still 0%, still 0%" is that something has hung.
 *
 * So the reported figure is treated as an **anchor** rather than as the whole truth, and the gaps
 * between anchors are filled from how long the run is taking against how long it was expected to
 * take. The estimate is already calibrated per model on this phone, which is what makes filling the
 * gaps defensible rather than decorative.
 *
 * Pure and Android-free, because the rules below are the kind that are easy to get subtly wrong and
 * only ever visible on a phone in the middle of a long job.
 */
object TranscriptionProgress {

    /**
     * Never quite finished until it really is.
     *
     * A prediction that reaches 100% while work continues is worse than one that stalls: it says
     * "done" to someone who then waits, which destroys trust in every number shown afterwards.
     */
    private const val PREDICTION_CEILING = 95

    /**
     * The floor, so a run that has started never reads as zero.
     *
     * "1%" and "0%" carry the same information about the work, and completely different information
     * about whether the phone is alive.
     */
    private const val STARTED = 1

    /**
     * How sharply the prediction bends.
     *
     * The first attempt capped the prediction a fixed thirty points ahead of the last confirmed
     * anchor. That traded a figure stuck at 0 for one stuck at 30 — the same complaint, moved along
     * the bar — because whisper's first anchor on a short call does not arrive until a third of the
     * way through.
     *
     * A curve instead of a cap. It rises quickly at first and then ever more slowly, so it is always
     * moving and never arrives: at the estimated time it reads about three quarters, at twice that
     * about nine tenths, and it keeps creeping after that. Chosen at 1.6 so the early motion is
     * visible without racing far ahead of the work.
     */
    private const val SHAPE = 1.6

    /**
     * What to display, given what whisper last said and how the clock is going.
     *
     * @param reportedPercent whisper's last anchor, 0-100.
     * @param elapsedMs how long this recording has been running.
     * @param estimatedMs how long it was expected to take, or 0 when that is not known — in which
     *   case nothing is predicted and only real anchors move the number.
     * @param previous the last value shown, so the figure can never go backwards. Progress that
     *   retreats reads as a fault even when the newer number is the better one.
     */
    fun display(
        reportedPercent: Int,
        elapsedMs: Long,
        estimatedMs: Long,
        previous: Int
    ): Int {
        val anchor = reportedPercent.coerceIn(0, 100)

        // A finished run is the one case that may show 100.
        if (anchor >= 100) return 100

        // Asymptotic, so it always moves and never arrives. An anchor from whisper can still jump
        // it forward — the curve is a floor for motion, not a substitute for the truth.
        val predicted = when {
            estimatedMs <= 0L -> 0
            else -> {
                val ratio = elapsedMs.toDouble() / estimatedMs.toDouble()
                (PREDICTION_CEILING * (1 - exp(-ratio * SHAPE))).toInt()
            }
        }

        return maxOf(anchor, predicted, previous, STARTED).coerceAtMost(PREDICTION_CEILING)
    }
}
