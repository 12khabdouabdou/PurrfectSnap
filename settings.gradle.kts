import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipInputStream

fun ensureAndroidSdk(rootDir: File) {
    val localProperties = File(rootDir, "local.properties")
    val props = Properties()
    if (localProperties.exists()) {
        localProperties.inputStream().use { props.load(it) }
    }

    val sdkPath = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: props.getProperty("sdk.dir")

    if (sdkPath != null && File(sdkPath).exists()) {
        println("Android SDK found at $sdkPath, skipping provisioning.")
        return
    }

    println("Android SDK not found. Provisioning a local version...")

    val sdkDir = File(rootDir, ".gradle/android-sdk").apply { mkdirs() }
    val os = System.getProperty("os.name").lowercase()
    val toolUrl = when {
        os.contains("win") -> "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
        os.contains("mac") -> "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
        else -> "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    }

    val cmdlineToolsZip = File(sdkDir, "cmdline-tools.zip")
    if (!cmdlineToolsZip.exists()) {
        println("Downloading Android command-line tools...")
        URI(toolUrl).toURL().openStream().use { Files.copy(it, cmdlineToolsZip.toPath()) }
    }

    val cmdlineToolsDir = File(sdkDir, "cmdline-tools/latest")
    if (!cmdlineToolsDir.exists()) {
        println("Unzipping command-line tools...")
        cmdlineToolsDir.mkdirs()
        ZipInputStream(cmdlineToolsZip.inputStream()).use {
            var entry = it.nextEntry
            while (entry != null) {
                val newFile = File(cmdlineToolsDir, entry.name.substringAfter('/'))
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile.mkdirs()
                    FileOutputStream(newFile).use { fos -> it.copyTo(fos) }
                }
                entry = it.nextEntry
            }
        }
    }

    val sdkManager = File(cmdlineToolsDir, "bin/sdkmanager" + if (os.contains("win")) ".bat" else "")
    sdkManager.setExecutable(true)

    fun runSdkManager(args: String) {
        println("Running sdkmanager $args")
        val process = ProcessBuilder(sdkManager.absolutePath, *args.split(" ").toTypedArray())
            .directory(sdkDir)
            .redirectErrorStream(true)
            .start()

        // Pipe "y" to accept licenses
        process.outputStream.bufferedWriter().apply {
            write("y\n".repeat(20))
            flush()
            close()
        }

        process.inputStream.bufferedReader().forEachLine { println(it) }
        process.waitFor()
    }

    runSdkManager("--licenses")
    runSdkManager("platform-tools platforms;android-35 build-tools;35.0.0 ndk;27.2.12479018")

    val ndkDir = File(sdkDir, "ndk/27.2.12479018")
    props["sdk.dir"] = sdkDir.absolutePath
    props["ndk.dir"] = ndkDir.absolutePath
    localProperties.outputStream().use { props.store(it, null) }

    System.setProperty("android.home", sdkDir.absolutePath)
    System.setProperty("android.sdk.root", sdkDir.absolutePath)

    println("Android SDK and NDK provisioned successfully.")
}

ensureAndroidSdk(rootDir)

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}



rootProject.name = "SnapEnhance"
include(":common")
include(":core")
include(":composer")
include(":app")
include(":mapper")
include(":native")
include(":manager")