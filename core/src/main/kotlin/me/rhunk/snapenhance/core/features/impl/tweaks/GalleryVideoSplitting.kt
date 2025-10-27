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
import me.rhunk.snapenhance.core.util.hook.HookAdapter
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import java.io.File
import java.lang.reflect.Method

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        // Log initialization
        context.log.verbose("GalleryVideoSplitting: Initializing feature")
        
        val actionHandlerClass = runCatching {
            findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        }.getOrElse { 
            context.log.error("GalleryVideoSplitting: Could not find ChatMediaDrawerActionHandler class", it)
            return
        }
        
        val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
            ?: run {
                context.log.error("GalleryVideoSplitting: Could not find sendItems method. Available methods: ${actionHandlerClass.methods.joinToString { it.name }}")
                return
            }

        context.log.verbose("GalleryVideoSplitting: Found sendItems method, hooking...")

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            context.log.verbose("GalleryVideoSplitting: sendItems hook triggered!")
            context.log.verbose("GalleryVideoSplitting: isSplitting=$isSplitting, config=${context.config.messaging.splitVideoIntoTenSecondSnaps.get()}")
            
            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                context.log.verbose("GalleryVideoSplitting: Skipping - isSplitting=$isSplitting or feature disabled")
                return@hook
            }

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                context.log.verbose("GalleryVideoSplitting: mediaItems count: ${mediaItems.size}")
                
                if (mediaItems.size != 1) {
                    context.log.verbose("GalleryVideoSplitting: Multiple items, skipping")
                    return@hook
                }

                val mediaItem = mediaItems.first() ?: run {
                    context.log.error("GalleryVideoSplitting: mediaItem is null")
                    return@hook
                }
                
                val item = mediaItem.getObjectField("item") ?: run {
                    context.log.error("GalleryVideoSplitting: item field is null")
                    return@hook
                }
                
                val itemType = item.getObjectField("type")?.toString()
                context.log.verbose("GalleryVideoSplitting: Item type: $itemType")

                if (itemType == "VIDEO") {
                    context.log.verbose("GalleryVideoSplitting: Video detected! Starting split process...")
                    
                    // Get video duration first to check if it needs splitting
                    val contentUriStr = item.getObjectField("contentUri")?.toString()
                    if (contentUriStr == null) {
                        context.log.error("GalleryVideoSplitting: contentUri is null")
                        return@hook
                    }
                    
                    val mediaUri = Uri.parse(contentUriStr)
                    val retriever = MediaMetadataRetriever()
                    
                    try {
                        retriever.setDataSource(context.androidContext, mediaUri)
                        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        context.log.verbose("GalleryVideoSplitting: Video duration: ${durationMs}ms")
                        
                        if (durationMs <= 10000) {
                            context.log.verbose("GalleryVideoSplitting: Video is 10 seconds or less, no splitting needed")
                            return@hook
                        }
                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: Error checking video duration", e)
                        return@hook
                    } finally {
                        retriever.release()
                    }
                    
                    // Cancel original call
                    param.setResult(null)
                    context.log.verbose("GalleryVideoSplitting: Original call cancelled, starting async split")

                    context.coroutineScope.launch {
                        isSplitting = true
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                        
                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
                            }

                            val cachedVideo = File(tempDir, "input.mp4")
                            context.log.verbose("GalleryVideoSplitting: Copying video to cache: ${cachedVideo.absolutePath}")

                            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                cachedVideo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw IllegalStateException("Failed to open input stream for media URI")

                            context.log.verbose("GalleryVideoSplitting: Video cached, size: ${cachedVideo.length()} bytes")

                            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                            context.log.verbose("GalleryVideoSplitting: Executing FFmpeg: $command")
                            
                            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
                            context.log.verbose("GalleryVideoSplitting: FFmpeg return code: ${session.returnCode}")
                            context.log.verbose("GalleryVideoSplitting: FFmpeg output: ${session.output}")

                            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}")
                            }

                            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                            context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} files")
                            
                            if (outputFiles.isEmpty()) throw IllegalStateException("FFmpeg produced no output files.")

                            val conversationIds = param.arg<List<Any>>(0)
                            val actionHandler = param.thisObject<Any>()

                            for ((index, file) in outputFiles.withIndex()) {
                                context.log.verbose("GalleryVideoSplitting: Sending chunk ${index + 1}/${outputFiles.size}: ${file.name}")
                                
                                val chunkUri = Uri.fromFile(file)
                                val chunkRetriever = MediaMetadataRetriever()
                                val newItem: Any?
                                val newMediaItem: Any?

                                try {
                                    chunkRetriever.setDataSource(context.androidContext, chunkUri)
                                    val chunkDuration = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    val chunkWidth = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                    val chunkHeight = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                                    context.log.verbose("GalleryVideoSplitting: Chunk metadata - duration: ${chunkDuration}ms, size: ${chunkWidth}x${chunkHeight}")

                                    newItem = item.javaClass.dataBuilder {
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

                                    newMediaItem = mediaItem.javaClass.dataBuilder {
                                        set("thumbnail", mediaItem.getObjectField("thumbnail"))
                                        set("item", newItem)
                                        set("order", index.toDouble())
                                    }
                                } finally {
                                    chunkRetriever.release()
                                }

                                sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                                delay(500)
                            }
                            
                            context.log.verbose("GalleryVideoSplitting: All chunks sent successfully!")
                            
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Video split sent!")
                            }
                        } catch (e: Exception) {
                            context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed: ${e.message}")
                            }
                        } finally {
                            tempDir.deleteRecursively()
                            isSplitting = false
                            context.log.verbose("GalleryVideoSplitting: Cleanup complete, isSplitting reset")
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Error in hook", e)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }
}
