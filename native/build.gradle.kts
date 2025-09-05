plugins {
    alias(libs.plugins.rust.android)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

// Set native library name statically to 'snapenhance_native'
val nativeName = "snapenhance_native"

android {
    namespace = rootProject.ext["applicationId"].toString() + ".nativelib"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = System.getenv("ANDROID_NDK_HOME")?.trimEnd('/')?.substringAfterLast("/") ?: "27.1.12297006"
    
    buildFeatures {
        buildConfig = true
    }
    
    defaultConfig {
        buildConfigField("String", "NATIVE_NAME", "\"$nativeName\"")
        minSdk = 28
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    
    kotlinOptions {
        jvmTarget = "21"
    }
    
    // Prevent AGP from stripping/renaming native libraries
    packagingOptions {
        doNotStrip "**/libsnapenhance_native.so"
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

cargo {
    module = "rust"
    libname = nativeName
    targetIncludes = arrayOf("libsnapenhance_native.so")
    profile = "release"
    targets = listOf("arm64", "arm")
}

// Remove all dynamic hash/renaming logic - use static name only
tasks.named("preBuild").configure {
    // Remove dependency on cleanNatives task
}
