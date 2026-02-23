// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.rust.android) apply false
}

// Remove these lines:
// var versionName = "1.0.0"
// var versionCode = 210

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GetVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:OutputFile
    abstract val versionFile: RegularFileProperty

    @TaskAction
    fun writeVersion() {
        val file = versionFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(versionName.get())
    }
}

tasks.register<GetVersionTask>("getVersion") {
    versionName.set(providers.gradleProperty("APP_VERSION_NAME").orElse("1.0.0"))
    versionFile.set(project.layout.projectDirectory.file("app/build/version.txt"))
}

// You can still set these for legacy use by submodules or scripts:
rootProject.ext.set("appVersionName", providers.gradleProperty("APP_VERSION_NAME").orElse("1.3.2").get())
rootProject.ext.set("appVersionCode", providers.gradleProperty("APP_VERSION_CODE").orElse("272").get().toInt())
rootProject.ext.set("applicationId", "me.eternal.purrfectsnap")
rootProject.ext.set(
    "buildHash",
    properties["debug_build_hash"]
        ?: java.security.SecureRandom()
            .nextLong(Long.MAX_VALUE / 1000L, Long.MAX_VALUE)
            .toString(16)
)
