/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.ui.common.BidiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The measurement this whole spike exists for: can a small local model summarise a real call, in the
 * language it was spoken in, at a cost a phone can pay?
 *
 * Not a pass/fail test. It runs every model it finds against every transcript it is given and writes
 * down what happened; a human then reads the summaries and decides. Written as an instrumented test
 * only because that is the cheapest way to run real code on a real phone with the app's own data.
 *
 * **Nothing it produces leaves the device.** The report is written to the app's own external files
 * directory, which is readable over adb and deletable in one command. Summaries and transcript text
 * are a private conversation; the numbers are what the decision needs, and the words are for the
 * maintainer to read on the phone.
 *
 * Put one or more `.gguf` files in /sdcard/Download and run:
 *
 *     ./gradlew :app:connectedDebugAndroidTest -PisolateTestApp \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.baba.callvault.summary.SummaryBenchmark
 */
@RunWith(AndroidJUnit4::class)
class SummaryBenchmark {

    /** How many transcripts to run. Enough to see a pattern, few enough to finish in an evening. */
    private val transcriptLimit = 4

    /** Room for a summary of a chunk. Beyond this the model is padding, not summarising. */
    private val maxTokensPerChunk = 220

    private val maxTokensForMerge = 320

    @Test
    fun measure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val models = File("/sdcard/Download")
            .listFiles { file -> file.isFile && file.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.length() }

        // Skipped rather than failed when there is no model: an empty Download folder means the
        // measurement was not set up, which is not the same as the code being broken.
        assumeTrue("no .gguf in /sdcard/Download — push a model first", models.isNotEmpty())

        val transcripts = loadTranscripts(context)
        assumeTrue("no transcripts on this device to summarise", transcripts.isNotEmpty())

        val report = StringBuilder()
        report.appendLine("CallVault summarisation benchmark")
        report.appendLine("device: ${android.os.Build.MODEL}  android ${android.os.Build.VERSION.SDK_INT}")
        report.appendLine("llama: ${LlamaNative.systemInfo()}")
        report.appendLine()

        models.forEach { model ->
            transcripts.forEach { (name, segments) ->
                report.appendLine(runOne(model, name, segments))
                report.appendLine()
            }
        }

        val out = File(context.getExternalFilesDir(null), "summary-benchmark.txt")
        out.writeText(report.toString())
        // The path, never the contents.
        println("benchmark written to ${out.absolutePath}")
    }

    private suspend fun runOne(
        modelFile: File,
        displayName: String,
        segments: List<TranscriptSegmentEntry>
    ): String {
        val chunks = SummaryChunking.chunk(segments)
        // The transcript's own script decides the language asked for, which is what the shipped
        // version would do. Whether the model obeys is the thing being measured.
        val language = if (BidiText.isRtl(segments.joinToString(" ") { it.text })) "he" else "en"

        val lines = StringBuilder()
        lines.appendLine("model:      ${modelFile.name}  (${modelFile.length() / 1_000_000} MB)")
        lines.appendLine("recording:  $displayName")
        lines.appendLine("transcript: ${segments.size} segments, " +
            "${segments.sumOf { it.text.length }} chars, asked for: $language")
        lines.appendLine("chunks:     ${chunks.size}")

        val loadStart = SystemClock.elapsedRealtime()

        return runCatching {
            SummaryEngine.withModel(modelFile.absolutePath) { session ->
                val loadMs = SystemClock.elapsedRealtime() - loadStart
                lines.appendLine("load:       ${loadMs} ms")

                val runStart = SystemClock.elapsedRealtime()
                val partial = chunks.mapIndexed { index, chunk ->
                    val prompt = SummaryPrompt.forChunk(chunk, language)
                    val started = SystemClock.elapsedRealtime()
                    val text = session.generate(prompt, maxTokensPerChunk)
                    lines.appendLine(
                        "  chunk ${index + 1}: ${session.countTokens(prompt)} prompt tokens, " +
                            "${SystemClock.elapsedRealtime() - started} ms"
                    )
                    text.trim()
                }

                val summary = if (partial.size == 1) partial.first() else {
                    val started = SystemClock.elapsedRealtime()
                    val merged = session.generate(SummaryPrompt.forMerge(partial, language), maxTokensForMerge)
                    lines.appendLine("  merge:   ${SystemClock.elapsedRealtime() - started} ms")
                    merged.trim()
                }

                lines.appendLine("total:      ${SystemClock.elapsedRealtime() - runStart} ms")
                lines.appendLine("peak pss:   ${peakPssMb()} MB")
                lines.appendLine("--- summary, to be read and rated by a human ---")
                lines.appendLine(summary)
                lines.toString()
            }
        }.getOrElse { failure ->
            // A model that cannot be loaded or run is a result, not a crash: it is exactly the sort
            // of thing this measurement exists to discover.
            lines.appendLine("FAILED: ${failure.message}")
            lines.toString()
        }
    }

    private suspend fun loadTranscripts(
        context: Context
    ): List<Pair<String, List<TranscriptSegmentEntry>>> {
        if (!TranscriptDatabase.exists(context)) return emptyList()
        val dao = TranscriptDatabase.get(context).transcriptDao()
        return dao.allTranscripts()
            .mapNotNull { entry -> dao.observe(entry.displayName).first() }
            .map { it.transcript.displayName to it.segments }
            // Longest first: a two-word transcript measures nothing, and the interesting behaviour —
            // chunking, merging, staying in the right language across a long call — only appears on
            // a substantial one.
            .sortedByDescending { (_, segments) -> segments.sumOf { it.text.length } }
            .take(transcriptLimit)
    }

    private fun peakPssMb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024
    }
}
