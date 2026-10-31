package com.timeagent.attachments

import org.junit.Assert.*
import org.junit.Test

class AttachmentOcrTest {
    @Test fun `ocr only accepts image mime types`() {
        assertTrue(AttachmentOcr.supports("image/jpeg"))
        assertTrue(AttachmentOcr.supports("image/png"))
        assertFalse(AttachmentOcr.supports("application/pdf"))
        assertFalse(AttachmentOcr.supports("text/plain"))
    }
}
