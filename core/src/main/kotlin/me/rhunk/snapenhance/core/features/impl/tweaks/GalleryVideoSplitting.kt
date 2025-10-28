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

        val actionHandlerClass = runCatching {
            findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        }.getOrElse {
            context.log.error("Could not find ChatMediaDrawerActionHandler class", it)
            return
        }

        val sendItemsMethod = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
        if (sendItemsMethod == null) {
            context.log.error("Could not find sendItems method, feature disabled.")
            return
        }

        context.log.verbose("GalleryVideoSplitting: Found sendItems method")

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, skipping")
                return@hook
            }

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                context.log.verbose("GalleryVideoSplitting: Intercepted sendItems with ${mediaItems.size} items")

                if (mediaItems.size != 1) {
                    context.log.verbose("GalleryVideoSplitting: Multiple items (${mediaItems.size}), skipping")
                    return@hook
                }

                val mediaItem = mediaItems.first() ?: run {
                    context.log.verbose("GalleryVideoSplitting: Media item is null")
                    return@hook
                }

                val item = mediaItem.getObjectField("item") ?: run {
                    context.log.verbose("GalleryVideoSplitting: Item field is null")
                    return@hook
                }

                val itemType = item.getObjectField("type")?.toString()
                context.log.verbose("GalleryVideoSplitting: Item type = $itemType")

                if (itemType != "VIDEO") {
                    context.log.verbose("GalleryVideoSplitting: Not a video, skipping")
                    return@hook
                }

                // Get video duration to check if splitting is needed
                val durationMs = (item.getObjectField("durationMs") as? Double)?.toLong() ?: 0L
                context.log.verbose("GalleryVideoSplitting: Video duration = ${durationMs}ms")

                if (durationMs < 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video is shorter than 10 seconds, skipping")
                    return@hook
                }

                // Cancel the original send
                param.setResult(null)
                context.log.verbose("GalleryVideoSplitting: Canceled original send, starting split process")

                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                    
                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
                        }

                        val contentUriStr = item.getObjectField("contentUri")?.toString()
                        if (contentUriStr == null) {
                            context.log.error("GalleryVideoSplitting: Content URI not found")
                            throw IllegalStateException("Content URI not found")
                        }

                        val mediaUri = Uri.parse(contentUriStr)
                        val cachedVideo = File(tempDir, "input.mp4")

                        // Copy video to temp location
                        context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                            cachedVideo.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IllegalStateException("Failed to open input stream")

                        context.log.verbose("GalleryVideoSplitting: Video cached to ${cachedVideo.absolutePath}")

                        // Split video using FFmpeg
                        val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
                        context.log.verbose("GalleryVideoSplitting: Executing FFmpeg: $command")

                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            val errorMsg = "FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}"
                            context.log.error("GalleryVideoSplitting: $errorMsg")
                            throw IllegalStateException(errorMsg)
                        }

                        val outputFiles = tempDir.listFiles()
                            ?.filter { it.name.startsWith("split_") }
                            ?.sortedBy { it.name }
                            ?: emptyList()

                        if (outputFiles.isEmpty()) {
                            throw IllegalStateException("FFmpeg produced no output files.")
                        }

                        context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} chunks")

                        val conversationIds = param.arg<List<Any>>(0)
                        val actionHandler = param.thisObject<Any>()

                        // Send each chunk
                        for ((index, file) in outputFiles.withIndex()) {
                            context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")
                            
                            val chunkUri = Uri.fromFile(file)
                            val retriever = MediaMetadataRetriever()
                            
                            try {
                                retriever.setDataSource(context.androidContext, chunkUri)
                                
                                val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                                context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} - duration: ${chunkDuration}ms, size: ${chunkWidth}x${chunkHeight}")

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

                                val newMediaItem = mediaItem.javaClass.dataBuilder {
                                    set("thumbnail", mediaItem.getObjectField("thumbnail"))
                                    set("item", newItem)
                                    set("order", index.toDouble())
                                }

                                sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                                context.log.verbose("GalleryVideoSplitting: Sent chunk ${index + 1}")
                                
                                delay(500)
                            } finally {
                                retriever.release()
                            }
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info, 
                                "Video split into ${outputFiles.size} parts"
                            )
                        }
                        context.log.verbose("GalleryVideoSplitting: All chunks sent successfully")

                    } catch (e: Exception) {
                        context.log.error("Failed to split and send video", e)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info, 
                                "Failed to process video: ${e.message}"
                            )
                        }
                    } finally {
                        tempDir.deleteRecursively()
                        isSplitting = false
                        context.log.verbose("GalleryVideoSplitting: Cleanup complete")
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error in GalleryVideoSplitting hook", e)
            }
        }

        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }
}
