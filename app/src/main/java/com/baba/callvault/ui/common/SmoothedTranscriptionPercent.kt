/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import androidx.compose.runtime.produceState
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.transcription.TranscriptionEstimate
import com.baba.callvault.transcription.TranscriptionProgress
import com.baba.callvault.transcription.model.TranscriptionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** How often the shown figure is recomputed. Fast enough to look alive, slow enough to be free. */
private const val TICK_MS = 500L

/**
 * A percentage that keeps moving between whisper's anchors.
 *
 * whisper reports at chunk boundaries, so the raw figure sits still for long stretches. This holds
 * the clock, asks [TranscriptionProgress] what to show, and never lets the answer go backwards.
 *
 * Restarts cleanly when the recording being worked on changes — a new file is a new clock, and
 * carrying the previous one over would make the second recording in a batch open at the first one's
 * percentage.
 */
@Composable
fun rememberSmoothedPercent(
    state: TranscribingPillState,
    recordings: List<RecordingItem>
): State<Int> {
    val context = LocalContext.current
    val current = state.currentName()

    // Read fresh on every tick without restarting the loop: the reported figure changes far more
    // often than the recording does, and keying the effect on it would reset the clock each time.
    val reported by rememberUpdatedState(state.percentFor(current.orEmpty()))

    // Read from the file, not from RecordingItem.durationSeconds.
    //
    // That field comes from matching the call log, and plenty of recordings never match — their rows
    // show a date and a size and no duration at all. For those, an estimate built on it is zero,
    // prediction is disabled, and the figure sits on whisper's anchors: exactly the stuck-looking 1%
    // this was meant to cure. The container knows its own length regardless, which is also the
    // source the confirmation dialog quotes, so the two now agree by construction.
    val estimatedMs by produceState(0L, current) {
        val uri = recordings.firstOrNull { it.displayName == current }?.uri
        value = if (uri == null) 0L else withContext(Dispatchers.IO) {
            val audioMs = runCatching { AudioDecoder.durationMs(context, uri) }.getOrDefault(0L)
            if (audioMs <= 0L) 0L else {
                val prefs = AppPreferences(context)
                val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId())
                    ?: TranscriptionModel.DEFAULT
                val rtf = prefs.getTranscriptionRtf(model.id) ?: model.realTimeFactor
                TranscriptionEstimate.estimateMs(audioMs, rtf)
            }
        }
    }

    val shown = remember(current) { mutableIntStateOf(0) }

    LaunchedEffect(current, estimatedMs) {
        if (current == null) {
            shown.intValue = 0
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            shown.intValue = TranscriptionProgress.display(
                reportedPercent = reported,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                estimatedMs = estimatedMs,
                previous = shown.intValue
            )
            delay(TICK_MS)
        }
    }

    return shown
}

/**
 * The pill state to actually draw: smoothed while running, and held for a moment at 100 when it ends.
 *
 * Without the hold the figure disappears at whatever it happened to read on the last tick — around
 * seventy on a short call — which looks like the run gave up rather than finished. A run that
 * completed earned its hundred, and showing it costs a second.
 *
 * A stop is not a finish and gets no hold: the work did not complete, and pretending otherwise would
 * be the one lie this whole feature cannot afford.
 */
@Composable
fun rememberTranscribingDisplay(
    state: TranscribingPillState,
    recordings: List<RecordingItem>
): TranscribingPillState {
    val smoothed by rememberSmoothedPercent(state, recordings)

    var holding by remember { mutableStateOf<TranscribingPillState?>(null) }

    // Both remembered while the run is going, because by the time they are needed the run is over:
    // `state` has gone Hidden and `smoothed` has already been reset to zero for the absent
    // recording. Reading either at that point raced the reset and usually lost.
    var lastRunning by remember { mutableStateOf<TranscribingPillState?>(null) }
    var lastPercent by remember { mutableIntStateOf(0) }

    if (state.occupiesTitleSlot) {
        lastRunning = state
        if (smoothed > 0) lastPercent = smoothed
    }

    LaunchedEffect(state.occupiesTitleSlot) {
        if (state.occupiesTitleSlot) {
            holding = null
            return@LaunchedEffect
        }
        // Only a run that had actually got somewhere earns the hundred; one cancelled early, or
        // never really started, should simply vanish.
        val previous = lastRunning
        if (previous != null && lastPercent >= FINISHED_ENOUGH) {
            holding = previous.withPercent(100)
            delay(FINISH_HOLD_MS)
        }
        holding = null
        lastRunning = null
        lastPercent = 0
    }

    return holding ?: state.withPercent(smoothed)
}

/** Near enough the end that finishing is what happened, rather than being stopped early. */
private const val FINISHED_ENOUGH = 50

/** Long enough to be read, short enough not to be in the way. */
private const val FINISH_HOLD_MS = 1_200L
