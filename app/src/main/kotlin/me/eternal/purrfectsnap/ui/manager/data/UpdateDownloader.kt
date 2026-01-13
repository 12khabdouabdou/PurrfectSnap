package me.eternal.purrfectsnap.ui.manager.data

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tonyodev.fetch2.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.RemoteSideContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object UpdateDownloader {
    private const val TAG = "UpdateDownloader"
    private var fetch: Fetch? = null
    private var listener: FetchListener? = null

    private fun getInstance(context: RemoteSideContext): Fetch {
        fetch?.let { return it }
        fetch = run {
            val fetchConfiguration = FetchConfiguration.Builder(context.androidContext)
                .setDownloadConcurrentLimit(3)
                .build()
            Fetch.getInstance(fetchConfiguration)
        }
        return fetch!!
    }
    enum class DownloadState {
        IDLE,
        DOWNLOADING,
        COMPLETED,
        FAILED
    }

    val downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadProgress = MutableStateFlow(0f)


    private fun unzip(zipFile: File, targetDirectory: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val newFile = File(targetDirectory, zipEntry.name)
                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zipEntry = zis.nextEntry
            }
        }
    }

    private fun resolveDownloadedApk(
        remoteContext: RemoteSideContext,
        downloadedFile: File
    ): File {
        val context = remoteContext.androidContext
        if (downloadedFile.extension.equals("zip", ignoreCase = true)) {
            val unzipDir = File(context.externalCacheDir, "update")
            if (unzipDir.exists()) unzipDir.deleteRecursively()
            unzipDir.mkdirs()
            remoteContext.log.info(
                "Extracting update archive ${downloadedFile.absolutePath} -> ${unzipDir.absolutePath}",
                TAG
            )
            unzip(downloadedFile, unzipDir)
            return unzipDir.walk().firstOrNull { it.isFile && it.extension.equals("apk", true) }
                ?: throw IllegalStateException("No APK found in the downloaded archive")
        }

        if (!downloadedFile.extension.equals("apk", ignoreCase = true)) {
            remoteContext.log.warn(
                "Downloaded file is not an APK or ZIP (${downloadedFile.name}); attempting installation anyway.",
                TAG
            )
        }
        return downloadedFile
    }

    fun downloadAndInstall(
        remoteContext: RemoteSideContext,
        downloadUrl: String,
        fileName: String,
        scope: CoroutineScope
    ) {
        val context = remoteContext.androidContext
        val fetch = getInstance(remoteContext)
        val filePath = File(context.externalCacheDir, fileName).path
        remoteContext.log.info("Starting update download from $downloadUrl -> $filePath", TAG)
        val request = Request(downloadUrl, filePath).apply {
            priority = Priority.HIGH
            networkType = NetworkType.ALL
        }
        listener?.let { fetch.removeListener(it) }
        listener = object : AbstractFetchListener() {
            override fun onAdded(download: Download) {
                downloadState.value = DownloadState.DOWNLOADING
                remoteContext.log.info("Queued update download: ${download.file}", TAG)
            }

            override fun onQueued(download: Download, waitingOnNetwork: Boolean) {
                downloadState.value = DownloadState.DOWNLOADING
                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
            }

            override fun onProgress(download: Download, etaInMilliSeconds: Long, downloadedBytesPerSecond: Long) {
                downloadProgress.value = download.progress / 100f
            }

            override fun onCompleted(download: Download) {
                downloadState.value = DownloadState.COMPLETED
                runCatching {
                    val downloadedFile = File(download.file)
                    remoteContext.log.info(
                        "Download completed -> ${downloadedFile.absolutePath} (${downloadedFile.length()} bytes)",
                        TAG
                    )
                    Toast.makeText(context, "Download completed", Toast.LENGTH_SHORT).show()
                    val apkFile = resolveDownloadedApk(remoteContext, downloadedFile)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    remoteContext.log.info("Launching installer for ${apkFile.absolutePath}", TAG)
                    context.startActivity(installIntent)
                    scope.launch(Dispatchers.IO) {
                        delay(30_000)
                        runCatching { downloadedFile.delete() }
                        apkFile.parentFile
                            ?.takeIf { it.name == "update" }
                            ?.let { dir -> runCatching { dir.deleteRecursively() } }
                        remoteContext.log.info("Cleaned downloaded update files", TAG)
                    }
                }.onFailure {
                    Toast.makeText(context, "Failed to install update. Check logs for more details.", Toast.LENGTH_SHORT).show()
                    remoteContext.log.error("Failed to install downloaded update", it, TAG)
                    downloadState.value = DownloadState.FAILED
                }
                fetch.removeListener(this)
                scope.launch {
                    delay(2000)
                    downloadState.value = DownloadState.IDLE
                }
            }

            override fun onError(download: Download, error: Error, throwable: Throwable?) {
                downloadState.value = DownloadState.FAILED
                Toast.makeText(context, "Download failed: $error", Toast.LENGTH_SHORT).show()
                throwable?.let { remoteContext.log.error("Update download failed: $error", it, TAG) }
                    ?: remoteContext.log.error("Update download failed: $error", TAG)
                fetch.removeListener(this)
                scope.launch {
                    delay(2000)
                    downloadState.value = DownloadState.IDLE
                }
            }
        }
        fetch.addListener(listener!!)
        fetch.enqueue(request, { }, { })
    }
}
