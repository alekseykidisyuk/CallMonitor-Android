/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers parsing of the ongoing-call notification used to label VoIP recordings.
 *
 * Two real failures drive most of these. A Telegram call was labelled "WhatsApp" because the package
 * and the title were read from different notification records; and its caller came out as "Ongoing
 * Telegram call", the notification's status line rather than a person. Both are pinned here.
 *
 * The overriding rule is the failure mode: anything unexpected must yield null so the recording is
 * named by time alone, never a wrong name.
 */
class VoipCallerNameTest {

    private fun record(pkg: String, title: String, isCall: Boolean = true) = """
        NotificationRecord(0x01: pkg=$pkg user=UserHandle{0} id=1 tag=null
          ${if (isCall) "category=call" else "category=msg"}
          extras={
                android.title=String ($title)
                android.text=String (tap to return to the call)
          }
    """.trimIndent()

    @Test
    fun `reads the package and caller from a call notification`() {
        val info = VoipCallerName.extractFromDump(record("com.whatsapp", "Feroza"))
        assertEquals("com.whatsapp", info.packageName)
        assertEquals("Feroza", info.callerName)
    }

    @Test
    fun `takes both facts from the SAME record when several apps have notifications`() {
        // The regression: a stale WhatsApp notification supplied the package for a Telegram call.
        val dump = record("com.whatsapp", "Feroza", isCall = false) + "\n" +
            record("org.telegram.messenger", "Alex")

        val info = VoipCallerName.extractFromDump(dump)

        assertEquals("org.telegram.messenger", info.packageName)
        assertEquals("Alex", info.callerName)
    }

    @Test
    fun `rejects a title that just restates the app`() {
        // Telegram titles its ongoing call "Ongoing Telegram call" rather than naming the contact;
        // that string in a filename is worse than no name at all.
        val info = VoipCallerName.extractFromDump(
            record("org.telegram.messenger", "Ongoing Telegram call")
        )
        assertEquals("org.telegram.messenger", info.packageName)
        assertNull(info.callerName)
    }

    @Test
    fun `keeps a real name even when the app also posts one`() {
        assertEquals("Dana Liza", VoipCallerName.extractFromDump(record("com.whatsapp", "Dana Liza")).callerName)
    }

    @Test
    fun `ignores notifications that are not calls`() {
        val info = VoipCallerName.extractFromDump(record("com.android.vending", "Uploaded 1 item", isCall = false))
        assertNull(info.packageName)
        assertNull(info.callerName)
    }

    @Test
    fun `strips characters that would break a filename`() {
        assertEquals("AaBb", VoipCallerName.extractFromDump(record("com.whatsapp", "A/a:B*b")).callerName)
    }

    @Test
    fun `keeps non-latin names intact`() {
        assertEquals("גבריאל", VoipCallerName.extractFromDump(record("com.whatsapp", "גבריאל")).callerName)
    }

    @Test
    fun `truncates an absurdly long title`() {
        val info = VoipCallerName.extractFromDump(record("com.whatsapp", "N".repeat(200)))
        assertEquals(40, info.callerName?.length)
    }

    @Test
    fun `returns the package even when the title is unusable`() {
        val info = VoipCallerName.extractFromDump(record("com.whatsapp", "///"))
        assertEquals("com.whatsapp", info.packageName)
        assertNull(info.callerName)
    }

    @Test
    fun `returns nothing for empty or junk input rather than throwing`() {
        assertNull(VoipCallerName.extractFromDump("").packageName)
        assertNull(VoipCallerName.extractFromDump("not a dump at all").callerName)
    }
}
