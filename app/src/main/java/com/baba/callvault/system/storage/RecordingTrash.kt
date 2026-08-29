/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * How a deleted recording is marked as trashed, and how long it stays.
 *
 * **Trashing renames the file where it lies; it never moves or copies it.** A move needs
 * `FLAG_SUPPORTS_MOVE`, which not every SAF provider offers, and the copy-and-delete fallback would
 * download and re-upload the file on the Drive folder — potentially a hundred megabytes over mobile
 * data to delete something. A rename transfers no bytes, is instant on every provider, and is exactly
 * as reversible.
 *
 * **The whole state lives in the file name.** No table, no second source of truth that can disagree
 * with the folder: if the file is there, it is recoverable, and if it is not, nothing claims
 * otherwise. That also means a user who finds these files in a file manager can rename them back by
 * hand, which is a better failure mode than a database the app alone can read.
 *
 * The trade-off, stated plainly: a trashed file **stays in the storage folder** and will still be
 * seen by a file manager and by whatever syncs it. It is a recycle bin, not a shredder.
 */
object RecordingTrash {

    /**
     * Marks a file as trashed and carries the moment it happened.
     *
     * Lower-case and distinctive so it cannot collide with a recording's own name — the formatter
     * produces names beginning with a date — and so the check stays a cheap prefix test rather than a
     * pattern match over every file in the folder on every listing.
     */
    private const val PREFIX = "cvtrash_"

    /** How long a trashed recording is kept before the retention sweep removes it for good. */
    const val RETENTION_DAYS = 30

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /** Whether [name] is a trashed recording rather than a live one. */
    fun isTrashed(name: String?): Boolean = name?.startsWith(PREFIX) == true

    /**
     * The name [original] takes while trashed, stamped with [atMillis].
     *
     * Trashing something already trashed returns it unchanged rather than nesting a second prefix,
     * which would otherwise make the original name unrecoverable after two passes.
     */
    fun trashedName(original: String, atMillis: Long): String =
        if (isTrashed(original)) original else "$PREFIX${atMillis}_$original"

    /**
     * The name to restore [trashed] to, or null when it is not a trashed name.
     *
     * Splits on the **first** underscore after the stamp only, so a recording whose own name contains
     * underscores — which every one of ours does — comes back exactly as it was.
     */
    fun originalName(trashed: String): String? {
        if (!isTrashed(trashed)) return null
        val afterPrefix = trashed.substring(PREFIX.length)
        val separator = afterPrefix.indexOf('_')
        if (separator <= 0) return null
        val original = afterPrefix.substring(separator + 1)
        return original.ifEmpty { null }
    }

    /** When [trashed] was deleted, or null when the name does not carry a readable stamp. */
    fun deletedAtMillis(trashed: String): Long? {
        if (!isTrashed(trashed)) return null
        val afterPrefix = trashed.substring(PREFIX.length)
        val separator = afterPrefix.indexOf('_')
        if (separator <= 0) return null
        return afterPrefix.substring(0, separator).toLongOrNull()
    }

    /**
     * Whether [trashed] is old enough to remove for good.
     *
     * **A name with no readable stamp is never expired.** The stamp is the only evidence of when
     * something was deleted, and treating its absence as "old" would destroy a recording on the
     * strength of a parsing failure. It stays in the trash, visible, until someone acts on it.
     */
    fun isExpired(trashed: String, nowMillis: Long, retentionDays: Int = RETENTION_DAYS): Boolean {
        val deletedAt = deletedAtMillis(trashed) ?: return false
        // A stamp in the future means a clock change, not a very old file. Keep it.
        if (deletedAt > nowMillis) return false
        return nowMillis - deletedAt >= retentionDays * MILLIS_PER_DAY
    }

    /** Whole days left before [trashed] is purged; 0 once it is due. */
    fun daysRemaining(trashed: String, nowMillis: Long, retentionDays: Int = RETENTION_DAYS): Int {
        val deletedAt = deletedAtMillis(trashed) ?: return retentionDays
        val elapsedDays = ((nowMillis - deletedAt).coerceAtLeast(0L) / MILLIS_PER_DAY).toInt()
        return (retentionDays - elapsedDays).coerceAtLeast(0)
    }
}
