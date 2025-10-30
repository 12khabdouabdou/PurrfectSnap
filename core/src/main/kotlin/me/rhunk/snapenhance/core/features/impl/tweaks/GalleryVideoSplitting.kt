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
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        val actionHandlerClass = findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
            ?: run {
                context.log.error("Could not find sendItems method, feature disabled.")
                return
            }

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (isSplitting) {
                return@hook
            }

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                if (mediaItems.size != 1) return@hook

                val mediaItem = mediaItems.first() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: return@hook
                val itemType = item.getObjectField("type")?.toString()

                if (itemType == "VIDEO") {
                    param.setResult(null) // Cancel original call

                    context.coroutineScope.launch {
                        isSplitting = true
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video...")
                            }

                            val contentUriStr = item.getObjectField("contentUri")?.toString() 
                                ?: throw IllegalStateException("Content URI not found")
                            val mediaUri = Uri.parse(contentUriStr)
                            val cachedVideo = File(tempDir, "input.mp4")

                            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                cachedVideo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw IllegalStateException("Failed to open input stream for media URI")

                            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}")
                            }

                            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                            if (outputFiles.isEmpty()) throw IllegalStateException("FFmpeg produced no output files.")

                            val conversationIds = param.arg<List<Any>>(0)
                            val actionHandler = param.thisObject<Any>()

                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info, 
                                    "Sending ${outputFiles.size} clips...",
                                    durationMs = 2000
                                )
                            }

                            for ((index, file) in outputFiles.withIndex()) {
                                val chunkUri = Uri.fromFile(file)
                                val retriever = MediaMetadataRetriever()
                                val newItem: Any
                                val newMediaItem: Any

                                try {
                                    retriever.setDataSource(context.androidContext, chunkUri)
                                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

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
                                    retriever.release()
                                }

                                sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                                delay(500)
                            }

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
                            tempDir.deleteRecursively()
                            isSplitting = false
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error in GalleryVideoSplitting hook", e)
            }
        }
    }
}
