/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.transcription.model.ModelDownloadWorker
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.ui.common.ModelDownloadState
import com.baba.callvault.ui.common.rememberModelDownloadState

/**
 * The summariser's own section in Settings.
 *
 * In its own file rather than in `SettingsScreen`, which is already past two thousand lines. It
 * reuses that file's `SettingsSection` and `NavigationRow`, widened to `internal` for the purpose.
 *
 * **The costs are stated before the button, not after it.** This is a 3.46 GB download that then
 * wants about 3.5 GB of memory to run, which genuinely rules the feature out on smaller phones.
 * Measured on the OP12; the numbers come from [SummaryModel] so the copy cannot drift from what was
 * actually measured.
 */
@Composable
internal fun SummarySection(
    expanded: Boolean,
    onToggle: () -> Unit,
    updateTrigger: Int,
    onDownload: (SummaryModel) -> Unit,
    onCancel: (SummaryModel) -> Unit,
    onDelete: (SummaryModel) -> Unit
) {
    val context = LocalContext.current
    val model = SummaryModel.DEFAULT
    val isInstalled = remember(updateTrigger) { ModelRepository.isInstalled(context, model) }
    val partialBytes = remember(updateTrigger) { ModelRepository.partialBytes(context, model) }
    val state by rememberModelDownloadState(model, isInstalled, partialBytes)

    SettingsSection(
        title = stringResource(R.string.settings_section_summaries),
        expanded = expanded,
        onToggle = onToggle
    ) {
        Note(
            stringResource(
                R.string.summary_requirements_body,
                model.sizeBytes.toGigabytes(),
                model.peakMemoryBytes.toGigabytes()
            )
        )

        when (val current = state) {
            ModelDownloadState.Installed -> NavigationRow(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.summary_model_delete),
                value = stringResource(R.string.summary_model_installed),
                onClick = { onDelete(model) }
            )

            ModelDownloadState.Absent -> NavigationRow(
                icon = Icons.Filled.Download,
                label = stringResource(R.string.summary_model_download),
                value = stringResource(
                    R.string.summary_model_download_subtitle,
                    model.sizeBytes.toGigabytes()
                ),
                onClick = { onDownload(model) }
            )

            // Stopped part-way. Saying so is the point: those bytes are banked, the server resumes
            // from exactly that offset, and a row reading "Download, 3.5 GB" would hide a gigabyte
            // the user has already paid for. The discard row exists because otherwise that
            // gigabyte can only be reclaimed by clearing the app's data.
            is ModelDownloadState.Paused -> {
                NavigationRow(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.summary_model_resume),
                    value = stringResource(
                        R.string.summary_model_resume_subtitle,
                        current.downloadedBytes.toGigabytes(),
                        model.sizeBytes.toGigabytes()
                    ),
                    onClick = { onDownload(model) }
                )
                NavigationRow(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.summary_model_discard),
                    value = stringResource(
                        R.string.summary_model_discard_subtitle,
                        current.downloadedBytes.toGigabytes()
                    ),
                    onClick = { onDelete(model) }
                )
            }

            // Queued and 0% look identical on a bar and mean different things, so waiting says so
            // in words and shows an indeterminate bar rather than an empty one.
            ModelDownloadState.Waiting -> DownloadingRow(
                label = stringResource(R.string.summary_model_waiting),
                percent = null,
                onCancel = { onCancel(model) }
            )

            is ModelDownloadState.Downloading -> DownloadingRow(
                label = stringResource(R.string.summary_model_downloading, current.percent),
                percent = current.percent,
                onCancel = { onCancel(model) }
            )

            // A failure says which one it was, because the two the user can act on need different
            // actions: free up space, or simply try again.
            is ModelDownloadState.Failed -> {
                NavigationRow(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.summary_model_download),
                    value = stringResource(
                        when (current.reason) {
                            ModelDownloadWorker.ERROR_NO_SPACE -> R.string.summary_model_failed_space
                            ModelDownloadWorker.ERROR_VERIFICATION_FAILED -> R.string.summary_model_failed_verify
                            else -> R.string.summary_model_failed
                        }
                    ),
                    onClick = { onDownload(model) }
                )
            }
        }

        // Named where it can be read before the download starts. CallVault never ships the weights,
        // so its own licensing is unaffected — but the person accepting Google's terms is entitled
        // to know that is what they are doing.
        Note(stringResource(R.string.summary_model_licence))
    }
}

/** A download in flight, with the one action that makes sense while it runs. */
@Composable
private fun DownloadingRow(label: String, percent: Int?, onCancel: () -> Unit) {
    // Both colours are stated. Left to the theme, M3 resolves the track to CoralDeep and the bar
    // renders teal-on-pink, which reads as an error rather than as progress — the same trap that
    // has already produced a maroon tonal button, a maroon selected card and a red progress ring
    // in this codebase. Observed here too, on the emulator, before it was pinned down.
    val accent = MaterialTheme.colorScheme.primary
    val track = accent.copy(alpha = TRACK_ALPHA)
    val barModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)

    Column(Modifier.fillMaxWidth()) {
        NavigationRow(
            icon = Icons.Filled.Close,
            label = label,
            value = stringResource(R.string.summary_model_cancel),
            onClick = onCancel
        )
        // Determinate only once there is a real figure; an empty bar sitting at zero reads as stuck.
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / PERCENT },
                modifier = barModifier,
                color = accent,
                trackColor = track
            )
        } else {
            LinearProgressIndicator(
                modifier = barModifier,
                color = accent,
                trackColor = track
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ColumnScope.Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

private const val PERCENT = 100f

/** How much of the accent shows through as the bar's unfilled track. */
private const val TRACK_ALPHA = 0.20f

/** Bytes as gigabytes, for copy that talks in the units a person would. */
private fun Long.toGigabytes(): Float = this / 1_000_000_000f
