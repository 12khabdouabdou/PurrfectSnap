plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = rootProject.ext["applicationId"].toString() + ".mapper"
    compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        minSdk = 28
    }
}

// Migrate to compilerOptions DSL for Kotlin 2.x
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.coroutines)
    implementation(libs.dexlib2)
    testImplementation(libs.junit)
}
