/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.baba.callvault.R
import com.baba.callvault.utils.AppLogger

private const val TAG = "CV:Share"

/** Used when the provider cannot say what the file is — every recording is audio regardless. */
private const val FALLBACK_MIME = "audio/*"

/**
 * Sends one recording to the system share-sheet.
 *
 * **The recording's own URI is shared directly, not a copy.** Recordings live in a folder the user
 * picked through the Storage Access Framework, so their URIs belong to a *documents provider* rather
 * than to us. That is shareable anyway: such providers declare `grantUriPermissions`, and a caller
 * holding a persisted read grant may extend a temporary one to whichever app the user picks. The
 * alternative — copying into our cache and re-exposing it through `FileProvider`, as
 * [shareLogFile] must do for a file we generate — would duplicate a recording that can run to tens of
 * megabytes and leave it in the cache afterwards.
 *
 * Both [Intent.EXTRA_STREAM] and [ClipData] carry the URI: some receivers read one and some the other,
 * and the grant travels with the clip data on every Android version we support.
 *
 * @param uri         The copy to share. A recording present both on the device and in Drive has one
 *                    URI per copy; the caller decides which, and the audio is identical either way.
 * @param displayName Shown as the subject where a receiver uses one (mail, mostly).
 */
fun Context.shareRecording(uri: Uri, displayName: String) {
    val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: FALLBACK_MIME
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, displayName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(displayName, uri)
    }
    val chooser = Intent.createChooser(intent, getString(R.string.home_share_chooser)).apply {
        if (this@shareRecording !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // A chooser resolves on any normal device, but a launch failure must not take the app down with
    // it: the user tapped Share on a list of their recordings, and losing the list would be worse
    // than the share not happening.
    runCatching { startActivity(chooser) }
        .onFailure { AppLogger.w(TAG, "Could not open the share sheet for $displayName: ${it.message}") }
}
