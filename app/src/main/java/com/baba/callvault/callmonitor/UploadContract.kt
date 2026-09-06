/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import org.json.JSONObject
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Receiver 0.1.1 contract. No credential or response body enters diagnostics. */
object UploadContract {
    const val MAX_BYTES = 100L * 1024 * 1024
    const val BUILD = 20
    private val namePattern = Regex("^(\\d{8}_\\d{6}\\.\\d{3}[+-]\\d{4})_(in|out)_(.*)\\.ogg$")
    private val nameDate = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss.SSSxx", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    data class CallName(val startedAt: String, val direction: String, val number: String)
    fun parseName(name: String): CallName {
        val m = namePattern.matchEntire(name) ?: throw InvalidAudio("filename_format")
        val date = try { OffsetDateTime.parse(m.groupValues[1], nameDate) }
        catch (_: Exception) { throw InvalidAudio("filename_date") }
        val number = m.groupValues[3]
        if (number.length > 64 || !Regex("[+0-9() .#*\\-]*").matches(number)) throw InvalidAudio("filename_number")
        return CallName(date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), m.groupValues[2], number)
    }
    fun origin(raw: String): String {
        val uri = try { URI(raw.trim().trimEnd('/')) } catch (_: Exception) {
            throw IllegalArgumentException("Некорректный адрес HTTPS")
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty() &&
            (uri.port == -1 || uri.port == 443)) { "Нужен адрес HTTPS без пути, логина и параметров" }
        return "https://${uri.host.lowercase(Locale.ROOT)}"
    }
    fun identifier(value: String): String {
        val v = value.trim()
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}").matches(v)) { "Некорректный идентификатор" }
        return v
    }
    fun token(value: String): String {
        val v = value.trim()
        require(Regex("[A-Za-z0-9_-]{43}").matches(v)) { "Токен устройства должен содержать 43 символа без пробелов и переносов" }
        return v
    }
    enum class Decision { ACK, RETRY, AUTH, CONFLICT, REJECT }
    data class Reply(val decision: Decision, val reason: String, val serverId: Long? = null)
    fun reply(code: Int, body: String, callId: String): Reply {
        if (code == 401 || code == 403) return Reply(Decision.AUTH, "http_$code")
        if (code == 409) return Reply(Decision.CONFLICT, "http_409")
        if (code >= 500 || code == 408 || code == 429) return Reply(Decision.RETRY, "http_$code")
        if (code == 200 || code == 201) {
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val sid = json?.opt("server_call_id") as? Number
            if (json?.opt("ok") == true && json.opt("stored") == true &&
                json.opt("call_id") == callId && sid != null && sid.toLong() > 0 &&
                sid.toDouble() == sid.toLong().toDouble()) {
                return Reply(Decision.ACK, "confirmed", sid.toLong())
            }
            return Reply(Decision.RETRY, "invalid_ack")
        }
        return Reply(Decision.REJECT, "http_$code")
    }
}
class InvalidAudio(val reason: String) : Exception(reason)
