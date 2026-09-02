/*
 * CallMonitor Android derives from CallVault under GPLv3.
 *
 * Upstream CallVault's in-app updater must not replace a CallMonitor installation: its APK does not
 * contain CallMonitor's transport fixes, server-sync client or signing identity. CallMonitor releases
 * will get their own update channel later. Until then this scheduler deliberately keeps all upstream
 * update-check work disabled.
 */

package com.baba.callvault.system.updates

import android.content.Context
import androidx.work.WorkManager
import com.baba.callvault.utils.AppLogger

object UpdateScheduler {

    private const val TAG = "CM:UpdateScheduler"
    private const val WORK_NAME = "cv_update_check"
    private const val CHECK_NOW_WORK_NAME = "cv_update_check_now"

    /** Kept for callers/UI compatibility; CallMonitor does not consume upstream CallVault releases. */
    const val INSTALL_WORK_NAME = "cv_update_install"

    /**
     * Always remove any periodic/immediate CallVault update work left by the upstream app or an older
     * fork build. A dedicated CallMonitor release feed will replace this when production signing and
     * deployment are ready.
     */
    fun apply(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME)
        workManager.cancelUniqueWork(CHECK_NOW_WORK_NAME)
        AppLogger.d(TAG, "Upstream CallVault update checks disabled in CallMonitor")
    }

    /** No-op by design: never query the upstream release feed on app open. */
    fun checkNowIfDue(context: Context) {
        AppLogger.d(TAG, "Skipping upstream update check")
    }

    /**
     * No-op by design. If a stale upstream update banner survives an in-place migration, tapping it
     * must not install a foreign-signed/upstream APK over CallMonitor.
     */
    fun enqueueInstallNow(context: Context) {
        AppLogger.w(TAG, "Ignoring upstream update install request in CallMonitor")
    }
}
