package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seconds → millis, so the fixtures read as wall-clock. */
private fun s(seconds: Long) = seconds * 1_000L

class CallGapDetectorTest {

    private fun sweep(
        entries: List<CallLogEntry>,
        observed: List<Long> = listOf(s(0)),
        incoming: Boolean = true,
        outgoing: Boolean = true,
        watermark: Long = 0L
    ) = CallGapDetector.sweep(entries, observed, incoming, outgoing, watermark)

    @Test
    fun `a call CallVault never observed is a gap`() {
        val call = CallLogEntry(startedAt = s(1_000), durationSeconds = 60, isIncoming = true, label = "Feroza")
        val result = sweep(listOf(call), observed = listOf(s(0)))
        assertEquals(listOf(CallGap(s(1_000), "Feroza")), result.gaps)
    }

    @Test
    fun `a call whose end matches an observed end is not a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 60, isIncoming = true, label = "Feroza")
        val result = sweep(listOf(call), observed = listOf(s(0), s(1_060)))
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun `a match within the ninety-second tolerance still counts as observed`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), observed = listOf(s(0), s(1_060) + 89_000L)).gaps.isEmpty())
        assertTrue(sweep(listOf(call), observed = listOf(s(0), s(1_060) - 89_000L)).gaps.isEmpty())
    }

    @Test
    fun `a match outside the tolerance is a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 60, isIncoming = true, label = null)
        assertEquals(1, sweep(listOf(call), observed = listOf(s(0), s(1_060) + 91_000L)).gaps.size)
    }

    @Test
    fun `an unanswered call is never a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 0, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call)).gaps.isEmpty())
    }

    @Test
    fun `a call shorter than the five-second floor is never a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 4, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call)).gaps.isEmpty())
    }

    @Test
    fun `a direction with auto-record off is never a gap`() {
        val incoming = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        val outgoing = CallLogEntry(s(2_000), 60, isIncoming = false, label = null)
        assertTrue(sweep(listOf(incoming), incoming = false).gaps.isEmpty())
        assertTrue(sweep(listOf(outgoing), outgoing = false).gaps.isEmpty())
    }

    @Test
    fun `entries at or before the watermark are not examined again`() {
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), watermark = s(1_000)).gaps.isEmpty())
    }

    @Test
    fun `calls older than the oldest remembered end are not judged`() {
        // The ring only reaches back to s(5_000); a call before that cannot be matched, and
        // "I cannot remember" must never render as "it failed".
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), observed = listOf(s(5_000))).gaps.isEmpty())
    }

    @Test
    fun `an empty ring judges nothing`() {
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), observed = emptyList()).gaps.isEmpty())
    }

    @Test
    fun `the watermark advances to the newest entry seen, gap or not`() {
        val entries = listOf(
            CallLogEntry(s(1_000), 60, isIncoming = true, label = null),
            CallLogEntry(s(3_000), 60, isIncoming = true, label = null)
        )
        assertEquals(s(3_000), sweep(entries, observed = listOf(s(0))).newWatermark)
    }

    @Test
    fun `an empty call log leaves the watermark alone`() {
        assertEquals(s(42), sweep(emptyList(), watermark = s(42)).newWatermark)
    }
}
