import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = rootProject.ext["applicationId"].toString() + ".composer"
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
    doLast {
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            // TypeScript compile
            providers.exec {
                commandLine("npx.cmd", "-y", "-p", "typescript", "tsc", "--project", "tsconfig.json")
            }.result.get()
            // Rollup bundle
            providers.exec {
                commandLine("npx.cmd", "-y", "-p", "rollup", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs")
            }.result.get()
        } else {
            // TypeScript compile
            providers.exec {
                commandLine("npx", "-y", "-p", "typescript", "tsc", "--project", "tsconfig.json")
            }.result.get()
            // Rollup bundle
            providers.exec {
                commandLine("npx", "-y", "-p", "rollup", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs")
            }.result.get()
        }

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
