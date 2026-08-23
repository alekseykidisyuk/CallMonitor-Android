/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.summary.SummaryBlocker
import com.baba.callvault.summary.SummaryQueue
import com.baba.callvault.summary.SummaryScheduler
import com.baba.callvault.transcription.model.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * What the summary card should show for one recording.
 *
 * Two sources have to agree: the database, which says whether a summary exists and whether the
 * transcript it would be read from is finished, and WorkManager, which says whether one is being
 * written right now. They are combined rather than read separately for the same reason the model
 * download's two halves are — read apart, they contradict each other at exactly the moment they
 * matter, which is when a run finishes.
 */
@Composable
fun rememberSummaryState(displayName: String): State<SummaryCardState> {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val workManager = remember(context) { WorkManager.getInstance(context) }

    return produceState<SummaryCardState>(
        initialValue = SummaryCardState.Blocked(SummaryBlocker.NO_TRANSCRIPT),
        workManager, displayName
    ) {
        val db = TranscriptDatabase.get(appContext)

        combine(
            db.summaryDao().observe(displayName),
            workManager.getWorkInfosForUniqueWorkFlow(SummaryScheduler.WORK_NAME)
        ) { stored, infos ->
            // Only the job for *this* recording counts. The queue is shared, so a summary running
            // for a different call must not make this one look busy.
            val mine = infos.lastOrNull { info ->
                SummaryScheduler.displayNameOf(info.progress) == displayName ||
                    (info.state == WorkInfo.State.ENQUEUED && infos.size == 1)
            }
            stored to mine
        }.collect { (stored, info) ->
            val running = info?.state == WorkInfo.State.RUNNING ||
                info?.state == WorkInfo.State.ENQUEUED

            value = when {
                running -> SummaryCardState.Running(
                    SummaryScheduler.percentOf(info!!.progress) ?: 0
                )

                stored != null -> {
                    val parsed = withContext(Dispatchers.Default) { CallSummary.parse(stored.document) }
                    // A row that will not parse is not a summary. Offering to write another is more
                    // use than rendering a card with nothing in it.
                    parsed?.let { SummaryCardState.Ready(it) } ?: SummaryCardState.Offered
                }

                info?.state == WorkInfo.State.FAILED -> SummaryCardState.Failed

                else -> {
                    val installed = withContext(Dispatchers.IO) {
                        ModelRepository.isInstalled(appContext, SummaryModel.DEFAULT)
                    }
                    val blocker = SummaryQueue.blockerFor(appContext, displayName, installed)
                    if (blocker == null) SummaryCardState.Offered else SummaryCardState.Blocked(blocker)
                }
            }
        }
    }
}
