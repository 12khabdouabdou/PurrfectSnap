import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".common"
    compileSdk = 34

    // Ship generated assets from the local build output
    sourceSets {
        getByName("main") {
            assets.srcDirs("build/assets")
        }
    }

    // Align Java toolchain with Kotlin
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Kotlin 2.x compilerOptions DSL
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// Compile/bundle the TypeScript loader using npx with package selection
// - typescript: exposes the 'tsc' binary
// - rollup: exposes the 'rollup' binary
tasks.register("compileTypeScript") {
    // Run only if this module actually has a tsconfig.json
    onlyIf { project.file("tsconfig.json").exists() }

    doLast {
        val npx = if (Os.isFamily(Os.FAMILY_WINDOWS)) "npx.cmd" else "npx"

        fun run(vararg args: String) {
            val exec = providers.exec { commandLine(npx, *args) }
            // Surface command output for CI logs
            val stdout = exec.standardOutput.asText.get()
            val stderr = exec.standardError.asText.get()
            if (stdout.isNotBlank()) logger.lifecycle(stdout)
            if (stderr.isNotBlank()) logger.error(stderr)
            exec.result.get() // fail the task on non-zero exit status
        }

        // Always resolve tools from their packages to avoid the wrong 'tsc' shim
        run("-y", "-p", "typescript", "tsc", "--project", "tsconfig.json") // npx -p typescript tsc ... [1]
        run("-y", "-p", "rollup", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs") // npx -p rollup rollup ... [1]

        // Copy loader output into packaged assets
        project.copy {
            from("build/loader.js")
            into("build/assets/composer")
        }
    }
}

// Ensure TS step runs before Android build
tasks.named("preBuild").configure {
    dependsOn("compileTypeScript")
}
