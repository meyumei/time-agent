package com.timeagent.backup

import android.util.AtomicFile
import android.util.Base64
import com.timeagent.attachments.AttachmentStore
import java.io.File

/** File bytes share the same durable rollback payload as the paired databases. */
internal object AttachmentBackup {
    private data class Metadata(val key:String,val name:String,val size:Long,val hash:String)
    private fun metadata(data:BackupDatabase):List<Metadata> {
        val table=data.tables["attachments"]?:return emptyList()
        fun value(row:List<BackupCell>,name:String)=row[table.columns.indexOf(name)].value
        return table.rows.map{row->Metadata(requireNotNull(value(row,"storageKey")),requireNotNull(value(row,"originalName")),requireNotNull(value(row,"sizeBytes")).toLong(),requireNotNull(value(row,"sha256")))}
    }
    fun capture(directory:File,data:BackupDatabase,includeOrphans:Boolean):Map<String,String> {
        val store=AttachmentStore(directory)
        val expected=metadata(data).associateBy{it.key}
        val result=linkedMapOf<String,String>()
        var total=0L
        directory.listFiles().orEmpty().filter{it.isFile && AttachmentStore.validKey(it.name)}.forEach{file->
            val item=expected[file.name]
            if(includeOrphans || item!=null && file.length()==item.size) {
                require(file.length()<=AttachmentStore.MAX_FILE_BYTES){"附件超过单件备份限额"}
                val bytes=file.readBytes()
                if(includeOrphans || item?.hash==AttachmentStore.hash(bytes)) {
                    total+=bytes.size;require(total<=AttachmentStore.MAX_TOTAL_BYTES){"附件超过备份总限额"}
                    result[file.name]=Base64.encodeToString(bytes,Base64.NO_WRAP)
                }
            }
        }
        return result
    }
    fun validate(data:BackupDatabase,files:Map<String,String>?,rollback:Boolean=false):Map<String,ByteArray> {
        val expected=metadata(data).associateBy{it.key}
        var total=0L
        return files.orEmpty().mapValues{(key,encoded)->
            require(AttachmentStore.validKey(key) && encoded.length<=((AttachmentStore.MAX_FILE_BYTES+2)/3*4)){"备份附件路径或大小无效"}
            val bytes=Base64.decode(encoded,Base64.NO_WRAP)
            total+=bytes.size;require((rollback || bytes.isNotEmpty()) && bytes.size<=AttachmentStore.MAX_FILE_BYTES && total<=AttachmentStore.MAX_TOTAL_BYTES){"备份附件超过限额"}
            if(!rollback) expected[key]?.let{require(it.size==bytes.size.toLong() && it.hash==AttachmentStore.hash(bytes)){"备份附件内容校验失败"}}
            bytes
        }
    }
    fun missing(data:BackupDatabase,files:Map<String,String>?)=metadata(data).filter{it.key !in files.orEmpty()}.map{it.name}
    fun replace(directory:File,data:BackupDatabase,files:Map<String,String>?,rollback:Boolean=false) {
        val bytes=validate(data,files,rollback);directory.mkdirs();val store=AttachmentStore(directory)
        bytes.forEach{(key,content)->
            val target=AtomicFile(store.file(key));val stream=target.startWrite()
            try{stream.write(content);stream.fd.sync();target.finishWrite(stream)}catch(failure:Throwable){target.failWrite(stream);throw failure}
        }
        directory.listFiles().orEmpty().filter{it.isFile && AttachmentStore.validKey(it.name) && it.name !in bytes}.forEach{store.delete(it.name)}
    }
}
