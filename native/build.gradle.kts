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
    
    // Modern AGP 8 packaging configuration
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libsnapenhance_native.so"
            useLegacyPackaging = true
        }
    }
}
