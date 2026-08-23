/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A model's summary of a call, stored beside the transcript it was read from.
 *
 * It belongs in this database rather than the recordings catalog for the same reason a transcript
 * does, only more so: the catalog is a rebuildable cache with a destructive fallback, and a summary
 * costs roughly ninety seconds of the user's CPU and battery on top of the transcription it depends
 * on. Losing one to an unrelated schema bump would be a real cost to the person holding the phone.
 *
 * **The summary is kept as its JSON document, not as six columns.**
 * [com.baba.callvault.summary.CallSummary] is the only thing that reads it, nothing ever queries or
 * sorts on a field inside it, and search runs over the transcript's FTS rows instead. One column
 * means a grammar that grows a field later is a prompt change rather than a migration, and it keeps
 * a single parser — the one every summary already passes through on its way out of the model —
 * rather than a second, quieter copy of the shape living in the schema.
 */
@Entity(tableName = "call_summaries")
data class CallSummaryEntry(
    /** The recording's `displayName`, this database's natural key throughout. */
    @PrimaryKey val displayName: String,
    /** [com.baba.callvault.summary.CallSummary.toJson]. Validated before it was written. */
    val document: String,
    /**
     * Which model wrote it.
     *
     * Recorded because summaries are not comparable across models — a card produced by a smaller
     * model, or by one that has since been replaced, is worth knowing about when the user says the
     * summary is wrong. It is also what lets a future version offer to redo the old ones.
     */
    val model: String,
    val createdAt: Long = 0L
)
