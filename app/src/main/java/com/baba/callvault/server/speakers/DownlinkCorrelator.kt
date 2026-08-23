/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import com.baba.callvault.data.ChannelMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Works out which captured channel is the far party, by asking the platform directly.
 *
 * The combined `VOICE_CALL` capture carries both directions but never says which is which — Android
 * documents it as "uplink + downlink" and leaves the channel order to the OEM. `VOICE_DOWNLINK` has
 * no such ambiguity: the platform defines it as the far party and nothing else. Capturing a few
 * seconds of it alongside the call turns an unanswerable question into a measurement.
 *
 * **Envelopes, not samples.** The two are separate `AudioRecord`s, opened microseconds apart, with
 * their own buffering and gain — they are never sample-aligned and their absolute levels differ. But
 * when the far party speaks, both get louder at the same moment, and the shape over time is enough.
 * A short lag search absorbs the rest.
 *
 * **It refuses far more readily than it answers.** Too few windows, a silent call, a probe that
 * yielded nothing, or two channels that track the downlink equally well all return
 * [ChannelMap.UNKNOWN]. A wrong answer here prints one person's words under the other's name, on a
 * record of a real conversation; no answer merely prints "Speaker A".
 */
class DownlinkCorrelator(private val sampleRate: Int) {

    private val samplesPerWindow = sampleRate * WINDOW_MS / MILLIS_PER_SECOND

    private val callA = ArrayList<Double>()
    private val callB = ArrayList<Double>()
    private val downlink = ArrayList<Double>()

    // Carried between chunks: a read never lands on a window boundary.
    private var callSumA = 0.0
    private var callSumB = 0.0
    private var callFrames = 0
    private var downSum = 0.0
    private var downFrames = 0

    /** Feeds interleaved stereo PCM-16 from the combined call capture. */
    fun acceptCall(pcm: ByteArray, len: Int = pcm.size) {
        if (callA.size >= MAX_WINDOWS) return
        var i = 0
        while (i + 4 <= len) {
            callSumA += abs(sampleAt(pcm, i)).toDouble()
            callSumB += abs(sampleAt(pcm, i + 2)).toDouble()
            callFrames++
            if (callFrames >= samplesPerWindow) {
                callA += callSumA / callFrames
                callB += callSumB / callFrames
                callSumA = 0.0; callSumB = 0.0; callFrames = 0
                if (callA.size >= MAX_WINDOWS) return
            }
            i += 4
        }
    }

    /** Feeds mono PCM-16 from the downlink-only probe. */
    fun acceptDownlink(pcm: ByteArray, len: Int = pcm.size) {
        if (downlink.size >= MAX_WINDOWS) return
        var i = 0
        while (i + 2 <= len) {
            downSum += abs(sampleAt(pcm, i)).toDouble()
            downFrames++
            if (downFrames >= samplesPerWindow) {
                downlink += downSum / downFrames
                downSum = 0.0; downFrames = 0
                if (downlink.size >= MAX_WINDOWS) return
            }
            i += 2
        }
    }

    /** The far-party channel, or [ChannelMap.UNKNOWN] when the evidence does not settle it. */
    fun result(): ChannelMap {
        if (callA.size < MIN_WINDOWS || downlink.size < MIN_WINDOWS) return ChannelMap.UNKNOWN

        // A probe that recorded silence cannot identify anything — and would otherwise "match"
        // whichever channel happened to be quietest. This is the guard for a device that accepts
        // VOICE_DOWNLINK and then feeds it nothing, which is a real possibility per OEM.
        if (downlink.average() < QUIET_FLOOR) return ChannelMap.UNKNOWN
        // A call in which nobody spoke says nothing about who is who.
        if (callA.average() < QUIET_FLOOR && callB.average() < QUIET_FLOOR) return ChannelMap.UNKNOWN

        val toA = bestCorrelation(downlink, callA)
        val toB = bestCorrelation(downlink, callB)

        return when {
            toA >= MIN_CORRELATION && toA - toB >= MARGIN -> ChannelMap.A_IS_FAR
            toB >= MIN_CORRELATION && toB - toA >= MARGIN -> ChannelMap.B_IS_FAR
            else -> ChannelMap.UNKNOWN
        }
    }

    /** How well [probe] tracks [channel], allowing a small constant offset between the captures. */
    private fun bestCorrelation(probe: List<Double>, channel: List<Double>): Double {
        var best = -1.0
        for (lag in -MAX_LAG_WINDOWS..MAX_LAG_WINDOWS) {
            val r = correlationAtLag(probe, channel, lag)
            if (r > best) best = r
        }
        return best
    }

    private fun correlationAtLag(probe: List<Double>, channel: List<Double>, lag: Int): Double {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in probe.indices) {
            val j = i + lag
            if (j in channel.indices) { xs += probe[i]; ys += channel[j] }
        }
        if (xs.size < MIN_WINDOWS) return -1.0

        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in xs.indices) {
            val a = xs[i] - mx
            val b = ys[i] - my
            num += a * b; dx += a * a; dy += b * b
        }
        // A flat envelope has no shape to match; treat it as no evidence rather than as agreement.
        if (dx <= 0.0 || dy <= 0.0) return -1.0
        return num / sqrt(dx * dy)
    }

    private fun sampleAt(pcm: ByteArray, at: Int): Int =
        ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    companion object {
        /** Envelope resolution. Fine enough to follow speech, coarse enough to ignore timing skew. */
        const val WINDOW_MS = 50

        private const val MILLIS_PER_SECOND = 1000

        /** Below this mean level a capture is treated as having recorded nothing. */
        private const val QUIET_FLOOR = 60.0

        /** Windows needed before any answer is offered — five seconds of call at 50 ms. */
        private const val MIN_WINDOWS = 8

        /** Stop after this much; the answer is a property of the device, not of the conversation. */
        private const val MAX_WINDOWS = 400

        /** How far the two captures may drift apart and still be compared. */
        private const val MAX_LAG_WINDOWS = 4

        /** The winning channel must genuinely track the downlink, not merely beat the other one. */
        private const val MIN_CORRELATION = 0.5

        /** And it must beat the other channel clearly, or neither is named. */
        private const val MARGIN = 0.35
    }
}
