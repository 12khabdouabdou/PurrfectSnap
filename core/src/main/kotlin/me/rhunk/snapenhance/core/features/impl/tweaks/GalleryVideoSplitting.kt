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
        // Hook the REAL implementation class: II2
        val actionHandlerImpl = findClass("II2")
        
        val sendItemsMethod = actionHandlerImpl.methods.firstOrNull { 
            it.name == "sendItems" && it.parameterTypes.size == 2 
        } ?: run {
            context.log.error("sendItems not found in II2")
            return
        }

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            context.log.info("🔵 sendItems called in II2")

            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            try {
                val conversationIds = param.arg<List<Any>>(0)
                val mediaItems = param.arg<List<Any?>>(1)
                
                context.log.info("  Items: ${mediaItems.size}")

                if (mediaItems.size != 1) return@hook

                val mediaItem = mediaItems.first() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: return@hook
                val itemType = item.getObjectField("type")?.toString()

                context.log.info("  Type: $itemType")

                if (itemType == "VIDEO") {
                    context.log.info("  ✅ Splitting video...")
                    param.setResult(null)

                    context.coroutineScope.launch {
                        isSplitting = true
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }

                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video...")
                            }

                            val contentUriStr = item.getObjectField("contentUri")?.toString() ?: throw IllegalStateException("URI not found")
                            val mediaUri = Uri.parse(contentUriStr)
                            val cachedVideo = File(tempDir, "input.mp4")

                            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                cachedVideo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
                            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                            }

                            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                            if (outputFiles.isEmpty()) throw IllegalStateException("No output")

                            val actionHandler = param.thisObject<Any>()

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
                            context.log.error("Split failed", e)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed: ${e.message}")
                            }
                        } finally {
                            tempDir.deleteRecursively()
                            isSplitting = false
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error", e)
            }
        }

        context.log.info("✅ GalleryVideoSplitting hooked II2.sendItems")
    }
}
