/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.data.transcripts.TranscriptStatus

/**
 * The single transcript affordance on a recording row.
 *
 * One slot that changes meaning: transcribe → in progress → read. Which it is comes from
 * [TranscriptRowAction], which is unit-tested; this only draws it.
 *
 * @param onTranscribe start a transcription for this recording.
 * @param onOpen       open the finished transcript.
 * @param onRetry      clear a failure and try again.
 */
@Composable
fun TranscriptActionButton(
    status: TranscriptStatus,
    onTranscribe: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** How far into this recording, 0-100. Zero means not known, and spins instead. */
    percent: Int = 0
) {
    val action = TranscriptRowAction.forStatus(status)
    val description = stringResource(action.contentDescriptionRes)

    // Work in flight is shown, never re-triggerable: a second tap would only queue the same call
    // again, and the first tap already committed the phone to minutes of CPU.
    if (!action.isTappable) {
        // Determinate as soon as there is a real number, indeterminate only until then. A circle
        // that spins for minutes without filling is indistinguishable from a hang, which is what
        // this row looked like on a long call — the ring filling is the difference between "still
        // working" and "possibly stuck", read at a glance and without any text to fit.
        // Colours stated, never defaulted. M3 draws a track behind the moving arc and takes it from
        // a container role, which in this theme is CoralDeep — so the row showed a RED ring with a
        // teal segment sweeping it, reading as an error on a recording that was working perfectly.
        // The same trap RecordingRow's selected-card colour records.
        val accent = MaterialTheme.colorScheme.primary
        val track = accent.copy(alpha = 0.20f)

        if (percent > 0) {
            CircularProgressIndicator(
                progress = { percent / 100f },
                modifier = modifier.size(PROGRESS_SIZE),
                color = accent,
                strokeWidth = PROGRESS_STROKE,
                trackColor = track
            )
        } else {
            CircularProgressIndicator(
                modifier = modifier.size(PROGRESS_SIZE),
                color = accent,
                strokeWidth = PROGRESS_STROKE,
                trackColor = track
            )
        }
        return
    }

    IconButton(
        onClick = when (action) {
            TranscriptRowAction.TRANSCRIBE -> onTranscribe
            TranscriptRowAction.OPEN -> onOpen
            TranscriptRowAction.RETRY -> onRetry
            TranscriptRowAction.BUSY -> return // unreachable: handled above
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = when (action) {
                TranscriptRowAction.TRANSCRIBE -> Icons.Outlined.Subtitles
                TranscriptRowAction.OPEN -> Icons.AutoMirrored.Filled.Article
                TranscriptRowAction.RETRY -> Icons.Filled.ErrorOutline
                TranscriptRowAction.BUSY -> Icons.Outlined.Subtitles // unreachable
            },
            contentDescription = description,
            // A finished transcript is worth drawing attention to; a failure more so.
            tint = when (action) {
                TranscriptRowAction.OPEN -> MaterialTheme.colorScheme.primary
                TranscriptRowAction.RETRY -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private val PROGRESS_SIZE = 20.dp
private val PROGRESS_STROKE = 2.dp
