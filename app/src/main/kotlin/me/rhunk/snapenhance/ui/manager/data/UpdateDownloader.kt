package me.rhunk.snapenhance.ui.manager.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(android.net.Uri.parse(downloadUrl))
        request.setTitle(fileName)
        request.setDescription("Downloading SnapEnhance Update")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val destination = File(context.externalCacheDir, fileName)
        request.setDestinationUri(android.net.Uri.fromFile(destination))

        val downloadId = downloadManager.enqueue(request)
        Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        downloadState.value = DownloadState.DOWNLOADING

        scope.launch(Dispatchers.IO) {
            var downloading = true
            while (downloading) {
                val q = DownloadManager.Query()
                q.setFilterById(downloadId)
                val cursor = downloadManager.query(q)
                if (cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (bytesTotal > 0) {
                        val progress = (bytesDownloaded * 100f / bytesTotal) / 100f
                        downloadProgress.value = progress
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        downloading = false
                        downloadState.value = if (status == DownloadManager.STATUS_SUCCESSFUL) DownloadState.COMPLETED else DownloadState.FAILED
                        downloadProgress.value = 0f
                    }
                }
                cursor.close()
                delay(250)
            }
        }

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    Toast.makeText(context, "Download completed", Toast.LENGTH_SHORT).show()
                    runCatching {
                        val downloadedFile = destination
                        val unzipDir = File(context.externalCacheDir, "update")
                        if (unzipDir.exists()) {
                            unzipDir.deleteRecursively()
                        }
                        unzipDir.mkdirs()
                        unzip(downloadedFile, unzipDir)
                        val apkFile = unzipDir.walk().find { it.isFile && it.extension == "apk" }
                        if (apkFile == null) {
                            throw Exception("No APK found in the downloaded file")
                        }
                        val uri = FileProvider.getUriForFile(context, "me.rhunk.snapenhance.fileprovider", apkFile)
                        val installIntent = Intent(Intent.ACTION_VIEW)
                        installIntent.setDataAndType(uri, "application/vnd.android.package-archive")
                        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(installIntent)
                    }.onFailure {
                        it.printStackTrace()
                        Toast.makeText(context, "Failed to install update. Check logs for more details.", Toast.LENGTH_SHORT).show()
                        downloadState.value = DownloadState.FAILED
                    }
                    context.unregisterReceiver(this)
                    // Reset state after a delay to allow UI to update
                    scope.launch {
                        delay(2000)
                        downloadState.value = DownloadState.IDLE
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }
}
