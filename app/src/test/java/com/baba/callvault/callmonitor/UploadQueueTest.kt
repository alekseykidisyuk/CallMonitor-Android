package com.baba.callvault.callmonitor

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Callable

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class UploadQueueTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var queue:UploadQueue
    private val name="20260906_234006.777+0500_in_123.ogg"
    private val profile="https://server.test|pilot|redmi-note12-01|operator-01"
    @Before fun setup() { context.deleteDatabase("callmonitor_upload.db");queue=UploadQueue(context) }
    @After fun tearDown() { queue.close() }
    private fun fixture():File {
        val raw=javaClass.getResourceAsStream("/callmonitor/stereo.ogg.b64")!!.use { Base64.getMimeDecoder().decode(it.readBytes()) }
        return File(context.cacheDir,"input-${System.nanoTime()}.ogg").apply { writeBytes(raw) }
    }
    @Test fun oneCallIdSurvivesRepeatedRegistrationAndDatabaseReopen() {
        val id=queue.enqueue("content://test/audio",name,profile)
        assertEquals(id,queue.enqueue("content://test/audio",name,profile))
        queue.attempting(id);queue.close();queue=UploadQueue(context)
        assertEquals(id,queue.pending().single().id)
        assertEquals(1,queue.get(id)!!.attempts)
        assertEquals("uploading",queue.get(id)!!.state)
        assertEquals(1L,queue.count())
    }
    @Test fun concurrentFinalizationHasOneDurableIdentity() {
        val pool=Executors.newFixedThreadPool(8)
        try {
            val ids=pool.invokeAll((1..8).map { Callable { queue.enqueue("content://test/audio",name,profile) } }).map { it.get() }
            assertEquals(1,ids.toSet().size);assertEquals(1L,queue.count())
        } finally { pool.shutdownNow() }
    }
    @Test fun acknowledgementIsDurableAndLateErrorCannotUndoIt() {
        val id=queue.enqueue("content://test/audio",name,profile)
        queue.ack(id,81);queue.state(id,"retry","late_error");queue.close();queue=UploadQueue(context)
        assertEquals("uploaded",queue.get(id)!!.state);assertEquals(81L,queue.get(id)!!.serverId)
        assertTrue(queue.pending().isEmpty());assertEquals(id,queue.enqueue("content://test/audio",name,profile))
    }
    @Test fun offlineCallsHaveIndependentIdsAndErrorsAreNotRetriedBlindly() {
        val first=queue.enqueue("content://test/1",name,profile)
        val second=queue.enqueue("content://test/2",name.replace("234006","234106"),profile)
        assertNotEquals(first,second);assertEquals(2,queue.pending().size)
        queue.state(first,"conflict","http_409");queue.state(second,"auth_error","http_401")
        assertTrue(queue.pending().isEmpty());queue.resumeAuth()
        assertEquals(second,queue.pending().single().id);assertEquals("conflict",queue.get(first)!!.state)
    }
    @Test fun immutableSnapshotSurvivesSourceEditAndLostAckRetry() {
        val source=fixture();val id=queue.enqueue(Uri.fromFile(source).toString(),name,profile)
        val snapshot=UploadSnapshot.prepare(context,queue.get(id)!!,queue){false}
        val hash=queue.get(id)!!.sha;val original=source.readBytes()
        queue.state(id,"retry","invalid_ack");source.writeText("changed by external application")
        queue.close();queue=UploadQueue(context)
        val again=UploadSnapshot.prepare(context,queue.get(id)!!,queue){false}
        assertEquals(snapshot,again);assertEquals(hash,UploadSnapshot.sha(again));assertArrayEquals(original,again.readBytes())
        queue.ack(id,1);again.delete()
        assertEquals("changed by external application",source.readText())
    }
    @Test fun lostSnapshotCannotSilentlyReplaceAlreadyAttemptedBytes() {
        val source=fixture();val id=queue.enqueue(Uri.fromFile(source).toString(),name,profile)
        UploadSnapshot.prepare(context,queue.get(id)!!,queue){false}.delete()
        source.appendText("extra bytes")
        try { UploadSnapshot.prepare(context,queue.get(id)!!,queue){false};fail() }
        catch(e:InvalidAudio) { assertEquals("local_integrity_conflict",e.reason) }
        assertEquals(id,queue.get(id)!!.id)
    }
    @Test fun interruptedCopyIsRecreatedInsteadOfUploaded() {
        val source=fixture();val id=queue.enqueue(Uri.fromFile(source).toString(),name,profile)
        try { UploadSnapshot.prepare(context,queue.get(id)!!,queue){true};fail() } catch (_:java.io.IOException) {}
        assertNull(queue.get(id)!!.sha)
        val snapshot=UploadSnapshot.prepare(context,queue.get(id)!!,queue){false}
        assertArrayEquals(source.readBytes(),snapshot.readBytes())
    }
}
