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
 * Covers the notification-title parsing used to name VoIP recordings.
 *
 * This is text scraping of `dumpsys` output, so the tests exist mainly to pin the shape it expects and
 * to guarantee the failure mode: anything unexpected must yield null (recording named by time alone)
 * rather than a wrong name or a filename-breaking string. The fixtures use the real format observed on
 * the device — `android.title=String (Name)`.
 */
class VoipCallerNameTest {

    private fun dump(vararg blocks: String) = blocks.joinToString("\n")

    private fun callBlock(title: String, pkg: String = "com.whatsapp") = """
        NotificationRecord(0x01: pkg=$pkg user=UserHandle{0} id=1 tag=null importance=4
          category=call
          extras={
                android.title=String ($title)
                android.text=String (Ongoing voice call)
          }
    """.trimIndent()

    @Test
    fun `takes the title of a call-category notification`() {
        assertEquals("Feroza", VoipCallerName.extractFromDump(dump(callBlock("Feroza"))))
    }

    @Test
    fun `ignores notifications that are not calls`() {
        // Arrange: a non-call notification with a perfectly good-looking title.
        val other = """
            NotificationRecord(0x02: pkg=com.android.vending id=9
              extras={
                    android.title=String (Uploaded 1 item)
              }
        """.trimIndent()

        assertNull(VoipCallerName.extractFromDump(other))
    }

    @Test
    fun `prefers the call notification when other notifications are present`() {
        // Arrange: a decoy before and after the real call block.
        val decoyBefore = "NotificationRecord(0x03: pkg=x\n  extras={\n android.title=String (Charging) }"
        val decoyAfter = "NotificationRecord(0x04: pkg=y\n  extras={\n android.title=String (Ready) }"

        val name = VoipCallerName.extractFromDump(dump(decoyBefore, callBlock("Alex Cohen"), decoyAfter))

        assertEquals("Alex Cohen", name)
    }

    @Test
    fun `strips characters that would break a filename`() {
        assertEquals("AaBb", VoipCallerName.extractFromDump(dump(callBlock("A/a:B*b"))))
    }

    @Test
    fun `keeps non-latin names intact`() {
        // The device's own contacts are Hebrew; mangling them would be worse than no name.
        assertEquals("גבריאל", VoipCallerName.extractFromDump(dump(callBlock("גבריאל"))))
    }

    @Test
    fun `truncates an absurdly long title rather than producing a huge filename`() {
        // Arrange
        val long = "N".repeat(200)

        // Act
        val name = VoipCallerName.extractFromDump(dump(callBlock(long)))

        // Assert
        assertEquals(40, name?.length)
    }

    @Test
    fun `returns null when the call notification has no title`() {
        val noTitle = "NotificationRecord(0x05: pkg=com.whatsapp\n  category=call\n  extras={ }"
        assertNull(VoipCallerName.extractFromDump(noTitle))
    }

    @Test
    fun `returns null for empty or junk input rather than throwing`() {
        assertNull(VoipCallerName.extractFromDump(""))
        assertNull(VoipCallerName.extractFromDump("not a dump at all"))
    }

    @Test
    fun `returns null when the title is only unsafe characters`() {
        assertNull(VoipCallerName.extractFromDump(dump(callBlock("///"))))
    }
}
