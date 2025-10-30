package me.rhunk.snapenhance.core.features.impl.tweaks

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.core.event.events.impl.ActivityResultEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookAdapter
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import java.io.File
import java.lang.reflect.Method
import kotlin.random.Random

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var splitRequestCode: Int? = null
    private lateinit var chatMediaDrawerActionHandler: Any
    private lateinit var sendItemsMethod: Method

    override fun init() {
        val actionHandlerClass = findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        sendItemsMethod = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
            ?: run {
                context.log.error("Could not find sendItems method, feature disabled.")
                return
            }

        // Original hook for gallery videos
        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            chatMediaDrawerActionHandler = param.thisObject<Any>()

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                if (mediaItems.size != 1) return@hook

                val mediaItem = mediaItems.first() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: return@hook
                val itemType = item.getObjectField("type")?.toString()

                if (itemType == "VIDEO") {
                    param.setResult(null) // Cancel original call

                    val contentUriStr = item.getObjectField("contentUri")?.toString() 
                        ?: throw IllegalStateException("Content URI not found")
                    val mediaUri = Uri.parse(contentUriStr)
                    val conversationIds = param.arg<List<Any>>(0)

                    // Split and send the video
                    splitAndSendVideo(mediaUri, conversationIds, mediaItem, item)
                }
            } catch (e: Exception) {
                context.log.error("Error in GalleryVideoSplitting hook", e)
            }
        }

        // New: Listen for file picker results
        context.event.subscribe(ActivityResultEvent::class) { event ->
            if (event.requestCode != splitRequestCode || event.resultCode != Activity.RESULT_OK) return@subscribe
            splitRequestCode = null

            val selectedUri = event.intent.data ?: return@subscribe
            
            context.coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing selected video...")
                }

                // Split the selected video and send it
                splitAndSendVideoFromPicker(selectedUri, listOf())
            }
        }
    }

    // Trigger file picker for manual video selection
    fun openVideoPicker() {
        if (!::chatMediaDrawerActionHandler.isInitialized) {
            context.log.error("ChatMediaDrawerActionHandler not initialized yet")
            return
        }

        splitRequestCode = Random.nextInt(0, 65535)
        context.mainActivity?.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            },
            splitRequestCode!!
        )
    }

    private fun splitAndSendVideo(
        mediaUri: Uri,
        conversationIds: List<Any>,
        mediaItem: Any,
        item: Any
    ) {
        context.coroutineScope.launch {
            isSplitting = true
            val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
            
            try {
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video...")
                }

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

                for ((index, file) in outputFiles.withIndex()) {
                    val chunkUri = Uri.fromFile(file)
                    val retriever = MediaMetadataRetriever()
                    val newItem: Any?
                    val newMediaItem: Any?

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

                    sendItemsMethod.invoke(chatMediaDrawerActionHandler, conversationIds, listOf(newMediaItem))
                    delay(500)
                }
                
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Video split and sent successfully!")
                }
            } catch (e: Exception) {
                context.log.error("Failed to split and send video", e)
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed to process video.")
                }
            } finally {
                tempDir.deleteRecursively()
                isSplitting = false
            }
        }
    }

    private suspend fun splitAndSendVideoFromPicker(
        selectedUri: Uri,
        conversationIds: List<Any>
    ) {
        if (!::chatMediaDrawerActionHandler.isInitialized) {
            context.log.error("ChatMediaDrawerActionHandler not initialized")
            return
        }

        isSplitting = true
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_picker_${System.currentTimeMillis()}").apply { mkdirs() }
        
        try {
            val cachedVideo = File(tempDir, "input.mp4")

            context.mainActivity!!.contentResolver.openInputStream(selectedUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open input stream for selected video")

            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            if (outputFiles.isEmpty()) throw IllegalStateException("FFmpeg produced no output files.")

            // Create dummy media items for each split
            for ((index, file) in outputFiles.withIndex()) {
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                    val itemClass = findClass("com.snap.memories.api.models.memories.MediaItem")
                    val newItem = itemClass.dataBuilder {
                        set("type", "VIDEO")
                        set("contentUri", chunkUri.toString())
                        set("durationMs", chunkDuration.toDouble())
                        set("width", chunkWidth)
                        set("height", chunkHeight)
                        from("itemId", new = true) {
                            set("itemId", chunkUri.toString())
                            set("type", "VIDEO")
                        }
                    }

                    val mediaItemClass = sendItemsMethod.genericParameterTypes[1].javaClass
                    val newMediaItem = mediaItemClass.dataBuilder {
                        set("item", newItem)
                        set("order", index.toDouble())
                    }

                    sendItemsMethod.invoke(chatMediaDrawerActionHandler, conversationIds, listOf(newMediaItem))
                    delay(500)
                } finally {
                    retriever.release()
                }
            }
            
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Video split and sent successfully!")
            }
        } catch (e: Exception) {
            context.log.error("Failed to split and send picked video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed to process video.")
            }
        } finally {
            tempDir.deleteRecursively()
            isSplitting = false
        }
    }
}
