/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class UploadTransport(private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    @Volatile private var active: HttpsURLConnection? = null
    fun cancel() { active?.disconnect() }
    fun send(auth: UploadSettings.Auth, item: UploadQueue.Item, audio: File, stopped: () -> Boolean): UploadContract.Reply {
        val name = UploadContract.parseName(item.name)
        val boundary = "CallMonitor"+UUID.randomUUID().toString().replace("-", "")
        val fields = linkedMapOf(
            "call_id" to item.id, "device_id" to auth.profile.device, "operator_id" to auth.profile.operator,
            "direction" to name.direction, "remote_number" to name.number, "started_at" to name.startedAt,
            "duration_ms" to item.duration.toString(), "app_build" to UploadContract.BUILD.toString(),
            "audio_bytes" to item.bytes.toString(), "audio_sha256" to item.sha!!,
            "codec" to "opus", "channels" to "2", "sample_rate" to "48000", "channel_layout" to "stereo",
            "left_role" to "operator", "right_role" to "client", "original_filename" to item.name)
        val prefix = buildString {
            for((key,value) in fields) append("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"${item.id}.ogg\"\r\nContent-Type: audio/ogg\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        val connection = connect(URL(auth.profile.origin+"/api/v1/calls"))
        active = connection
        try {
            connection.instanceFollowRedirects = false // Never forward a device token to another host.
            connection.requestMethod = "POST"
            connection.connectTimeout = 30000; connection.readTimeout = 90000
            connection.doOutput = true; connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer ${auth.token}")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setFixedLengthStreamingMode(prefix.size.toLong()+audio.length()+suffix.size)
            if(stopped()) throw IOException("interrupted")
            connection.outputStream.use { out ->
                out.write(prefix)
                audio.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while(true) {
                        if(stopped()) throw IOException("interrupted")
                        val n = input.read(buffer); if(n < 0) break; out.write(buffer,0,n)
                    }
                }
                out.write(suffix)
            }
            val code = connection.responseCode
            val input = if(code in 200..299) connection.inputStream else connection.errorStream
            val body = input?.use { stream ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while(bytes.size() <= 65536) {
                    val n = stream.read(buffer,0,minOf(buffer.size,65537-bytes.size()))
                    if(n < 0) break
                    bytes.write(buffer,0,n)
                }
                if(bytes.size() <= 65536) bytes.toString("UTF-8") else ""
            } ?: ""
            return UploadContract.reply(code,if(body.toByteArray(Charsets.UTF_8).size <= 65536) body else "",item.id)
        } finally { active = null; connection.disconnect() }
    }
}
