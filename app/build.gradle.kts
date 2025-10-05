import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.gradle.configurationcache.extensions.capitalized
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
    id("kotlin-parcelize")
    id("com.google.devtools.ksp") version "2.2.20-2.0.3"
}

android {
    namespace = rootProject.ext["applicationId"].toString()
    compileSdk = 36
    buildFeatures {
        aidl = true
        compose = true
    }

    defaultConfig {
        applicationId = rootProject.ext["applicationId"].toString()
        versionCode = rootProject.ext["appVersionCode"].toString().toInt()
        versionName = rootProject.ext["appVersionName"].toString()
        minSdk = 28
        targetSdk = 36
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

androidComponents {
    onVariants { variant ->
        val flavorName = variant.flavorName
        if (properties["debug_flavor"] == flavorName) {
            // variant.makeDefault.set(true) // This is no longer supported
        }

        variant.outputs.forEach { output ->
            val variantOutput = output as com.android.build.api.variant.impl.VariantOutputImpl
            variantOutput.outputFileName.set(
                when {
                    variant.name.startsWith("core") -> "core.apk"
                    else -> "snapenhance_${rootProject.ext["appVersionName"]}-${variant.name}.apk"
                }
            )
        }
    }

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
    implementation(libs.osmdroid.android)
    implementation(libs.rhino)
    implementation(libs.androidx.activity.ktx)
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
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation(libs.androidx.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.fetch) {
        exclude(group = "androidx.room", module = "room-runtime")
    }
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // --- COMPOSE: explicit modern UI/Foundation for widthIn/wrapContentWidth ----
    fullImplementation(libs.foundation)
    fullImplementation(libs.ui)
    fullImplementation(libs.foundation.layout)

    // Animated navigation + transitions (Accompanist)
    fullImplementation(libs.accompanist.navigation.animation)
}

afterEvaluate {
    properties["debug_flavor"]?.toString()
        ?.let { flavor -> tasks.findByName("install${flavor.replaceFirstChar { it.uppercase() }}Debug") }
        ?.doLast {
            runCatching {
                val packageName = properties["debug_package_name"]?.toString() ?: return@runCatching
                val devicesProcess = ProcessBuilder("adb", "devices")
                    .redirectErrorStream(true)
                    .start()
                val devices = devicesProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.drop(1)
                        .mapNotNull { line ->
                            line.split("\t").firstOrNull()?.takeIf { it.isNotEmpty() }
                        }
                        .toList()
                }
                devicesProcess.waitFor()

                runBlocking {
                    devices.forEach { device ->
                        launch {
                            ProcessBuilder("adb", "-s", device, "shell", "am", "force-stop", packageName)
                                .redirectErrorStream(true)
                                .start()
                                .apply { waitFor() }
                            delay(500)
                            ProcessBuilder("adb", "-s", device, "shell", "am", "start", packageName)
                                .redirectErrorStream(true)
                                .start()
                                .apply { waitFor() }
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

