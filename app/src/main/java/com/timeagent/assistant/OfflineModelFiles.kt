package com.timeagent.assistant

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object OfflineModelFiles {
    const val MODEL = "vosk-model-small-cn-0.22"
    const val SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"

    @Synchronized fun prepare(context: Context): File {
        val parent = File(context.noBackupFilesDir, "speech").apply { mkdirs() }
        val destination = File(parent, MODEL)
        val marker = File(destination, ".ready")
        if (marker.isFile && marker.readText() == SHA256 && required.all { File(destination, it).isFile }) return destination
        val staging = File(parent, "$MODEL-staging")
        check(staging.canonicalFile.parentFile == parent.canonicalFile)
        staging.deleteRecursively()
        check(staging.mkdirs())
        try {
            context.assets.open("asr/$MODEL.zip").use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val checked = java.security.DigestInputStream(stream, digest)
                unpack(checked, staging)
                // Drain after ZIP's last entry so the central directory participates in integrity checking.
                while (checked.read(ByteArray(8192)) >= 0) { /* bounded bundled asset */ }
                check(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "中文语音模型校验失败" }
            }
            check(required.all { File(staging, it).isFile }) { "中文语音模型不完整" }
            File(staging, ".ready").writeText(SHA256)
            check(destination.canonicalFile.parentFile == parent.canonicalFile)
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "无法准备中文语音模型" }
            return destination
        } catch (e: Exception) { staging.deleteRecursively(); throw e }
    }

    internal fun unpack(input: InputStream, destination: File) {
        // Do not close the digest stream here: its trailing ZIP directory must still be read.
        val zip = ZipInputStream(object : java.io.FilterInputStream(input) { override fun close() {} })
        var expanded = 0L
        var entries = 0
        try {
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entries <= 100 && entry.name.startsWith("$MODEL/")) { "模型文件格式错误" }
            val relative = entry.name.removePrefix("$MODEL/")
            val file = File(destination, relative)
            require(file.canonicalPath == destination.canonicalPath || file.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "模型路径无效" }
            if (entry.isDirectory) file.mkdirs() else {
                file.parentFile?.mkdirs()
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = zip.read(buffer)
                        if (size < 0) break
                        expanded += size
                        require(expanded <= 128L * 1024 * 1024) { "模型大小无效" }
                        output.write(buffer, 0, size)
                    }
                }
            }
            zip.closeEntry()
        }
        } finally { zip.close() }
    }
    private val required = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst", "ivector/final.ie")
}
