/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Immutable retry payload. Kept until durable server acknowledgement; never deletes the source OGG. */
object UploadSnapshot {
    fun file(context: Context, id: String): File = File(File(context.noBackupFilesDir, "callmonitor_spool"), "$id.ogg")
    fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val b = ByteArray(65536)
            while(true) { val n = input.read(b); if(n < 0) break; digest.update(b,0,n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun prepare(context: Context, item: UploadQueue.Item, queue: UploadQueue, stopped: () -> Boolean): File {
        val dest = file(context,item.id)
        check(dest.parentFile!!.isDirectory || dest.parentFile!!.mkdirs())
        if (!dest.exists()) {
            val part = File(dest.path+".part")
            // An interrupted copy is never a retry payload. Re-create it from the retained source.
            context.contentResolver.openInputStream(Uri.parse(item.uri)).use { input ->
                if(input == null) throw IOException("source_unavailable")
                FileOutputStream(part,false).use { output ->
                    val buffer = ByteArray(65536); var bytes = 0L
                    while(true) {
                        if(stopped()) throw IOException("interrupted")
                        val n = input.read(buffer); if(n < 0) break
                        bytes += n; if(bytes > UploadContract.MAX_BYTES) throw InvalidAudio("audio_too_large")
                        output.write(buffer,0,n)
                    }
                    output.flush(); output.fd.sync()
                }
            }
            if(!part.renameTo(dest)) throw IOException("snapshot_rename")
        }
        if(dest.length() !in 1..UploadContract.MAX_BYTES) throw InvalidAudio("audio_size")
        val hash = sha(dest)
        if(item.sha != null) {
            // If the original was edited/deleted after a prior attempt, keep UUID and original hash.
            if(hash != item.sha || dest.length() != item.bytes) throw InvalidAudio("local_integrity_conflict")
        } else {
            val info = OggInfo.inspect(dest)
            queue.prepared(item.id,info,hash,dest.length())
        }
        return dest
    }
}
