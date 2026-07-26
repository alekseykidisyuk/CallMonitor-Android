/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import com.baba.callvault.ui.viewmodels.HomeViewModel.WhatsNewNote
import com.baba.callvault.ui.viewmodels.HomeViewModel.Companion.selectWhatsNew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers which one-time feature note is shown after an update.
 *
 * The rules that matter: a note never appears outside an update, each note appears at most once, and
 * someone who skipped a release still meets the feature they missed rather than having it swallowed by
 * the newer one.
 */
class WhatsNewSelectionTest {

    @Test
    fun `shows nothing when the app was not just updated`() {
        assertNull(selectWhatsNew(justUpdated = false, seenResilient = false, seenOffWifi = false))
    }

    @Test
    fun `shows the newest unseen feature first`() {
        // The common case: an existing user who already met off-Wi-Fi updates into this release.
        assertEquals(
            WhatsNewNote.RESILIENT_RECORDING,
            selectWhatsNew(justUpdated = true, seenResilient = false, seenOffWifi = true),
        )
    }

    @Test
    fun `still shows an older feature the user skipped past`() {
        // Someone who saw the resilient note but never the off-Wi-Fi one must not lose it.
        assertEquals(
            WhatsNewNote.OFF_WIFI,
            selectWhatsNew(justUpdated = true, seenResilient = true, seenOffWifi = false),
        )
    }

    @Test
    fun `shows one note at a time when several are unseen`() {
        // Arrange: a user updating across both releases at once.
        val first = selectWhatsNew(justUpdated = true, seenResilient = false, seenOffWifi = false)

        // Assert: the newest comes first; the other is still pending for the next update.
        assertEquals(WhatsNewNote.RESILIENT_RECORDING, first)
        assertEquals(
            WhatsNewNote.OFF_WIFI,
            selectWhatsNew(justUpdated = true, seenResilient = true, seenOffWifi = false),
        )
    }

    @Test
    fun `shows nothing once every note has been seen`() {
        assertNull(selectWhatsNew(justUpdated = true, seenResilient = true, seenOffWifi = true))
    }
}
