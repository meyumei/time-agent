package com.timeagent.reports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Long images and paginated PDFs use the same locally computed report contents. */
object ReportDocuments {
    private fun text(snapshot: ReportSnapshot, sections: Set<ReportSection>, fishCommentary: String?): List<String> = buildList {
        add("时间 Agent · ${snapshot.kind.label}")
        add(ReportFormat.windowTitle(snapshot.window))
        val overview = snapshot.overview
        sections.forEach { section ->
            add(section.label)
            when (section) {
                ReportSection.OVERVIEW -> {
                    add("计划 ${ReportFormat.minutes(overview.plannedMinutes)} · 实际 ${ReportFormat.minutes(overview.actualMinutes)}")
                    add("覆盖 ${ReportFormat.minutes(overview.coveredMinutes)} · 完成率 ${ReportFormat.rate(overview.planCompletionRate)}")
                    add("专注 ${ReportFormat.minutes(overview.focusMinutes)} / ${overview.focusSessions} 次")
                    add("睡眠 ${ReportFormat.minutes(overview.sleepMinutes)} / ${overview.sleepNights} 夜")
                }
                ReportSection.PROJECTS -> snapshot.projects.forEach { add("${it.name} · ${ReportFormat.minutes(it.minutes)} · ${it.sharePercent}%") }
                ReportSection.TAGS -> snapshot.tags.forEach { add("${it.name} · ${ReportFormat.minutes(it.minutes)} · ${it.sharePercent}%") }
                ReportSection.DEVIATIONS -> snapshot.deviations.forEach { add("${it.label} · 计划 ${ReportFormat.minutes(it.plannedMinutes)} / 实际 ${ReportFormat.minutes(it.actualMinutes)}") }
                ReportSection.DEADLINES -> add(snapshot.deadlines?.let { "共 ${it.total} 项 · 完成 ${it.completed} · 待办 ${it.pending} · 过期 ${it.overdue}" } ?: "无数据")
                ReportSection.HABITS -> {
                    val habits = snapshot.habits
                    if (habits == null || habits.unavailable) add("无数据")
                    else habits.lines.forEach { add("${it.name} · ${it.achieved}/${it.evaluated} · ${ReportFormat.rate(it.rate)} · ${it.targetText}") }
                }
                ReportSection.WEEKLY_GOALS -> snapshot.weeklyGoals.forEach { add("${it.title} · ${ReportFormat.minutes(it.actualMinutes)} / ${it.targetMinutes} 分钟") }
                ReportSection.WORKOUTS -> add(snapshot.workout?.takeUnless { it.unavailable }?.let { "${it.sessions} 次 · ${it.minutes} 分钟 · ${it.completedSets} 组 · 总量 ${it.volumeKg} kg" } ?: "无数据")
                ReportSection.LEDGER -> {
                    val ledger = snapshot.ledger
                    if (ledger == null) add("无数据")
                    else {
                        add("${ledger.count} 笔 · 支出 ${ReportFormat.money(ledger.expenseMinor)} · 收入 ${ReportFormat.money(ledger.incomeMinor)}")
                        add("退款 ${ReportFormat.money(ledger.refundMinor)} · 净支出 ${ReportFormat.money(ledger.netExpenseMinor)}")
                        ledger.topCategories.forEach { add("${it.name} · ${ReportFormat.money(it.amountMinor)}") }
                    }
                }
            }
        }
        snapshot.notes.forEach(::add)
        if (!fishCommentary.isNullOrBlank()) { add("大肥鱼点评（不参与本地数字计算）"); add(fishCommentary) }
    }

    private val paint get() = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 39, 43); textSize = 26f }
    private fun layout(text: String) = StaticLayout.Builder.obtain(text, 0, text.length, paint, 920)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).setLineSpacing(8f, 1f).build()

    private fun drawPages(snapshot: ReportSnapshot, sections: Set<ReportSection>, commentary: String?, render: (Int, List<Pair<StaticLayout, Float>>) -> Unit) {
        var page = 0
        var y = 50f
        var lines = mutableListOf<Pair<StaticLayout, Float>>()
        text(snapshot, sections, commentary).forEach { paragraph ->
            // Split by rendered line so even a long commentary can continue onto another page.
            val block = layout(paragraph)
            for (index in 0 until block.lineCount) {
                val line = layout(paragraph.substring(block.getLineStart(index), block.getLineEnd(index)).trimEnd())
                if (y + line.height > 1370 && lines.isNotEmpty()) { render(page++, lines); lines = mutableListOf(); y = 50f }
                lines += line to y
                y += line.height + 6f
            }
            y += 14f
        }
        if (lines.isNotEmpty()) render(page, lines)
    }

    fun renderLongImages(context: Context, snapshot: ReportSnapshot, sections: Set<ReportSection>, fishCommentary: String?): List<File> {
        val folder = File(context.cacheDir, "report-share/${UUID.randomUUID()}").apply { mkdirs() }
        val blocks = text(snapshot, sections, fishCommentary).map(::layout)
        val width = 1020
        val height = Math.addExact(100, blocks.sumOf { Math.addExact(it.height, 20) })
        val file = File(folder, "report.png")
        // Encode each strip into the same PNG so report length does not require one giant bitmap.
        file.outputStream().buffered().use { output ->
            PngStripWriter(output, width, height).use { writer ->
            val stripHeight = 1024
            var top = 0
            while (top < height) {
                val rows = minOf(stripHeight, height - top)
                val bitmap = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    var y = 50
                    for (block in blocks) {
                        if (y + block.height > top && y < top + rows) {
                            canvas.save()
                            canvas.translate(50f, (y - top).toFloat())
                            block.draw(canvas)
                            canvas.restore()
                        }
                        y += block.height + 20
                    }
                    writer.write(bitmap)
                } finally { bitmap.recycle() }
                top += rows
            }
            writer.finish()
            }
        }
        return listOf(file)
    }

    fun renderPdf(context: Context, snapshot: ReportSnapshot, sections: Set<ReportSection>, fishCommentary: String?): File {
        val file = File(context.cacheDir, "report-share/${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs() }
        val document = PdfDocument()
        try {
            drawPages(snapshot, sections, fishCommentary) { index, lines ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(1020, 1440, index + 1).create())
                lines.forEach { (line, y) -> page.canvas.save(); page.canvas.translate(50f, y); line.draw(page.canvas); page.canvas.restore() }
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }

    fun shareImages(context: Context, files: List<File>) = share(context, files, "image/png")
    fun sharePdf(context: Context, file: File) = share(context, listOf(file), "application/pdf")
    private fun share(context: Context, files: List<File>, type: String) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.attachments", it) })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("时间 Agent 报表", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享报表"))
    }
}
