/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only encrypted token bytes are persisted; WorkManager receives a call UUID only. */
class UploadSettings(context: Context) {
    private val prefs = context.getSharedPreferences("callmonitor_upload", Context.MODE_PRIVATE)
    data class Profile(val origin: String, val tenant: String, val device: String, val operator: String) {
        fun identity() = listOf(origin, tenant, device, operator).joinToString("|")
    }
    data class Auth(val profile: Profile, val token: String, val revision: String)
    fun profile(): Profile? = if (!prefs.contains("cipher")) null else Profile(
        prefs.getString("origin", "")!!, prefs.getString("tenant", "")!!,
        prefs.getString("device", "")!!, prefs.getString("operator", "")!!)
    fun enabled() = prefs.getBoolean("enabled", false)
    fun revision() = prefs.getString("revision", "")!!
    fun authBlocked() = prefs.getString("blocked_revision", null) == prefs.getString("revision", "unconfigured")
    fun configuredAt() = prefs.getLong("configured_at", 0)
    fun queueError(): Boolean = prefs.getBoolean("queue_error", false)
    fun queueFailed() { prefs.edit().putBoolean("queue_error", true).commit() }
    fun setEnabled(value: Boolean) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    @Synchronized fun save(profile: Profile, token: String?, enabled: Boolean) {
        val edit = prefs.edit().putString("origin", profile.origin).putString("tenant", profile.tenant)
            .putString("device", profile.device).putString("operator", profile.operator)
            .putBoolean("enabled", enabled)
        if (token != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(UploadContract.token(token).toByteArray(Charsets.UTF_8))
            edit.putString("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("revision", UUID.randomUUID().toString()).remove("blocked_revision")
        } else check(this.profile() != null) { "Введите токен устройства" }
        if (configuredAt() == 0L) edit.putLong("configured_at", System.currentTimeMillis())
        check(edit.commit()) { "Не удалось сохранить настройки" }
    }
    fun auth(): Auth? {
        val p = profile() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        val token = String(cipher.doFinal(Base64.decode(prefs.getString("cipher", ""), Base64.NO_WRAP)), Charsets.UTF_8)
        return Auth(p, token, prefs.getString("revision", "")!!)
    }
    fun blockAuth(revision: String) {
        // A response from an old token cannot block its replacement.
        if (prefs.getString("revision", null) == revision) prefs.edit().putString("blocked_revision", revision).commit()
    }
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    companion object { private const val KEY_ALIAS = "callmonitor_device_token_v1"; private val KEY_LOCK = Any() }
}
