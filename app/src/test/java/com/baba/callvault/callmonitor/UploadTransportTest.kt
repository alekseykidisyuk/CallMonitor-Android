package com.baba.callvault.callmonitor

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class UploadTransportTest {
    @Test fun streamsExactBytesAndContractWithoutFollowingRedirects() {
        val id="11111111-2222-4333-8444-555555555555"
        val output=ByteArrayOutputStream()
        val connection=mockk<HttpsURLConnection>(relaxed=true)
        every { connection.outputStream } returns output
        every { connection.responseCode } returns 201
        every { connection.inputStream } answers { """{"ok":true,"stored":true,"call_id":"$id","server_call_id":9}""".byteInputStream() }
        val auth=UploadSettings.Auth(UploadSettings.Profile("https://server.test","pilot","device","operator"),"t".repeat(43),"rev")
        val audio=File.createTempFile("upload", ".ogg").apply { writeBytes(byteArrayOf(0,1,2,3,-1)) }
        try {
            val item=UploadQueue.Item(id,"file://unused","20260906_234006.777+0500_in_123.ogg",auth.profile.identity(),0,"pending",0,null,
                UploadSnapshot.sha(audio),audio.length(),15094,false,null)
            val reply=UploadTransport { url -> assertEquals("https://server.test/api/v1/calls",url.toString());connection }.send(auth,item,audio){false}
            assertEquals(UploadContract.Decision.ACK,reply.decision)
            verify { connection.instanceFollowRedirects=false }
            verify { connection.setFixedLengthStreamingMode(output.size().toLong()) }
            verify { connection.setRequestProperty("Authorization","Bearer ${auth.token}") }
            val body=output.toString("ISO-8859-1")
            for(field in listOf("call_id","device_id","operator_id","direction","remote_number","started_at","duration_ms","app_build","audio_bytes","audio_sha256","codec","channels","sample_rate","channel_layout","audio")) assertTrue(body.contains("name=\"$field\""))
            assertTrue(body.contains(id));assertFalse(body.contains(auth.token))
            assertTrue(body.contains(String(audio.readBytes(),Charsets.ISO_8859_1)))
            verify { connection.disconnect() }
        } finally { audio.delete() }
    }
}
