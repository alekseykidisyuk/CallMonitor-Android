/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of the transcription language list.
 *
 * Sorted by the **displayed** name rather than the code, because the names are translated: sorting by
 * "de"/"he"/"zh" would produce an order that looks arbitrary in every language including English.
 */
class TranscriptionLanguageOrderTest {

    private val auto = TranscriptionLabels.AUTO_DETECT_KEY

    @Test
    fun languages_come_out_in_alphabetical_order_of_their_names() {
        val sorted = TranscriptionLabels.sortLanguageOptions(
            listOf("he" to "Hebrew", "en" to "English", "ar" to "Arabic", "zh" to "Chinese")
        )

        assertEquals(listOf("Arabic", "Chinese", "English", "Hebrew"), sorted.map { it.second })
    }

    @Test
    fun detect_automatically_comes_first() {
        // It is not a language, so it does not belong in the alphabet — and it is the default, so it is
        // the entry most people want. Both point at the top of the list.
        val sorted = TranscriptionLabels.sortLanguageOptions(
            listOf("he" to "Hebrew", auto to "Detect automatically", "ar" to "Arabic")
        )

        assertEquals(listOf("Detect automatically", "Arabic", "Hebrew"), sorted.map { it.second })
    }

    @Test
    fun accented_names_sort_where_a_reader_expects_them() {
        // A plain string sort puts every accented letter after Z, so a French or German list would look
        // broken. A collator is what makes "Á" sit next to "A".
        val sorted = TranscriptionLabels.sortLanguageOptions(
            listOf("z" to "Zulu", "a" to "Árabe", "b" to "Alemán")
        )

        assertEquals(listOf("Alemán", "Árabe", "Zulu"), sorted.map { it.second })
    }

    @Test
    fun nothing_is_lost_or_duplicated() {
        val input = listOf(auto to "Detect automatically", "he" to "Hebrew", "en" to "English")

        val sorted = TranscriptionLabels.sortLanguageOptions(input)

        assertEquals(input.size, sorted.size)
        assertEquals(input.map { it.first }.toSet(), sorted.map { it.first }.toSet())
    }
}
