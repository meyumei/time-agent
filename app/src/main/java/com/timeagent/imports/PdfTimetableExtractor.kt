package com.timeagent.imports

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs

/** Local PDF parsing only. The SAF stream is never copied to external storage or sent to a service. */
object PdfTimetableExtractor {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_PAGES = 12
    private const val MAX_GLYPHS = 200_000

    fun extract(context: Context, uri: Uri): List<PdfTextPage> = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开所选 PDF，请重新选择文件" }.use { extract(context, it) }

    fun extract(context: Context, input: InputStream): List<PdfTextPage> {
        val buffer = ByteArray(8192)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "PDF 超过 16 MB，请导出较小的单学期课表" }
            bytes.write(buffer, 0, count)
        }
        val data = bytes.toByteArray()
        require(data.size >= 5 && data.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") { "所选文件不是有效 PDF，请重新选择教务导出的 PDF" }
        PDFBoxResourceLoader.init(context.applicationContext)
        try {
            PDDocument.load(data.inputStream(), MemoryUsageSetting.setupMainMemoryOnly()).use { document ->
                require(!document.isEncrypted && document.currentAccessPermission.canExtractContent()) { "PDF 已加密或不允许提取文字，请从教务系统重新导出" }
                require(document.numberOfPages in 1..MAX_PAGES) { "请使用不超过 $MAX_PAGES 页的单学期课表" }
                val result = mutableListOf<PdfTextPage>()
                var glyphCount = 0
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    require(page.rotation in listOf(0, 90, 180, 270)) { "PDF 页面方向无效，请重新导出或手工补录" }
                    val runs = mutableListOf<PdfTextRun>()
                    val stripper = object : PDFTextStripper() {
                        override fun processTextPosition(position: TextPosition) {
                            require(++glyphCount <= MAX_GLYPHS) { "PDF 文字过多，请仅导出当前学期课表" }
                            val text = position.unicode.orEmpty()
                            // x/y/width include the page rotation. Direction-only getters would put rules and text in different coordinate systems.
                            if (text.isNotEmpty()) runs += PdfTextRun(text, position.x, position.y - position.heightDir, position.width, position.heightDir)
                            super.processTextPosition(position)
                        }
                    }
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    stripper.sortByPosition = true
                    stripper.getText(document)
                    val graphics = TableRules(page)
                    graphics.processPage(page)
                    val quarterTurn = page.rotation == 90 || page.rotation == 270
                    result += PdfTextPage(if (quarterTurn) page.cropBox.height else page.cropBox.width, if (quarterTurn) page.cropBox.width else page.cropBox.height, runs, graphics.rules)
                }
                require(result.any { page -> page.runs.any { it.text.isNotBlank() } }) { "此 PDF 没有可提取文字，可能是图片扫描版。暂不支持 OCR，请选择文字版 PDF 或手工补录。" }
                return result
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("无法读取这份 PDF。文件可能损坏、加密或字体不受支持，请重新导出，或切换手工补录。")
        }
    }

    private class TableRules(private val source: PDPage) : PDFGraphicsStreamEngine(source) {
        val rules = mutableListOf<PdfRule>()
        private val pending = mutableListOf<PdfRule>()
        private var current = PointF()
        private var start = PointF()
        private fun displayed(point: PointF): PointF {
            val box = source.cropBox
            val x = point.x - box.lowerLeftX
            val y = point.y - box.lowerLeftY
            return when (source.rotation) {
                90 -> PointF(y, x)
                180 -> PointF(box.width - x, y)
                270 -> PointF(box.height - y, box.width - x)
                else -> PointF(x, box.height - y)
            }
        }
        private fun edge(a: PointF, b: PointF) {
            if (abs(a.x - b.x) < .7f || abs(a.y - b.y) < .7f) {
                require(pending.size < 50_000) { "PDF 表格过于复杂，请重新导出或手工补录" }
                val first = displayed(a)
                val last = displayed(b)
                pending += PdfRule(first.x, first.y, last.x, last.y)
            }
        }
        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            edge(p0, p1); edge(p1, p2); edge(p2, p3); edge(p3, p0)
            current = PointF(p0.x, p0.y); start = PointF(p0.x, p0.y)
        }
        override fun moveTo(x: Float, y: Float) { current = PointF(x, y); start = PointF(x, y) }
        override fun lineTo(x: Float, y: Float) { val target = PointF(x, y); edge(current, target); current = target }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { current = PointF(x3, y3) }
        override fun getCurrentPoint(): PointF = current
        override fun closePath() { edge(current, start); current = PointF(start.x, start.y) }
        override fun endPath() { pending.clear() }
        private fun save() { require(rules.size + pending.size <= 50_000) { "PDF 表格过于复杂，请重新导出或手工补录" }; rules += pending; pending.clear() }
        override fun strokePath() { save() }
        override fun fillPath(windingRule: Path.FillType) { save() }
        override fun fillAndStrokePath(windingRule: Path.FillType) { save() }
        override fun clip(windingRule: Path.FillType) = Unit
        override fun drawImage(pdImage: PDImage) = Unit
        override fun shadingFill(shadingName: COSName) = Unit
    }
}
