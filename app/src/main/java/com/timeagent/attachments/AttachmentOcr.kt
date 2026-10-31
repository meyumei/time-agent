package com.timeagent.attachments

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface AttachmentTextRecognizer {
    suspend fun recognize(file: File): String
}

class AttachmentOcr(private val context: Context) : AttachmentTextRecognizer {
    override suspend fun recognize(file: File): String {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, Uri.fromFile(file)) }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val pending = try { recognizer.process(image) } catch (failure: Exception) { recognizer.close(); throw failure }
        return suspendCancellableCoroutine { continuation ->
            pending
                .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result.text) }
                .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
                .addOnCompleteListener { recognizer.close() }
        }
    }

    companion object {
        fun supports(mimeType: String): Boolean = mimeType.startsWith("image/")
    }
}

