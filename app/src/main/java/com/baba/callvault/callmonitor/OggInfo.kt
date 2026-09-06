/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Container validation, not audio decoding or proof of the entire call's completeness. */
data class OggInfo(val durationMs: Long, val eos: Boolean) {
    companion object {
        private val crcTable = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = (r shl 1) xor (if (r < 0) 0x04c11db7 else 0) }; r
        }
        fun crc(bytes: ByteArray): Int {
            var crc = 0
            for (b in bytes) crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (b.toInt() and 255)) and 255]
            return crc
        }
        private fun fail(reason: String): Nothing = throw InvalidAudio(reason)
        private fun exact(input: InputStream, count: Int): ByteArray {
            val b = ByteArray(count); var offset = 0
            while (offset < count) { val n = input.read(b, offset, count-offset); if(n < 0) fail("truncated_ogg"); offset += n }
            return b
        }
        fun inspect(file: File): OggInfo = file.inputStream().buffered().use { input ->
            var seq = 0; var serial: Int? = null; var packets = 0; var audio = 0
            var eos = false; var granule = 0L; var lastPageGranule = -1L; var preskip = 0
            val packet = ByteArrayOutputStream()
            while (true) {
                val first = input.read(); if (first < 0) break
                val head = byteArrayOf(first.toByte()) + exact(input, 26)
                if (eos || String(head,0,4,Charsets.US_ASCII) != "OggS" || head[4].toInt() != 0) fail("invalid_ogg")
                val flags = head[5].toInt() and 255; val n = head[26].toInt() and 255
                if ((flags and 7) != flags || n == 0) fail("invalid_ogg_flags")
                val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val s = h.getInt(14); val pageSeq = h.getInt(18)
                if (serial == null) serial = s
                if (serial != s || pageSeq != seq || ((flags and 2) != 0) != (seq == 0) ||
                    ((flags and 1) != 0) != (packet.size() > 0)) fail("invalid_ogg_sequence")
                val laces = exact(input, n); val body = exact(input, laces.sumOf { it.toInt() and 255 })
                val expected = h.getInt(22); head.fill(0,22,26)
                if(crc(head+laces+body) != expected) fail("ogg_crc_mismatch")
                var offset = 0
                for ((index, lace) in laces.withIndex()) {
                    val len = lace.toInt() and 255
                    packet.write(body,offset,len); offset += len
                    if (packet.size() > 1048576) fail("ogg_packet_too_large")
                    if(len == 255) continue
                    val p = packet.toByteArray()
                    if (packets == 0) {
                        if(p.size != 19 || String(p,0,8,Charsets.US_ASCII) != "OpusHead" || p[8].toInt() != 1 ||
                            p[9].toInt() != 2 || p[18].toInt() != 0 || seq != 0 || index != n-1) fail("unsupported_opus_profile")
                        preskip = (p[10].toInt() and 255) or ((p[11].toInt() and 255) shl 8)
                    } else if (packets == 1) {
                        if(p.size < 16 || String(p,0,8,Charsets.US_ASCII) != "OpusTags" || index != n-1) fail("invalid_opus_tags")
                        val tags = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                        val vendor = tags.getInt(8).toLong() and 0xffffffffL
                        var pos = 12L + vendor
                        if(pos+4 > p.size) fail("invalid_opus_tags")
                        val count = tags.getInt(pos.toInt()).toLong() and 0xffffffffL; pos += 4
                        if(count > 10000) fail("invalid_opus_tags")
                        repeat(count.toInt()) {
                            if(pos+4 > p.size) fail("invalid_opus_tags")
                            val size = tags.getInt(pos.toInt()).toLong() and 0xffffffffL; pos += 4+size
                            if(pos > p.size) fail("invalid_opus_tags")
                        }
                    } else { if(p.isEmpty()) fail("empty_opus_packet"); audio++ }
                    packets++; packet.reset()
                }
                lastPageGranule = h.getLong(6)
                if(lastPageGranule != -1L) {
                    if(lastPageGranule < granule || lastPageGranule > 0xffffffffL) fail("invalid_ogg_granule")
                    granule = lastPageGranule
                } else if((flags and 4) != 0) fail("invalid_ogg_granule")
                eos = (flags and 4) != 0; seq++
                if(eos && packet.size() != 0) fail("incomplete_ogg_packet")
            }
            if(audio == 0 || packet.size() != 0 || granule <= preskip) fail("incomplete_ogg")
            if(!eos && lastPageGranule <= preskip) fail("invalid_ogg_granule")
            val duration = (granule-preskip+24)/48 // nearest millisecond, matching receiver round-half-up
            if(duration !in 1..86400000) fail("audio_duration")
            OggInfo(duration, eos)
        }
    }
}
