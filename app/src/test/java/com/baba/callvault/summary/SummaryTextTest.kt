/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTextTest {

    @Test
    fun `removes a reasoning block and keeps the answer`() {
        val raw = "<think>\nThinking Process: the user wants a summary.\n</think>\n\nDani moved the delivery."
        assertEquals("Dani moved the delivery.", SummaryText.stripReasoning(raw))
    }

    @Test
    fun `removes a block that never closed`() {
        // Measured: the token budget ran out mid-thought, leaving an open tag and no answer. Shown
        // raw, the user would read the model's deliberations as the summary of their call.
        val raw = "<think>\nThinking Process:\n  1. Analyze the Request\n  2. I need to look at"
        assertEquals("", SummaryText.stripReasoning(raw))
    }

    @Test
    fun `leaves an ordinary answer alone`() {
        assertEquals("Sam moved the boiler service.", SummaryText.stripReasoning("Sam moved the boiler service."))
    }

    @Test
    fun `handles an empty block, which is what a model that declined to think emits`() {
        assertEquals("Dani moved the delivery.", SummaryText.stripReasoning("<think>\n\n</think>\n\nDani moved the delivery."))
    }

    @Test
    fun `trims the whitespace a block leaves behind`() {
        assertEquals("Answer.", SummaryText.stripReasoning("  <think>x</think>  \n Answer. \n "))
    }
}
