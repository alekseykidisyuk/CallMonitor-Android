package com.baba.callvault.callmonitor

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class OggInfoTest {
    private fun bytes(name:String)=javaClass.getResourceAsStream("/callmonitor/$name.ogg.b64")!!.use { Base64.getMimeDecoder().decode(it.readBytes()) }
    private fun inspect(bytes:ByteArray):OggInfo {
        val f=File.createTempFile("callmonitor",".ogg")
        try { f.writeBytes(bytes);return OggInfo.inspect(f) } finally { f.delete() }
    }
    @Test fun acceptsValidStereoAndPreservesItsBytes() {
        val raw=bytes("stereo");val copy=raw.clone();val info=inspect(raw)
        assertTrue(info.eos);assertTrue(info.durationMs>0);assertArrayEquals(copy,raw)
    }
    @Test fun acceptsPageAlignedMissingEosWithoutRewritingAudio() {
        val raw=bytes("stereo");var pos=0;var last=0
        while(pos<raw.size) { last=pos;val n=raw[pos+26].toInt() and 255;pos+=27+n+(0 until n).sumOf{raw[pos+27+it].toInt() and 255} }
        raw[last+5]=(raw[last+5].toInt() and 4.inv()).toByte();raw.fill(0,last+22,last+26)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(last+22,OggInfo.crc(raw.copyOfRange(last,raw.size)))
        val info=inspect(raw);assertFalse(info.eos);assertTrue(info.durationMs>0)
    }
    @Test fun rejectsMonoTruncationAndBadCrc() {
        val stereo=bytes("stereo")
        for(raw in listOf(bytes("mono"),stereo.copyOf(stereo.size-1),stereo.clone().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})) {
            try { inspect(raw);fail("Accepted invalid recording") } catch (_:InvalidAudio) {}
        }
    }
}
