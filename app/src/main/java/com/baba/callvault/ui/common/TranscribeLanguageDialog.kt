/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.transcription.TranscriptionLanguageChoice

/**
 * Asks which language to transcribe one recording in.
 *
 * Shown only when the user has turned the ask on (`AppPreferences.getTranscriptionAskLanguage`), and
 * the answer applies to that recording alone: a phone that takes calls in two languages should not
 * have to visit Settings twice a day, and should not have its default silently rewritten either.
 *
 * The pinned setting is preselected and labelled as such, so the common case — the language is the
 * usual one — is a single confirming tap rather than a hunt through the list.
 *
 * @param setting the pinned Settings language, null meaning auto-detect.
 * @param onConfirm receives the pick already encoded by [TranscriptionLanguageChoice.encode].
 */
@Composable
fun TranscribeLanguageDialog(
    title: String,
    setting: String?,
    onDismiss: () -> Unit,
    onConfirm: (language: String) -> Unit
) {
    var picked by remember { mutableStateOf(TranscriptionLanguageChoice.encode(setting)) }

    val options = TranscriptionLabels.sortLanguageOptions(
        TranscriptionLabels.LANGUAGE_OPTIONS.map { code ->
            TranscriptionLanguageChoice.encode(code) to stringResource(TranscriptionLabels.languageOf(code))
        }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Translate, contentDescription = null) },
        title = { Text(stringResource(R.string.transcribe_language_title)) },
        text = {
            // Thirteen languages plus auto-detect do not fit a dialog on a short phone, and a list that
            // cannot reach its last entry would hide whichever languages sort last in the user's locale.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.transcribe_language_message, title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                options.forEach { (key, label) ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = key == picked,
                                onClick = { picked = key },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = key == picked, onClick = null)
                        Text(
                            text = if (key == TranscriptionLanguageChoice.encode(setting)) {
                                stringResource(R.string.transcribe_language_current, label)
                            } else {
                                label
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(stringResource(R.string.transcribe_language_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}
