/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object UploadScheduler {
    fun enqueue(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<CallMonitorUploadWorker>()
            .setInputData(workDataOf("call_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .addTag("callmonitor-upload").build()
        WorkManager.getInstance(context).enqueueUniqueWork("callmonitor-upload-$id",ExistingWorkPolicy.KEEP,request)
    }
    fun apply(context: Context) {
        if(UploadSettings(context).profile() == null) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("callmonitor-reconcile",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CallMonitorReconcileWorker>(15,TimeUnit.MINUTES).build())
        reconcile(context)
    }
    fun reconcile(context: Context) {
        val settings = UploadSettings(context)
        if(!settings.enabled() || settings.authBlocked()) return
        val queue = UploadQueue.get(context)
        queue.resumeAuth()
        queue.pending().forEach { enqueue(context,it.id) }
    }
    /** Called after release + final filename resolution. Durable INSERT finishes before service teardown. */
    fun recordingClosed(context: Context, uri: android.net.Uri, name: String) {
        val settings = UploadSettings(context)
        val profile = settings.profile() ?: return // First setup applies to new calls, never silently imports history.
        try {
            val id = UploadQueue.get(context).enqueue(uri.toString(),name,profile.identity())
            // WorkManager and SQLite aren't one transaction; periodic reconciliation closes that gap.
            if(settings.enabled() && !settings.authBlocked()) enqueue(context,id)
        } catch (_: Exception) {
            settings.queueFailed()
            com.baba.callvault.utils.AppLogger.e("CM:Upload", "Cannot register finished recording for delivery; source retained")
        }
    }
}

class CallMonitorReconcileWorker(context: Context, params: WorkerParameters) : Worker(context,params) {
    override fun doWork(): Result = try { UploadScheduler.reconcile(applicationContext); Result.success() }
    catch (_: Exception) { Result.retry() }
}
