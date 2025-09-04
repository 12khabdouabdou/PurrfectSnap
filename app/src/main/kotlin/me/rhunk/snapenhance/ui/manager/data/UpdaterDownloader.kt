package me.rhunk.snapenhance.ui.manager.data

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.R
import me.rhunk.snapenhance.common.logger.AbstractLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object UpdaterDownloader {
    private const val NOTIFICATION_CHANNEL_ID = "updater_channel"
    private const val NOTIFICATION_ID = 1

    private data class AssetResult(val name: String, val url: String)
    private data class AbiChoice(val assetLabel: String, val desiredLibDir: String)

    private var installDeferred: CompletableDeferred<Int>? = null
    private lateinit var installLauncher: ActivityResultLauncher<Intent>

    fun register(launcher: ActivityResultLauncher<Intent>) {
        installLauncher = launcher
    }

    fun completeInstall(resultCode: Int) {
        installDeferred?.complete(resultCode)
        installDeferred = null
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    private fun detectAbiChoice(): AbiChoice {
        val abis = (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",").lowercase(Locale.ROOT)
        return when {
            "arm64" in abis || "v8a" in abis || "aarch64" in abis || "armv8" in abis -> AbiChoice("arm64-v8a", "arm64-v8a")
            "armeabi-v7a" in abis || "armv7" in abis || "armeabi" in abis || "v7a" in abis -> AbiChoice("armeabi-v7a", "armeabi-v7a")
            else -> AbiChoice("arm64-v8a", "arm64-v8a")
        }
    }

    private suspend fun installPackage(context: Context, file: File, packageName: String): Boolean {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        val deferred = CompletableDeferred<Int>()
        installDeferred = deferred
        installLauncher.launch(intent)
        val resultCode = deferred.await()
        return resultCode == Activity.RESULT_OK
    }

    private fun fetchLatestDebugBuild(abi: String): AssetResult? {
        return runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/rhunk/SnapEnhance/releases")
                .build()
            val releases = client.newCall(request).execute().use {
                if (!it.isSuccessful) throw Throwable("Failed to fetch releases: ${it.code}")
                JsonParser.parseString(it.body.string()).asJsonArray
            }
            val latestRelease = releases.firstOrNull()?.asJsonObject ?: throw Throwable("No releases found")
            if (latestRelease.get("prerelease")?.asBoolean != true) {
                throw Throwable("Latest release is not a pre-release")
            }

            val assets = latestRelease.getAsJsonArray("assets") ?: throw Throwable("No assets found in release")
            val asset = assets.map { it.asJsonObject }.find {
                it.get("name")?.asString == "snapenhance-android-$abi-debug.apk"
            } ?: throw Throwable("No debug apk found in release for abi $abi")

            AssetResult(
                name = asset.get("name").asString,
                url = asset.get("browser_download_url").asString
            )
        }.onFailure {
            AbstractLogger.directError("Failed to fetch latest debug build", it)
        }.getOrNull()
    }

    private fun downloadWithProgress(context: Context, url: String, toDir: File, onProgress: (Float) -> Unit, fileNameOverride: String? = null): File? {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Updater", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading Update")
            .setContentText("Download in progress")
            .setSmallIcon(R.drawable.ic_github)
            .setOngoing(true)
            .setProgress(100, 0, false)

        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())

        var result: File? = null
        val fileName = fileNameOverride ?: url.substringAfterLast("/")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("Download failed: HTTP ${resp.code}")
                }
                val out = File(toDir, fileName)
                val size = resp.body?.contentLength() ?: -1L
                var total = 0L
                resp.body?.byteStream()?.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            total += read
                            if (size > 0) {
                                val progress = (total * 100 / size).toInt()
                                onProgress(total.toFloat() / size.toFloat())
                                notificationBuilder.setProgress(100, progress, false)
                                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                            }
                        }
                    }
                }
                result = out
            }
        } catch (e: Exception) {
            AbstractLogger.directError("Download exception", e)
            notificationManager.cancel(NOTIFICATION_ID)
            return null
        } finally {
            notificationBuilder.setContentText("Download complete")
                .setProgress(0, 0, false)
                .setOngoing(false)
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
        }
        return result
    }

    fun downloadAndInstall(
        scope: CoroutineScope,
        context: Context,
        onDownloadStart: () -> Unit,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val abi = detectAbiChoice()
                val asset = fetchLatestDebugBuild(abi.desiredLibDir) ?: throw Exception("Failed to fetch latest debug build")
                val cacheDir = context.cacheDir

                launch(Dispatchers.Main) {
                    onDownloadStart()
                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                }

                val apkFile = downloadWithProgress(context, asset.url, cacheDir, onProgress, asset.name) ?: throw Exception("Failed to download update")

                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Download completed", Toast.LENGTH_SHORT).show()
                }
                if (installPackage(context, apkFile, context.packageName)) {
                    onSuccess()
                } else {
                    throw Exception("Failed to install update")
                }
            }.onFailure {
                val message = it.message ?: "Unknown error"
                AbstractLogger.directError("Update failed", it)
                launch(Dispatchers.Main) {
                    onFailure(message)
                }
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }
}
