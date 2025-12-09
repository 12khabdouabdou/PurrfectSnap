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
import java.lang.reflect.Method

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.info("GalleryVideoSplitting feature is disabled in config")
            return
        }

        try {
            context.log.info("=== Initializing GalleryVideoSplitting ===")

            // Find the ChatMediaDrawerActionHandler class
            val actionHandlerClass = findClass("com.snap.composer.memories.ChatMediaDrawerActionHandler")
            context.log.info("✓ Found class: ${actionHandlerClass.name}")

            // List all methods for debugging
            context.log.info("Available methods in class:")
            actionHandlerClass.methods.forEach { method ->
                context.log.info("  - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
            }

            // Find the sendItems method
            val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { method ->
                method.name == "sendItems" && method.parameterTypes.size == 2
            } ?: run {
                context.log.error("❌ sendItems method not found!")
                return
            }

            context.log.info("✓ Found sendItems method")

            // Hook the sendItems method
            sendItemsMethod.hook(HookStage.BEFORE) { param ->
                context.log.info("🔵 sendItems called")

                if (isSplitting) {
                    context.log.info("  Already splitting, skipping...")
                    return@hook
                }

                try {
                    val conversationIds = param.arg<List<Any>>(0)
                    val mediaItems = param.arg<List<Any?>>(1)
                    
                    context.log.info("  Conversations: ${conversationIds.size}, Media items: ${mediaItems.size}")

                    if (mediaItems.size != 1) {
                        context.log.info("  Multiple items, skipping")
                        return@hook
                    }

                    val mediaItem = mediaItems.first() ?: run {
                        context.log.warn("  mediaItem is null")
                        return@hook
                    }

                    // Get the item field (might be obfuscated)
                    val item = try {
                        mediaItem.getObjectField("item")
                    } catch (e: Exception) {
                        context.log.error("  Failed to get 'item' field", e)
                        // Try to list fields for debugging
                        try {
                            val fields = mediaItem.javaClass.declaredFields
                            context.log.info("  Available fields: ${fields.joinToString { field -> field.name }}")
                        } catch (e2: Exception) {
                            context.log.error("  Can't list fields", e2)
                        }
                        return@hook
                    }

                    if (item == null) {
                        context.log.warn("  item is null")
                        return@hook
                    }

                    // Check if it's a video
                    val itemType = item.getObjectField("type")?.toString()
                    context.log.info("  Item type: $itemType")

                    if (itemType == "VIDEO") {
                        context.log.info("  ✅ VIDEO detected! Starting split process...")
                        param.setResult(null) // Cancel the original send

                        context.coroutineScope.launch {
                            isSplitting = true
                            val tempDir = File(
                                context.mainActivity!!.cacheDir, 
                                "split_video_${System.currentTimeMillis()}"
                            ).apply { mkdirs() }

                            try {
                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info, 
                                        "Processing video..."
                                    )
                                }

                                // Get the video URI
                                val contentUriStr = item.getObjectField("contentUri")?.toString() 
                                    ?: throw IllegalStateException("Content URI not found")
                                
                                context.log.info("  Content URI: $contentUriStr")
                                val mediaUri = Uri.parse(contentUriStr)
                                val cachedVideo = File(tempDir, "input.mp4")

                                // Copy video to cache
                                context.log.info("  Copying video to cache...")
                                context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                    cachedVideo.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                } ?: throw IllegalStateException("Failed to open input stream for media URI")

                                context.log.info("  Video cached: ${cachedVideo.length()} bytes")

                                // Run FFmpeg to split the video
                                val inputPath = cachedVideo.absolutePath
                                val outputPattern = "${tempDir.absolutePath}/split_%03d.mp4"
                                val command = "-i $inputPath -c copy -f segment -segment_time 10 -reset_timestamps 1 $outputPattern"
                                
                                context.log.info("  Running FFmpeg: $command")
                                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                    val errorOutput = session.output ?: "No output"
                                    context.log.error("  FFmpeg failed with code ${session.returnCode}")
                                    context.log.error("  FFmpeg output: $errorOutput")
                                    throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                                }

                                context.log.info("  FFmpeg completed successfully")

                                // Get all split files
                                val outputFiles = tempDir.listFiles()
                                    ?.filter { it.name.startsWith("split_") }
                                    ?.sortedBy { it.name } 
                                    ?: emptyList()

                                if (outputFiles.isEmpty()) {
                                    throw IllegalStateException("FFmpeg produced no output files")
                                }

                                context.log.info("  Split into ${outputFiles.size} segments")

                                val actionHandler = param.thisObject<Any>()

                                // Send each segment
                                for ((index, file) in outputFiles.withIndex()) {
                                    context.log.info("  Processing segment ${index + 1}/${outputFiles.size}: ${file.name}")
                                    
                                    val chunkUri = Uri.fromFile(file)
                                    val retriever = MediaMetadataRetriever()

                                    try {
                                        retriever.setDataSource(context.androidContext, chunkUri)
                                        val chunkDuration = retriever.extractMetadata(
                                            MediaMetadataRetriever.METADATA_KEY_DURATION
                                        )?.toLongOrNull() ?: 0L
                                        val chunkWidth = retriever.extractMetadata(
                                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                                        )?.toDoubleOrNull() ?: 1080.0
                                        val chunkHeight = retriever.extractMetadata(
                                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                                        )?.toDoubleOrNull() ?: 1920.0

                                        context.log.info("    Duration: ${chunkDuration}ms, Size: ${chunkWidth}x${chunkHeight}")

                                        // Create new item with chunk data
                                        val newItem = item.javaClass.dataBuilder {
                                            set("type", item.getObjectField("type"))
                                            set("encryptionInfo", item.getObjectField("encryptionInfo"))
                                            set("contentUri", chunkUri.toString())
                                            set("durationMs", chunkDuration.toDouble())
                                            set("width", chunkWidth)
                                            set("height", chunkHeight)
                                            from("itemId", new = true) {
                                                set("itemId", chunkUri.toString())
                                            }
                                        }

                                        // Create new media item
                                        val newMediaItem = mediaItem.javaClass.dataBuilder {
                                            set("thumbnail", mediaItem.getObjectField("thumbnail"))
                                            set("item", newItem)
                                            set("order", index.toDouble())
                                        }

                                        // Send the chunk
                                        sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                                        context.log.info("    ✓ Sent segment ${index + 1}")
                                        
                                        // Small delay between sends
                                        delay(500)

                                    } finally {
                                        retriever.release()
                                    }
                                }

                                context.log.info("  ✅ All segments sent successfully!")

                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info, 
                                        "Sent ${outputFiles.size} video segments!"
                                    )
                                }

                            } catch (e: Exception) {
                                context.log.error("Failed to split and send video", e)
                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info, 
                                        "Failed to process video: ${e.message}"
                                    )
                                }
                            } finally {
                                // Clean up temp files
                                context.log.info("  Cleaning up temp directory...")
                                tempDir.deleteRecursively()
                                isSplitting = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    context.log.error("Error in GalleryVideoSplitting hook", e)
                }
            }

            context.log.info("✅ GalleryVideoSplitting hook installed successfully")

        } catch (e: Exception) {
            context.log.error("Failed to initialize GalleryVideoSplitting feature", e)
        }
    }
}
