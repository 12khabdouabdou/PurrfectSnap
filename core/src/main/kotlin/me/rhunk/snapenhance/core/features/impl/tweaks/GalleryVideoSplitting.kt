package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.DataClassBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.util.ktx.setObjectField
import java.io.File
import java.lang.reflect.Method

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    private val splittingMutex = Mutex()
    private val maxSegmentDurationMs = 10000L // 10 seconds

    override fun init() {
        val actionHandlerClass = runCatching { 
            findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        }.getOrNull() ?: run {
            context.log.warn("GalleryVideoSplitting: Could not find ChatMediaDrawerActionHandler class")
            return
        }

        val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { 
            it.name == "sendItems" 
        } ?: run {
            context.log.warn("GalleryVideoSplitting: Could not find sendItems method")
            return
        }

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            if (!splittingMutex.tryLock()) {
                context.log.verbose("GalleryVideoSplitting: Already processing a video, skipping")
                return@hook
            }

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                if (mediaItems.size != 1) {
                    splittingMutex.unlock()
                    return@hook
                }

                val mediaItem = mediaItems.firstOrNull() ?: run {
                    splittingMutex.unlock()
                    return@hook
                }

                val item = mediaItem.getObjectField("item") ?: run {
                    splittingMutex.unlock()
                    return@hook
                }

                val itemType = item.getObjectField("type")?.toString()

                if (itemType == "VIDEO") {
                    val durationMs = (item.getObjectField("durationMs") as? Number)?.toLong() ?: 0L
                    
                    if (durationMs <= maxSegmentDurationMs) {
                        splittingMutex.unlock()
                        return@hook
                    }

                    param.setResult(null)

                    context.coroutineScope.launch {
                        try {
                            processAndSendVideo(param, mediaItem, item, sendItemsMethod)
                        } finally {
                            splittingMutex.unlock()
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Error in hook", e)
                splittingMutex.unlock()
            }
        }
    }

    private suspend fun processAndSendVideo(
        param: Any,
        mediaItem: Any,
        item: Any,
        sendItemsMethod: Method
    ) {
        val tempDir = File(
            context.mainActivity!!.cacheDir, 
            "split_video_${System.currentTimeMillis()}"
        ).apply { mkdirs() }

        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Processing video for splitting..."
                )
            }

            val contentUriStr = item.getObjectField("contentUri")?.toString() 
                ?: throw IllegalStateException("Content URI not found")
            val mediaUri = Uri.parse(contentUriStr)
            val cachedVideo = File(tempDir, "input.mp4")

            withContext(Dispatchers.IO) {
                context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                    cachedVideo.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Failed to open input stream")
            }

            val outputFiles = try {
                splitVideoWithFFmpeg(cachedVideo, tempDir)
            } catch (e: ClassNotFoundException) {
                context.log.error("GalleryVideoSplitting: FFmpegKit not found, using manual splitting")
                splitVideoManually(cachedVideo, tempDir)
            }

            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No video segments produced")
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Sending ${outputFiles.size} video segments..."
                )
            }

            val conversationIds = param.arg<List<Any>>(0)
            val actionHandler = param.thisObject<Any>()

            sendVideoSegments(
                outputFiles, 
                item, 
                mediaItem, 
                actionHandler, 
                conversationIds, 
                sendItemsMethod
            )

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Video sent successfully!"
                )
            }

        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Failed: ${e.message ?: "Unknown error"}"
                )
            }
        } finally {
            withContext(Dispatchers.IO) {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun splitVideoWithFFmpeg(input: File, outputDir: File): List<File> {
        val command = "-i \"${input.absolutePath}\" -c copy -f segment " +
                     "-segment_time 10 -reset_timestamps 1 " +
                     "\"${outputDir.absolutePath}/split_%03d.mp4\""
        
        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
        
        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
            throw IllegalStateException("FFmpeg failed: ${session.failStackTrace}")
        }

        return outputDir.listFiles()
            ?.filter { it.name.startsWith("split_") }
            ?.sortedBy { it.name } 
            ?: emptyList()
    }

    private fun splitVideoManually(input: File, outputDir: File): List<File> {
        context.log.warn("GalleryVideoSplitting: Manual splitting not implemented, returning original")
        return emptyList()
    }

    private suspend fun sendVideoSegments(
        files: List<File>,
        originalItem: Any,
        originalMediaItem: Any,
        actionHandler: Any,
        conversationIds: List<Any>,
        sendMethod: Method
    ) {
        for ((index, file) in files.withIndex()) {
            val chunkUri = Uri.fromFile(file)
            val retriever = MediaMetadataRetriever()

            try {
                withContext(Dispatchers.IO) {
                    retriever.setDataSource(context.androidContext, chunkUri)
                }

                val chunkDuration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L

                val chunkWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toDoubleOrNull() ?: 1080.0

                val chunkHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toDoubleOrNull() ?: 1920.0

                val newItem = createModifiedItem(
                    originalItem,
                    chunkUri.toString(),
                    chunkDuration.toDouble(),
                    chunkWidth,
                    chunkHeight
                )

                val newMediaItem = createModifiedMediaItem(
                    originalMediaItem,
                    newItem,
                    index.toDouble()
                )

                withContext(Dispatchers.Main) {
                    sendMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                }

                delay(500)
            } finally {
                retriever.release()
            }
        }
    }

    private fun createModifiedItem(
        originalItem: Any,
        contentUri: String,
        durationMs: Double,
        width: Double,
        height: Double
    ): Any? {
        val builder = DataClassBuilder(originalItem)
        builder.set("type", originalItem.getObjectField("type") ?: "VIDEO")
        builder.set("encryptionInfo", originalItem.getObjectField("encryptionInfo"))
        builder.set("contentUri", contentUri)
        builder.set("durationMs", durationMs)
        builder.set("width", width)
        builder.set("height", height)
        builder.from("itemId", true) {
            set("itemId", "${contentUri}_${System.currentTimeMillis()}")
        }
        return builder.build()
    }

    private fun createModifiedMediaItem(
        originalMediaItem: Any,
        newItem: Any?,
        order: Double
    ): Any? {
        val builder = DataClassBuilder(originalMediaItem)
        builder.set("thumbnail", originalMediaItem.getObjectField("thumbnail"))
        builder.set("item", newItem)
        builder.set("order", order)
        return builder.build()
    }
}
