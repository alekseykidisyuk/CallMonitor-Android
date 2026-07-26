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
 * Best-effort "who is this VoIP call with?", for naming recordings.
 *
 * A VoIP call leaves no call-log entry and exposes no phone number, and the apps' own call histories
 * live in their private data directories, which are unreadable even to the shell user. What IS readable
 * is the ongoing-call notification every one of these apps posts, whose title is normally the contact.
 *
 * Deliberately done from the DAEMON: it already runs as shell and can read notifications, so this needs
 * no new user-facing permission. The alternative — a `NotificationListenerService` in the app — would
 * give structured data but requires the user to grant access to EVERY notification on the device, which
 * is far too much to ask of a call recorder for a nicety like a filename.
 *
 * Treated as a nicety throughout: this is text scraping, formats vary by app and locale and can change
 * with any update, so every failure path simply returns null and the recording is named by time alone.
 * Nothing depends on it.
 */
internal object VoipCallerName {
    private const val TAG = "CV:VoipName"

    private const val DUMP_TIMEOUT_MS = 1_500L
    private const val MAX_NAME_LENGTH = 40

    /** Notification categories the platform defines for calls; apps tag ongoing calls with these. */
    private val CALL_CATEGORIES = listOf("category=call", "category=CATEGORY_CALL")

    /** Characters that are unsafe or annoying in a filename, plus anything non-printable. */
    private val UNSAFE = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

    /**
     * The contact name from an ongoing call notification, or null when nothing usable is found.
     * Blocking (spawns `dumpsys`), so call it off the critical path — it runs once per call.
     */
    fun resolve(): String? = runCatching {
        val dump = readNotificationDump() ?: return null
        val name = extractFromDump(dump)
        if (name == null) AppLogger.d(TAG, "No caller name found in the call notification")
        else AppLogger.i(TAG, "VoIP caller name resolved")   // the name itself is not logged
        name
    }.onFailure { AppLogger.d(TAG, "Caller-name lookup failed: ${it.message}") }.getOrNull()

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

    /**
     * Finds the title of a notification tagged as a call. Split out from the I/O so the parsing can be
     * exercised without a device.
     */
    internal fun extractFromDump(dump: String): String? {
        val lines = dump.lines()
        val titleRegex = Regex("""android\.title=String \((.+?)\)""")
        // Walk forward from each call-tagged record; its title follows within the same block.
        for ((i, line) in lines.withIndex()) {
            if (CALL_CATEGORIES.none { line.contains(it, ignoreCase = true) }) continue
            for (j in i until minOf(i + BLOCK_LOOKAHEAD, lines.size)) {
                val m = titleRegex.find(lines[j]) ?: continue
                return sanitize(m.groupValues[1])
            }
        }
        return null
    }

    /** Trims to something safe and sensible for a filename, or null if nothing usable remains. */
    private fun sanitize(raw: String): String? {
        val cleaned = UNSAFE.replace(raw, "").trim().trimEnd('.')
        if (cleaned.isEmpty()) return null
        // A title that is just a number, or obviously a status line rather than a person, is no better
        // than no name at all.
        if (cleaned.length > MAX_NAME_LENGTH) return cleaned.take(MAX_NAME_LENGTH).trim()
        return cleaned
    }

    private const val BLOCK_LOOKAHEAD = 40
}
