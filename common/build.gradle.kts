import java.io.ByteArrayOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

        // Use ProviderFactory.exec (lazy + Gradle 9 compatible)
        val gitHashProvider = providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.map { it.trim() }
        buildConfigField("String", "GIT_HASH", "\"${gitHashProvider.get()}\"")

        val sif = properties["debug_sif_endpoint"]?.toString()
            ?: "https://github.com/SnapEnhance/resources/raw/refs/heads/main/sif"
        buildConfigField("String", "SIF_ENDPOINT", "\"$sif\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Migrate to compilerOptions DSL (replaces deprecated kotlinOptions.jvmTarget)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.coroutines)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)

    // Rhino
    implementation(libs.rhino)
    implementation(libs.rhino.android) {
        exclude(group = "org.mozilla", module = "rhino-runtime")
    }

    // Local modules
    implementation(project(":mapper"))
    // Make :bridge optional to avoid hard failure when it is not included
    findProject(":bridge")?.let { implementation(it) }

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.ripple)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation("androidx.activity:activity-compose:1.9.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // DataStore Preferences for ThemePreferences.kt
    implementation("androidx.datastore:datastore-preferences:1.1.7")
}
