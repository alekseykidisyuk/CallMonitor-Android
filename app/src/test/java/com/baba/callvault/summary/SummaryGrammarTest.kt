/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar is text until a model is running under it, and a mistake in it fails *silently* — the
 * native side logs the parse error and generates unconstrained, so the output still looks plausible
 * and the constraint simply is not there. Nothing on a phone would ever say so.
 *
 * These are the properties that can be checked without a 3.46 GB file.
 */
class SummaryGrammarTest {

    private val rules = SummaryGrammar.JSON.lines().filter { it.isNotBlank() }

    @Test
    fun `every rule is on one line`() {
        // GBNF ends a rule at the newline, so a root spread over several lines parses as several
        // broken rules. Caught once by running the grammar through llama-cli, which refuses to start.
        rules.forEach { rule ->
            assertTrue("not a rule: $rule", rule.contains("::="))
        }
    }

    @Test
    fun `the lists are bounded, which is what stops the repetition loop`() {
        // Unbounded arrays plus greedy decoding is the documented repetition setup — Holtzman et al.
        // measured a loop in 73.66% of greedy generations — and it matched what we saw: four
        // decisions, three of them the same sentence.
        val array = rules.single { it.startsWith("array ::=") }

        assertTrue("the array rule is unbounded", array.contains("{0,${SummaryGrammar.MAX_LIST_ITEMS - 1}}"))
        assertFalse("a bare * would permit infinitely many items", array.contains("string)*"))
    }

    @Test
    fun `the bound and the prompt name the same number`() {
        // One item, then at most MAX - 1 more.
        assertEquals(8, SummaryGrammar.MAX_LIST_ITEMS)
        assertTrue(
            SummaryPrompt.forMergeJson(listOf("""{"intent":"a"}"""), "en")
                .contains("At most ${SummaryGrammar.MAX_LIST_ITEMS} items")
        )
    }

    @Test
    fun `the root names the six keys the parser requires, in order`() {
        val root = rules.single { it.startsWith("root ::=") }
        val order = listOf("intent", "summary", "keyPoints", "decisions", "actionItems", "keyFacts")

        val positions = order.map { root.indexOf("\\\"$it\\\"") }
        assertFalse("a key the parser requires is not in the grammar", positions.any { it < 0 })
        assertEquals("the key order is fixed and the parser relies on it", positions.sorted(), positions)
    }

    @Test
    fun `strings accept any script, not only Latin`() {
        // A negated class rather than an allow-list. Hyprnote ships the broken [A-Z] form, which
        // would make a Hebrew or Arabic summary literally unsampleable.
        val string = rules.single { it.startsWith("char ::=") }

        assertTrue(string.contains("[^"))
    }
}
