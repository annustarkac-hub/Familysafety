plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Robolectric downloads android-all runtime jars itself; reuse the optional mirror when configured.
providers.gradleProperty("familysafety.mavenMirror").orNull?.let { mirror ->
    subprojects {
        tasks.withType<Test>().configureEach { systemProperty("robolectric.dependency.repo.url", mirror) }
    }
}
