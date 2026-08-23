/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

/**
 * The decisions a download makes that are not about bytes on a socket.
 *
 * Split out from `ModelDownloadWorker` because both only started to matter when the summarisation
 * model arrived at 3.46 GB — six times the largest whisper model — and both are worth being able to
 * reason about without WorkManager in the way.
 */
object ModelDownloadPolicy {

    /** Percent has a hundred and one distinct values, and reporting is capped to match. */
    private const val PERCENT = 100

    /**
     * Free space a download must leave behind after it finishes.
     *
     * Filling the last byte of a phone's storage is its own kind of failure: the system starts
     * shedding processes and the user's next photo does not save. Refusing with a reason is better
     * than succeeding into that.
     */
    const val HEADROOM_BYTES = 250L * 1024 * 1024

    /** How far through, 0-100, clamped. */
    fun percentOf(written: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((written * PERCENT) / total).toInt().coerceIn(0, PERCENT)
    }

    /**
     * Whether [percent] is worth writing to WorkManager's progress store.
     *
     * Only when the whole number moves forward. The worker reads the socket in 64 KB buffers, so
     * publishing on every read is roughly 8,700 database transactions for a 574 MB model and 52,800
     * for a 3.46 GB one — all to move a bar that has a hundred positions.
     *
     * Forward only. A server that ignores a `Range` header replies with the whole file and the byte
     * count restarts at zero; a progress bar that visibly rewinds reads as a fault rather than as a
     * resume.
     *
     * @param lastPublished the last figure published, or -1 if none has been.
     */
    fun shouldPublish(lastPublished: Int, percent: Int): Boolean = percent > lastPublished

    /**
     * Whether [remainingBytes] can be written with [HEADROOM_BYTES] left over.
     *
     * Only the remaining bytes are asked for: a resumed download already owns what is on disk, and
     * demanding the full size again would refuse a download that is nearly finished on a phone with
     * room for the rest of it.
     */
    fun hasRoomFor(freeBytes: Long, remainingBytes: Long): Boolean =
        freeBytes >= remainingBytes + HEADROOM_BYTES
}
