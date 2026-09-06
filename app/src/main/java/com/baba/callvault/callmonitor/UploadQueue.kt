/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Separate database: no migration or destructive fallback for the upstream recording catalog. */
class UploadQueue(context: Context) : SQLiteOpenHelper(context, "callmonitor_upload.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE uploads (
          call_id TEXT PRIMARY KEY, source_key TEXT NOT NULL UNIQUE, source_uri TEXT NOT NULL,
          filename TEXT NOT NULL, profile TEXT NOT NULL, created_at INTEGER NOT NULL,
          state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
          error TEXT, sha TEXT, bytes INTEGER, duration INTEGER, eos INTEGER,
          server_id INTEGER, ack_at INTEGER)""")
        db.execSQL("CREATE INDEX uploads_state ON uploads(state)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Explicit queue migration required") }
    data class Item(val id: String, val uri: String, val name: String, val profile: String, val createdAt: Long,
        val state: String, val attempts: Int, val error: String?, val sha: String?, val bytes: Long?,
        val duration: Long?, val eos: Boolean?, val serverId: Long?)
    fun enqueue(uri: String, name: String, profile: String): String {
        // A finished filename is the upstream catalog's identity. Retained ACK rows prevent re-enqueue.
        val key = "$profile|$name"
        val v = ContentValues().apply {
            put("call_id", UUID.randomUUID().toString()); put("source_key", key); put("source_uri", uri)
            put("filename", name); put("profile", profile); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("uploads", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        return readableDatabase.rawQuery("SELECT call_id FROM uploads WHERE source_key=?", arrayOf(key)).use {
            check(it.moveToFirst()) { "Queue insert failed" }; it.getString(0)
        }
    }
    fun get(id: String): Item? = readableDatabase.rawQuery("SELECT * FROM uploads WHERE call_id=?", arrayOf(id)).use {
        if (it.moveToFirst()) item(it) else null
    }
    fun pending(): List<Item> = query("SELECT * FROM uploads WHERE state IN ('pending','retry','uploading') ORDER BY created_at")
    fun recent(): List<Item> = query("SELECT * FROM uploads ORDER BY created_at DESC LIMIT 12")
    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM uploads", null).use { it.moveToFirst(); it.getLong(0) }
    fun counts(): Map<String, Int> = readableDatabase.rawQuery("SELECT state,COUNT(*) FROM uploads GROUP BY state", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
    }
    fun attempting(id: String) {
        writableDatabase.execSQL("UPDATE uploads SET state='uploading',attempts=attempts+1,error=NULL WHERE call_id=? AND state!='uploaded'", arrayOf(id))
    }
    fun prepared(id: String, info: OggInfo, sha: String, bytes: Long) {
        val v = ContentValues().apply { put("sha", sha); put("bytes", bytes); put("duration", info.durationMs); put("eos", if (info.eos) 1 else 0) }
        check(writableDatabase.update("uploads", v, "call_id=? AND sha IS NULL", arrayOf(id)) == 1)
    }
    fun state(id: String, state: String, reason: String) {
        writableDatabase.update("uploads", ContentValues().apply { put("state", state); put("error", reason) }, "call_id=? AND state!='uploaded'", arrayOf(id))
    }
    fun ack(id: String, serverId: Long) {
        check(writableDatabase.update("uploads", ContentValues().apply {
            put("state", "uploaded"); put("server_id", serverId); put("ack_at", System.currentTimeMillis()); putNull("error")
        }, "call_id=?", arrayOf(id)) == 1)
    }
    fun resumeAuth() { writableDatabase.execSQL("UPDATE uploads SET state='pending',error=NULL WHERE state='auth_error'") }
    fun resumeLocal() { writableDatabase.execSQL("UPDATE uploads SET state='pending',error=NULL WHERE state='local_error'") }
    private fun query(sql: String): List<Item> = readableDatabase.rawQuery(sql, null).use { c -> buildList { while(c.moveToNext()) add(item(c)) } }
    private fun item(c: Cursor): Item {
        fun s(n: String): String? = c.getColumnIndexOrThrow(n).let { if(c.isNull(it)) null else c.getString(it) }
        fun l(n: String): Long? = c.getColumnIndexOrThrow(n).let { if(c.isNull(it)) null else c.getLong(it) }
        return Item(s("call_id")!!,s("source_uri")!!,s("filename")!!,s("profile")!!,l("created_at")!!,
            s("state")!!,l("attempts")!!.toInt(),s("error"),s("sha"),l("bytes"),l("duration"),l("eos")?.let { it == 1L },l("server_id"))
    }
    companion object {
        @Volatile private var instance: UploadQueue? = null
        fun get(context: Context): UploadQueue = instance ?: synchronized(this) {
            instance ?: UploadQueue(context.applicationContext).also { instance = it }
        }
    }
}
