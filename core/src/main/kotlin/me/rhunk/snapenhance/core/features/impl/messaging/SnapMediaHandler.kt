package me.rhunk.snapenhance.core.features.impl.messaging

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.core.ModContext
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Gère la capture et l'envoi de médias Snap (photos et vidéos) pour le système de batch
 */
class SnapMediaHandler(private val context: ModContext) {

    companion object {
        private const val TEMP_MEDIA_DIR = "batch_snaps"
    }

    data class SnapMediaData(
        val mediaPath: String,
        val mediaType: MediaType,
        val duration: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val fileSize: Long? = null,
        val thumbnailPath: String? = null,
        val originalUri: Uri? = null
    )

    enum class MediaType {
        PHOTO,
        VIDEO,
        BOOMERANG,
        DUAL_CAMERA
    }

    private var capturedMedia: SnapMediaData? = null
    private val tempMediaDir: File by lazy {
        File(context.androidContext.cacheDir, TEMP_MEDIA_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    init {
        hookMediaCapture()
    }

    /** Hook le système de capture de média de Snapchat */
    private fun hookMediaCapture() {
        try {
            // Hook CameraController pour capturer les médias
            findClass("com.snapchat.client.camera.CameraController")?.apply {
                hook("capturePhoto", HookStage.AFTER) { param ->
                    try {
                        val photoData = param.getResult<Any>()
                        context.log.info("Photo captured, extracting data...")
                        if (photoData != null) extractPhotoData(photoData)
                    } catch (e: Exception) {
                        context.log.error("Error extracting photo data", e)
                    }
                }

                hook("captureVideo", HookStage.AFTER) { param ->
                    try {
                        val videoData = param.getResult<Any>()
                        context.log.info("Video captured, extracting data...")
                        if (videoData != null) extractVideoData(videoData)
                    } catch (e: Exception) {
                        context.log.error("Error extracting video data", e)
                    }
                }
            }

            // Hook SnapCreationController pour différents types de médias
            findClass("com.snapchat.client.messaging.SnapCreationController")?.apply {
                hook("onMediaReady", HookStage.AFTER) { param ->
                    try {
                        val mediaObject = param.arg<Any>(0)
                        context.log.info("Media ready, processing...")
                        processMediaObject(mediaObject)
                    } catch (e: Exception) {
                        context.log.error("Error processing media object", e)
                    }
                }
            }

            // Hook SendToViewModel pour intercepter les envois batch
            findClass("com.snapchat.client.messaging.SendToViewModel")?.apply {
                hook("prepareMediaForSend", HookStage.BEFORE) { param ->
                    try {
                        val mediaData = param.arg<Any>(0)
                        val recipients = param.arg<List<Any>>(1)

                        context.log.info("Preparing media for send to ${recipients.size} recipients")

                        if (recipients.size > context.config.messaging.batchFriendSelector.batchSize.get()) {
                            storeMediaForBatch(mediaData)
                        }
                    } catch (e: Exception) {
                        context.log.error("Error in prepareMediaForSend hook", e)
                    }
                }
            }

        } catch (e: Exception) {
            context.log.error("Error setting up media capture hooks", e)
        }
    }

    private fun findClass(className: String) = try {
        context.androidContext.classLoader.loadClass(className)
    } catch (_: Exception) {
        null
    }

    /** Extrait les données d'une photo capturée */
    private fun extractPhotoData(photoData: Any) {
        try {
            val cls = photoData.javaClass
            val bitmap = cls.methods.find { it.name == "getBitmap" }?.invoke(photoData) as? Bitmap
            val filePath = cls.methods.find { it.name == "getFilePath" || it.name == "getPath" }?.invoke(photoData)?.toString()
            val width = cls.methods.find { it.name == "getWidth" }?.invoke(photoData) as? Int
            val height = cls.methods.find { it.name == "getHeight" }?.invoke(photoData) as? Int

            if (bitmap != null) {
                val tempFile = saveBitmapToTemp(bitmap)
                capturedMedia = SnapMediaData(
                    mediaPath = tempFile.absolutePath,
                    mediaType = MediaType.PHOTO,
                    width = width ?: bitmap.width,
                    height = height ?: bitmap.height,
                    fileSize = tempFile.length()
                )
            } else if (filePath != null) {
                val file = File(filePath)
                capturedMedia = SnapMediaData(
                    mediaPath = filePath,
                    mediaType = MediaType.PHOTO,
                    width = width,
                    height = height,
                    fileSize = file.length()
                )
            }

            context.log.info("Photo data extracted: $capturedMedia")
        } catch (e: Exception) {
            context.log.error("Failed to extract photo data", e)
        }
    }

    /** Extrait les données d'une vidéo capturée */
    private fun extractVideoData(videoData: Any) {
        try {
            val cls = videoData.javaClass
            val filePath = cls.methods.find { it.name.contains("Path", ignoreCase = true) }?.invoke(videoData)?.toString()
            val duration = cls.methods.find { it.name.contains("Duration", ignoreCase = true) }?.invoke(videoData) as? Long
            val width = cls.methods.find { it.name == "getWidth" }?.invoke(videoData) as? Int
            val height = cls.methods.find { it.name == "getHeight" }?.invoke(videoData) as? Int

            if (filePath != null) {
                val file = File(filePath)
                val thumbnailPath = generateVideoThumbnail(filePath)
                capturedMedia = SnapMediaData(
                    mediaPath = filePath,
                    mediaType = MediaType.VIDEO,
                    duration = duration,
                    width = width,
                    height = height,
                    fileSize = file.length(),
                    thumbnailPath = thumbnailPath
                )
            }

            context.log.info("Video data extracted: $capturedMedia")
        } catch (e: Exception) {
            context.log.error("Failed to extract video data", e)
        }
    }

    /** Gère les objets média génériques */
    private fun processMediaObject(mediaObject: Any) {
        try {
            val cls = mediaObject.javaClass
            val mediaTypeName = cls.methods.find { it.name.contains("Type", ignoreCase = true) }
                ?.invoke(mediaObject)?.toString() ?: "UNKNOWN"

            when {
                mediaTypeName.contains("VIDEO", true) -> extractVideoData(mediaObject)
                mediaTypeName.contains("PHOTO", true) || mediaTypeName.contains("IMAGE", true) -> extractPhotoData(mediaObject)
                mediaTypeName.contains("BOOMERANG", true) -> extractBoomerangData(mediaObject)
                mediaTypeName.contains("DUAL", true) -> extractDualCameraData(mediaObject)
                else -> context.log.warn("Unknown media type: $mediaTypeName")
            }
        } catch (e: Exception) {
            context.log.error("Failed to process media object", e)
        }
    }

    private fun extractBoomerangData(data: Any) = extractSimpleMedia(data, MediaType.BOOMERANG)
    private fun extractDualCameraData(data: Any) = extractSimpleMedia(data, MediaType.DUAL_CAMERA)

    private fun extractSimpleMedia(data: Any, type: MediaType) {
        try {
            val filePath = data.javaClass.methods.find { it.name.contains("Path", true) }?.invoke(data)?.toString()
            if (filePath != null) {
                val file = File(filePath)
                capturedMedia = SnapMediaData(filePath, type, fileSize = file.length())
                context.log.info("$type data extracted: $capturedMedia")
            }
        } catch (e: Exception) {
            context.log.error("Failed to extract $type data", e)
        }
    }

    private fun saveBitmapToTemp(bitmap: Bitmap): File {
        val file = File(tempMediaDir, "snap_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        return file
    }

    private fun generateVideoThumbnail(videoPath: String): String? = try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(videoPath)
        val bitmap = retriever.getFrameAtTime(0)
        retriever.release()

        if (bitmap != null) {
            val thumbFile = File(tempMediaDir, "thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(thumbFile).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            thumbFile.absolutePath
        } else null
    } catch (e: Exception) {
        context.log.error("Failed to generate video thumbnail", e)
        null
    }

    private fun storeMediaForBatch(mediaData: Any) {
        try {
            capturedMedia?.let {
                val source = File(it.mediaPath)
                val dest = File(tempMediaDir, "batch_${System.currentTimeMillis()}_${source.name}")
                copyFile(source, dest)
                capturedMedia = it.copy(mediaPath = dest.absolutePath)
                context.log.info("Media stored for batch: ${dest.absolutePath}")
            }
        } catch (e: Exception) {
            context.log.error("Failed to store media for batch", e)
        }
    }

    private fun copyFile(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    fun getCapturedMedia(): SnapMediaData? = capturedMedia
    fun clearCapturedMedia() { capturedMedia = null }

    /** Envoie un snap (photo ou vidéo) */
    suspend fun sendSnapToConversations(
        conversationIds: List<SnapUUID>,
        mediaData: SnapMediaData,
        onProgress: ((Int, Int) -> Unit)? = null
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            context.log.info("Sending ${mediaData.mediaType} snap to ${conversationIds.size} conversations")

            when (mediaData.mediaType) {
                MediaType.VIDEO, MediaType.BOOMERANG -> sendVideoSnap(conversationIds, mediaData, onProgress)
                MediaType.PHOTO, MediaType.DUAL_CAMERA -> sendPhotoSnap(conversationIds, mediaData, onProgress)
            }
        } catch (e: Exception) {
            context.log.error("Failed to send snap", e)
            SendResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun sendPhotoSnap(
        conversationIds: List<SnapUUID>,
        mediaData: SnapMediaData,
        onProgress: ((Int, Int) -> Unit)?
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            val sendClass = findClass("com.snapchat.client.messaging.SendController")
            val sendInstance = getSendControllerInstance() ?: return@withContext SendResult.Failure("SendController instance not found")
            val file = File(mediaData.mediaPath)
            if (!file.exists()) return@withContext SendResult.Failure("Media file not found: ${mediaData.mediaPath}")

            val ids = conversationIds.map { it.toString() }
            val sendMethod = sendClass?.methods?.find { it.name == "sendPhotoSnap" || it.name == "sendImageSnap" }
            val result = sendMethod?.invoke(sendInstance, ids, file.absolutePath, mediaData.width, mediaData.height)

            if (result != null && isSuccessResult(result)) SendResult.Success(ids.size)
            else SendResult.Failure("Send returned unsuccessful result")
        } catch (e: Exception) {
            context.log.error("Failed to send photo snap", e)
            SendResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun sendVideoSnap(
        conversationIds: List<SnapUUID>,
        mediaData: SnapMediaData,
        onProgress: ((Int, Int) -> Unit)?
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            val sendClass = findClass("com.snapchat.client.messaging.SendController")
            val sendInstance = getSendControllerInstance() ?: return@withContext SendResult.Failure("SendController instance not found")
            val file = File(mediaData.mediaPath)
            if (!file.exists()) return@withContext SendResult.Failure("Media file not found: ${mediaData.mediaPath}")

            val ids = conversationIds.map { it.toString() }
            val sendMethod = sendClass?.methods?.find { it.name == "sendVideoSnap" || it.name == "sendVideo" }
            val result = sendMethod?.invoke(sendInstance, ids, file.absolutePath, mediaData.duration, mediaData.width, mediaData.height)

            if (result != null && isSuccessResult(result)) SendResult.Success(ids.size)
            else SendResult.Failure("Send returned unsuccessful result")
        } catch (e: Exception) {
            context.log.error("Failed to send video snap", e)
            SendResult.Failure(e.message ?: "Unknown error")
        }
    }

    private fun getSendControllerInstance(): Any? = try {
        val cls = findClass("com.snapchat.client.messaging.SendController")
        val method = cls?.methods?.find { it.name == "getInstance" || it.name == "get" }
        method?.invoke(null)
    } catch (e: Exception) {
        context.log.error("Failed to get SendController instance", e)
        null
    }

    private fun isSuccessResult(result: Any): Boolean = try {
        when (result) {
            is Boolean -> result
            is Number -> result.toInt() >= 0
            else -> result.javaClass.methods.find { it.name == "isSuccess" || it.name == "isSuccessful" }
                ?.invoke(result) as? Boolean ?: false
        }
    } catch (_: Exception) {
        false
    }

    fun cleanupTempFiles(olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        try {
            val cutoff = System.currentTimeMillis() - olderThanMs
            tempMediaDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                    context.log.verbose("Deleted old temp file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            context.log.error("Failed to cleanup temp files", e)
        }
    }

    sealed class SendResult {
        data class Success(val sentCount: Int) : SendResult()
        data class Failure(val error: String) : SendResult()
    }
}
