package com.timeagent.reports

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Streams RGB scanlines into one PNG while retaining only a small bitmap strip in memory. */
internal class PngStripWriter(output: OutputStream, private val width: Int, private val height: Int) : AutoCloseable {
    private val sink = DataOutputStream(output)
    private val compressor = Deflater()
    private val compressed = ByteArray(32 * 1024)
    private var writtenRows = 0

    init {
        sink.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use {
            it.writeInt(width)
            it.writeInt(height)
            it.write(byteArrayOf(8, 2, 0, 0, 0))
        }
        chunk("IHDR", header.toByteArray())
    }

    fun write(bitmap: Bitmap) {
        require(bitmap.width == width && writtenRows + bitmap.height <= height)
        val pixels = IntArray(width)
        val row = ByteArray(1 + width * 3)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in pixels.indices) {
                row[1 + x * 3] = (pixels[x] shr 16).toByte()
                row[2 + x * 3] = (pixels[x] shr 8).toByte()
                row[3 + x * 3] = pixels[x].toByte()
            }
            compressor.setInput(row)
            while (!compressor.needsInput()) drain()
            writtenRows++
        }
    }

    fun finish() {
        check(writtenRows == height)
        compressor.finish()
        while (!compressor.finished()) drain()
        chunk("IEND", byteArrayOf())
    }

    override fun close() = compressor.end()

    private fun drain() {
        val count = compressor.deflate(compressed)
        if (count > 0) chunk("IDAT", compressed.copyOf(count))
    }

    private fun chunk(type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(data) }
        sink.writeInt(data.size)
        sink.write(name)
        sink.write(data)
        sink.writeInt(crc.value.toInt())
    }
}
