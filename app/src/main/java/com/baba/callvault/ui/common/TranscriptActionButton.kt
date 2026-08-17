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
    modifier: Modifier = Modifier
) {
    val action = TranscriptRowAction.forStatus(status)
    val description = stringResource(action.contentDescriptionRes)

    // Work in flight is shown, never re-triggerable: a second tap would only queue the same call
    // again, and the first tap already committed the phone to minutes of CPU.
    if (!action.isTappable) {
        CircularProgressIndicator(
            modifier = modifier.size(PROGRESS_SIZE),
            strokeWidth = PROGRESS_STROKE
        )
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
