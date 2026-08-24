/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How an app (VoIP) call is captured, which is not the same in the two modes.
 *
 * Standalone keeps CallVault's own design — a dynamic audio policy that loops back the far party plus a
 * plain mic for the near one — which is proven two-sided on a real WhatsApp call.
 *
 * That design cannot work under Shizuku: it is built from `AudioRecord`s inside the recorder process, and
 * one cannot reach RECORDING state there. **Ever-Call-Recorder** records VoIP under Shizuku by forcing
 * scrcpy's `output` (REMOTE_SUBMIX) source instead, which runs in scrcpy's own process — so it is
 * available where ours is not.
 */
class VoipCapturePlanTest {

    @Test
    fun standalone_uses_our_own_loopback_policy() {
        assertEquals(VoipCapturePlan.POLICY_LOOPBACK, VoipCapturePlan.forMode(PrivilegedMode.STANDALONE))
    }

    @Test
    fun shizuku_uses_the_system_output_mix() {
        assertEquals(VoipCapturePlan.SYSTEM_OUTPUT_MIX, VoipCapturePlan.forMode(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun only_the_policy_plan_has_to_be_armed_before_the_call() {
        // The arming constraint is what makes a missed VoIP call unrecoverable in standalone; the
        // system-mix plan has no such moment, which is a real advantage of it.
        assertEquals(true, VoipCapturePlan.POLICY_LOOPBACK.needsArmingBeforeCall)
        assertEquals(false, VoipCapturePlan.SYSTEM_OUTPUT_MIX.needsArmingBeforeCall)
    }

    @Test
    fun the_system_mix_plan_names_the_scrcpy_source_it_needs() {
        assertEquals("output", VoipCapturePlan.SYSTEM_OUTPUT_MIX.scrcpySourceKey)
    }

    @Test
    fun the_policy_plan_names_no_scrcpy_source() {
        assertEquals(null, VoipCapturePlan.POLICY_LOOPBACK.scrcpySourceKey)
    }
}
