package com.timeagent.attachments
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
class AttachmentStoreTest {
    @Test fun `private copy has safe path verifies bytes and rejects oversized files`() {
        val dir=Files.createTempDirectory("attachment-synthetic").toFile()
        try {
            val store=AttachmentStore(dir)
            val item=store.importFile("synthetic only".byteInputStream(),"../../photo.txt","text/plain")
            assertEquals("synthetic only",store.readableFile(item)!!.readText())
            assertTrue(AttachmentStore.validKey(item.storageKey))
            assertThrows(IllegalArgumentException::class.java){store.file("../escape")}
            store.file(item.storageKey).writeText("wrong")
            assertNull(store.readableFile(item))
            assertThrows(IllegalArgumentException::class.java){store.importFile(ByteArray(AttachmentStore.MAX_FILE_BYTES.toInt()+1).inputStream(),"too big","application/octet-stream")}
            assertEquals(1,dir.listFiles()!!.size)
        } finally {dir.deleteRecursively()}
    }
}
