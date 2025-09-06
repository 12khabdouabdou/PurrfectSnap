package me.rhunk.snapenhance.ui.manager.data

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tonyodev.fetch2.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.RemoteSideContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object UpdateDownloader {
    enum class DownloadState {
        IDLE,
        DOWNLOADING,
        COMPLETED,
        FAILED
    }

    val downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadProgress = MutableStateFlow(0f)

    private var fetch: Fetch? = null
    private var listener: FetchListener? = null

    private fun getInstance(context: Context): Fetch {
        if (fetch == null) {
            fetch = (context.applicationContext as RemoteSideContext).fetch
        }
        return fetch!!
    }

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

    fun downloadAndInstall(context: Context, downloadUrl: String, fileName: String, scope: CoroutineScope) {
        val fetch = getInstance(context)
        val filePath = File(context.externalCacheDir, fileName).path
        val request = Request(downloadUrl, filePath).apply {
            priority = Priority.HIGH
            networkType = NetworkType.ALL
        }

        listener?.let { fetch.removeListener(it) }

        listener = object : AbstractFetchListener() {
            override fun onAdded(download: Download) {
                // Optional: mark as preparing; will switch to DOWNLOADING on onQueued
                downloadState.value = DownloadState.DOWNLOADING
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
                Toast.makeText(context, "Download completed", Toast.LENGTH_SHORT).show()
                runCatching {
                    val downloadedFile = File(download.file)
                    val unzipDir = File(context.externalCacheDir, "update")
                    if (unzipDir.exists()) {
                        unzipDir.deleteRecursively()
                    }
                    unzipDir.mkdirs()
                    unzip(downloadedFile, unzipDir)
                    val apkFile = unzipDir.walk().find { it.isFile && it.extension == "apk" }
                        ?: throw Exception("No APK found in the downloaded file")

                    val uri = FileProvider.getUriForFile(context, "me.rhunk.snapenhance.fileprovider", apkFile)
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(installIntent)
                }.onFailure {
                    it.printStackTrace()
                    Toast.makeText(context, "Failed to install update. Check logs for more details.", Toast.LENGTH_SHORT).show()
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
