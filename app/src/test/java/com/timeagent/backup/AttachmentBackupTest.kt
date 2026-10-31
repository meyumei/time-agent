package com.timeagent.backup
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.attachments.AttachmentStore
import com.timeagent.data.*
import com.timeagent.workout.data.WorkoutDatabase
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AttachmentBackupTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var main:TimeAgentDatabase
    private lateinit var workout:WorkoutDatabase
    private lateinit var backup:PersonalBackup
    private val dir get()=File(context.filesDir,"attachments")
    private val marker get()=File(context.noBackupFilesDir,"attachment-synthetic-rollback.json")
    @Before fun setup(){
        PersonalDataGate.recoveryRequired=false
        context.deleteDatabase("attachment-synthetic.db");context.deleteDatabase("attachment-synthetic-workout.db")
        dir.deleteRecursively();marker.delete()
        main=Room.databaseBuilder(context,TimeAgentDatabase::class.java,"attachment-synthetic.db").allowMainThreadQueries().build()
        workout=Room.databaseBuilder(context,WorkoutDatabase::class.java,"attachment-synthetic-workout.db").allowMainThreadQueries().build()
        backup=PersonalBackup(context,main,workout,marker)
        File(context.filesDir,"workout-custom-exercises.json").writeText("[]")
    }
    @After fun close(){main.close();workout.close();context.deleteDatabase("attachment-synthetic.db");context.deleteDatabase("attachment-synthetic-workout.db");dir.deleteRecursively();marker.delete();PersonalDataGate.recoveryRequired=false}
    @Test fun `attachment bytes and editable text survive restore with missing preview and file rollback`()=runBlocking<Unit>{
        val repo=TimeRepository(main);val store=AttachmentStore(dir)
        var item=repo.addAttachment(store.importFile("original synthetic bytes".byteInputStream(),"合成.txt","text/plain"))
        item=repo.editAttachment(item.id,item.version,"可纠正草稿")
        val complete=backup.create();assertEquals(1,backup.preview(complete).attachmentFileCount)
        store.delete(item.storageKey)
        val missing=backup.create();assertEquals(listOf("合成.txt"),backup.preview(missing).missingAttachmentNames)
        backup.restore(complete);assertEquals("original synthetic bytes",store.readableFile(item)!!.readText())
        assertEquals("可纠正草稿",main.dao().attachment(item.id)!!.ocrText)
        store.file(item.storageKey).writeText("corrupt synthetic bytes")
        backup.afterAttachmentFilesRestored={error("synthetic after file write")}
        assertThrows(IllegalStateException::class.java){runBlocking{backup.restore(missing)}}
        assertEquals("corrupt synthetic bytes",store.file(item.storageKey).readText())
        assertNull(store.readableFile(item))
        store.file(item.storageKey).writeText("original synthetic bytes")
        backup.beforeRollback={error("synthetic interrupted rollback")}
        assertThrows(IllegalStateException::class.java){runBlocking{backup.restore(missing)}}
        main.close();workout.close();PersonalBackup.recoverIfNeeded(context,marker)
        main=Room.databaseBuilder(context,TimeAgentDatabase::class.java,"attachment-synthetic.db").allowMainThreadQueries().build()
        workout=Room.databaseBuilder(context,WorkoutDatabase::class.java,"attachment-synthetic-workout.db").allowMainThreadQueries().build()
        assertEquals("original synthetic bytes",store.readableFile(item)!!.readText())
        assertEquals("可纠正草稿",main.dao().attachment(item.id)!!.ocrText)
        assertFalse(marker.exists())
        val currentRepo=TimeRepository(main)
        val current=main.dao().attachment(item.id)!!
        val trashed=currentRepo.setAttachmentDeleted(current.id,current.version,true)
        store.delete(current.storageKey)
        currentRepo.markAttachmentPurged(current.id,trashed.version)
        currentRepo.undoLastAction()
        val restored=main.dao().attachment(current.id)!!
        assertNull(restored.deletedAt)
        assertNotNull(restored.purgedAt)
        assertNull(store.readableFile(restored))
        assertEquals("可纠正草稿",restored.ocrText)
    }
}
