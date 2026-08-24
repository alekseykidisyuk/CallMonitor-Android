/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuBackend
import com.baba.callvault.server.ShizukuStatus

/**
 * Where CallVault's privileges come from, and whether that source is working right now.
 *
 * Shown to everyone, but written for the person who has Shizuku: standalone is the default and needs no
 * explanation here — its readiness is what the setup wizard is about. What this adds is an honest
 * answer to "is the other way available, and is it working?", which is otherwise invisible.
 *
 * The status is read on each recomposition rather than observed: Shizuku's server can stop at any time
 * (it does not survive a reboot), and a cached "ready" would be a lie the moment it did.
 */
@Composable
fun PrivilegedModeSubSection() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }

    // Bumped to re-read after anything that could change the answer.
    var refresh by remember { mutableIntStateOf(0) }

    val mode = remember(refresh) { prefs.getPrivilegedMode() }
    val status = remember(refresh) { RecorderBackend.shizukuStatus(context) }

    SettingsToggleRow(
        icon = Icons.Filled.Link,
        label = stringResource(R.string.settings_shizuku_mode),
        description = stringResource(
            when (status) {
                // Only worth offering when it could actually work. Saying "use Shizuku" to someone who
                // does not have it is an instruction to go and install a second app, which is precisely
                // the trade CallVault exists to avoid — so it is described, not urged.
                ShizukuStatus.NOT_INSTALLED -> R.string.settings_shizuku_not_installed
                ShizukuStatus.NOT_RUNNING -> R.string.settings_shizuku_not_running
                ShizukuStatus.NO_PERMISSION -> R.string.settings_shizuku_no_permission
                ShizukuStatus.READY -> R.string.settings_shizuku_ready
            }
        ),
        checked = mode == PrivilegedMode.SHIZUKU,
        enabled = status != ShizukuStatus.NOT_INSTALLED || mode == PrivilegedMode.SHIZUKU,
        onCheckedChange = { wantShizuku ->
            // switchTo stops the old backend before starting the new one; two shell-uid recorders
            // would otherwise compete for the same audio input.
            RecorderBackend.switchTo(
                context,
                if (wantShizuku) PrivilegedMode.SHIZUKU else PrivilegedMode.STANDALONE
            )
            refresh++
        }
    )

    // Only when it is the one thing standing in the way, and only in the mode that needs it.
    if (mode == PrivilegedMode.SHIZUKU && status == ShizukuStatus.NO_PERMISSION) {
        NavigationRow(
            icon = Icons.Filled.Key,
            label = stringResource(R.string.settings_shizuku_grant),
            value = "",
            supporting = stringResource(R.string.settings_shizuku_grant_description),
            onClick = {
                ShizukuBackend.requestPermission()
                refresh++
            }
        )
    }
}
