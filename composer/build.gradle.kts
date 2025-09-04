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
    doLast {
        if (Os.isFamily(Os.FAMILY_WINDOWS))  {
            // npx tsc
            providers.exec {
                commandLine("npx.cmd", "--yes", "tsc", "--project", "tsconfig.json")
            }.result.get()
            // npx rollup
            providers.exec {
                commandLine("npx.cmd", "--yes", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs")
            }.result.get()
        } else {
            // npx tsc
            providers.exec {
                commandLine("npx", "--yes", "tsc", "--project", "tsconfig.json")
            }.result.get()
            // npx rollup
            providers.exec {
                commandLine("npx", "--yes", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs")
            }.result.get()
        }
        project.copy {
            from("build/loader.js")
            into("build/assets/composer")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(tasks.named("compileTypeScript"))
}
