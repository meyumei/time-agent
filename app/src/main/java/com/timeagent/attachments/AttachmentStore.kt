package com.timeagent.attachments
import com.timeagent.data.AttachmentEntity
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
class AttachmentStore(private val directory:File) {
    companion object {
        const val MAX_FILE_BYTES=8L*1024*1024
        const val MAX_TOTAL_BYTES=12L*1024*1024
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun validKey(key:String)=key.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin"))
    }
    fun file(key:String):File {
        require(validKey(key)){"附件路径无效"}
        val root=directory.canonicalFile
        return File(root,key).also{require(it.canonicalFile.parentFile==root){"附件路径无效"}}
    }
    fun importFile(input:InputStream,name:String,mime:String):AttachmentEntity {
        directory.mkdirs()
        val id=UUID.randomUUID().toString();val target=file("$id.bin")
        val existing=directory.listFiles().orEmpty().filter{it.isFile}.sumOf{it.length()}
        var size=0L;val digest=MessageDigest.getInstance("SHA-256")
        try {
            target.outputStream().use { out ->
                val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;size+=count
                    require(size<=MAX_FILE_BYTES){"单个附件最多8 MiB"}
                    require(existing+size<=MAX_TOTAL_BYTES){"附件总空间最多12 MiB，请先清理回收站"}
                    out.write(buffer,0,count);digest.update(buffer,0,count)
                };out.fd.sync()
            }
            require(size>0){"附件为空"}
            return AttachmentEntity(id,target.name,name.take(200).ifBlank{"附件"},mime.ifBlank{"application/octet-stream"},size,digest.digest().joinToString(""){"%02x".format(it)})
        }catch(failure:Throwable){target.delete();throw failure}
    }
    fun readableFile(item:AttachmentEntity):File?=file(item.storageKey).takeIf{it.isFile && it.length()==item.sizeBytes && hash(it.readBytes())==item.sha256}
    fun delete(key:String){file(key).let{check(!it.exists()||it.delete()){"附件原件未能清除"}}}
}
