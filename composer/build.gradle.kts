import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".composer"
    compileSdk = 34

    // Ensure assets folder is wired for the generated bundle
    sourceSets {
        getByName("main") {
            assets.srcDirs("build/assets")
        }
    }

    // Keep Java toolchain aligned with Kotlin jvmTarget
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// New Kotlin compilerOptions DSL for Kotlin 2.x (replaces deprecated android.kotlinOptions)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.register("compileTypeScript") {
    // Run only if this module actually has a tsconfig.json
    onlyIf { project.file("tsconfig.json").exists() }

    doLast {
        val npx = if (Os.isFamily(Os.FAMILY_WINDOWS)) "npx.cmd" else "npx"

        fun run(vararg args: String) {
            val exec = providers.exec { commandLine(npx, *args) }
            // Capture and surface output for CI debuggability
            val stdout = exec.standardOutput.asText.get()
            val stderr = exec.standardError.asText.get()
            if (stdout.isNotBlank()) logger.lifecycle(stdout)
            if (stderr.isNotBlank()) logger.error(stderr)
            exec.result.get() // fail task if exit code != 0
        }

        // Always run the correct tsc and rollup via npx package selection
        run("-y", "-p", "typescript", "tsc", "--project", "tsconfig.json") // runs the 'tsc' binary from the 'typescript' package [19]
        run("-y", "-p", "rollup", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs") // runs 'rollup' from the 'rollup' package [19]

        project.copy {
            from("build/loader.js")
            into("build/assets/composer")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(tasks.named("compileTypeScript"))
}
