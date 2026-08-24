/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionLanguageChoiceTest {

    @Test
    fun no_choice_falls_back_to_the_setting() {
        assertEquals("he", TranscriptionLanguageChoice.resolve(chosen = null, setting = "he"))
    }

    @Test
    fun no_choice_and_no_setting_is_auto_detect() {
        assertNull(TranscriptionLanguageChoice.resolve(chosen = null, setting = null))
    }

    @Test
    fun a_choice_wins_over_the_setting() {
        assertEquals("en", TranscriptionLanguageChoice.resolve(chosen = "en", setting = "he"))
    }

    @Test
    fun auto_detect_can_be_chosen_over_a_pinned_setting() {
        // The one case a plain nullable string cannot carry: "chose auto" and "chose nothing" would
        // both be null, and the setting would quietly win.
        val chosen = TranscriptionLanguageChoice.encode(null)

        assertNull(TranscriptionLanguageChoice.resolve(chosen = chosen, setting = "he"))
    }

    @Test
    fun encoding_a_language_round_trips_it() {
        val chosen = TranscriptionLanguageChoice.encode("ar")

        assertEquals("ar", TranscriptionLanguageChoice.resolve(chosen = chosen, setting = "he"))
    }

    @Test
    fun an_unknown_code_is_ignored_rather_than_sent_to_whisper() {
        // Work input data outlives an install: a code retired in a later version must not become a
        // language whisper has never heard of.
        assertEquals("he", TranscriptionLanguageChoice.resolve(chosen = "klingon", setting = "he"))
    }
}
