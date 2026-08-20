/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

/**
 * Keeps a name in its own writing direction when it sits inside a sentence in another.
 *
 * Most of the calls this app records are Hebrew, and most of its UI is English, so nearly every place
 * a contact name appears is a mixed-direction paragraph. Without isolation the whole paragraph resolves
 * one way and the name is just characters inside it: a name of Hebrew followed by a digit rendered as
 * *"Transcribing 2 גבריאלb keeps your phone busy"*, with the digit and the Latin letter thrown to the
 * wrong side.
 *
 * Unicode's isolate characters are the fix the standard provides for exactly this, and they work
 * regardless of which direction the surrounding layout happens to be in — unlike right-aligning things
 * by hand, which only ever fixes one of the two cases.
 */
object BidiText {

    /** FIRST STRONG ISOLATE — the run takes its direction from its own first strong character. */
    private const val FSI = '⁨'

    /** POP DIRECTIONAL ISOLATE — restores whatever direction was in force outside. */
    private const val PDI = '⁩'

    /**
     * [text] wrapped so it reads in its own direction wherever it is placed.
     *
     * Blank text is returned untouched: wrapping it would leave two invisible characters that every
     * `isBlank()` downstream would then call content. Already-isolated text is returned untouched too,
     * since labels pass through several layers and the markers would otherwise accumulate.
     */
    fun isolate(text: String): String = when {
        text.isBlank() -> ""
        text.first() == FSI && text.last() == PDI -> text
        else -> "$FSI$text$PDI"
    }
}
