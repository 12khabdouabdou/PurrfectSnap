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
        // Try multiple class candidates in priority order
        val actionHandlerClass = runCatching {
            // First try: Interface (cleanest approach)
            findClass("com.snap.composer.memories.ChatMediaDrawerActionHandler")
        }.recoverCatching {
            context.log.warn("Interface not found, trying obfuscated implementation class...")
            // Second try: Known obfuscated implementation
            findClass("defpackage.C34621oO2")
        }.recoverCatching {
            context.log.warn("C34621oO2 not found, trying II2...")
            // Third try: Old fallback
            findClass("II2")
        }.recoverCatching {
            context.log.warn("Trying to find via proxy class...")
            // Fourth try: Proxy class
            findClass("defpackage.C37362qO2")
        }.getOrElse {
            context.log.error("❌ Could not find any suitable action handler class")
            return
        }

        context.log.info("✅ Found action handler class: ${actionHandlerClass.name}")
        
        // Find sendItems method with flexible matching
        val sendItemsMethod = actionHandlerClass.methods.firstOrNull { method ->
            method.name == "sendItems" && 
            method.parameterTypes.size == 2 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            List::class.java.isAssignableFrom(method.parameterTypes[1])
        } ?: run {
            context.log.error("❌ sendItems method not found in ${actionHandlerClass.name}")
            context.log.error("Available methods:")
            actionHandlerClass.methods.forEach { method ->
                context.log.error("  - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
            }
            return
        }

        context.log.info("✅ Found sendItems method: ${sendItemsMethod.name}")

        // Hook the sendItems method
        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            context.log.info("🔵 sendItems intercepted in ${actionHandlerClass.simpleName}")

            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                context.log.verbose("  Skipping: isSplitting=$isSplitting, config=${context.config.messaging.splitVideoIntoTenSecondSnaps.get()}")
                return@hook
            }

            try {
                val conversationIds = param.arg<List<Any>>(0)
                val mediaItems = param.arg<List<Any?>>(1)
                
                context.log.info("  📋 Conversations: ${conversationIds.size}, Items: ${mediaItems.size}")

                // Only process single video items
                if (mediaItems.size != 1) {
                    context.log.verbose("  Skipping: Multiple items (${mediaItems.size})")
                    return@hook
                }

                val mediaItem = mediaItems.firstOrNull() ?: run {
                    context.log.warn("  ⚠️ No media item found")
                    return@hook
                }

                val item = mediaItem.getObjectField("item") ?: run {
                    context.log.warn("  ⚠️ No 'item' field found in mediaItem")
                    return@hook
                }

                val itemType = item.getObjectField("type")?.toString()
                context.log.info("  📹 Item type: $itemType")

                if (itemType != "VIDEO") {
                    context.log.verbose("  Skipping: Not a video (type=$itemType)")
                    return@hook
                }

                // Block the original call and process video splitting
                context.log.info("  ✅ Blocking original send - will split video")
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
                context.log.error("❌ Error in sendItems hook", e)
                // Don't block on error - let original call proceed
            }
        }

        context.log.info("✅ GalleryVideoSplitting initialized on ${actionHandlerClass.simpleName}.sendItems()")
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
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "🎬 Processing video for splitting..."
                )
            }

            // Get content URI
            val contentUriStr = item.getObjectField("contentUri")?.toString() 
                ?: throw IllegalStateException("Content URI not found")
            val mediaUri = Uri.parse(contentUriStr)
            
            context.log.info("📹 Source video URI: $mediaUri")

            // Copy video to cache
            val cachedVideo = File(tempDir, "input.mp4")
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to read video file")

            context.log.info("💾 Video cached at: ${cachedVideo.absolutePath} (${cachedVideo.length()} bytes)")

            // Get original duration
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(cachedVideo.absolutePath)
            val originalDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()
            
            context.log.info("⏱️ Original duration: ${originalDuration}ms (${originalDuration / 1000.0}s)")

            // Split video using FFmpeg
            val outputPattern = "${tempDir.absolutePath}/split_%03d.mp4"
            val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 $outputPattern"
            
            context.log.info("🎞️ Executing FFmpeg: $command")
            
            withContext(Dispatchers.IO) {
                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                    val output = session.output
                    context.log.error("❌ FFmpeg failed with code ${session.returnCode}")
                    context.log.error("FFmpeg output: $output")
                    throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                }
                
                context.log.info("✅ FFmpeg completed successfully")
            }

            // Get split files
            val outputFiles = tempDir.listFiles()
                ?.filter { it.name.startsWith("split_") && it.extension == "mp4" }
                ?.sortedBy { it.name }
                ?: emptyList()
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No output files generated by FFmpeg")
            }

            context.log.info("📦 Generated ${outputFiles.size} video segments")
            outputFiles.forEachIndexed { index, file ->
                context.log.info("  Segment ${index + 1}: ${file.name} (${file.length()} bytes)")
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "📤 Sending ${outputFiles.size} segments..."
                )
            }

            // Send each segment
            for ((index, file) in outputFiles.withIndex()) {
                try {
                    context.log.info("📤 Sending segment ${index + 1}/${outputFiles.size}")
                    
                    sendVideoSegment(
                        file = file,
                        index = index,
                        item = item,
                        mediaItem = mediaItem,
                        conversationIds = conversationIds,
                        actionHandler = actionHandler,
                        sendItemsMethod = sendItemsMethod
                    )
                    
                    context.log.info("✅ Segment ${index + 1} sent successfully")
                    
                    // Add delay between sends to avoid rate limiting
                    if (index < outputFiles.size - 1) {
                        delay(500)
                    }
                } catch (e: Exception) {
                    context.log.error("❌ Failed to send segment ${index + 1}", e)
                    // Continue with next segment even if one fails
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "✅ Sent ${outputFiles.size} video segments!"
                )
            }

        } catch (e: Exception) {
            context.log.error("❌ Video splitting failed", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "❌ Split failed: ${e.message}"
                )
            }
        } finally {
            // Cleanup temp files
            context.log.info("🧹 Cleaning up temp directory: ${tempDir.absolutePath}")
            tempDir.deleteRecursively()
            isSplitting = false
            context.log.info("✅ Cleanup complete")
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

            context.log.info("  📏 Segment ${index + 1} metadata: ${chunkDuration}ms, ${chunkWidth}x${chunkHeight}")

            // Create new item with segment data
            val newItem = item.javaClass.dataBuilder {
                set("type", item.getObjectField("type"))
                set("encryptionInfo", item.getObjectField("encryptionInfo"))
                set("contentUri", chunkUri.toString())
                set("durationMs", chunkDuration.toDouble())
                set("width", chunkWidth)
                set("height", chunkHeight)
                from("itemId", new = true) {
                    set("itemId", "${System.currentTimeMillis()}_segment_$index")
                }
            }

            val newMediaItem = mediaItem.javaClass.dataBuilder {
                set("thumbnail", mediaItem.getObjectField("thumbnail"))
                set("item", newItem)
                set("order", index.toDouble())
            }

            // Send the segment via the original method
            withContext(Dispatchers.Main) {
                sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
            }
            
        } finally {
            retriever.release()
        }
    }
}
