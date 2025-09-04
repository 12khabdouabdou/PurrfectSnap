// common/build.gradle.kts

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    id("kotlin-parcelize")
}

android {
    // Use the common namespace (not composer)
    namespace = rootProject.ext["applicationId"].toString() + ".common"
    compileSdk = 34

    // Generated assets directory used at runtime
    sourceSets {
        getByName("main") {
            assets.srcDirs("build/assets")
        }
    }

    // Enable Compose and BuildConfig for AGP 8+
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Compose compiler extension
    composeOptions {
        // Align this with the project-wide Compose Compiler version
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    // Toolchains
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Local modules used by 'common' (adjust names if different)
    implementation(project(":bridge"))
    implementation(project(":mapper"))

    // Jetpack Compose (use BOM to keep versions in sync)
    implementation(platform("androidx.compose:compose-bom:2024.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose + Android integration and AndroidX basics
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Gson JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // OkHttp (includes Okio)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Rhino for Android (Context, ScriptableObject, RhinoAndroidHelper, etc.)
    implementation("com.faendir.rhino:rhino-android:1.6.0")
}
