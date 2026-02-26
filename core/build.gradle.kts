plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".core"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "NATIVE_KEY", "\"${System.getenv("NATIVE_KEY") ?: ""}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    compileOnly(files("libs/LSPosed-api-1.0-SNAPSHOT.jar"))
    implementation(libs.coroutines)
    implementation(libs.recyclerview)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.rhino)

    implementation(project(":common"))
    implementation(project(":mapper"))
    implementation(project(":native"))
    implementation(project(":valdi"))

    implementation(libs.androidx.activity.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.ripple)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.hiddenapibypass)
    implementation(libs.osmdroid.android)
    implementation(libs.osmbonuspack)
    implementation(libs.colorpicker.compose)
    testImplementation(libs.junit)
}
