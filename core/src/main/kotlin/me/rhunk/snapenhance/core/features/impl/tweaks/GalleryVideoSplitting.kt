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
        // The class name from your Frida output
        val actionHandlerClass = context.mappings.getMappedClass(
            "com.snap.composer.memories", 
            "ChatMediaDrawerActionHandler"
        ) ?: run {
            context.log.error("ChatMediaDrawerActionHandler not found in mappings")
            return
        }

        // Find the sendItems method with the exact signature from Frida
        val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { method ->
            method.name == "sendItems" && 
            method.parameterTypes.size == 2 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            List::class.java.isAssignableFrom(method.parameterTypes[1])
        } ?: run {
            context.log.error("sendItems method not found. Available methods:")
            actionHandlerClass.methods.forEach { 
                context.log.error("  ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})")
            }
            return
        }

        context.log.info("✓ Successfully hooked ChatMediaDrawerActionHandler.sendItems")

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            try {
                // Arguments from Frida: (List<ConversationId>, List<MediaItem>)
                val conversationIds = param.arg<List<Any>>(0)
                val mediaItems = param.arg<List<Any?>>(1)
                
                context.log.info("sendItems called: convos=${conversationIds.size}, items=${mediaItems.size}")

                if (mediaItems.size != 1) return@hook

                val mediaItem = mediaItems.first() ?: run {
                    context.log.warn("mediaItem is null")
                    return@hook
                }

                // Try different possible field names (Snapchat obfuscates)
                val item = try {
                    mediaItem.getObjectField("item") 
                        ?: mediaItem.getObjectField("a") 
                        ?: mediaItem.getObjectField("b")
                } catch (e: Exception) {
                    context.log.error("Failed to get item field", e)
                    return@hook
                }

                if (item == null) {
                    context.log.warn("item field is null")
                    return@hook
                }

                // Check item type
                val itemType = item.getObjectField("type")?.toString()
                context.log.info("Item type: $itemType")

                if (itemType == "VIDEO") {
                    param.setResult(null) // Cancel original send

                    context.coroutineScope.launch {
                        isSplitting = true
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                        
                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
                            }

                            val contentUriStr = item.getObjectField("contentUri")?.toString() 
                                ?: throw IllegalStateException("Content URI not found")
                            val mediaUri = Uri.parse(contentUriStr)
                            val cachedVideo = File(tempDir, "input.mp4")

                            // Copy video to cache
                            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                cachedVideo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw IllegalStateException("Failed to open input stream")

                            // FFmpeg split command (fixed for Android)
                            val inputPath = cachedVideo.absolutePath
                            val outputPattern = "${tempDir.absolutePath}/split_%03d.mp4"
                            val command = "-i $inputPath -c copy -f segment -segment_time 10 -reset_timestamps 1 $outputPattern"
                            
                            context.log.info("Running FFmpeg: $command")
                            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                throw IllegalStateException("FFmpeg failed: ${session.output}")
                            }

                            val outputFiles = tempDir.listFiles()
                                ?.filter { it.name.startsWith("split_") }
                                ?.sortedBy { it.name } 
                                ?: emptyList()

                            if (outputFiles.isEmpty()) {
                                throw IllegalStateException("No output files produced")
                            }

                            context.log.info("Split into ${outputFiles.size} segments")

                            val actionHandler = param.thisObject<Any>()

                            // Send each segment
                            for ((index, file) in outputFiles.withIndex()) {
                                val chunkUri = Uri.fromFile(file)
                                val retriever = MediaMetadataRetriever()
                                
                                try {
                                    retriever.setDataSource(context.androidContext, chunkUri)
                                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

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
                                    delay(500)
                                    
                                } finally {
                                    retriever.release()
                                }
                            }

                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sent ${outputFiles.size} segments!")
                            }

                        } catch (e: Exception) {
                            context.log.error("Failed to split video", e)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Split failed: ${e.message}")
                            }
                        } finally {
                            tempDir.deleteRecursively()
                            isSplitting = false
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error in hook", e)
            }
        }
    }
}
