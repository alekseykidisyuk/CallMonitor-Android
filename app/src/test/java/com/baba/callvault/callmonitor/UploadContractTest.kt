package com.baba.callvault.callmonitor

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class UploadContractTest {
    private val id="11111111-2222-4333-8444-555555555555"
    private fun good()="""{"ok":true,"stored":true,"call_id":"$id","server_call_id":1}"""
    @Test fun acceptsOnlyConfirmedMatching200or201() {
        for(code in listOf(200,201)) {
            val r=UploadContract.reply(code,good(),id)
            assertEquals(UploadContract.Decision.ACK,r.decision);assertEquals(1L,r.serverId)
        }
    }
    @Test fun lostOrInvalidAcknowledgementNeverMarksUploaded() {
        for(body in listOf("", "<html>error</html>", "{}",good().replace(id,"other"),
            good().replace("true","false"),good().replace(":1}",":0}"),good().replace(":1}",":1.5}"),
            good().replace("\"stored\":true", "\"stored\":\"true\""))) {
            assertEquals(UploadContract.Decision.RETRY,UploadContract.reply(201,body,id).decision)
        }
        assertEquals(UploadContract.Decision.REJECT,UploadContract.reply(202,good(),id).decision)
    }
    @Test fun statusPolicyStopsAuthAndConflictButRetriesTransientFailures() {
        for(code in listOf(401,403)) assertEquals(UploadContract.Decision.AUTH,UploadContract.reply(code,"",id).decision)
        assertEquals(UploadContract.Decision.CONFLICT,UploadContract.reply(409,"",id).decision)
        for(code in listOf(408,429,500,502,503,504)) assertEquals(UploadContract.Decision.RETRY,UploadContract.reply(code,"",id).decision)
        for(code in listOf(301,302,400,404,413)) assertEquals(UploadContract.Decision.REJECT,UploadContract.reply(code,"",id).decision)
    }
    @Test fun preservesFilenameTimezoneDirectionAndAnonymousNumber() {
        val n=UploadContract.parseName("20260906_234006.777+0500_in_+998900000001.ogg")
        assertEquals("2026-09-06T23:40:06.777+05:00",n.startedAt)
        assertEquals("in",n.direction);assertEquals("+998900000001",n.number)
        assertEquals("",UploadContract.parseName("20260906_234006.777+0500_out_.ogg").number)
    }
    @Test fun refusesUnsupportedNamesAndHeaderInjection() {
        for(n in listOf("file.ogg","20260931_234006.777+0500_in_123.ogg","20260906_234006.777+0500_in_123\r\nX.ogg")) {
            try { UploadContract.parseName(n); fail("Accepted invalid filename") } catch (_: InvalidAudio) {}
        }
    }
    @Test fun credentialsAndServerOriginAreStrict() {
        assertEquals("https://callmonitor.sensera.online",UploadContract.origin("https://callmonitor.sensera.online/"))
        assertEquals("a".repeat(43),UploadContract.token("  "+"a".repeat(43)+"\n"))
        for(t in listOf("a".repeat(42),"a".repeat(20)+"\n"+"b".repeat(23))) {
            try { UploadContract.token(t); fail() } catch (_: IllegalArgumentException) {}
        }
        for(url in listOf("http://server.test","https://user:pass@server.test","https://server.test/path","https://server.test?token=x","https://server.test:8443")) {
            try { UploadContract.origin(url); fail() } catch (_: IllegalArgumentException) {}
        }
    }
}
