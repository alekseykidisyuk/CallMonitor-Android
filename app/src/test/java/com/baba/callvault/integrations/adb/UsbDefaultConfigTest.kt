/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the two decisions that left issue #22's reporter without the one piece of advice that would
 * have helped: how a `dumpsys usb` line is classified, and when the user is told about it.
 */
class UsbDefaultConfigTest {

    // ---- parse: what the device reports -> what we think it means

    @Test
    fun `treats adb alone as debugging-only, the One UI 8 default`() {
        assertEquals(
            UsbDefaultMode.DEBUGGING_ONLY,
            UsbDefaultConfig.parse("    screen_unlocked_functions=adb"),
        )
    }

    @Test
    fun `treats a data function combined with adb as a data mode`() {
        assertEquals(
            UsbDefaultMode.FILE_TRANSFER,
            UsbDefaultConfig.parse("screen_unlocked_functions=mtp,adb"),
        )
    }

    @Test
    fun `treats an empty function list as charging only`() {
        assertEquals(UsbDefaultMode.CHARGING, UsbDefaultConfig.parse("screen_unlocked_functions="))
        assertEquals(UsbDefaultMode.CHARGING, UsbDefaultConfig.parse("screen_unlocked_functions=none"))
    }

    @Test
    fun `reports an unrecognised function as unknown rather than guessing`() {
        assertEquals(
            UsbDefaultMode.UNKNOWN,
            UsbDefaultConfig.parse("screen_unlocked_functions=some_oem_mode"),
        )
    }

    // ---- noticeFor: what the user is told

    @Test
    fun `warns about a data mode even when the recorder is not ready`() {
        // The regression this exists to prevent: the data mode kills the daemon, the dead daemon reads
        // as not-ready, and readiness used to gate the warning — so it vanished exactly when it applied.
        assertEquals(
            UsbNotice.DATA_MODE_RISK,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.FILE_TRANSFER, recorderReady = false),
        )
    }

    @Test
    fun `warns about a data mode while the recorder is ready`() {
        assertEquals(
            UsbNotice.DATA_MODE_RISK,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.TETHERING, recorderReady = true),
        )
    }

    @Test
    fun `says nothing about the safe modes`() {
        assertEquals(UsbNotice.NONE, UsbDefaultConfig.noticeFor(UsbDefaultMode.CHARGING, recorderReady = true))
        assertEquals(UsbNotice.NONE, UsbDefaultConfig.noticeFor(UsbDefaultMode.CHARGING, recorderReady = false))
        assertEquals(UsbNotice.NONE, UsbDefaultConfig.noticeFor(UsbDefaultMode.DEBUGGING_ONLY, recorderReady = false))
    }

    @Test
    fun `admits it could not check only while the recorder is failing to come up`() {
        assertEquals(
            UsbNotice.COULD_NOT_CHECK,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.UNKNOWN, recorderReady = false),
        )
        // A working phone is not nagged about a check that turned out not to matter.
        assertEquals(
            UsbNotice.NONE,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.UNKNOWN, recorderReady = true),
        )
    }
}
