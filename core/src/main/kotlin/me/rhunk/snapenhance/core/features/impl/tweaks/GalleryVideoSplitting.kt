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
import me.rhunk.snapenhance.common.util.ktx.getTypeArguments
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
            context.log.verbose("GalleryVideoSplitting is disabled in config")
            return
        }

        context.log.verbose("GalleryVideoSplitting init() called")

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
                context.log.verbose("Generic superclass: $genericSuperclass")

                val typeArguments = genericSuperclass?.getTypeArguments()
                context.log.verbose("Type arguments: ${typeArguments?.joinToString()}")

                val typeArg = typeArguments?.getOrNull(1)
                if (typeArg != null) {
                    context.log.verbose("Type argument [1]: ${typeArg.typeName}")
                    
                    val handlerMethod = typeArg.methods.firstOrNull {
                        it.parameterTypes.size == 1 && it.parameterTypes[0].name.endsWith("ChatMediaDrawerActionHandler")
                    }
                    
                    if (handlerMethod != null) {
                        context.log.verbose("Found handler method: ${handlerMethod.name}")
                        context.log.verbose("Handler parameter type: ${handlerMethod.parameterTypes[0].name}")
                        
                        val actionHandlerClass = handlerMethod.parameterTypes[0]
                        context.log.verbose("Action handler class: ${actionHandlerClass.name}, is interface: ${actionHandlerClass.isInterface}")
                        
                        handlerMethod.hook(HookStage.AFTER) { hookParam ->
                            chatMediaDrawerActionHandler = hookParam.arg(0)
                            context.log.verbose("Captured chatMediaDrawerActionHandler: ${chatMediaDrawerActionHandler?.javaClass?.name}")
                            
                            val handler = chatMediaDrawerActionHandler
                            if (handler != null) {
                                val concreteClass = handler.javaClass
                                sendItemsMethod = concreteClass.methods.firstOrNull { it.name == "sendItems" }
                                
                                if (sendItemsMethod != null) {
                                    context.log.verbose("Found concrete sendItems method in: ${concreteClass.name}")
                                    context.log.verbose("Setting up sendItems hook on concrete implementation using object hook")
                                    
                                    me.rhunk.snapenhance.core.util.hook.Hooker.hookObjectMethod(
                                        concreteClass,
                                        handler,
                                        "sendItems",
                                        HookStage.BEFORE
                                    ) { param ->
                                        context.log.verbose("sendItems hook triggered, isSplitting=$isSplitting")
                                        
                                        if (!isSplitting) {
                                            try {
                                                val conversationIds = param.arg<List<Any>>(0)
                                                context.log.verbose("Conversation IDs count: ${conversationIds.size}")
                                                
                                                // Check if user selected conversations
                                                if (conversationIds.isEmpty()) {
                                                    context.log.verbose("No conversations selected, skipping split")
                                                    return@hookObjectMethod
                                                }
                                                
                                                val mediaItems = param.arg<List<Any?>>(1)
                                                context.log.verbose("Media items count: ${mediaItems.size}")
                                                
                                                if (mediaItems.size == 1) {
                                                    val mediaItem = mediaItems.first()
                                                    if (mediaItem != null) {
                                                        context.log.verbose("Got media item: ${mediaItem.javaClass.name}")
                                                        
                                                        val item = mediaItem.getObjectField("_item")
                                                        if (item != null) {
                                                            context.log.verbose("Got item: ${item.javaClass.name}")
                                                            
                                                            val itemId = item.getObjectField("_itemId")
                                                            val itemType = itemId?.getObjectField("_type")?.toString()
                                                            context.log.verbose("Item type: $itemType")

                                                            if (itemType == "VIDEO") {
                                                                // Check video duration before splitting
                                                                val durationMs = (item.getObjectField("_durationMs") as? Double)?.toLong() ?: 0L
                                                                context.log.verbose("Video duration: ${durationMs}ms")
                                                                
                                                                if (durationMs <= 10000) {
                                                                    context.log.verbose("Video is 10s or less, no need to split")
                                                                    return@hookObjectMethod
                                                                }
                                                                
                                                                context.log.verbose("Video detected! Starting split process")
                                                                param.setResult(null) // Cancel original call

                                                                context.coroutineScope.launch {
                                                                    isSplitting = true
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
                                                                                input.copyTo(output)
                                                                            }
                                                                        } ?: throw IllegalStateException("Failed to open input stream for media URI")

                                                                        context.log.verbose("Copied video to cache: ${cachedVideo.absolutePath}, size: ${cachedVideo.length()} bytes")

                                                                        // Improved FFmpeg command with force_key_frames for better splitting
                                                                        val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 -force_key_frames \"expr:gte(t,n_forced*10)\" \"${tempDir.absolutePath}/split_%03d.mp4\""
                                                                        context.log.verbose("Executing FFmpeg command: $command")
                                                                        
                                                                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                                                                        context.log.verbose("FFmpeg return code: ${session.returnCode}")
                                                                        context.log.verbose("FFmpeg output: ${session.output}")

                                                                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                                                            throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}")
                                                                        }

                                                                        val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                                                                        context.log.verbose("Generated ${outputFiles.size} split files")
                                                                        outputFiles.forEachIndexed { index, file ->
                                                                            context.log.verbose("Split $index: ${file.name}, size: ${file.length()} bytes")
                                                                        }
                                                                        
                                                                        if (outputFiles.isEmpty()) {
                                                                            throw IllegalStateException("FFmpeg produced no output files.")
                                                                        }

                                                                        withContext(Dispatchers.Main) {
                                                                            context.inAppOverlay.showStatusToast(
                                                                                Icons.Default.Info, 
                                                                                "Sending ${outputFiles.size} clips...",
                                                                                durationMs = 2000
                                                                            )
                                                                        }

                                                                        val method = sendItemsMethod
                                                                        if (method != null) {
                                                                            for ((index, file) in outputFiles.withIndex()) {
                                                                                context.log.verbose("Processing split $index/${outputFiles.size}")
                                                                                
                                                                                val chunkUri = Uri.fromFile(file)
                                                                                val retriever = MediaMetadataRetriever()

                                                                                try {
                                                                                    retriever.setDataSource(context.androidContext, chunkUri)
                                                                                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                                                                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                                                                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                                                                                    context.log.verbose("Chunk metadata - duration: ${chunkDuration}ms, size: ${chunkWidth}x${chunkHeight}")

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
                                                                                    } ?: throw IllegalStateException("Failed to create new item")

                                                                                    context.log.verbose("Created newItem for chunk $index")

                                                                                    val newMediaItem = mediaItem.javaClass.dataBuilder {
                                                                                        set("_item", newItem)
                                                                                        set("_order", index.toDouble())
                                                                                    } ?: throw IllegalStateException("Failed to create new media item")

                                                                                    context.log.verbose("Created newMediaItem for chunk $index")

                                                                                    context.log.verbose("Invoking sendItems for chunk $index with ${conversationIds.size} conversations")
                                                                                    method.invoke(handler, conversationIds, listOf(newMediaItem))
                                                                                    context.log.verbose("Sent chunk $index successfully")
                                                                                    
                                                                                    // Delay between sends to avoid rate limiting
                                                                                    if (index < outputFiles.size - 1) {
                                                                                        delay(800)
                                                                                    }
                                                                                } catch (e: Exception) {
                                                                                    context.log.error("Failed to process chunk $index", e)
                                                                                    throw e
                                                                                } finally {
                                                                                    retriever.release()
                                                                                }
                                                                            }
                                                                        } else {
                                                                            throw IllegalStateException("sendItemsMethod is null")
                                                                        }

                                                                        context.log.verbose("All chunks sent successfully!")
                                                                        withContext(Dispatchers.Main) {
                                                                            context.inAppOverlay.showStatusToast(
                                                                                Icons.Default.CheckCircle, 
                                                                                "Sent ${outputFiles.size} video clips!"
                                                                            )
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        context.log.error("Failed to split and send video", e)
                                                                        withContext(Dispatchers.Main) {
                                                                            context.inAppOverlay.showStatusToast(
                                                                                Icons.Default.Error, 
                                                                                "Failed to process video: ${e.message}"
                                                                            )
                                                                        }
                                                                    } finally {
                                                                        context.log.verbose("Cleaning up temp directory: ${tempDir.absolutePath}")
                                                                        tempDir.deleteRecursively()
                                                                        isSplitting = false
                                                                        context.log.verbose("Split process completed, isSplitting reset to false")
                                                                    }
                                                                }
                                                            } else {
                                                                context.log.verbose("Not a video, skipping (type: $itemType)")
                                                            }
                                                        } else {
                                                            context.log.warn("Could not get '_item' field from mediaItem")
                                                        }
                                                    } else {
                                                        context.log.verbose("Media item is null")
                                                    }
                                                } else {
                                                    context.log.verbose("Not exactly 1 media item, skipping")
                                                }
                                            } catch (e: Exception) {
                                                context.log.error("Error in GalleryVideoSplitting hook", e)
                                            }
                                        } else {
                                            context.log.verbose("Already splitting, ignoring")
                                        }
                                    }
                                } else {
                                    context.log.error("Could not find sendItems method in concrete class")
                                }
                            }
                        }
                    } else {
                        context.log.error("Could not find method with ChatMediaDrawerActionHandler parameter")
                    }
                } else {
                    context.log.error("Could not get type argument [1] from ChatMediaDrawer, feature disabled.")
                }
            } else {
                context.log.error("Failed to find ChatMediaDrawer class")
            }
        }
    }
}
