package com.timeagent.lint

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File

/** Source inventory generation, not a lint run or a product test. Never run automatically. */
fun main(args: Array<String>) {
    val root = File(args[0])
    val destination = File(args[1])
    val disposable = Disposer.newDisposable()
    try {
        val environment = KotlinCoreEnvironment.createForProduction(disposable,
            CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val factory = KtPsiFactory(environment.project, false)
        val counts = sortedMapOf<String, Int>()
        for (module in listOf("app", "core", "agent-runtime")) {
            for (source in File(root, "$module/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                val path = source.relativeTo(root).invariantSeparatorsPath
                val file = factory.createFile(source.name, source.readText())
                for (literal in ChineseLiterals.collect(file)) {
                    val key = ChineseLiterals.key(path, literal.text)
                    counts[key] = counts.getOrDefault(key, 0) + 1
                }
            }
        }
        destination.parentFile.mkdirs()
        destination.writeText("# Legacy inventory; do not regenerate to allow new hardcoded text.\n" +
            counts.entries.joinToString("\n", postfix = "\n") { (key, count) -> "$key\t$count" })
        println("Captured ${counts.size} legacy source literals (${counts.values.sum()} occurrences).")
    } finally {
        Disposer.dispose(disposable)
    }
}
