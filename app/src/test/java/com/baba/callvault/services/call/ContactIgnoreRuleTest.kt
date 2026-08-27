/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import com.baba.callvault.data.AppPreferences.IgnoreContactsMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a call is skipped because of who it is with.
 *
 * Extracted from `CallSessionManager` to be testable at all, and because the two ways to get it wrong
 * are asymmetric: **wrongly ignoring means a call is silently never recorded**, while wrongly recording
 * costs a file the user can delete. Every ambiguous case here therefore resolves to "record it".
 *
 * The lookup is passed in rather than reached for, so these tests do not need a contacts provider — and
 * so the throwing case, which is the one that caused the original bug, can actually be exercised.
 */
class ContactIgnoreRuleTest {

    private val neverAContact: () -> Boolean = { false }
    private val alwaysAContact: () -> Boolean = { true }

    @Test
    fun `a blank number is never ignored`() {
        // Teams, withheld numbers and short codes arrive with nothing to match on. Previously this
        // built a PhoneLookup URI from an empty string and asked the provider about it.
        assertFalse(ContactIgnoreRule.shouldIgnore("", IgnoreContactsMode.ALL, emptySet(), alwaysAContact))
        assertFalse(ContactIgnoreRule.shouldIgnore("   ", IgnoreContactsMode.ALL, emptySet(), alwaysAContact))
    }

    @Test
    fun `a blank number is not matched by a blank entry in the ignore list`() {
        // A stray empty entry must not silently turn into "ignore every unidentified call".
        assertFalse(ContactIgnoreRule.shouldIgnore("", IgnoreContactsMode.SELECTED, setOf(""), neverAContact))
    }

    @Test
    fun `a lookup that throws records the call rather than propagating`() {
        // The original defect: no try/catch around the contacts query, so a provider that objected
        // threw straight up through the call-state handler.
        val exploding: () -> Boolean = { throw IllegalArgumentException("provider said no") }

        assertFalse(ContactIgnoreRule.shouldIgnore("+972500000000", IgnoreContactsMode.ALL, emptySet(), exploding))
    }

    @Test
    fun `mode NONE ignores nobody`() {
        assertFalse(ContactIgnoreRule.shouldIgnore("+972500000000", IgnoreContactsMode.NONE, setOf("+972500000000"), alwaysAContact))
    }

    @Test
    fun `mode ALL ignores a number that is a contact`() {
        assertTrue(ContactIgnoreRule.shouldIgnore("+972500000000", IgnoreContactsMode.ALL, emptySet(), alwaysAContact))
    }

    @Test
    fun `mode ALL records a number that is not a contact`() {
        assertFalse(ContactIgnoreRule.shouldIgnore("+972500000000", IgnoreContactsMode.ALL, emptySet(), neverAContact))
    }

    @Test
    fun `mode SELECTED matches regardless of how the number was written`() {
        // The stored entry and the live number rarely agree on spacing, dashes or parentheses.
        assertTrue(
            ContactIgnoreRule.shouldIgnore(
                "+972500000000",
                IgnoreContactsMode.SELECTED,
                setOf("+972 50-000-0000"),
                neverAContact,
            )
        )
    }

    @Test
    fun `mode SELECTED does not consult the contacts provider at all`() {
        // A list of numbers is a list of numbers; touching contacts here would be a needless
        // permission dependency and a second way to throw.
        val exploding: () -> Boolean = { throw IllegalStateException("must not be called") }

        assertFalse(ContactIgnoreRule.shouldIgnore("+972500000000", IgnoreContactsMode.SELECTED, emptySet(), exploding))
    }
}
