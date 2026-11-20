pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// Optional Maven Central mirror for restricted networks (see TROUBLESHOOTING.md), e.g.
// familysafety.mavenMirror=https://maven-central.storage-download.googleapis.com/maven2
val mavenMirror: String? = providers.gradleProperty("familysafety.mavenMirror").orNull

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        if (mavenMirror != null) maven(mavenMirror)
        mavenCentral()
    }
}

rootProject.name = "FamilySafety"
include(":app", ":core", ":domain", ":data", ":feature-parent", ":feature-child")
