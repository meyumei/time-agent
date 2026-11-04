plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

val baselineRuntime by configurations.creating
dependencies {
    compileOnly("com.android.tools.lint:lint-api:31.9.2")
    baselineRuntime("com.android.tools.lint:lint-api:31.9.2")
}

tasks.jar {
    manifest.attributes["Lint-Registry-v2"] = "com.timeagent.lint.TimeAgentIssueRegistry"
}

// Explicit capture only: normal compilation/lint must never bless newly added strings.
tasks.register<JavaExec>("captureLegacyChinese") {
    group = "code generation"
    description = "Capture the reviewed legacy Chinese literals using Kotlin PSI."
    dependsOn(tasks.compileKotlin)
    classpath = sourceSets.main.get().runtimeClasspath + baselineRuntime
    mainClass.set("com.timeagent.lint.LegacyChineseBaselineKt")
    args(rootProject.projectDir.absolutePath,
        layout.projectDirectory.file("src/main/resources/legacy-chinese-literals.tsv").asFile.absolutePath)
}

tasks.processResources { mustRunAfter("captureLegacyChinese") }
