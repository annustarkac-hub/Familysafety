import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing material is read from (in order) environment variables or an untracked keystore.properties.
// Nothing secret is committed. See BUILD_GUIDE.md.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: signingProps.getProperty(prop)

val appVersionCode = providers.gradleProperty("familysafety.versionCode").getOrElse("2").toInt()
val appVersionName = providers.gradleProperty("familysafety.versionName").getOrElse("1.1.0")

android {
    namespace = "com.familysafety.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.familysafety.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("FS_KEYSTORE_PATH", "storeFile")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("FS_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("FS_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("FS_KEY_PASSWORD", "keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    // Per-ABI APKs (~1/3 the size, WebRTC native code dominates) plus a universal APK for sideloading.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        // App-module JVM tests run on the debug variant: AGP does not merge test-only manifests (Compose's
        // ComponentActivity host) into an application's release unit-test manifest. Library modules test both.
        unitTests.all { if (it.name.contains("Release")) it.enabled = false }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        xmlReport = true
        htmlReport = true
        // Usage-access permission is a protected permission that the user grants in Settings; this is expected.
        disable += "ProtectedPermissions"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":feature-parent"))
    implementation(project(":feature-child"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3.windowsize)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
