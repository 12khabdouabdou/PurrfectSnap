plugins {
  id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6"
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinAndroid)
}

val nativeName = "snapenhance_native"

android {
  namespace = rootProject.ext["applicationId"].toString() + ".nativelib"
  compileSdk = 34
  buildToolsVersion = "34.0.0"
  ndkVersion = System.getenv("ANDROID_NDK_HOME")?.trimEnd('/')?.substringAfterLast("/") ?: "27.1.12297006"

  buildFeatures { buildConfig = true }

  defaultConfig {
    buildConfigField("String", "NATIVE_NAME", "\"$nativeName\"")
    minSdk = 28
  }

  // Modern AGP 8 DSL
  packaging {
    jniLibs {
      keepDebugSymbols += "**/libsnapenhance_native.so"
      useLegacyPackaging = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

// Kotlin 2.x compiler DSL (alternative to deprecated kotlinOptions)
kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
  }
}

// rust-android-gradle configuration
cargo {
  module = "rust"                  // the crate folder under native/
  libname = nativeName             // produces libsnapenhance_native.so
  targetIncludes = arrayOf("libsnapenhance_native.so")
  profile = "release"
  targets = listOf("arm64", "arm")
}
