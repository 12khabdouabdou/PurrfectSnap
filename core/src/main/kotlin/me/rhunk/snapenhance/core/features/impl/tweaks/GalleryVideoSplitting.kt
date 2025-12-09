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
        // Find all classes that implement ChatMediaDrawerActionHandler
        val handlerInterface = findClass("com.snap.composer.memories.ChatMediaDrawerActionHandler")
        
        // Hook the class that creates/uses the handler
        // Usually this is the drawer fragment or view model
        val chatMediaDrawerClass = findClass("com.snap.composer.memories.ChatMediaDrawer")
        
        chatMediaDrawerClass.hook("getActionHandler", HookStage.AFTER) { param ->
            val actionHandler = param.getResult() ?: return@hook
            
            // Now hook the actual implementation instance
            val actionHandlerClass = actionHandler.javaClass
            context.log.info("Found implementation: ${actionHandlerClass.name}")
            
            val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
                ?: return@hook

            // Hook the concrete implementation
            sendItemsMethod.hook(HookStage.BEFORE) { sendParam ->
                context.log.info("🔵 sendItems called")

                if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                    return@hook
                }

                try {
                    val conversationIds = sendParam.arg<List<Any>>(0)
                    val mediaItems = sendParam.arg<List<Any?>>(1)
                    
                    context.log.info("  Conversations: ${conversationIds.size}, Media items: ${mediaItems.size}")

                    if (mediaItems.size != 1) return@hook

                    val mediaItem = mediaItems.first() ?: return@hook
                    val item = mediaItem.getObjectField("item") ?: return@hook
                    val itemType = item.getObjectField("type")?.toString()

                    context.log.info("  Item type: $itemType")

                    if (itemType == "VIDEO") {
                        context.log.info("  ✅ VIDEO detected! Starting split process...")
                        sendParam.setResult(null)

                        context.coroutineScope.launch {
                            isSplitting = true
                            val tempDir = File(
                                context.mainActivity!!.cacheDir,
                                "split_video_${System.currentTimeMillis()}"
                            ).apply { mkdirs() }

                            try {
                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video...")
                                }

                                val contentUriStr = item.getObjectField("contentUri")?.toString()
                                    ?: throw IllegalStateException("Content URI not found")

                                context.log.info("  Content URI: $contentUriStr")
                                val mediaUri = Uri.parse(contentUriStr)
                                val cachedVideo = File(tempDir, "input.mp4")

                                context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                    cachedVideo.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                } ?: throw IllegalStateException("Failed to open input stream")

                                context.log.info("  Video cached: ${cachedVideo.length()} bytes")

                                val inputPath = cachedVideo.absolutePath
                                val outputPattern = "${tempDir.absolutePath}/split_%03d.mp4"
                                val command = "-i $inputPath -c copy -f segment -segment_time 10 -reset_timestamps 1 $outputPattern"

                                context.log.info("  Running FFmpeg: $command")
                                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                    throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                                }

                                val outputFiles = tempDir.listFiles()
                                    ?.filter { it.name.startsWith("split_") }
                                    ?.sortedBy { it.name }
                                    ?: emptyList()

                                if (outputFiles.isEmpty()) {
                                    throw IllegalStateException("FFmpeg produced no output files")
                                }

                                context.log.info("  Split into ${outputFiles.size} segments")

                                for ((index, file) in outputFiles.withIndex()) {
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
                                        context.log.info("    ✓ Sent segment ${index + 1}")
                                        delay(500)

                                    } finally {
                                        retriever.release()
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info,
                                        "Sent ${outputFiles.size} segments!"
                                    )
                                }

                            } catch (e: Exception) {
                                context.log.error("Failed to split video", e)
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
                    }
                } catch (e: Exception) {
                    context.log.error("Error in hook", e)
                }
            }
        }

        context.log.info("✅ GalleryVideoSplitting initialized")
    }
}
