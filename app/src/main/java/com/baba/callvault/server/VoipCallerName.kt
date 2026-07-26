/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger

/**
 * Best-effort "which app, and who with?" for naming a VoIP recording.
 *
 * A VoIP call leaves no call-log entry and exposes no phone number, and the apps' own call histories
 * live in private data directories unreadable even to the shell user. What IS readable is the
 * ongoing-call notification these apps post, which identifies the app and often the contact.
 *
 * Done from the DAEMON, which already runs as shell and can read notifications, so this needs no new
 * user-facing permission. A `NotificationListenerService` in the app would give structured data but
 * requires access to EVERY notification on the device — far too much to ask for a nicer filename.
 *
 * A nicety throughout: it is text scraping, formats vary by app, locale and version, so every failure
 * path returns null and the recording falls back to being named by time alone. Nothing depends on it.
 */
internal object VoipCallerName {
    private const val TAG = "CV:VoipName"

    private const val DUMP_TIMEOUT_MS = 1_500L
    private const val MAX_NAME_LENGTH = 40

    /** Notification categories the platform defines for calls; apps tag ongoing calls with these. */
    private val CALL_CATEGORIES = listOf("category=call", "category=CATEGORY_CALL")

    /** Characters unsafe or annoying in a filename, plus anything non-printable. */
    private val UNSAFE = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

    private val PKG_REGEX = Regex("""pkg=([A-Za-z0-9_.]+)""")
    private val TITLE_REGEX = Regex("""android\.title=String \((.+?)\)""")

    /** What a single ongoing-call notification tells us. */
    data class CallInfo(val packageName: String?, val callerName: String?)

    /**
     * Reads the ongoing-call notification once and returns both facts together.
     *
     * Deliberately ONE dump and ONE record: reading the package and the title separately let them come
     * from different notifications entirely — which is exactly how a Telegram call was once attributed
     * to WhatsApp, because a stale WhatsApp record supplied the package.
     *
     * Blocking (spawns `dumpsys`), so keep it off the critical path — it runs once per call.
     */
    fun resolve(): CallInfo = runCatching {
        val dump = readNotificationDump() ?: return CallInfo(null, null)
        extractFromDump(dump)
    }.onFailure { AppLogger.d(TAG, "Call-info lookup failed: ${it.message}") }
        .getOrDefault(CallInfo(null, null))

    /**
     * Finds the first notification tagged as a call and reads the package and title from THAT record.
     * Split from the I/O so the parsing can be exercised without a device.
     */
    internal fun extractFromDump(dump: String): CallInfo {
        // Records begin with "NotificationRecord(", so slicing on that keeps every field with its owner.
        val records = dump.split("NotificationRecord(")
        for (record in records) {
            if (CALL_CATEGORIES.none { record.contains(it, ignoreCase = true) }) continue
            val pkg = PKG_REGEX.find(record)?.groupValues?.get(1)?.takeIf { it.contains('.') }
            val rawTitle = TITLE_REGEX.find(record)?.groupValues?.get(1)
            val name = rawTitle?.let { sanitize(it, pkg) }
            if (pkg != null || name != null) {
                AppLogger.i(TAG, "VoIP call info resolved (pkg=$pkg, name=${if (name != null) "yes" else "no"})")
                return CallInfo(pkg, name)   // the name itself is never logged
            }
        }
        return CallInfo(null, null)
    }

    /**
     * Trims a title to something usable as a filename, or null.
     *
     * Rejects titles that merely restate the app — Telegram's ongoing-call notification is titled
     * "Ongoing Telegram call" rather than naming the contact, and "Ongoing Telegram call" in a filename
     * is worse than no name at all. WhatsApp, by contrast, puts the person there.
     */
    private fun sanitize(raw: String, packageName: String?): String? {
        val cleaned = UNSAFE.replace(raw, "").trim().trimEnd('.')
        if (cleaned.isEmpty()) return null

        // "org.telegram.messenger" -> "telegram"; a title containing it is describing the app, not a person.
        val appToken = packageName?.split('.')
            ?.filter { it.length > 3 && it !in GENERIC_PACKAGE_PARTS }
            ?.maxByOrNull { it.length }
        if (appToken != null && cleaned.contains(appToken, ignoreCase = true)) {
            AppLogger.d(TAG, "Ignoring notification title that just names the app")
            return null
        }
        return if (cleaned.length > MAX_NAME_LENGTH) cleaned.take(MAX_NAME_LENGTH).trim() else cleaned
    }

    private val GENERIC_PACKAGE_PARTS = setOf("com", "org", "net", "android", "messenger", "app", "mobile")

    private fun readNotificationDump(): String? {
        val proc = ProcessBuilder("sh", "-c", "dumpsys notification --noredact")
            .redirectErrorStream(true).start()
        return try {
            val text = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(DUMP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) proc.destroy()
            text.takeIf { it.isNotBlank() }
        } finally {
            runCatching { proc.destroy() }
        }
    }
}
