/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping a Hebrew name from being torn apart by the English sentence around it.
 *
 * The failure this prevents is real and was on screen: a contact whose name is Hebrew followed by a
 * digit rendered as "Transcribing 2 גבריאלb keeps your phone busy" — the digit and the Latin letter
 * had migrated to the wrong side, because the whole paragraph was resolved left-to-right and the name
 * was just characters inside it.
 */
class BidiTextTest {

    private val FSI = '\u2068'
    private val PDI = '\u2069'

    @Test
    fun a_name_is_wrapped_so_it_keeps_its_own_direction() {
        // First-strong isolate: the run decides its own direction from its first strong character, and
        // the pop restores whatever was in force outside. Hebrew therefore reads right-to-left even
        // inside an English sentence, and vice versa.
        val isolated = BidiText.isolate("גבריאל 2")

        assertEquals(FSI + "גבריאל 2" + PDI, isolated)
    }

    @Test
    fun latin_text_is_wrapped_the_same_way() {
        // Not a Hebrew-only fix: an English name inside a Hebrew sentence has the mirror problem, and
        // a rule with an exception is a rule someone will forget.
        assertEquals(FSI + "AthenX" + PDI, BidiText.isolate("AthenX"))
    }

    @Test
    fun an_empty_name_gains_nothing() {
        // Wrapping nothing would put two invisible characters into an otherwise empty string, which
        // then reads as non-empty to every isBlank() check downstream.
        assertEquals("", BidiText.isolate(""))
        assertEquals("", BidiText.isolate("   "))
    }

    @Test
    fun wrapping_twice_does_not_nest() {
        // Labels pass through several layers; isolating an already-isolated name would accumulate
        // invisible characters on every hop.
        val once = BidiText.isolate("גבריאל")

        assertEquals(once, BidiText.isolate(once))
    }

    @Test
    fun the_visible_text_is_unchanged() {
        // The markers are formatting characters, not content: what the user reads must be identical.
        val name = "גבריאל 2"
        val isolated = BidiText.isolate(name)

        assertTrue(isolated.contains(name))
        assertEquals(name, isolated.filterNot { it == FSI || it == PDI })
    }
}
