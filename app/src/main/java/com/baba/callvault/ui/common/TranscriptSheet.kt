/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptWithSegments

/**
 * The transcript, near full height.
 *
 * A [ModalBottomSheet] with `skipPartiallyExpanded` rather than a full-screen dialog: it opens at
 * nearly the full height as intended while keeping the platform's dismiss gestures, which a custom
 * screen would have to reimplement.
 *
 * Segment text is deliberately never logged anywhere — it is the substance of a private call.
 *
 * @param transcript      the transcript to show, or null while it is still loading.
 * @param title           who the call was with, for the header.
 * @param onSeekTo        jump playback to a segment's start; a transcript you cannot navigate from is
 *                        only text, which is why the timestamps are stored at all.
 * @param onRetranscribe  run it again, e.g. after switching to a better model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(
    transcript: TranscriptWithSegments?,
    title: String,
    onDismiss: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetranscribe: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        val segments = transcript?.segments.orEmpty()

        when {
            transcript == null -> Text(
                text = stringResource(R.string.transcript_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )

            // Silence is a result, not a failure. A blank sheet would read as a bug.
            segments.isEmpty() -> Text(
                text = stringResource(R.string.transcript_no_speech),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )

            else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(segments, key = { it.id }) { segment ->
                    TranscriptLine(segment = segment, onClick = { onSeekTo(segment.startMs) })
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val plain = segments.asPlainText()
            TextButton(onClick = { onCopy(plain) }, enabled = segments.isNotEmpty()) {
                Text(stringResource(R.string.transcript_copy))
            }
            TextButton(onClick = { onShare(plain) }, enabled = segments.isNotEmpty()) {
                Text(stringResource(R.string.transcript_share))
            }
            TextButton(onClick = onRetranscribe) {
                Text(stringResource(R.string.transcript_retranscribe))
            }
        }
    }
}

/**
 * One line: when it was said, optionally who said it, and the words.
 *
 * The speaker column is omitted entirely when unknown rather than rendered blank, so a transcript with
 * no speaker data reads as ordinary timestamped text instead of looking broken.
 */
@Composable
private fun TranscriptLine(segment: TranscriptSegmentEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = TranscriptTimestamp.format(segment.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(TIMESTAMP_WIDTH)
        )

        Column(modifier = Modifier.weight(1f)) {
            segment.speaker?.let { speaker ->
                Text(
                    text = speaker,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // No textAlign override: the text follows the ambient layout direction, so Hebrew renders
            // right-to-left without the timestamp column being dragged along with it.
            Text(text = segment.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The transcript as shareable plain text, one timestamped line per segment. */
private fun List<TranscriptSegmentEntry>.asPlainText(): String = joinToString("\n") { segment ->
    val speaker = segment.speaker?.let { "$it " }.orEmpty()
    "[${TranscriptTimestamp.format(segment.startMs)}] $speaker${segment.text}"
}

private val TIMESTAMP_WIDTH = 56.dp
