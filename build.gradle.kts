// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.rust.android) apply false
}

// Remove these lines:
// var versionName = "2.1.0"
// var versionCode = 210

import org.gradle.api.provider.Property
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class GetVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @TaskAction
    fun writeVersion() {
        val versionFile = project.layout.projectDirectory.file("app/build/version.txt").asFile
        versionFile.parentFile.mkdirs()
        versionFile.writeText(versionName.get())
    }
}

tasks.register<GetVersionTask>("getVersion") {
    // Value comes from gradle.properties; falls back to 2.1.0 if not set.
    versionName.set(providers.gradleProperty("APP_VERSION_NAME").orElse("2.1.0"))
}

// You can still set these for legacy use by submodules or scripts:
rootProject.ext.set("appVersionName", providers.gradleProperty("APP_VERSION_NAME").orElse("2.1.0").get())
rootProject.ext.set("appVersionCode", 210)
rootProject.ext.set("applicationId", "me.rhunk.snapenhance")
rootProject.ext.set(
    "buildHash",
    properties["debug_build_hash"]
        ?: java.security.SecureRandom()
            .nextLong(Long.MAX_VALUE / 1000L, Long.MAX_VALUE)
            .toString(16)
)
