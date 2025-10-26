Here's the corrected version of the `SnapMediaHandler.kt` file:

```kotlin
package me.rhunk.snapenhance.core.features.impl.messaging

import android.graphics.Bitmap
import android.net.Uri
import me.rhunk.snapenhance.core.ModContext
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
    
    /**
     * Hook le système de capture de média de Snapchat
     */
    private fun hookMediaCapture() {
        try {
            // Hook la classe de capture de caméra
            findClass("com.snapchat.client.camera.CameraController")?.apply {
                hook("capturePhoto", HookStage.AFTER) { param ->
                    try {
                        val photoData = param.getResult() // Fixed: Removed generic type arguments
                        context.log.info("Photo captured, extracting data...")
                        if (photoData != null) extractPhotoData(photoData)
                    } catch (e: Exception) {
                        context.log.error("Error extracting photo data", e)
                    }
                }
                
                hook("captureVideo", HookStage.AFTER) { param ->
                    try {
                        val videoData = param.getResult() // Fixed: Removed generic type arguments
                        context.log.info("Video captured, extracting data...")
                        if (videoData != null) extractVideoData(videoData)
                    } catch (e: Exception) {
                        context.log.error("Error extracting video data", e)
                    }
                }
            }
            
            // Hook le SnapCreationController pour capturer tous les types de médias
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
            
            // Hook SendToViewModel pour intercepter avant l'envoi
            findClass("com.snapchat.client.messaging.SendToViewModel")?.apply {
                hook("prepareMediaForSend", HookStage.BEFORE) { param ->
                    try {
                        val mediaData = param.arg<Any>(0)
                        val recipients = param.arg<List<Any>>(1)
                        
                        context.log.info("Preparing media for send to ${recipients.size} recipients")
                        
                        // Si c'est un batch send, on intercepte
                        if (recipients.size > context.config.messaging.batchFriendSelector.batchSize.get()) {
                            // Sauvegarder les données média
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
    } catch (e: Exception) {
        null
    }
    
    /**
     * Extrait les données d'une photo capturée
     */
    private fun extractPhotoData(photoData: Any) {
        try {
            val photoClass = photoData.javaClass
            
            // Extraire le bitmap ou le chemin du fichier
            val bitmap = photoClass.methods.find { 
                it.name == "getBitmap" || it.returnType == Bitmap::class.java 
            }?.invoke(photoData) as? Bitmap
            
            val filePath = photoClass.methods.find { 
                it.name == "getFilePath" || it.name == "getPath" 
            }?.invoke(photoData)?.toString()
            
            val width = photoClass.methods.find { it.name == "getWidth" }?.invoke(photoData) as? Int
            val height = photoClass.methods.find { it.name == "getHeight" }?.invoke(photoData) as? Int
            
            if (bitmap != null) {
                // Sauvegarder le bitmap dans un fichier temporaire
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
    
    /**
     * Extrait les données d'une vidéo capturée
     */
    private fun extractVideoData(videoData: Any) {
        try {
            val videoClass = videoData.javaClass
            
            val filePath = videoClass.methods.find { 
                it.name == "getVideoPath" || it.name == "getFilePath" || it.name == "getPath" 
            }?.invoke(videoData)?.toString()
            
            val duration = videoClass.methods.find { 
                it.name == "getDuration" || it.name == "getDurationMs" 
            }?.invoke(videoData) as? Long
            
            val width = videoClass.methods.find { it.name == "getWidth" }?.invoke(videoData) as? Int
            val height = videoClass.methods.find { it.name == "getHeight" }?.invoke(videoData) as? Int
            
            if (filePath != null) {
                val file = File(filePath)
                
                // Générer une miniature pour la vidéo
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
    
    /**
     * Traite un objet média générique de Snapchat
     */
    private fun processMediaObject(mediaObject: Any) {
        try {
            val mediaClass = mediaObject.javaClass
            
            // Déterminer le type de média
            val mediaTypeField = mediaClass.methods.find { 
                it.name == "getMediaType" || it.name == "getType" 
            }?.invoke(mediaObject)
            
            val mediaTypeName = mediaTypeField?.toString() ?: "UNKNOWN"
            
            when {
                mediaTypeName.contains("VIDEO", ignoreCase = true) -> {
                    extractVideoData(mediaObject)
                }
                mediaTypeName.contains("PHOTO", ignoreCase = true) || 
                mediaTypeName.contains("IMAGE", ignoreCase = true) -> {
                    extractPhotoData(mediaObject)
                }
                mediaTypeName.contains("BOOMERANG", ignoreCase = true) -> {
                    extractBoomerangData(mediaObject)
                }
                mediaTypeName.contains("DUAL", ignoreCase = true) -> {
                    extractDualCameraData(mediaObject)
                }
                else -> {
                    context.log.warn("Unknown media type: $mediaTypeName")
                }
            }
        } catch (e: Exception) {
            context.log.error("Failed to process media object", e)
        }
    }
    
    /**
     * Extrait les données d'un Boomerang
     */
    private fun extractBoomerangData(boomerangData: Any) {
        try {
            val boomerangClass = boomerangData.javaClass
            
            val filePath = boomerangClass.methods.find { 
                it.name.contains("Path", ignoreCase = true) 
            }?.invoke(boomerangData)?.toString()
            
            if (filePath != null) {
                val file = File(filePath)
                capturedMedia = SnapMediaData(
                    mediaPath = filePath,
                    mediaType = MediaType.BOOMERANG,
                    fileSize = file.length()
                )
            }
            
            context.log.info("Boomerang data extracted: $capturedMedia")
        } catch (e: Exception) {
            context.log.error("Failed to extract boomerang data", e)
        }
    }
    
    /**
     * Extrait les données d'une capture dual camera
     */
    private fun extractDualCameraData(dualData: Any) {
        try {
            val dualClass = dualData.javaClass
            
            val filePath = dualClass.methods.find { 
                it.name.contains("Path", ignoreCase = true) 
            }?.invoke(dualData)?.toString()
            
            if (filePath != null) {
                val file = File(filePath)
                capturedMedia = SnapMediaData(
                    mediaPath = filePath,
                    mediaType = MediaType.DUAL_CAMERA,
                    fileSize = file.length()
                )
            }
            
            context.log.info("Dual camera data extracted: $capturedMedia")
        } catch (e: Exception) {
            context.log.error("Failed to extract dual camera data", e)
        }
    }
    
    /**
     * Sauvegarde un bitmap dans un fichier temporaire
     */
    private fun saveBitmapToTemp(bitmap: Bitmap): File {
        val tempFile = File(tempMediaDir, "snap_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return tempFile
    }
    
    /**
     * Génère une miniature pour une vidéo
     */
    private fun generateVideoThumbnail(videoPath: String): String? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val bitmap = retriever.getFrameAtTime(0)
            retriever.release()
            
            if (bitmap != null) {
                val thumbFile = File(tempMediaDir, "thumb_${System.currentTimeMillis()}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                thumbFile.absolutePath
            } else null
        } catch (e: Exception) {
            context.log.error("Failed to generate video thumbnail", e)
            null
        }
    }
    
    /**
     * Stocke les données média pour une session de batch
     */
    private fun storeMediaForBatch(mediaData: Any) {
        try {
            // Copier le fichier média vers un emplacement permanent pour la session
            if (capturedMedia != null) {
                val sourceFile = File(capturedMedia!!.mediaPath)
                val destFile = File(tempMediaDir, "batch_${System.currentTimeMillis()}_${sourceFile.name}")
                
                copyFile(sourceFile, destFile)
                
                capturedMedia = capturedMedia!!.copy(mediaPath = destFile.absolutePath)
                context.log.info("Media stored for batch: ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            context.log.error("Failed to store media for batch", e)
        }
    }
    
    /**
     * Copie un fichier
     */
    private fun copyFile(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }
    
    /**
     * Récupère les données du média capturé
     */
    fun getCapturedMedia(): SnapMediaData? = capturedMedia
    
    /**
     * Réinitialise les données du média capturé
     */
    fun clearCapturedMedia() {
        capturedMedia = null
    }
    
    /**
     * Envoie un snap (photo ou vidéo) à une liste de conversations
     */
    suspend fun sendSnapToConversations(
        conversationIds: List<SnapUUID>,
        mediaData: SnapMediaData,
        onProgress: ((Int, Int) -> Unit)? = null
    ): SendResult {
        return try {
            context.log.info("Sending ${mediaData.mediaType} snap to ${conversationIds.size} conversations")
            
            // Appeler la méthode native de Snapchat pour envoyer le snap
            val sendClass = findClass("com.snapchat.client.messaging.SendController")
            val sendMethod = sendClass?.methods?.find { method ->
                method.name == "sendSnap" || 
                method.name == "sendMedia" ||
                method.name == "sendSnapToConversations"
            }
            
            if (sendMethod == null) {
                return SendResult.Failure("Send method not found")
            }
            
            // Créer l'objet média pour Snapchat
            val snapMediaObject = createSnapMediaObject(mediaData)
            
            // Invoquer la méthode d'envoi
            val result = when (mediaData.mediaType) {
                MediaType.VIDEO, MediaType.BOOMERANG -> {
                    sendVideoSnap(conversationIds, mediaData, onProgress)
                }
                MediaType.PHOTO, MediaType.DUAL_CAMERA -> {
                    sendPhotoSnap(conversationIds, mediaData, onProgress)
                }
            }
            
            result
            
        } catch (e: Exception) {
            context.log.error("Failed") }
