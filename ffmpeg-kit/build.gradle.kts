plugins {
    id("com.android.library")
}

android {
    namespace = "me.rhunk.snapenhance.ffmpegkit"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}

dependencies {
    implementation(files("ffmpeg-kit-full-gpl-6.0-2.LTS.aar"))
}