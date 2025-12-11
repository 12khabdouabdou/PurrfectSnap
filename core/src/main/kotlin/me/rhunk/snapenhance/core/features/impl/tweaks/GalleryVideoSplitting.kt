package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        // Try to find the correct class - based on the trace, it should be ChatMediaDrawerActionHandler
        val actionHandlerClass = runCatching {
            findClass("com.snap.composer.memories.ChatMediaDrawerActionHandler")
        }.getOrElse {
            context.log.error("ChatMediaDrawerActionHandler not found, trying II2")
            runCatching { findClass("II2") }.getOrNull()
        } ?: run {
            context.log.error("Could not find any suitable action handler class")
            return
        }

        context.log.info("✅ Found action handler class: ${actionHandlerClass.name}")
        
        val sendItemsMethod = actionHandlerClass.methods.firstOrNull { 
            it.name == "sendItems" && 
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == List::class.java &&
            it.parameterTypes[1] == List::class.java
        } ?: run {
            context.log.error("sendItems method not found in ${actionHandlerClass.name}")
            context.log.error("Available methods: ${actionHandlerClass.methods.joinToString { "${it.name}(${it.parameterTypes.joinToString()})" }}")
            return
        }

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            context.log.info("🔵 sendItems called in ${actionHandlerClass.simpleName}")

            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            try {
                val conversationIds = param.arg<List<Any>>(0)
                val mediaItems = param.arg<List<Any?>>(1)
                
                context.log.info("  Conversations: ${conversationIds.size}, Items: ${mediaItems.size}")

                // Only process single video items
                if (mediaItems.size != 1) return@hook

                val mediaItem = mediaItems.firstOrNull() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: run {
                    context.log.warn("  No 'item' field found in mediaItem")
                    return@hook
                }
                val itemType = item.getObjectField("type")?.toString()

                context.log.info("  Item type: $itemType")

                if (itemType != "VIDEO") return@hook

                // Block the original call
                context.log.info("  ✅ Processing video for splitting...")
                param.setResult(null)

                context.coroutineScope.launch {
                    splitAndSendVideo(
                        item = item,
                        mediaItem = mediaItem,
                        conversationIds = conversationIds,
                        actionHandler = param.thisObject<Any>(),
                        sendItemsMethod = sendItemsMethod
                    )
                }
            } catch (e: Exception) {
                context.log.error("Error in sendItems hook", e)
            }
        }

        context.log.info("✅ GalleryVideoSplitting hooked ${actionHandlerClass.simpleName}.sendItems")
    }

    private suspend fun splitAndSendVideo(
        item: Any,
        mediaItem: Any,
        conversationIds: List<Any>,
        actionHandler: Any,
        sendItemsMethod: java.lang.reflect.Method
    ) {
        isSplitting = true
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}")
        
        try {
            tempDir.mkdirs()
            
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video...")
            }

            // Get content URI
            val contentUriStr = item.getObjectField("contentUri")?.toString() 
                ?: throw IllegalStateException("Content URI not found")
            val mediaUri = Uri.parse(contentUriStr)
            
            // Copy video to cache
            val cachedVideo = File(tempDir, "input.mp4")
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to read video file")

            context.log.info("Video cached at: ${cachedVideo.absolutePath}")

            // Split video using FFmpeg
            val outputPattern = "${tempDir.absolutePath}/split_%03d.mp4"
            val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 $outputPattern"
            
            context.log.info("Executing FFmpeg: $command")
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                val output = session.output
                context.log.error("FFmpeg failed with code ${session.returnCode}: $output")
                throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
            }

            // Get split files
            val outputFiles = tempDir.listFiles()
                ?.filter { it.name.startsWith("split_") && it.extension == "mp4" }
                ?.sortedBy { it.name }
                ?: emptyList()
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No output files generated")
            }

            context.log.info("Generated ${outputFiles.size} video segments")

            // Send each segment
            for ((index, file) in outputFiles.withIndex()) {
                try {
                    sendVideoSegment(
                        file = file,
                        index = index,
                        item = item,
                        mediaItem = mediaItem,
                        conversationIds = conversationIds,
                        actionHandler = actionHandler,
                        sendItemsMethod = sendItemsMethod
                    )
                    
                    // Add delay between sends
                    if (index < outputFiles.size - 1) {
                        delay(500)
                    }
                } catch (e: Exception) {
                    context.log.error("Failed to send segment ${index + 1}", e)
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "✅ Sent ${outputFiles.size} segments!"
                )
            }

        } catch (e: Exception) {
            context.log.error("Video splitting failed", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "❌ Failed: ${e.message}"
                )
            }
        } finally {
            // Cleanup
            tempDir.deleteRecursively()
            isSplitting = false
        }
    }

    private suspend fun sendVideoSegment(
        file: File,
        index: Int,
        item: Any,
        mediaItem: Any,
        conversationIds: List<Any>,
        actionHandler: Any,
        sendItemsMethod: java.lang.reflect.Method
    ) {
        val chunkUri = Uri.fromFile(file)
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context.androidContext, chunkUri)
            
            val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toDoubleOrNull() ?: 1080.0
            val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toDoubleOrNull() ?: 1920.0

            context.log.info("Segment $index: ${chunkDuration}ms, ${chunkWidth}x${chunkHeight}")

            // Create new item with segment data
            val newItem = item.javaClass.dataBuilder {
                set("type", item.getObjectField("type"))
                set("encryptionInfo", item.getObjectField("encryptionInfo"))
                set("contentUri", chunkUri.toString())
                set("durationMs", chunkDuration.toDouble())
                set("width", chunkWidth)
                set("height", chunkHeight)
                from("itemId", new = true) {
                    set("itemId", "${chunkUri.toString()}_$index")
                }
            }

            val newMediaItem = mediaItem.javaClass.dataBuilder {
                set("thumbnail", mediaItem.getObjectField("thumbnail"))
                set("item", newItem)
                set("order", index.toDouble())
            }

            // Send the segment
            sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
            
        } finally {
            retriever.release()
        }
    }
}
