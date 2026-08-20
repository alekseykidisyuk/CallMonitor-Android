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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.TranscriptRepository
import kotlinx.coroutines.delay

/**
 * Waiting this long after the last keystroke before searching.
 *
 * Every query is an FTS scan across every transcript ever made; running one per character would put a
 * database read on the main-thread dispatcher's heels for the whole time someone is typing.
 */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * Search across every transcript, and jump to the moment a word was said.
 *
 * Deliberately searches the **whole** library rather than the filtered list: someone reaching for
 * search is looking for a call they cannot find by scrolling, and silently honouring an active contact
 * or date filter would hide the very result they wanted. Tapping a result plays that recording from
 * the matching timestamp, which works whether or not the row is currently on screen.
 *
 * @param recordings every recording the user has, used to turn a hit into something openable.
 * @param onOpen     play the matched recording from the matched moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSearchSheet(
    recordings: List<RecordingItem>,
    onDismiss: () -> Unit,
    onOpen: (TranscriptSearchRow) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<TranscriptSearchRow>()) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query, recordings) {
        if (query.isBlank()) {
            rows = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        rows = TranscriptSearch.rowsFor(TranscriptRepository.search(context, query), recordings)
        searched = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(stringResource(R.string.transcript_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        )

        when {
            !searched -> SearchNote(stringResource(R.string.transcript_search_idle))

            // The caveat belongs here rather than in a help page: unicode61 matches whole words with
            // no stemming, so an inflected form genuinely will not match and would otherwise read as
            // the search being broken.
            rows.isEmpty() -> SearchNote(stringResource(R.string.transcript_search_empty))

            else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(rows, key = { it.displayName }) { row ->
                    SearchResultRow(row = row, onClick = { onOpen(row) })
                }
            }
        }
    }
}

/** One result: who the call was with, when in it, and the words that matched. */
@Composable
private fun SearchResultRow(row: TranscriptSearchRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = TranscriptTimestamp.format(row.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // No textAlign override: the snippet follows the ambient layout direction so Hebrew
            // renders right-to-left without dragging the rest of the row with it.
            Text(
                text = row.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
    )
}
