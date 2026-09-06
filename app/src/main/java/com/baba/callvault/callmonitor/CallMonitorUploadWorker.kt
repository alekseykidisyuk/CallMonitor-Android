/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.FileNotFoundException
import java.io.IOException

class CallMonitorUploadWorker(context: Context, params: WorkerParameters) : Worker(context,params) {
    private val transport = UploadTransport()
    override fun onStopped() { transport.cancel(); super.onStopped() }
    override fun doWork(): Result {
        val id = inputData.getString("call_id") ?: return Result.failure()
        val queue = UploadQueue.get(applicationContext)
        val initial = queue.get(id) ?: return Result.success()
        if(initial.state == "uploaded") { UploadSnapshot.file(applicationContext,id).delete(); return Result.success() }
        if(initial.state !in setOf("pending","retry","uploading","auth_error")) return Result.failure()
        val settings = UploadSettings(applicationContext)
        if(!settings.enabled() || settings.authBlocked()) return Result.success()
        val auth = try { settings.auth() } catch (_: Exception) {
            queue.state(id,"auth_error","credential_unavailable"); return Result.failure()
        } ?: return Result.success()
        if(initial.profile != auth.profile.identity()) {
            queue.state(id,"rejected","profile_changed"); return Result.failure()
        }
        try {
            UploadContract.parseName(initial.name)
            queue.attempting(id)
            val snapshot = UploadSnapshot.prepare(applicationContext,initial,queue) { isStopped }
            val item = queue.get(id)!!
            val result = transport.send(auth,item,snapshot) { isStopped }
            when(result.decision) {
                UploadContract.Decision.ACK -> {
                    queue.ack(id,result.serverId!!)
                    snapshot.delete() // Only private retry copy. The user-selected recording is retained.
                    return Result.success()
                }
                UploadContract.Decision.AUTH -> {
                    settings.blockAuth(auth.revision)
                    queue.state(id,"auth_error",result.reason)
                    return Result.failure()
                }
                UploadContract.Decision.CONFLICT -> queue.state(id,"conflict",result.reason)
                UploadContract.Decision.REJECT -> queue.state(id,"rejected",result.reason)
                UploadContract.Decision.RETRY -> {
                    queue.state(id,"retry",result.reason); return Result.retry()
                }
            }
            return Result.failure()
        } catch(e: InvalidAudio) {
            queue.state(id,if(e.reason == "local_integrity_conflict") "conflict" else "rejected",e.reason)
            return Result.failure()
        } catch (_: FileNotFoundException) {
            queue.state(id,"local_error","source_unavailable"); return Result.failure()
        } catch (_: SecurityException) {
            queue.state(id,"local_error","source_permission"); return Result.failure()
        } catch (_: IOException) {
            queue.state(id,"retry","io_retry"); return Result.retry()
        } catch (_: Exception) {
            // No stack trace/exception text: a provider or HTTP exception can contain a private URI.
            queue.state(id,"retry","delivery_retry"); return Result.retry()
        }
    }
}
