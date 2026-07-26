/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.StringRes
import com.baba.callvault.R

/**
 * What the post-update note tells the user, newest release first.
 *
 * One entry per release rather than one per feature. Someone who skips a few updates should still find
 * out what changed, so the note shows the last [MAX_SHOWN] releases together, each labelled with its
 * version, instead of introducing a single feature and silently dropping the rest.
 *
 * Keep this list in sync with `CHANGELOG.md` — same releases, plain language. Add new entries at the
 * top; older ones fall off the end on their own.
 */
data class ReleaseHighlight(
    val version: String,
    @StringRes val title: Int,
    @StringRes val body: Int,
    /** Where to find it, when the feature is off by default and needs switching on. */
    @StringRes val whereToFind: Int? = null,
)

object ReleaseHighlights {

    /** How many releases the note shows. Older entries stay listed here but are not displayed. */
    const val MAX_SHOWN = 3

    private val ALL = listOf(
        ReleaseHighlight(
            version = "1.4.7",
            title = R.string.whatsnew_147_title,
            body = R.string.whatsnew_147_body,
            whereToFind = R.string.whatsnew_147_where,
        ),
        ReleaseHighlight(
            version = "1.4.6",
            title = R.string.whatsnew_146_title,
            body = R.string.whatsnew_146_body,
            whereToFind = R.string.whatsnew_146_where,
        ),
        ReleaseHighlight(
            version = "1.4.5",
            title = R.string.whatsnew_145_title,
            body = R.string.whatsnew_145_body,
        ),
    )

    /** The releases to show in the post-update note, newest first. */
    fun recent(): List<ReleaseHighlight> = ALL.take(MAX_SHOWN)
}
