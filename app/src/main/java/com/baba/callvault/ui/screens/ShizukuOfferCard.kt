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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.ShizukuStatus

/**
 * Offers Shizuku during setup — but only to someone who already has it running.
 *
 * The whole point of CallVault is that it needs no second app, so this must never read as an
 * instruction to go and install one. `PermissionsScreen` therefore renders this only when a Shizuku
 * server is actually up: for everyone else the card does not exist and setup proceeds as it always has.
 *
 * The choice is a real fork, so both sides are buttons of equal weight — "Use Shizuku" skips pairing
 * entirely, "Use CallVault" is the ordinary path. Neither is styled as the recommendation, because
 * which one is better genuinely depends on whether this person wants to keep Shizuku running.
 *
 * Colours are stated explicitly rather than left to the M3 defaults: this app's scheme resolves several
 * of those to its coral tone, and a setup card that shows up red reads as an error.
 */
@Composable
fun ShizukuOfferCard(
    status: ShizukuStatus,
    mode: PrivilegedMode,
    onUseShizuku: () -> Unit,
    onUseCallVault: () -> Unit,
    onGrant: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.permissions_shizuku_offer_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    when {
                        mode != PrivilegedMode.SHIZUKU -> R.string.permissions_shizuku_offer_body
                        status == ShizukuStatus.NO_PERMISSION -> R.string.permissions_shizuku_needs_permission
                        else -> R.string.permissions_shizuku_active
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                when {
                    // Not chosen yet: the fork itself.
                    mode != PrivilegedMode.SHIZUKU -> {
                        TextButton(onClick = onUseCallVault) {
                            Text(stringResource(R.string.permissions_shizuku_use_callvault))
                        }
                        TextButton(onClick = onUseShizuku) {
                            Text(stringResource(R.string.permissions_shizuku_use))
                        }
                    }
                    // Chosen, but Shizuku has not let us in yet — the one remaining step.
                    status == ShizukuStatus.NO_PERMISSION -> {
                        TextButton(onClick = onUseCallVault) {
                            Text(stringResource(R.string.permissions_shizuku_use_callvault))
                        }
                        TextButton(onClick = onGrant) {
                            Text(stringResource(R.string.settings_shizuku_grant))
                        }
                    }
                    // Chosen and working: leave a way back, and nothing else.
                    else -> {
                        TextButton(onClick = onUseCallVault) {
                            Text(stringResource(R.string.permissions_shizuku_use_callvault))
                        }
                    }
                }
            }
        }
    }
}
