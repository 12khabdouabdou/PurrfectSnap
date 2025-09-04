import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec

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

// Helper to pick the correct npx launcher on Windows/non-Windows
val npxCmd = if (Os.isFamily(Os.FAMILY_WINDOWS)) "npx.cmd" else "npx"

// Optional: allow skipping TS in CI via -PskipTs=true
val skipTs = providers.gradleProperty("skipTs").map { it.equals("true", ignoreCase = true) }.orElse(false)

// Compile TypeScript with npx, resolving the compiler from the 'typescript' package
tasks.register<Exec>("tscCompile") {
    onlyIf {
        !skipTs.get() && project.file("tsconfig.json").exists()
    }
    workingDir = project.projectDir
    environment("CI", "true")
    commandLine(npxCmd, "-y", "-p", "typescript", "tsc", "--project", "tsconfig.json") // runs 'tsc' from the 'typescript' package
}

// Bundle with rollup via npx, resolving from the 'rollup' package
tasks.register<Exec>("rollupBundle") {
    onlyIf {
        !skipTs.get() &&
        project.file("tsconfig.json").exists() && // require TS config if rollup depends on compiled output
        (project.file("rollup.config.js").exists() || project.file("rollup.config.mjs").exists())
    }
    dependsOn("tscCompile")
    workingDir = project.projectDir
    environment("CI", "true")
    // Prefer rollup.config.js; if using .mjs, adjust the path here
    commandLine(npxCmd, "-y", "-p", "rollup", "rollup", "--config", "rollup.config.js", "--bundleConfigAsCjs")
}

// Aggregate task: runs tsc then rollup and copies the output into assets
tasks.register("compileTypeScript") {
    // Skip entirely if skipTs=true or no tsconfig
    onlyIf {
        !skipTs.get() && project.file("tsconfig.json").exists()
    }
    dependsOn("tscCompile", "rollupBundle")
    doLast {
        // Copy built loader into packaged assets if present
        val loaderFile = project.file("build/loader.js")
        if (loaderFile.exists()) {
            project.copy {
                from(loaderFile)
                into("build/assets/composer")
            }
        } else {
            logger.warn("compose/loader.js not found at build/loader.js (rollup may have failed or output path differs)")
        }
    }
}

// Ensure TS step runs before Android build
tasks.named("preBuild").configure {
    dependsOn("compileTypeScript")
}
