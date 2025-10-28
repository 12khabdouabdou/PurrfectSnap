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
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config")
            return
        }

        context.log.verbose("GalleryVideoSplitting: Initializing...")

        try {
            val actionHandlerClass = context.androidContext.classLoader.loadClass(
                "com.snap.memories.composer.ChatMediaDrawerActionHandler"
            )
            context.log.verbose("GalleryVideoSplitting: Found ChatMediaDrawerActionHandler class")

            val sendItemsMethod = actionHandlerClass.declaredMethods.firstOrNull { 
                it.name == "sendItems" && it.parameterCount == 2 
            }

            if (sendItemsMethod == null) {
                context.log.error("GalleryVideoSplitting: Could not find sendItems method")
                context.log.verbose("GalleryVideoSplitting: Available methods: ${actionHandlerClass.declaredMethods.joinToString { it.name }}")
                return
            }

            context.log.verbose("GalleryVideoSplitting: Found sendItems method: ${sendItemsMethod.name}(${sendItemsMethod.parameterTypes.joinToString { it.simpleName }})")

            sendItemsMethod.hook(HookStage.BEFORE) { param ->
                if (isSplitting) {
                    context.log.verbose("GalleryVideoSplitting: Already splitting, ignoring")
                    return@hook
                }

                runCatching {
                    val conversationIds = param.arg<List<Any>>(0)
                    val mediaItems = param.arg<List<Any?>>(1)
                    
                    context.log.verbose("GalleryVideoSplitting: sendItems hooked! conversations=${conversationIds.size}, mediaItems=${mediaItems.size}")

                    if (mediaItems.size != 1) {
                        context.log.verbose("GalleryVideoSplitting: Multiple items (${mediaItems.size}), skipping")
                        return@hook
                    }

                    val mediaItem = mediaItems.firstOrNull() ?: run {
                        context.log.verbose("GalleryVideoSplitting: First mediaItem is null")
                        return@hook
                    }

                    val item = mediaItem.getObjectField("item") ?: run {
                        context.log.verbose("GalleryVideoSplitting: item field is null")
                        return@hook
                    }

                    val itemType = item.getObjectField("type")?.toString()
                    context.log.verbose("GalleryVideoSplitting: Item type = $itemType")

                    if (itemType != "VIDEO") {
                        context.log.verbose("GalleryVideoSplitting: Not a video, skipping")
                        return@hook
                    }

                    // Get the content URI
                    val contentUriStr = item.getObjectField("contentUri")?.toString()
                    if (contentUriStr == null) {
                        context.log.error("GalleryVideoSplitting: Content URI is null")
                        return@hook
                    }

                    context.log.verbose("GalleryVideoSplitting: Content URI = $contentUriStr")

                    // Get duration from the actual video file
                    val mediaUri = Uri.parse(contentUriStr)
                    val retriever = MediaMetadataRetriever()
                    val videoDuration: Long

                    try {
                        retriever.setDataSource(context.androidContext, mediaUri)
                        videoDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        context.log.verbose("GalleryVideoSplitting: Video duration from file = ${videoDuration}ms")
                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: Failed to read video metadata", e)
                        return@hook
                    } finally {
                        retriever.release()
                    }

                    // Only split if longer than 10 seconds
                    if (videoDuration <= 10000) {
                        context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                        return@hook
                    }

                    context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), canceling original and splitting")
                    
                    // Cancel the original send
                    param.setResult(null)

                    // Start splitting in coroutine
                    context.coroutineScope.launch {
                        isSplitting = true
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
                            mkdirs() 
                        }

                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info, 
                                    "Splitting video (${videoDuration / 1000}s)..."
                                )
                            }

                            // Copy video to temp file
                            val cachedVideo = File(tempDir, "input.mp4")
                            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                cachedVideo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw IllegalStateException("Failed to open input stream")

                            context.log.verbose("GalleryVideoSplitting: Cached video to ${cachedVideo.absolutePath}")

                            // Split with FFmpeg
                            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                            context.log.verbose("GalleryVideoSplitting: FFmpeg command: $command")

                            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                throw IllegalStateException("FFmpeg failed: ${session.returnCode} - ${session.failStackTrace}")
                            }

                            val outputFiles = tempDir.listFiles()
                                ?.filter { it.name.startsWith("split_") }
                                ?.sortedBy { it.name } 
                                ?: throw IllegalStateException("No output files")

                            context.log.verbose("GalleryVideoSplitting: Created ${outputFiles.size} split files")

                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info,
                                    "Sending ${outputFiles.size} parts..."
                                )
                            }

                            // Send each split file
                            val actionHandler = param.thisObject<Any>()

                            for ((index, file) in outputFiles.withIndex()) {
                                val chunkUri = Uri.fromFile(file)
                                val chunkRetriever = MediaMetadataRetriever()
                                
                                try {
                                    chunkRetriever.setDataSource(context.androidContext, chunkUri)
                                    val chunkDuration = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    val chunkWidth = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                    val chunkHeight = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                                    context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1}: ${chunkDuration}ms, ${chunkWidth}x${chunkHeight}")

                                    // Create new item for this chunk
                                    val newItem = item.javaClass.dataBuilder {
                                        set("type", item.getObjectField("type"))
                                        set("encryptionInfo", item.getObjectField("encryptionInfo"))
                                        set("contentUri", chunkUri.toString())
                                        set("durationMs", chunkDuration.toDouble())
                                        set("width", chunkWidth)
                                        set("height", chunkHeight)
                                        from("itemId", new = true) {
                                            set("itemId", "${chunkUri}_${System.currentTimeMillis()}")
                                        }
                                    }

                                    val newMediaItem = mediaItem.javaClass.dataBuilder {
                                        set("thumbnail", mediaItem.getObjectField("thumbnail"))
                                        set("item", newItem)
                                        set("order", index.toDouble())
                                    }

                                    // Send this chunk
                                    sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                                    
                                    context.log.verbose("GalleryVideoSplitting: Sent chunk ${index + 1}/${outputFiles.size}")

                                    // Delay between sends
                                    if (index < outputFiles.size - 1) {
                                        delay(500)
                                    }
                                } finally {
                                    chunkRetriever.release()
                                }
                            }

                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info,
                                    "Successfully sent ${outputFiles.size} parts!"
                                )
                            }

                            context.log.verbose("GalleryVideoSplitting: All chunks sent successfully")

                        } catch (e: Exception) {
                            context.log.error("GalleryVideoSplitting: Failed to split/send video", e)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info,
                                    "Failed: ${e.message}"
                                )
                            }
                        } finally {
                            tempDir.deleteRecursively()
                            isSplitting = false
                        }
                    }

                }.onFailure { e ->
                    context.log.error("GalleryVideoSplitting: Hook error", e)
                }
            }

            context.log.verbose("GalleryVideoSplitting: Hook installed successfully")

        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to initialize", e)
        }
    }
}
