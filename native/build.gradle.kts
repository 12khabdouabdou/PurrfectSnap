plugins {
    alias(libs.plugins.rust.android)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val nativeName = rootProject.ext.get("buildHash")

android {
    namespace = rootProject.ext["applicationId"].toString() + ".nativelib"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    // Keep the dynamic NDK version fallback
    ndkVersion = System.getenv("ANDROID_NDK_HOME")?.trimEnd('/')?.substringAfterLast("/") ?: "27.1.12297006"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "NATIVE_NAME", "\"$nativeName\".toString()")
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Migrate to compilerOptions DSL for Kotlin 2.x
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

cargo {
    module = "rust"
    libname = nativeName.toString()
    targetIncludes = arrayOf("libsnapenhance.so")
    profile = "release"
    targets = listOf("arm64", "arm")
}

fun getNativeFiles() =
    File(projectDir, "build/rustJniLibs/android").listFiles()?.flatMap { abiFolder ->
        abiFolder.takeIf { it.isDirectory }?.listFiles()?.toList() ?: emptyList()
    }

val buildAndRename by tasks.registering {
    dependsOn("cargoBuild")
    doLast {
        getNativeFiles()?.forEach { file ->
            if (file.name.endsWith(".so")) {
                println("Renaming ${file.absolutePath}")
                file.renameTo(File(file.parent, "lib$nativeName.so"))
            }
        }
    }
}

val cleanNatives by tasks.registering {
    finalizedBy(buildAndRename)
    doFirst {
        println("Cleaning native files")
        getNativeFiles()?.forEach { file ->
            file.deleteRecursively()
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(cleanNatives)
}
