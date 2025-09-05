import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
    id("kotlin-parcelize")
}

android {
    namespace = rootProject.ext["applicationId"].toString()
    compileSdk = 35

    buildFeatures {
        aidl = true
        compose = true
    }

    defaultConfig {
        applicationId = rootProject.ext["applicationId"].toString()
        versionCode = rootProject.ext["appVersionCode"].toString().toInt()
        versionName = rootProject.ext["appVersionName"].toString()
        minSdk = 28
        targetSdk = 34
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles += file("proguard-rules.pro")
        }
        debug {
            (properties["debug_flavor"] == null).also {
                isDebuggable = !it
                isMinifyEnabled = it
                isShrinkResources = it
            }
            proguardFiles += file("proguard-rules.pro")
        }
    }

    flavorDimensions += "abi"

    productFlavors {
        create("core") {
            dimension = "abi"
        }
        create("armv8") {
            ndk {
                abiFilters += "arm64-v8a"
            }
            dimension = "abi"
        }
        create("armv7") {
            ndk {
                abiFilters += "armeabi-v7a"
            }
            dimension = "abi"
        }
        create("all") {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            dimension = "abi"
        }
    }

    packaging {
        jniLibs {
            excludes += "**/*_neon.so"
        }
        resources {
            excludes += "DebugProbesKt.bin"
            excludes += "okhttp3/internal/publicsuffix/**"
            excludes += "META-INF/*.version"
            excludes += "META-INF/services/**"
            excludes += "META-INF/*.kotlin_builtins"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    properties["debug_flavor"]?.let { debugFlavor ->
        android.productFlavors.find { pf -> pf.name == debugFlavor.toString() }?.setIsDefault(true)
    }

    applicationVariants.all {
        outputs.map { it as BaseVariantOutputImpl }.forEach { outputVariant ->
            outputVariant.outputFileName = when {
                name.startsWith("core") -> "core.apk"
                else -> "snapenhance_${rootProject.ext["appVersionName"]}-${outputVariant.name}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// New Kotlin compilerOptions DSL replaces deprecated kotlinOptions { jvmTarget = "21" }
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

androidComponents {
    onVariants(selector().withFlavor("abi", "core")) {
        it.packaging.jniLibs.apply {
            pickFirsts.set(listOf("**/lib${rootProject.ext["buildHash"]}.so"))
            excludes.set(listOf("**/*.so"))
        }
    }
}

dependencies {
    fun fullImplementation(dependencyNotation: Any) {
        compileOnly(dependencyNotation)
        for (flavorName in listOf("armv8", "armv7", "all")) {
            dependencies.add("${flavorName}Implementation", dependencyNotation)
        }
    }

    implementation(project(":core"))
    implementation(project(":common"))

    implementation(libs.androidx.documentfile)
    implementation(libs.gson)
    implementation(libs.smart.exception.java)
    implementation(files("libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar"))
    implementation(libs.osmdroid.android)
    implementation(libs.rhino)
    implementation(libs.androidx.activity.ktx)

    // Compose (via BOM + libs catalog)
    fullImplementation(platform(libs.androidx.compose.bom))
    fullImplementation(libs.bcprov.jdk18on)
    fullImplementation(libs.androidx.navigation.compose)
    fullImplementation(libs.androidx.material.icons.core)
    fullImplementation(libs.androidx.material.ripple)
    fullImplementation(libs.androidx.material.icons.extended)
    fullImplementation(libs.androidx.material3)
    fullImplementation(libs.coil.compose)
    fullImplementation(libs.coil.video)
    fullImplementation(libs.colorpicker.compose)
    fullImplementation(libs.androidx.ui.tooling.preview)

    properties["debug_flavor"]?.let {
        debugImplementation(libs.androidx.ui.tooling)
    }

    // AppCompat / Material
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")

    // Core + OkHttp
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation(libs.androidx.work)
}

afterEvaluate {
    // Replace deprecated capitalized() with replaceFirstChar + Locale.ROOT
    val installTask = properties["debug_flavor"]?.toString()?.let { flavor ->
        val cap = flavor.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } // replaces capitalized()
        tasks.findByName("install${cap}Debug")
    }

    installTask?.doLast {
        runCatching {
            // Use ProcessBuilder instead of deprecated project.exec or ProviderFactory.exec
            val proc = ProcessBuilder("adb", "devices")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            proc.waitFor()

            val devices = output.lines()
                .drop(1)
                .mapNotNull { line ->
                    line.split("\t").firstOrNull()?.takeIf { it.isNotEmpty() }
                }

            runBlocking {
                devices.forEach { device ->
                    launch {
                        ProcessBuilder(
                            "adb", "-s", device, "shell", "am", "force-stop",
                            properties["debug_package_name"].toString()
                        ).inheritIO().start().waitFor()

                        delay(500)

                        ProcessBuilder(
                            "adb", "-s", device, "shell", "am", "start",
                            properties["debug_package_name"].toString()
                        ).inheritIO().start().waitFor()
                    }
                }
            }
        }
    }
}

properties["debug_flavor"]?.let {
    configurations.all {
        exclude(group = "androidx.profileinstaller", "profileinstaller")
    }
}
