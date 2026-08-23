/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.SpeakerNames
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
 * @param onSeekTo        start playing from a segment's start; a transcript you cannot navigate from
 *                        is only text, which is why the timestamps are stored at all. It starts
 *                        playback rather than merely seeking, so a tap works on a call that is not
 *                        loaded yet.
 * @param onRetranscribe  run it again, e.g. after switching to a better model.
 * @param onDelete        discard the text and keep the audio. Someone may want the recording without
 *                        a searchable transcript of what was said in it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranscriptSheet(
    transcript: TranscriptWithSegments?,
    title: String,
    /**
     * Where playback has reached, so the line being spoken can be lit and the transport bar knows
     * what to show. -1 when this recording is not the loaded track.
     */
    positionMs: Long = -1L,
    durationMs: Long = 0L,
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetranscribe: () -> Unit,
    onDelete: () -> Unit,
    /**
     * How to name the two captured channels.
     *
     * Passed in rather than read here so the sheet stays a pure rendering of what it is given, and
     * because the answer belongs to the phone rather than to this transcript: it is learned from
     * ringback over several calls and applies to all of them at once.
     */
    speakerNames: SpeakerNames,
    /** The summary for this recording, so the sheet can show a stored one rather than rewrite it. */
    summaryState: SummaryCardState,
    onSummarise: () -> Unit,
    onStopSummary: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        // Directly under the title, above the words. Someone who opened the transcript to find out
        // what a call was about is answered here without reading it — and a summary that already
        // exists is simply shown, because writing another costs ninety seconds of CPU for a page
        // that is already on the phone.
        SummarySheetStrip(
            state = summaryState,
            onCreate = onSummarise,
            onStop = onStopSummary,
            onSeek = onSeekTo
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

            else -> {
                val active = remember(segments, positionMs) {
                    if (positionMs < 0) -1
                    else TranscriptFollow.activeIndex(segments.map { it.startMs }, positionMs)
                }
                val listState = rememberLazyListState()

                // Follow the speaker, but only while it is actually moving: scrolling the list out
                // from under someone who is reading it would be worse than not following at all.
                LaunchedEffect(active) {
                    if (active >= 0) listState.animateScrollToItem(active)
                }

                LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(segments, key = { _, s -> s.id }) { index, segment ->
                        TranscriptLine(
                            segment = segment,
                            speaker = speakerNames.of(segment.speaker),
                            isActive = index == active,
                            onClick = { onSeekTo(segment.startMs) }
                        )
                    }
                }
            }
        }

        // Pinned below the transcript rather than scrolling with it: the point of these controls is
        // to pause or step back while reading, which is when they would have scrolled away.
        //
        // Present in every state, including "still loading" and "no speech was recognised" — the
        // recording exists and is playable whether or not there are words to show for it.
        TranscriptPlayerBar(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isLoading = isLoading,
            onPlay = onPlay,
            onPause = onPause,
            onResume = onResume,
            onSeek = onSeek,
            onSkip = onSkip,
            modifier = Modifier.padding(top = 4.dp)
        )

        // FlowRow, not Row: four actions do not fit on one line on a narrow screen in every locale,
        // and wrapping is better than squeezing labels until they ellipsize.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val plain = segments.asPlainText(speakerNames)
            TextButton(onClick = { onCopy(plain) }, enabled = segments.isNotEmpty()) {
                Text(stringResource(R.string.transcript_copy))
            }
            TextButton(onClick = { onShare(plain) }, enabled = segments.isNotEmpty()) {
                Text(stringResource(R.string.transcript_share))
            }
            TextButton(onClick = onRetranscribe) {
                Text(stringResource(R.string.transcript_retranscribe))
            }
            // Error-tinted, because it destroys something: the audio survives but the text does not,
            // and getting it back costs a full re-run of the model.
            TextButton(
                onClick = onDelete,
                enabled = transcript != null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.transcript_delete))
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
private fun TranscriptLine(
    segment: TranscriptSegmentEntry,
    speaker: String?,
    isActive: Boolean,
    onClick: () -> Unit
) {
    // The whole line mirrors, not just the words. Setting only the text's direction left the timestamp
    // column stranded on the left of a Hebrew transcript and short lines hugging the wrong edge,
    // because "start" still meant left. Providing the layout direction moves the column to the correct
    // side and makes start-alignment mean the right edge, in one stroke.
    //
    // Per line rather than per transcript: a call that switches language mid-way still reads correctly
    // throughout, and each line is judged only on what it actually says.
    val direction = if (BidiText.isRtl(segment.text)) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                // A tint rather than bolder text: re-weighting the line would reflow it, so every
                // line would twitch sideways as the highlight passed through.
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent
                )
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
                speaker?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
                )
            }
        }
    }
}

/**
 * The transcript as shareable plain text, one timestamped line per segment.
 *
 * Named with [names] rather than with the stored `A`/`B`, so what leaves the phone reads the way the
 * screen does — and stays neutral for exactly as long as the screen does.
 */
private fun List<TranscriptSegmentEntry>.asPlainText(names: SpeakerNames): String =
    joinToString("\n") { segment ->
        val speaker = names.of(segment.speaker)?.let { "$it: " }.orEmpty()
        "[${TranscriptTimestamp.format(segment.startMs)}] $speaker${segment.text}"
    }

private val TIMESTAMP_WIDTH = 56.dp
