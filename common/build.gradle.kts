import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
    id("kotlin-parcelize")
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".common"
    compileSdk = 34

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    defaultConfig {
        minSdk = 28

        buildConfigField("String", "VERSION_NAME", "\"${rootProject.ext["appVersionName"]}\"")
        buildConfigField("int", "VERSION_CODE", "${rootProject.ext["appVersionCode"]}")
        buildConfigField("String", "APPLICATION_ID", "\"${rootProject.ext["applicationId"]}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
        buildConfigField("String", "BUILD_HASH", "\"${rootProject.ext["buildHash"]}\".toString()")

        val gitHash = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = gitHash
        }
        buildConfigField("String", "GIT_HASH", "\"${gitHash.toString(Charsets.UTF_8).trim()}\"")

        buildConfigField(
            "String",
            "SIF_ENDPOINT",
            "\"${properties["debug_sif_endpoint"]?.toString() ?: "https://github.com/SnapEnhance/resources/raw/refs/heads/main/sif"}\""
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    // Core libs already in catalog
    implementation(libs.coroutines)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)

    // Rhino (Android helper + core), exclude duplicate runtime if catalog provides both
    implementation(libs.rhino)
    implementation(libs.rhino.android) {
        exclude(group = "org.mozilla", module = "rhino-runtime")
    }

    // Local modules
    implementation(project(":mapper"))
    // IMPORTANT: add the module that provides AIDL/interfaces like FileHandleManager, LoggerInterface, etc.
    // If this module exists in the repo, keep this line and include it in settings.gradle(.kts):
    // include(":bridge")
    // Otherwise, remove bridge imports/usages from the source.
    implementation(project(":bridge"))

    // Compose: use BOM and add required artifacts as implementation for compile-time visibility
    implementation(platform("androidx.compose:compose-bom:2024.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Navigation compose if used by this module’s sources
    implementation("androidx.navigation:navigation-compose:2.8.4")
    // Tooling for debug
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity Compose for ComposeView and setContent integration
    implementation("androidx.activity:activity-compose:1.9.2")

    // AndroidX lifecycle + savedstate used by code (ViewModel, lifecycle runtime, SavedState APIs)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // DataStore Preferences for ThemePreferences.kt
    implementation("androidx.datastore:datastore-preferences:1.1.7")
}
