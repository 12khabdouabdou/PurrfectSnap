package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.util.ktx.getTypeArguments
import me.rhunk.snapenhance.common.util.protobuf.ProtoEditor
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
import me.rhunk.snapenhance.core.wrapper.impl.Message
import java.io.File
import java.lang.reflect.Method

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.verbose("GalleryVideoSplitting is disabled in config")
            return
        }

        context.log.verbose("GalleryVideoSplitting init() called")

        // Hook for gallery uploads via SendMessageWithContentEvent
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            context.log.verbose("=== SendMessageWithContentEvent triggered ===")
            context.log.verbose("isSplitting=$isSplitting")
            
            if (isSplitting) {
                context.log.verbose("Already splitting, ignoring gallery upload")
                return@subscribe
            }

            val localMessageContent = event.messageContent
            context.log.verbose("Content type: ${localMessageContent.contentType}")
            
            // Only process external media (gallery videos)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                context.log.verbose("Not external media, skipping")
                return@subscribe
            }

            // Check if this has external content metadata
            val externalMetadata = localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata")
            context.log.verbose("External metadata present: ${externalMetadata != null}")
            
            if (externalMetadata == null) {
                context.log.verbose("No external content metadata, skipping")
                return@subscribe
            }

            val messageContent = localMessageContent.content
            if (messageContent == null) {
                context.log.verbose("Message content is null, skipping")
                return@subscribe
            }
            
            context.log.verbose("Message content size: ${messageContent.size} bytes")
            
            val messageProtoReader = ProtoReader(messageContent)
            
            // Log proto structure
            context.log.verbose("Proto contains path 7 (story reply): ${messageProtoReader.contains(7)}")
            context.log.verbose("Proto path 3 exists: ${messageProtoReader.followPath(3) != null}")
            
            // Skip story replies
            if (messageProtoReader.contains(7)) {
                context.log.verbose("Story reply detected, skipping")
                return@subscribe
            }

            // Skip if multiple media items
            val mediaItemCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
            context.log.verbose("Media item count: $mediaItemCount")
            
            if (mediaItemCount > 1) {
                context.log.verbose("Multiple media items, skipping")
                return@subscribe
            }

            // Get video duration from proto (path 3,3,5,1,1,15)
            val videoDurationMs = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)
            context.log.verbose("Video duration from proto path [3,3,5,1,1,15]: ${videoDurationMs}ms")

            if (videoDurationMs == null) {
                context.log.verbose("Could not extract video duration, skipping")
                return@subscribe
            }
            
            if (videoDurationMs <= 10000) {
                context.log.verbose("Video is ${videoDurationMs}ms (≤10s), no need to split")
                return@subscribe
            }

            // Get the media type to ensure it's video
            val mediaType = messageProtoReader.getVarInt(3, 3, 5, 2, 5)
            context.log.verbose("Media type from proto path [3,3,5,2,5]: $mediaType (1=VIDEO)")
            
            if (mediaType != 1) {
                context.log.verbose("Not a video, skipping")
                return@subscribe
            }

            // Get content URI - try multiple paths
            var contentUriStr = messageProtoReader.getString(3, 3, 3)
            context.log.verbose("Content URI from path [3,3,3]: $contentUriStr")
            
            if (contentUriStr.isNullOrEmpty()) {
                // Try alternative path
                val uriBytes = messageProtoReader.getByteArray(3, 3, 3)
                context.log.verbose("URI bytes length: ${uriBytes?.size}")
                if (uriBytes != null && uriBytes.isNotEmpty()) {
                    contentUriStr = String(uriBytes)
                    context.log.verbose("Content URI from bytes: $contentUriStr")
                }
            }
            
            if (contentUriStr.isNullOrEmpty()) {
                context.log.verbose("Could not extract content URI from proto, skipping")
                return@subscribe
            }

            context.log.verbose("✓ Gallery video detected: URI=$contentUriStr, duration=${videoDurationMs}ms")
            context.log.verbose("Destinations: conversations=${event.destinations.conversations?.size}, stories=${event.destinations.stories?.size}")

            // Cancel the original send
            event.canceled = true
            context.log.verbose("Canceled original gallery send, starting split process")

            context.coroutineScope.launch {
                isSplitting = true
                context.log.verbose("Set isSplitting=true")
                splitAndSendVideo(contentUriStr, videoDurationMs.toLong(), event)
            }
        }

        // Hook for media drawer uploads
        onNextActivityCreate(defer = true) {
            context.log.verbose("GalleryVideoSplitting onNextActivityCreate called")
            
            var chatMediaDrawerActionHandler: Any? = null
            var sendItemsMethod: Method? = null

            val chatMediaDrawerClass = runCatching {
                findClass("com.snap.composer.memories.ChatMediaDrawer")
            }.getOrNull()

            if (chatMediaDrawerClass != null) {
                context.log.verbose("Found ChatMediaDrawer class: ${chatMediaDrawerClass.name}")

                val genericSuperclass = chatMediaDrawerClass.genericSuperclass
                val typeArguments = genericSuperclass?.getTypeArguments()

                val typeArg = typeArguments?.getOrNull(1)
                if (typeArg != null) {
                    val handlerMethod = typeArg.methods.firstOrNull {
                        it.parameterTypes.size == 1 && it.parameterTypes[0].name.endsWith("ChatMediaDrawerActionHandler")
                    }
                    
                    if (handlerMethod != null) {
                        val actionHandlerClass = handlerMethod.parameterTypes[0]
                        context.log.verbose("Action handler class: ${actionHandlerClass.name}")
                        
                        handlerMethod.hook(HookStage.AFTER) { hookParam ->
                            chatMediaDrawerActionHandler = hookParam.arg(0)
                            context.log.verbose("Captured chatMediaDrawerActionHandler: ${chatMediaDrawerActionHandler?.javaClass?.name}")
                            
                            val handler = chatMediaDrawerActionHandler
                            if (handler != null) {
                                val concreteClass = handler.javaClass
                                sendItemsMethod = concreteClass.methods.firstOrNull { it.name == "sendItems" }
                                
                                if (sendItemsMethod != null) {
                                    context.log.verbose("Setting up sendItems hook on concrete implementation")
                                    
                                    me.rhunk.snapenhance.core.util.hook.Hooker.hookObjectMethod(
                                        concreteClass,
                                        handler,
                                        "sendItems",
                                        HookStage.BEFORE
                                    ) { param ->
                                        context.log.verbose("=== Media drawer sendItems triggered ===")
                                        context.log.verbose("isSplitting=$isSplitting")
                                        
                                        if (!isSplitting) {
                                            try {
                                                val conversationIds = param.arg<List<Any>>(0)
                                                context.log.verbose("Conversation IDs count: ${conversationIds.size}")
                                                
                                                if (conversationIds.isEmpty()) {
                                                    context.log.verbose("No conversations selected, skipping")
                                                    return@hookObjectMethod
                                                }
                                                
                                                val mediaItems = param.arg<List<Any?>>(1)
                                                context.log.verbose("Media items count: ${mediaItems.size}")
                                                
                                                if (mediaItems.size == 1) {
                                                    val mediaItem = mediaItems.first()
                                                    if (mediaItem != null) {
                                                        context.log.verbose("Media item class: ${mediaItem.javaClass.name}")
                                                        
                                                        val item = mediaItem.getObjectField("_item")
                                                        if (item != null) {
                                                            context.log.verbose("Item class: ${item.javaClass.name}")
                                                            
                                                            val itemId = item.getObjectField("_itemId")
                                                            val itemType = itemId?.getObjectField("_type")?.toString()
                                                            context.log.verbose("Item type: $itemType")

                                                            if (itemType == "VIDEO") {
                                                                val durationMs = (item.getObjectField("_durationMs") as? Double)?.toLong() ?: 0L
                                                                context.log.verbose("Video duration: ${durationMs}ms")
                                                                
                                                                if (durationMs > 10000) {
                                                                    context.log.verbose("✓ Video >10s detected via media drawer, splitting")
                                                                    param.setResult(null)

                                                                    context.coroutineScope.launch {
                                                                        isSplitting = true
                                                                        context.log.verbose("Set isSplitting=true")
                                                                        splitAndSendViaMediaDrawer(
                                                                            item,
                                                                            mediaItem,
                                                                            conversationIds,
                                                                            handler,
                                                                            sendItemsMethod!!
                                                                        )
                                                                    }
                                                                } else {
                                                                    context.log.verbose("Video ≤10s, no split needed")
                                                                }
                                                            } else {
                                                                context.log.verbose("Not a video, skipping")
                                                            }
                                                        } else {
                                                            context.log.warn("Could not get '_item' field")
                                                        }
                                                    } else {
                                                        context.log.verbose("Media item is null")
                                                    }
                                                } else {
                                                    context.log.verbose("Not exactly 1 media item, skipping")
                                                }
                                            } catch (e: Exception) {
                                                context.log.error("Error in media drawer hook", e)
                                            }
                                        } else {
                                            context.log.verbose("Already splitting, ignoring")
                                        }
                                    }
                                } else {
                                    context.log.error("Could not find sendItems method")
                                }
                            }
                        }
                    } else {
                        context.log.error("Could not find handler method")
                    }
                } else {
                    context.log.error("Could not get type argument [1]")
                }
            } else {
                context.log.error("Failed to find ChatMediaDrawer class")
            }
        }
    }

    private suspend fun splitAndSendVideo(contentUriStr: String, originalDurationMs: Long, originalEvent: SendMessageWithContentEvent) {
        context.log.verbose("=== splitAndSendVideo START ===")
        context.log.verbose("URI: $contentUriStr")
        context.log.verbose("Duration: ${originalDurationMs}ms")
        
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
        context.log.verbose("Created temp directory: ${tempDir.absolutePath}")
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting ${originalDurationMs/1000}s video...")
            }

            val mediaUri = Uri.parse(contentUriStr)
            context.log.verbose("Parsed URI: $mediaUri")
            
            val cachedVideo = File(tempDir, "input.mp4")

            context.log.verbose("Opening input stream from content resolver...")
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    context.log.verbose("Copied $bytesCopied bytes to cache")
                }
            } ?: throw IllegalStateException("Failed to open input stream")

            context.log.verbose("Cached video: ${cachedVideo.absolutePath}, size=${cachedVideo.length()} bytes")

            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 -break_non_keyframes 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
            context.log.verbose("Executing FFmpeg command: $command")
            
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            val returnCode = session.returnCode
            context.log.verbose("FFmpeg return code: $returnCode")
            context.log.verbose("FFmpeg output length: ${session.output?.length ?: 0} chars")

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(returnCode)) {
                context.log.error("FFmpeg output: ${session.output}")
                context.log.error("FFmpeg error: ${session.failStackTrace}")
                throw IllegalStateException("FFmpeg failed: $returnCode")
            }

            val allFiles = tempDir.listFiles()
            context.log.verbose("All files in temp dir: ${allFiles?.joinToString { it.name }}")
            
            val outputFiles = allFiles?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            context.log.verbose("Generated ${outputFiles.size} split files:")
            outputFiles.forEachIndexed { index, file ->
                context.log.verbose("  [$index] ${file.name} - ${file.length()} bytes")
            }
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No output files generated")
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Sending ${outputFiles.size} clips...",
                    durationMs = 2000
                )
            }

            val originalProto = ProtoReader(originalEvent.messageContent.content!!)
            context.log.verbose("Original proto size: ${originalEvent.messageContent.content!!.size} bytes")

            // Send each clip by creating a new message for each
            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("--- Processing clip ${index + 1}/${outputFiles.size} ---")
                context.log.verbose("File: ${file.name}")
                
                val chunkUri = Uri.fromFile(file)
                context.log.verbose("Chunk URI: $chunkUri")
                
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 1080L
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 1920L
                    
                    context.log.verbose("Chunk metadata: duration=${chunkDuration}ms, size=${chunkWidth}x${chunkHeight}")

                    // Rebuild the proto message with the new chunk data
                    context.log.verbose("Rebuilding proto message...")
                    val newProto = ProtoEditor(originalEvent.messageContent.content!!).apply {
                        // Update content URI (path 3,3,3)
                        context.log.verbose("Updating content URI at path [3,3,3]")
                        edit(3, 3) {
                            remove(3)
                            addString(3, chunkUri.toString())
                        }
                        
                        // Update video duration (path 3,3,5,1,1,15)
                        context.log.verbose("Updating duration at path [3,3,5,1,1,15]")
                        edit(3, 3, 5, 1, 1) {
                            remove(15)
                            addVarInt(15, chunkDuration)
                        }

                        // Update width (path 3,3,5,1,1,12)
                        context.log.verbose("Updating width at path [3,3,5,1,1,12]")
                        edit(3, 3, 5, 1, 1) {
                            remove(12)
                            addVarInt(12, chunkWidth)
                        }

                        // Update height (path 3,3,5,1,1,13)
                        context.log.verbose("Updating height at path [3,3,5,1,1,13]")
                        edit(3, 3, 5, 1, 1) {
                            remove(13)
                            addVarInt(13, chunkHeight)
                        }
                    }.toByteArray()

                    context.log.verbose("New proto size: ${newProto.size} bytes")

                    // Update the message content
                    originalEvent.messageContent.content = newProto
                    context.log.verbose("Updated message content")

                    // Update the content URI in external metadata
                    originalEvent.messageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata")?.let { metadata ->
                        context.log.verbose("Updating mExternalContentMetadata URI")
                        runCatching {
                            val field = metadata.javaClass.getDeclaredField("mContentUri")
                            field.isAccessible = true
                            field.set(metadata, chunkUri)
                            context.log.verbose("Successfully updated mContentUri field")
                        }.onFailure {
                            context.log.warn("Failed to update mContentUri: ${it.message}")
                        }
                    } ?: context.log.warn("mExternalContentMetadata is null")

                    // Invoke the original send
                    context.log.verbose("Invoking originalEvent.invokeOriginal()...")
                    originalEvent.invokeOriginal()
                    context.log.verbose("✓ Clip $index sent successfully")
                    
                    if (index < outputFiles.size - 1) {
                        context.log.verbose("Waiting 1000ms before next send...")
                        delay(1000)
                    }
                } catch (e: Exception) {
                    context.log.error("Failed to send clip $index", e)
                    throw e
                } finally {
                    retriever.release()
                }
            }

            context.log.verbose("All clips sent successfully!")
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.CheckCircle, 
                    "Sent ${outputFiles.size} clips!"
                )
            }
        } catch (e: Exception) {
            context.log.error("Failed to split gallery video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Error, 
                    "Failed: ${e.message}"
                )
            }
        } finally {
            context.log.verbose("Cleaning up temp directory: ${tempDir.absolutePath}")
            val deleted = tempDir.deleteRecursively()
            context.log.verbose("Temp directory deleted: $deleted")
            isSplitting = false
            context.log.verbose("Set isSplitting=false")
            context.log.verbose("=== splitAndSendVideo END ===")
        }
    }

    private suspend fun splitAndSendViaMediaDrawer(
        item: Any,
        mediaItem: Any,
        conversationIds: List<Any>,
        handler: Any,
        sendItemsMethod: Method
    ) {
        context.log.verbose("=== splitAndSendViaMediaDrawer START ===")
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
        context.log.verbose("Created temp directory: ${tempDir.absolutePath}")
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video...")
            }

            val contentUriStr = item.getObjectField("_contentUri")?.toString() 
                ?: throw IllegalStateException("Content URI not found")
            context.log.verbose("Content URI: $contentUriStr")
            
            val mediaUri = Uri.parse(contentUriStr)
            val cachedVideo = File(tempDir, "input.mp4")

            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    context.log.verbose("Copied $bytesCopied bytes")
                }
            } ?: throw IllegalStateException("Failed to open input stream")

            context.log.verbose("Cached video: ${cachedVideo.length()} bytes")

            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 -break_non_keyframes 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
            context.log.verbose("Executing FFmpeg: $command")
            
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            context.log.verbose("FFmpeg return code: ${session.returnCode}")

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                context.log.error("FFmpeg failed: ${session.output}")
                throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            context.log.verbose("Generated ${outputFiles.size} split files")
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No output files")
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Sending ${outputFiles.size} clips...",
                    durationMs = 2000
                )
            }

            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("--- Processing clip ${index + 1}/${outputFiles.size} ---")
                
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                    context.log.verbose("Chunk metadata: ${chunkDuration}ms, ${chunkWidth}x${chunkHeight}")

                    context.log.verbose("Creating new item via dataBuilder...")
                    val newItem = item.javaClass.dataBuilder {
                        set("_cameraRollSource", "Snapchat")
                        set("_contentUri", chunkUri.toString())
                        set("_durationMs", chunkDuration.toDouble())
                        set("_disabled", false)
                        set("_imageRotation", 0.0)
                        set("_width", chunkWidth)
                        set("_height", chunkHeight)
                        set("_timestampMs", System.currentTimeMillis().toDouble())
                        from("_itemId") {
                            set("_itemId", chunkUri.toString())
                            set("_type", "VIDEO")
                        }
                    } ?: throw IllegalStateException("Failed to create item")

                    context.log.verbose("Created newItem")

                    val newMediaItem = mediaItem.javaClass.dataBuilder {
                        set("_item", newItem)
                        set("_order", index.toDouble())
                    } ?: throw IllegalStateException("Failed to create media item")

                    context.log.verbose("Created newMediaItem")

                    context.log.verbose("Invoking sendItemsMethod...")
                    sendItemsMethod.invoke(handler, conversationIds, listOf(newMediaItem))
                    context.log.verbose("✓ Clip $index sent")
                    
                    if (index < outputFiles.size - 1) {
                        context.log.verbose("Waiting 800ms...")
                        delay(800)
                    }
                } catch (e: Exception) {
                    context.log.error("Failed to send clip $index", e)
                    throw e
                } finally {
                    retriever.release()
                }
            }

            context.log.verbose("All clips sent!")
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.CheckCircle, 
                    "Sent ${outputFiles.size} clips!"
                )
            }
        } catch (e: Exception) {
            context.log.error("Failed to split media drawer video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Error, 
                    "Failed: ${e.message}"
                )
            }
        } finally {
            val deleted = tempDir.deleteRecursively()
            context.log.verbose("Cleaned up temp directory: $deleted")
            isSplitting = false
            context.log.verbose("Set isSplitting=false")
            context.log.verbose("=== splitAndSendViaMediaDrawer END ===")
        }
    }
}
