package com.timeagent.attachments
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.timeagent.data.AttachmentEntity
import com.timeagent.data.PersonalDataGate
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
class AttachmentService(context:Context,private val repository:TimeRepository) {
    private val context=context.applicationContext
    private val store=AttachmentStore(File(this.context.filesDir,"attachments"))
    suspend fun importDocument(uri:Uri):AttachmentEntity=withContext(Dispatchers.IO){PersonalDataGate.access{
        require(uri.scheme=="content"){"请通过系统文件选择器选择附件"}
        val resolver=context.contentResolver
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0) else null}?:"附件"
        val item=requireNotNull(resolver.openInputStream(uri)){"无法读取所选文件"}.use{store.importFile(it,name,resolver.getType(uri).orEmpty())}
        withContext(NonCancellable){try{repository.addAttachment(item)}catch(failure:Throwable){store.delete(item.storageKey);throw failure}}
    }}
    suspend fun readableFile(item:AttachmentEntity):File?=withContext(Dispatchers.IO){PersonalDataGate.access{store.readableFile(item)}}
    suspend fun saveDraft(id:String,text:String,expectedVersion:Long)=repository.editAttachment(id,expectedVersion,text)
    suspend fun link(id:String,projectId:String?,taskId:String?,expectedVersion:Long)=repository.linkAttachment(id,projectId,taskId,expectedVersion)
    suspend fun trash(id:String,expectedVersion:Long)=repository.setAttachmentDeleted(id,expectedVersion,true)
    suspend fun restore(id:String,expectedVersion:Long)=repository.setAttachmentDeleted(id,expectedVersion,false)
    suspend fun purge(id:String,expectedVersion:Long):AttachmentEntity=withContext(Dispatchers.IO){PersonalDataGate.access{
        val item=requireNotNull(repository.snapshot().attachments.find{it.id==id})
        require(item.version==expectedVersion && item.deletedAt!=null){"请重新打开回收站后重试"}
        withContext(NonCancellable){store.delete(item.storageKey);repository.markAttachmentPurged(id,expectedVersion)}
    }}
}
