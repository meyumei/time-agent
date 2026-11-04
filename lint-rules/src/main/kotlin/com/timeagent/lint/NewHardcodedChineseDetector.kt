package com.timeagent.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import java.security.MessageDigest

internal object ChineseLiterals {
    fun collect(file: PsiFile): List<PsiElement> = buildList {
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is KtStringTemplateExpression ||
                    (element is PsiLiteralExpression && element.value is String)) {
                    if (element.text.any { it in '\u3400'..'\u4dbf' || it in '\u4e00'..'\u9fff' }) add(element)
                    // A whole template includes nested expressions; count each source string once.
                    return
                }
                super.visitElement(element)
            }
        })
    }

    fun key(path: String, text: String): String = path + "\t" +
        MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class NewHardcodedChineseDetector : Detector(), SourceCodeScanner {
    private val legacy by lazy {
        requireNotNull(javaClass.getResourceAsStream("/legacy-chinese-literals.tsv")).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                val (path, hash, count) = line.split('\t')
                "$path\t$hash" to count.toInt()
            }
        }
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext) = object : UElementHandler() {
        override fun visitFile(node: UFile) {
            val source = node.sourcePsi ?: return
            val path = context.file.relativeTo(context.project.dir.parentFile).invariantSeparatorsPath
            if (!path.contains("/src/main/")) return
            val seen = mutableMapOf<String, Int>()
            for (literal in ChineseLiterals.collect(source)) {
                val key = ChineseLiterals.key(path, literal.text)
                val count = seen.getOrDefault(key, 0) + 1
                seen[key] = count
                if (count > legacy.getOrDefault(key, 0)) context.report(
                    ISSUE, literal, context.getLocation(literal),
                    "Move new Chinese text to a string resource; use stringResource or Context.getString, including formatted text and accessibility descriptions.",
                )
            }
        }
    }

    companion object {
        val ISSUE: Issue = Issue.create(
            "NewHardcodedChinese",
            "New Chinese text must use resources",
            "UI text, formatted messages and accessibility descriptions belong in string resources. " +
                "The checked-in snapshot temporarily allows the existing literal count; it does not exempt whole files or additional copies.",
            Category.I18N, 6, Severity.ERROR,
            Implementation(NewHardcodedChineseDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}

class TimeAgentIssueRegistry : IssueRegistry() {
    override val api = CURRENT_API
    override val issues = listOf(NewHardcodedChineseDetector.ISSUE)
}
