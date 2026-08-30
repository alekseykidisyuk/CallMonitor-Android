/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.R
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * The ongoing notification shown while an app call is being recorded.
 *
 * Until this existed, a VoIP recording in progress had **no controls at all**: the carrier path's
 * notification is driven by `RecordingForegroundService`, which this path deliberately does not go
 * through, so an app call could be started and finished with nothing to stop it and nothing to mark
 * a moment in it. For anyone whose calls are mostly in a messenger, that was the whole feature
 * missing.
 *
 * Stop and Mark only. There is no pause on this path — the daemon's VoIP capture has no pause to
 * drive — and offering a button that did nothing would be worse than offering none.
 */
object VoipRecordingNotification {

    private const val TAG = "CV:VoipRecNotification"

    /** Distinct from the recording service (1), error (2) and record-prompt (3) notifications. */
    private const val NOTIFICATION_ID = 5

    @SuppressLint("MissingPermission")
    fun show(context: Context, appLabel: String?) {
        val title =
            if (appLabel.isNullOrBlank()) context.getString(R.string.voip_recording_title)
            else context.getString(R.string.voip_recording_title_named, appLabel)

        val builder = NotificationCompat.Builder(context, RecordingNotificationHelper.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.voip_recording_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        builder.addAction(
            R.drawable.ic_mic,
            context.getString(R.string.general_flag),
            serviceIntent(context, DaemonKeepAliveService.ACTION_VOIP_FLAG_MOMENT)
        )
        // Last, like the carrier notification, and for the same reason: it is the one button here
        // that cannot be undone.
        builder.addAction(
            R.drawable.ic_stop,
            context.getString(R.string.general_stop),
            serviceIntent(context, DaemonKeepAliveService.ACTION_VOIP_STOP)
        )

        if (!PermissionChecks.hasNotificationPermission(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLogger.w(TAG, "Could not post the VoIP recording notification: ${it.message}") }
    }

    fun dismiss(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /**
     * A PendingIntent per action, keyed by the action string.
     *
     * Same trap the carrier notification had: under FLAG_UPDATE_CURRENT two actions sharing a
     * request code are one PendingIntent, and Mark would end the recording.
     */
    private fun serviceIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, DaemonKeepAliveService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
