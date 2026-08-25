/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers which steps the wizard shows, because one of them had a side effect: entering the reliability
 * step probed the USB default over the embedded shell, which turned **Wireless debugging on** in Shizuku
 * mode — a mode that never pairs and could never have used the answer.
 */
class WizardStepsTest {

    @Test
    fun `omits the reliability step in Shizuku mode`() {
        val steps = wizardSteps(usesDrive = false, usesEmbeddedAdb = false)

        assertFalse(
            "The reliability step is embedded-ADB machinery end to end; showing it in Shizuku mode is " +
                "what switched Wireless debugging on.",
            steps.contains(WizardStep.RELIABILITY),
        )
    }

    @Test
    fun `keeps the reliability step when CallVault owns the ADB connection`() {
        val steps = wizardSteps(usesDrive = false, usesEmbeddedAdb = true)

        assertTrue(steps.contains(WizardStep.RELIABILITY))
    }

    @Test
    fun `offers the schedule step only when Drive is a target`() {
        assertTrue(wizardSteps(usesDrive = true, usesEmbeddedAdb = true).contains(WizardStep.SCHEDULE))
        assertFalse(wizardSteps(usesDrive = false, usesEmbeddedAdb = true).contains(WizardStep.SCHEDULE))
    }

    @Test
    fun `keeps every other step in both modes`() {
        // The recording decisions, the audio settings and the update opt-in are mode-independent, so a
        // Shizuku user must still be asked about all of them — the exclusion is meant to be surgical.
        val shizuku = wizardSteps(usesDrive = true, usesEmbeddedAdb = false)

        assertEquals(
            wizardSteps(usesDrive = true, usesEmbeddedAdb = true) - WizardStep.RELIABILITY,
            shizuku,
        )
    }

    @Test
    fun `starts on storage and ends on updates whatever is excluded`() {
        // "Step N of M" is derived from this list, so order matters as much as membership.
        for (usesDrive in listOf(false, true)) {
            for (usesEmbeddedAdb in listOf(false, true)) {
                val steps = wizardSteps(usesDrive, usesEmbeddedAdb)
                assertEquals(WizardStep.STORAGE, steps.first())
                assertEquals(WizardStep.UPDATES, steps.last())
                assertEquals("No step is ever listed twice", steps.size, steps.toSet().size)
            }
        }
    }
}
