package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import java.io.File
import java.lang.reflect.Method

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private data class SplitVideoContext(
        val chunks: List<File>,
        val tempDir: File,
        val conversationIds: List<Any>,
        val actionHandler: Any,
        val sendItemsMethod: Method,
        val originalItem: Any,
        val originalMediaItem: Any
    )
    
    private var currentSplitContext: SplitVideoContext? = null
    private var firstChunkSent = false

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        // Hook the original sendItems to intercept and split videos
        val actionHandlerClass = findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
            ?: run {
                context.log.error("Could not find sendItems method, feature disabled.")
                return
            }

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (isSplitting) return@hook

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                if (mediaItems.size != 1) return@hook

                val mediaItem = mediaItems.first() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: return@hook
                val itemType = item.getObjectField("type")?.toString()

                if (itemType == "VIDEO") {
                    // Check video duration to see if splitting is needed
                    val durationMs = (item.getObjectField("durationMs") as? Double)?.toLong() ?: 0L
                    if (durationMs <= 10000) {
                        // Video is 10 seconds or less, no need to split
                        return@hook
                    }

                    param.setResult(null) // Cancel original call

                    context.coroutineScope.launch {
                        isSplitting = true
                        firstChunkSent = false
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                        
                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
                            }

                            val contentUriStr = item.getObjectField("contentUri")?.toString() 
                                ?: throw IllegalStateException("Content URI not found")
                            val mediaUri = Uri.parse(contentUriStr)
                            val cachedVideo = File(tempDir, "input.mp4")

                            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                cachedVideo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw IllegalStateException("Failed to open input stream")

                            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                            }

                            val outputFiles = tempDir.listFiles()?.filter { 
                                it.name.startsWith("split_") 
                            }?.sortedBy { it.name } ?: emptyList()
                            
                            if (outputFiles.isEmpty()) {
                                throw IllegalStateException("FFmpeg produced no output files.")
                            }

                            val conversationIds = param.arg<List<Any>>(0)
                            val actionHandler = param.thisObject<Any>()

                            // Store the context for later chunks
                            currentSplitContext = SplitVideoContext(
                                chunks = outputFiles,
                                tempDir = tempDir,
                                conversationIds = conversationIds,
                                actionHandler = actionHandler,
                                sendItemsMethod = sendItemsMethod,
                                originalItem = item,
                                originalMediaItem = mediaItem
                            )

                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info, 
                                    "Sending chunk 1/${outputFiles.size}..."
                                )
                            }

                            // Send first chunk through normal flow
                            val firstChunk = outputFiles.first()
                            val newMediaItem = createMediaItemForChunk(
                                firstChunk, 
                                item, 
                                mediaItem, 
                                0
                            )

                            isSplitting = false // Allow the send to go through
                            sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))

                        } catch (e: Exception) {
                            context.log.error("Failed to split video", e)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed to process video: ${e.message}")
                            }
                            isSplitting = false
                            firstChunkSent = false
                            currentSplitContext = null
                            tempDir.deleteRecursively()
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error in GalleryVideoSplitting hook", e)
                isSplitting = false
            }
        }

        // Intercept SendMessageWithContentEvent to send remaining chunks after first one
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            val splitContext = currentSplitContext ?: return@subscribe
            
            // Only process EXTERNAL_MEDIA (gallery items)
            if (event.messageContent.contentType != ContentType.EXTERNAL_MEDIA) return@subscribe
            
            // Check if this is from our split video
            val messageProtoReader = ProtoReader(event.messageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe // Skip story replies
            
            // Mark that first chunk was sent and send remaining chunks
            if (!firstChunkSent) {
                firstChunkSent = true
                
                context.coroutineScope.launch {
                    try {
                        delay(1000) // Wait for first chunk to be processed
                        
                        val chunks = splitContext.chunks
                        val totalChunks = chunks.size
                        
                        // Send remaining chunks (skip first one)
                        for (index in 1 until chunks.size) {
                            try {
                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info, 
                                        "Sending chunk ${index + 1}/$totalChunks..."
                                    )
                                }
                                
                                val chunk = chunks[index]
                                val newMediaItem = createMediaItemForChunk(
                                    chunk,
                                    splitContext.originalItem,
                                    splitContext.originalMediaItem,
                                    index
                                )
                                
                                splitContext.sendItemsMethod.invoke(
                                    splitContext.actionHandler,
                                    splitContext.conversationIds,
                                    listOf(newMediaItem)
                                )
                                
                                delay(1500) // Wait between sends to avoid rate limiting
                                
                            } catch (e: Exception) {
                                context.log.error("Failed to send chunk $index", e)
                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info,
                                        "Failed to send chunk ${index + 1}"
                                    )
                                }
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info,
                                "All chunks sent successfully!"
                            )
                        }
                        
                    } catch (e: Exception) {
                        context.log.error("Failed to send remaining chunks", e)
                    } finally {
                        // Cleanup
                        splitContext.tempDir.deleteRecursively()
                        currentSplitContext = null
                        firstChunkSent = false
                    }
                }
            }
        }
    }

    private fun createMediaItemForChunk(
        chunkFile: File,
        originalItem: Any,
        originalMediaItem: Any,
        index: Int
    ): Any {
        val chunkUri = Uri.fromFile(chunkFile)
        val retriever = MediaMetadataRetriever()
        
        return try {
            retriever.setDataSource(context.androidContext, chunkUri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

            val newItem = originalItem.javaClass.dataBuilder {
                set("type", originalItem.getObjectField("type"))
                set("encryptionInfo", originalItem.getObjectField("encryptionInfo"))
                set("contentUri", chunkUri.toString())
                set("durationMs", duration.toDouble())
                set("width", width)
                set("height", height)
                from("itemId", new = true) {
                    set("itemId", "${chunkUri}_$index")
                }
            }

            originalMediaItem.javaClass.dataBuilder {
                set("thumbnail", originalMediaItem.getObjectField("thumbnail"))
                set("item", newItem)
                set("order", index.toDouble())
            }
        } finally {
            retriever.release()
        }
    }
}
