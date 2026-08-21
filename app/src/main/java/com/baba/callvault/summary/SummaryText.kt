/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

/**
 * Turning what a model emitted into what a person should read.
 *
 * Some models think out loud first, in a `<think>` block, and only then answer. Measured on the OP12
 * with Qwen3.5-4B, that block ran to hundreds of tokens of *"Analyze the Request... Correction: I
 * need to look at the actual user input..."* — and on two of four runs it consumed the entire token
 * budget, so the summary was never reached at all. Left in, it would be shown to the user as though
 * it were the summary of their call.
 */
object SummaryText {

    private val THINK_BLOCK = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)

    /** An opened block that never closed, because the token budget ran out mid-thought. */
    private val UNCLOSED_THINK = Regex("""<think>.*""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The answer alone, or empty when the model never got to one.
     *
     * Empty is a meaningful result rather than a failure to paper over: it means the budget was
     * spent thinking, and the caller should say so instead of showing a blank summary.
     */
    fun stripReasoning(raw: String): String =
        raw.replace(THINK_BLOCK, "")
            .replace(UNCLOSED_THINK, "")
            .trim()
}
