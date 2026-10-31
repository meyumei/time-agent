package com.timeagent.imports

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTimetableExtractorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun extractsRealPdfGlyphPositionsAndTableRules() {
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(30f, 740f)
                content.showText("Selectable timetable")
                content.endText()
                content.moveTo(20f, 720f)
                content.lineTo(300f, 720f)
                content.stroke()
            }
            document.save(bytes)
        }
        val pages = PdfTimetableExtractor.extract(context, ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1, pages.size)
        assertTrue(pages.single().runs.joinToString("") { it.text }.contains("Selectable timetable"))
        assertTrue(pages.single().runs.all { it.y > 0f && it.x >= 0f })
        assertTrue(pages.single().rules.any { kotlin.math.abs(it.y1 - it.y2) < .1f })
    }

    @Test fun imageOnlyPdfAndWrongFileAreExplicitErrors() {
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document -> document.addPage(PDPage()); document.save(bytes) }
        assertThrows(IllegalArgumentException::class.java) { PdfTimetableExtractor.extract(context, ByteArrayInputStream(bytes.toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) { PdfTimetableExtractor.extract(context, ByteArrayInputStream("plain text is not a PDF".toByteArray())) }
    }

    @Test fun completeSyntheticChinesePdfPreservesAllMergedRowsAndWeeks() {
        val draft = requireNotNull(javaClass.getResourceAsStream("/imports/scut-synthetic.pdf")).use {
            ScutPdfTimetableParser.parse(PdfTimetableExtractor.extract(context, it))
        }
        assertEquals(16, draft.rows.size)
        assertEquals(mapOf("1" to 3, "2" to 6, "3" to 2, "4" to 5), draft.rows.groupingBy { it.dayOfWeek }.eachCount())
        assertEquals(145, draft.rows.sumOf { TimetableFields.weeks(it.weeks).size })
        assertEquals("合成课程A", draft.rows.first().name)
        assertEquals("测试教师", draft.rows.first().teacher)
        assertEquals("教室A", draft.rows.first().location)
    }

    @Test fun standardPageRotationsKeepGlyphAndRuleCoordinatesAligned() {
        fun parse(name: String) = requireNotNull(javaClass.getResourceAsStream("/imports/$name")).use {
            ScutPdfTimetableParser.parse(PdfTimetableExtractor.extract(context, it))
        }
        val expected = parse("scut-synthetic.pdf")
        for (angle in listOf(90, 180, 270)) {
            val rotated = parse("scut-synthetic-rotated-$angle.pdf")
            assertEquals("Page rotation $angle must preserve every parsed field", expected.rows, rotated.rows)
        }
    }

    /** Opt-in local acceptance only. No personal PDF or extracted course data is committed. */
    @Test fun locallyProvidedScutPdfHasSixteenScheduledRows() {
        val path = System.getenv("TIME_AGENT_TEST_PDF")
        org.junit.Assume.assumeTrue("Set TIME_AGENT_TEST_PDF to run local private-PDF acceptance", !path.isNullOrBlank())
        val draft = File(requireNotNull(path)).inputStream().use { ScutPdfTimetableParser.parse(PdfTimetableExtractor.extract(context, it)) }
        assertEquals(16, draft.rows.size)
        assertEquals(mapOf("1" to 3, "2" to 6, "3" to 2, "4" to 5), draft.rows.groupingBy { it.dayOfWeek }.eachCount())
        assertTrue(draft.rows.all { it.name.isNotBlank() && it.teacher.isNotBlank() && it.location.isNotBlank() })
        assertTrue(draft.warnings.any { "实践" in it })
        assertEquals(145, draft.rows.sumOf { TimetableFields.weeks(it.weeks).size })
        val referencePath = System.getenv("TIME_AGENT_TEST_PDF_REFERENCE")?.let(::File)
            ?: listOf(File("../evidence/private-pdf-verification.json"), File("evidence/private-pdf-verification.json")).firstOrNull { it.isFile }
        requireNotNull(referencePath) { "Generate the ignored private pdfplumber reference before private acceptance" }
        val reference = JsonParser.parseString(referencePath.readText(Charsets.UTF_8)).asJsonObject
        val expected = reference.getAsJsonArray("referenceRows")
        val actualRows = JsonArray()
        val comparisons = JsonArray()
        var matchingRows = 0
        for ((index, actual) in draft.rows.withIndex()) {
            val row = expected[index].asJsonObject
            val actualFields = linkedMapOf("name" to actual.name, "teacher" to actual.teacher, "location" to actual.location,
                "dayOfWeek" to actual.dayOfWeek, "periods" to actual.periods, "externalKey" to actual.externalKey.orEmpty(), "campus" to actual.campus)
            val check = JsonObject().apply { addProperty("row", index + 1) }
            val actualJson = JsonObject()
            actualFields.forEach { (key, value) ->
                check.addProperty(key, row.get(key).asString == value)
                actualJson.addProperty(key, value)
            }
            check.addProperty("weeks", row.getAsJsonArray("weeks").map { it.asInt }.toSet() == TimetableFields.weeks(actual.weeks))
            actualJson.addProperty("weeksText", actual.weeks)
            val allMatch = check.entrySet().filter { it.key != "row" }.all { it.value.asBoolean }
            if (allMatch) matchingRows++
            check.addProperty("allFieldsMatch", allMatch)
            comparisons.add(check); actualRows.add(actualJson)
        }
        reference.add("productionRows", actualRows)
        reference.add("comparisons", comparisons)
        reference.add("verification", JsonObject().apply {
            addProperty("status", if (matchingRows == 16) "verified" else "mismatch")
            addProperty("rowsCompared", 16); addProperty("matchingRows", matchingRows)
            addProperty("allFieldsMatch", matchingRows == 16)
        })
        referencePath.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(reference), Charsets.UTF_8)
        assertTrue("Private PDF row-by-row fields differ; inspect ignored private-pdf-verification.json", matchingRows == 16)
    }
}
