package me.eternal.purrfectsnap.download

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.eternal.purrfectsnap.RemoteSideContext
import me.eternal.purrfectsnap.bridge.DownloadCallback
import me.eternal.purrfectsnap.common.Constants
import me.eternal.purrfectsnap.common.ReceiversConfig
import me.eternal.purrfectsnap.common.data.FileType
import me.eternal.purrfectsnap.common.data.download.DownloadMediaType
import me.eternal.purrfectsnap.common.data.download.DownloadMetadata
import me.eternal.purrfectsnap.common.data.download.DownloadRequest
import me.eternal.purrfectsnap.common.data.download.InputMedia
import me.eternal.purrfectsnap.common.data.download.SplitMediaAssetType
import me.eternal.purrfectsnap.common.util.snap.MediaDownloaderHelper
import me.eternal.purrfectsnap.common.util.snap.RemoteMediaResolver
import me.eternal.purrfectsnap.core.features.impl.downloader.decoder.AttachmentType
import me.eternal.purrfectsnap.task.PendingTask
import me.eternal.purrfectsnap.task.PendingTaskListener
import me.eternal.purrfectsnap.task.Task
import me.eternal.purrfectsnap.task.TaskStatus
import me.eternal.purrfectsnap.task.TaskType
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class DownloadedFile(
    val file: File,
    val fileType: FileType
)

/**
 * DownloadProcessor handles the download requests of the user
 */
@OptIn(ExperimentalEncodingApi::class)
class DownloadProcessor (
    private val remoteSideContext: RemoteSideContext,
    private val callback: DownloadCallback
) {
    private data class GallerySaveResult(
        val uri: Uri,
        val alreadyDownloaded: Boolean = false
    )

    private val translation by lazy {
        remoteSideContext.translation.getCategory("download_processor")
    }

    private val gson by lazy { GsonBuilder().setPrettyPrinting().create() }

    private fun fallbackToast(message: Any) {
        android.os.Handler(remoteSideContext.androidContext.mainLooper).post {
            Toast.makeText(remoteSideContext.androidContext, message.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun callbackOnSuccess(path: String) = runCatching {
        callback.onSuccess(path)
    }.onFailure {
        fallbackToast(it)
    }

    private fun callbackOnFailure(message: String, throwable: String? = null) = runCatching {
        callback.onFailure(message, throwable)
    }.onFailure {
        fallbackToast("$message\n$throwable")
    }

    private fun callbackOnProgress(message: String) = runCatching {
        callback.onProgress(message)
    }.onFailure {
        fallbackToast(it)
    }

    private fun newFFMpegProcessor(pendingTask: PendingTask) = FFMpegProcessor.newFFMpegProcessor(remoteSideContext, pendingTask)

    suspend fun saveMediaToGallery(pendingTask: PendingTask, inputFile: File, metadata: DownloadMetadata) {
        if (coroutineContext.job.isCancelled) return

        runCatching {
            var fileType = FileType.fromFile(inputFile)

            if (fileType.isImage) {
                remoteSideContext.config.root.downloader.forceImageFormat.getNullable()?.let { format ->
                    val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath) ?: throw Exception("Failed to decode bitmap")
                    @Suppress("DEPRECATION") val compressFormat = when (format) {
                        "png" -> Bitmap.CompressFormat.PNG
                        "jpg" -> Bitmap.CompressFormat.JPEG
                        "webp" -> Bitmap.CompressFormat.WEBP
                        else -> throw Exception("Invalid image format")
                    }

                    pendingTask.updateProgress("Converting image to $format")
                    inputFile.outputStream().use {
                        bitmap.compress(compressFormat, 100, it)
                    }
                    fileType = FileType.fromFile(inputFile)
                }
            }

            val fileName = metadata.outputPath.substringAfterLast("/") + "." + fileType.fileExtension
            val configuredFolder = remoteSideContext.config.root.downloader.saveFolder.get().orEmpty().trim()
            val saveResult = if (configuredFolder.isBlank()) {
                saveToSystemDefault(fileName, fileType, inputFile, metadata)?.let { GallerySaveResult(it) }
            } else {
                runCatching {
                    saveToConfiguredFolder(
                        configuredFolder = configuredFolder,
                        fileName = fileName,
                        fileType = fileType,
                        inputFile = inputFile,
                        metadata = metadata,
                        pendingTask = pendingTask
                    )
                }.onFailure {
                    remoteSideContext.log.error("Failed to save to configured folder, falling back to system default", it)
                }.getOrNull() ?: saveToSystemDefault(fileName, fileType, inputFile, metadata)?.let {
                    GallerySaveResult(it)
                }
            } ?: throw Exception("Failed to save media (no output uri)")

            pendingTask.task.extra = saveResult.uri.toString()
            pendingTask.success()

            if (saveResult.alreadyDownloaded) {
                callbackOnFailure(translation["already_downloaded_toast"])
                return
            }

            runCatching {
                remoteSideContext.androidContext.sendBroadcast(Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE").apply {
                    data = saveResult.uri
                })
            }.onFailure {
                remoteSideContext.log.error("Failed to scan media file", it)
                callbackOnFailure(translation.format("failed_gallery_toast", "error" to it.toString()), it.message)
            }

            remoteSideContext.log.verbose("download complete")
            callbackOnSuccess(fileName)
        }.onFailure { exception ->
            remoteSideContext.log.error("Failed to save media to gallery", exception)
            callbackOnFailure(translation.format("failed_gallery_toast", "error" to exception.toString()), exception.message)
            pendingTask.fail("Failed to save media to gallery")
        }
    }

    private fun saveToConfiguredFolder(
        configuredFolder: String,
        fileName: String,
        fileType: FileType,
        inputFile: File,
        metadata: DownloadMetadata,
        pendingTask: PendingTask,
    ): GallerySaveResult {
        val outputFolder = DocumentFile.fromTreeUri(remoteSideContext.androidContext, Uri.parse(configuredFolder))
            ?: throw Exception("Failed to open output folder")

        val outputFileFolder = metadata.outputPath.let {
            if (it.contains("/")) {
                it.substringBeforeLast("/").split("/").fold(outputFolder) { folder, name ->
                    folder.findFile(name)
                        ?: folder.createDirectory(name)
                        ?: throw Exception("Failed to create output directory $name")
                }
            } else {
                outputFolder
            }
        }

        outputFileFolder.findFile(fileName)?.let { existingFile ->
            pendingTask.updateProgress("Comparing existing media")
            if (existingFile.length() != inputFile.length()) {
                existingFile.delete()
            } else {
                val existingInputStream = remoteSideContext.androidContext.contentResolver.openInputStream(existingFile.uri)
                    ?: throw Exception("Failed to open existing media for comparison")

                existingInputStream.use { currentExistingInputStream ->
                    val buffer1 = ByteArray(1024 * 1024)
                    val buffer2 = ByteArray(1024 * 1024)
                    var read1: Int
                    var read2: Int

                    inputFile.inputStream().use { inputStream ->
                        while (true) {
                            read1 = inputStream.read(buffer1)
                            read2 = currentExistingInputStream.read(buffer2)
                            if (read1 != read2 || (read1 > 0 && !buffersMatch(buffer1, buffer2, read1))) {
                                existingFile.delete()
                                return@let
                            }
                            if (read1 == -1) break
                        }
                    }
                }

                return GallerySaveResult(existingFile.uri, alreadyDownloaded = true)
            }
        }

        val outputFile = outputFileFolder.createFile(fileType.mimeType, fileName)
            ?: throw Exception("Failed to create output file $fileName")

        pendingTask.updateProgress("Saving media to gallery")
        val outputStream = remoteSideContext.androidContext.contentResolver.openOutputStream(outputFile.uri)
            ?: throw Exception("Failed to open output stream for $fileName")

        outputStream.use { currentOutputStream ->
            inputFile.inputStream().use { inputStream ->
                inputStream.copyTo(currentOutputStream)
            }
        }

        return GallerySaveResult(outputFile.uri)
    }

    private fun buffersMatch(left: ByteArray, right: ByteArray, length: Int): Boolean {
        for (index in 0 until length) {
            if (left[index] != right[index]) return false
        }
        return true
    }

    private fun saveToSystemDefault(
        fileName: String,
        fileType: FileType,
        inputFile: File,
        metadata: DownloadMetadata,
    ): Uri? {
        val subPath = metadata.outputPath.substringBeforeLast("/", missingDelimiterValue = "")
            .replace("\\", "/")
            .trim('/')
        val baseRelative = when {
            fileType.isImage -> Environment.DIRECTORY_PICTURES
            fileType.isVideo -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val relativePath = listOfNotNull(baseRelative, "PurrfectSnap", subPath.takeIf { it.isNotBlank() })
            .joinToString("/") + "/"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when {
                fileType.isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                fileType.isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, fileType.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val resolver = remoteSideContext.androidContext.contentResolver
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                inputFile.inputStream().use { it.copyTo(out) }
            } ?: return null
            uri
        } else {
            @Suppress("DEPRECATION")
            val baseDir = Environment.getExternalStoragePublicDirectory(baseRelative)
            val destDir = File(baseDir, "PurrfectSnap" + (if (subPath.isNotBlank()) "/$subPath" else ""))
            destDir.mkdirs()
            val destFile = File(destDir, fileName)
            FileOutputStream(destFile).use { out ->
                inputFile.inputStream().use { it.copyTo(out) }
            }
            runCatching {
                remoteSideContext.androidContext.sendBroadcast(
                    Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE").apply {
                        data = Uri.fromFile(destFile)
                    }
                )
            }
            Uri.fromFile(destFile)
        }
    }

    private fun createMediaTempFile(): File {
        return File.createTempFile("media", ".tmp")
    }

    private fun downloadInputMedias(pendingTask: PendingTask, downloadRequest: DownloadRequest) = runBlocking {
        val jobs = mutableListOf<Job>()
        val downloadedMedias = mutableMapOf<InputMedia, File>()
        var totalSize = 1L
        val inputMediaDownloadedBytes = mutableMapOf<InputMedia, Long>()
        val inputMediaProgress = ConcurrentHashMap<InputMedia, String>()

        fun updateDownloadProgress() {
            pendingTask.updateProgress(
                inputMediaProgress.values.joinToString("\n"),
                progress = (inputMediaDownloadedBytes.values.sum() * 100 / totalSize.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            )
        }

        downloadRequest.inputMedias.forEach { inputMedia ->
            fun setProgress(progress: String) {
                inputMediaProgress[inputMedia] = progress
                updateDownloadProgress()
            }

            fun handleInputStream(inputStream: InputStream, estimatedSize: Long = 0L) {
                createMediaTempFile().apply {
                    val decryptedInputStream = (inputMedia.encryption?.decryptInputStream(inputStream) ?: inputStream).buffered()
                    val buffer = ByteArray(1024 * 1024 * 2) // 2MB
                    var read: Int
                    var totalRead = 0L

                    outputStream().use { outputStream ->
                        while (decryptedInputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                            totalRead += read
                            inputMediaDownloadedBytes[inputMedia] = totalRead
                            setProgress("${totalRead / 1024}KB/${estimatedSize / 1024}KB")
                        }
                    }
                }.also { downloadedMedias[inputMedia] = it }
            }

            launch {
                when (inputMedia.type) {
                    DownloadMediaType.PROTO_MEDIA -> {
                        RemoteMediaResolver.downloadBoltMedia(Base64.UrlSafe.decode(inputMedia.content), decryptionCallback = { it }, resultCallback = { inputStream, length ->
                            totalSize += length
                            inputStream.use {
                                handleInputStream(it, estimatedSize = length)
                            }
                        })
                    }
                    DownloadMediaType.REMOTE_MEDIA -> {
                        with(URL(inputMedia.content).openConnection() as HttpURLConnection) {
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", Constants.USER_AGENT)
                            connect()
                            totalSize += contentLength.toLong()
                            inputStream.use {
                                handleInputStream(it, estimatedSize = contentLength.toLong())
                            }
                        }
                    }
                    DownloadMediaType.DIRECT_MEDIA -> {
                        val decoded = Base64.UrlSafe.decode(inputMedia.content)
                        totalSize += decoded.size.toLong()
                        handleInputStream(decoded.inputStream(), estimatedSize = decoded.size.toLong())
                    }
                    else -> {
                        File(inputMedia.content).inputStream().use {
                            totalSize += it.available().toLong()
                            handleInputStream(it, estimatedSize = it.available().toLong())
                        }
                    }
                }
            }.also { jobs.add(it) }
        }

        jobs.joinAll()
        downloadedMedias
    }

    private suspend fun downloadRemoteMedia(pendingTask: PendingTask, metadata: DownloadMetadata, downloadedMedias: Map<InputMedia, DownloadedFile>, downloadRequest: DownloadRequest) {
        downloadRequest.inputMedias.first().let { inputMedia ->
            val mediaType = inputMedia.type
            val media = downloadedMedias[inputMedia]!!

            if (!downloadRequest.isDashPlaylist) {
                if (inputMedia.attachmentType == AttachmentType.NOTE.key) {
                    remoteSideContext.config.root.downloader.forceVoiceNoteFormat.getNullable()?.let { format ->
                        val outputFile = File.createTempFile("voice_note", ".$format")
                        newFFMpegProcessor(pendingTask).execute(FFMpegProcessor.Request(
                            action = FFMpegProcessor.Action.CONVERSION,
                            inputs = listOf(media.file.absolutePath),
                            output = outputFile
                        ))
                        media.file.delete()
                        saveMediaToGallery(pendingTask, outputFile, metadata)
                        outputFile.delete()
                        return
                    }
                }

                saveMediaToGallery(pendingTask, media.file, metadata)
                media.file.delete()
                return
            }

            assert(mediaType == DownloadMediaType.REMOTE_MEDIA)

            val playlistXml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(media.file)
            val baseUrlNodeList = playlistXml.getElementsByTagName("BaseURL")
            for (i in 0 until baseUrlNodeList.length) {
                val baseUrlNode = baseUrlNodeList.item(i)
                val baseUrl = baseUrlNode.textContent
                baseUrlNode.textContent = "${RemoteMediaResolver.CF_ST_CDN_D}$baseUrl"
            }

            val dashOptions = downloadRequest.dashOptions!!

            val dashPlaylistFile = renameFromFileType(media.file, FileType.MPD)
            dashPlaylistFile.outputStream().use {
                TransformerFactory.newInstance().newTransformer().transform(DOMSource(playlistXml), StreamResult(it))
            }

            callbackOnProgress(translation.format("download_toast", "path" to dashPlaylistFile.nameWithoutExtension))
            val outputFile = File.createTempFile("dash", ".mp4")
            runCatching {
                newFFMpegProcessor(pendingTask).execute(FFMpegProcessor.Request(
                    action = FFMpegProcessor.Action.DOWNLOAD_DASH,
                    inputs = listOf(dashPlaylistFile.absolutePath),
                    output = outputFile,
                    startTime = dashOptions.offsetTime,
                    duration = dashOptions.duration
                ))
                saveMediaToGallery(pendingTask, outputFile, metadata)
            }.onFailure { exception ->
                if (coroutineContext.job.isCancelled) return@onFailure
                remoteSideContext.log.error("Failed to download dash media", exception)
                callbackOnFailure(translation.format("failed_processing_toast", "error" to exception.toString()), exception.message)
                pendingTask.fail("Failed to download dash media")
            }

            dashPlaylistFile.delete()
            outputFile.delete()
            media.file.delete()
        }
    }

    private fun renameFromFileType(file: File, fileType: FileType): File {
        val newFile = File(file.parentFile, file.nameWithoutExtension + "." + fileType.fileExtension)
        file.renameTo(newFile)
        return newFile
    }

    fun enqueue(downloadRequest: DownloadRequest, downloadMetadata: DownloadMetadata) {
        remoteSideContext.coroutineScope.launch {
            remoteSideContext.taskManager.getTaskByHash(downloadMetadata.mediaIdentifier)?.let { task ->
                remoteSideContext.log.debug("already queued or downloaded")

                if (task.status.isFinalStage()) {
                    if (task.status != TaskStatus.SUCCESS) return@let
                    // check if the media file has been deleted
                    if (task.type == TaskType.DOWNLOAD) {
                        val outputFile = runCatching {
                            DocumentFile.fromTreeUri(remoteSideContext.androidContext, Uri.parse(task.extra))
                        }.getOrNull()

                        if (outputFile != null && !outputFile.exists()) {
                            return@let
                        }
                    }
                    callbackOnFailure(translation["already_downloaded_toast"])
                    return@launch
                } else {
                    callbackOnFailure(translation["already_queued_toast"], null)
                }
                return@launch
            }

            callbackOnProgress(translation["download_started_toast"])
            remoteSideContext.log.debug("downloading media")
            val pendingTask = remoteSideContext.taskManager.createPendingTask(
                Task(
                    type = TaskType.DOWNLOAD,
                    title = downloadMetadata.downloadSource,
                    author = downloadMetadata.mediaAuthor,
                    hash = downloadMetadata.mediaIdentifier
                )
            ).apply {
                status = TaskStatus.RUNNING
                addListener(PendingTaskListener(onCancel = {
                    coroutineContext.job.cancel()
                }))
                updateProgress("Downloading...")
            }

            runCatching {
                if (downloadRequest.isAudioStream) {
                    val streamUrl = downloadRequest.inputMedias.first().content
                    val outputFile = File.createTempFile("audio_stream", ".mp3")

                    callbackOnProgress("Downloading audio stream")
                    pendingTask.updateProgress("Downloading audio stream")
                    newFFMpegProcessor(pendingTask).execute(FFMpegProcessor.Request(
                        action = FFMpegProcessor.Action.DOWNLOAD_AUDIO_STREAM,
                        inputs = listOf(streamUrl),
                        output = outputFile,
                        audioStreamFormat = downloadRequest.audioStreamFormat
                    ))
                    saveMediaToGallery(pendingTask, outputFile, downloadMetadata)
                    return@launch
                }

                //first download all input medias into cache
                val downloadedMedias = downloadInputMedias(pendingTask, downloadRequest).map {
                    it.key to DownloadedFile(it.value, FileType.fromFile(it.value))
                }.toMap().toMutableMap()
                remoteSideContext.log.verbose("downloaded ${downloadedMedias.size} medias")

                var shouldMergeOverlay = downloadRequest.shouldMergeOverlay

                //if there is a zip file, extract it and replace the downloaded media with the extracted ones
                downloadedMedias.values.find { it.fileType == FileType.ZIP }?.let { zipFile ->
                    val oldDownloadedMedias = downloadedMedias.toMap()
                    downloadedMedias.clear()

                    zipFile.file.inputStream().use { zipFileInputStream ->
                        MediaDownloaderHelper.getSplitElements(zipFileInputStream) { type, inputStream ->
                            createMediaTempFile().apply {
                                outputStream().use {
                                    inputStream.copyTo(it)
                                }
                            }.also {
                                downloadedMedias[InputMedia(
                                    type = DownloadMediaType.LOCAL_MEDIA,
                                    content = it.absolutePath,
                                    isOverlay = type == SplitMediaAssetType.OVERLAY
                                )] = DownloadedFile(it, FileType.fromFile(it))
                            }
                        }
                    }

                    oldDownloadedMedias.forEach { (_, value) ->
                        value.file.delete()
                    }

                    shouldMergeOverlay = true
                }

                if (shouldMergeOverlay) {
                    assert(downloadedMedias.size == 2)
                    val media = downloadedMedias.entries.first { !it.key.isOverlay }.value
                    val overlayMedia = downloadedMedias.entries.first { it.key.isOverlay }.value

                    val renamedMedia = renameFromFileType(media.file, media.fileType)
                    val renamedOverlayMedia = renameFromFileType(overlayMedia.file, overlayMedia.fileType)
                    val mergedOverlay: File = File.createTempFile("merged", ".mp4")
                    runCatching {
                        callbackOnProgress(translation.format("processing_toast", "path" to media.file.nameWithoutExtension))

                        newFFMpegProcessor(pendingTask).execute(FFMpegProcessor.Request(
                            action = FFMpegProcessor.Action.MERGE_OVERLAY,
                            inputs = listOf(renamedMedia.absolutePath),
                            output = mergedOverlay,
                            overlay = renamedOverlayMedia
                        ))

                        saveMediaToGallery(pendingTask, mergedOverlay, downloadMetadata)
                    }.onFailure { exception ->
                        if (coroutineContext.job.isCancelled) return@onFailure
                        remoteSideContext.log.error("Failed to merge overlay", exception)
                        callbackOnFailure(translation.format("failed_processing_toast", "error" to exception.toString()), exception.message)
                        pendingTask.fail("Failed to merge overlay")
                    }

                    mergedOverlay.delete()
                    renamedOverlayMedia.delete()
                    renamedMedia.delete()
                    return@launch
                }

                downloadRemoteMedia(pendingTask, downloadMetadata, downloadedMedias, downloadRequest)
            }.onFailure { exception ->
                pendingTask.fail("Failed to download media")
                remoteSideContext.log.error("Failed to download media", exception)
                callbackOnFailure(translation["failed_generic_toast"], exception.message)
            }
        }
    }

    fun onReceive(intent: Intent) {
        val downloadMetadata = gson.fromJson(intent.getStringExtra(ReceiversConfig.DOWNLOAD_METADATA_EXTRA)!!, DownloadMetadata::class.java)
        val downloadRequest = gson.fromJson(intent.getStringExtra(ReceiversConfig.DOWNLOAD_REQUEST_EXTRA)!!, DownloadRequest::class.java)

        enqueue(downloadRequest, downloadMetadata)
    }
}
