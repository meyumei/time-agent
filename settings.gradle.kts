pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroup("com.google.testing.platform") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google\\.android.*"); includeGroupByRegex("com\\.google\\.mlkit.*"); includeGroupByRegex("com\\.google\\.firebase.*"); includeGroup("com.google.testing.platform") } }
        mavenCentral()
    }
}
rootProject.name = "TimeAgent"
include(":core", ":app", ":agent-runtime")
