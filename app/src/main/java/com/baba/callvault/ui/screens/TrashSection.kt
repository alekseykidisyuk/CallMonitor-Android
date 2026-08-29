/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.system.storage.RecordingTrash
import com.baba.callvault.system.storage.RecordingTrashRepository
import com.baba.callvault.system.storage.TrashedRecording
import kotlinx.coroutines.launch

/**
 * Recordings the user has deleted and can still get back.
 *
 * **Every row says how long it has left.** A recycle bin whose contents vanish on a schedule nobody
 * stated is a worse promise than no recycle bin: the whole value is knowing that a mistake is
 * recoverable *for a while*, and "a while" has to be visible or it cannot be relied on.
 *
 * Home reloads its list on resume, so a restored recording is simply there when the user goes back;
 * this section deliberately does not reach across to tell it, which would be a second path to keep in
 * step with the first.
 *
 * Deleting for good is error-coloured and immediate. It is the only irreversible action left in the
 * delete story, and it is one the user has now taken two deliberate steps to reach.
 */
@Composable
fun TrashSection(expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrashedRecording>>(emptyList()) }
    var reloadNonce by remember { mutableStateOf(0) }

    // Read only while the section is open. It walks both storage folders, which on the Drive folder
    // is a network round trip, and doing that to draw a section header nobody expanded would make
    // opening Settings slower for everyone who never deletes anything.
    LaunchedEffect(expanded, reloadNonce) {
        if (expanded) items = RecordingTrashRepository.list(context)
    }

    SettingsSection(
        title = stringResource(R.string.settings_section_trash),
        expanded = expanded,
        onToggle = onToggle
    ) {
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_trash_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            return@SettingsSection
        }

        val now = System.currentTimeMillis()
        items.forEach { item ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = item.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.settings_trash_days_left,
                        RecordingTrash.daysRemaining(item.trashedName, now)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        scope.launch {
                            RecordingTrashRepository.restore(context, item.trashedName)
                            reloadNonce++
                        }
                    }) { Text(stringResource(R.string.settings_trash_restore)) }

                    TextButton(
                        onClick = {
                            scope.launch {
                                RecordingTrashRepository.deleteForever(context, item.trashedName)
                                reloadNonce++
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.settings_trash_delete_forever)) }
                }
            }
        }
    }
}
