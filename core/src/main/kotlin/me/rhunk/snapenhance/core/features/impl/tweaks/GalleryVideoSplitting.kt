package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
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
        val actionHandlerClass = findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        val sendItemsMethod = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
            ?: run {
                context.log.error("Could not find sendItems method, feature disabled.")
                return
            }

        hook(sendItemsMethod, HookStage.BEFORE) { param ->
            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            runCatching {
                val mediaItems = param.arg<List<*>>(1)
                if (mediaItems.size != 1) return@runCatching

                val mediaItem = mediaItems.firstOrNull() ?: return@runCatching
                val item = mediaItem.getObjectField<Any>("item") ?: return@runCatching
                val itemType = item.getObjectField<Any>("type")?.toString()

                if (itemType == "VIDEO") {
                    // Cancel original call
                    param.setResult(null)

                    context.coroutineScope.launch {
                        isSplitting = true
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showToast("Processing video...")
                            }

                            val contentUriStr = item.getObjectField<Any>("contentUri")?.toString() ?: throw IllegalStateException("Content URI not found")
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

                            for ((index, file) in outputFiles.withIndex()) {
                                val chunkUri = Uri.fromFile(file)
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(context.androidContext, chunkUri)
                                val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0
                                retriever.release()

                                val newItem = item::class.java.dataBuilder {
                                    from(item)
                                    set("contentUri", chunkUri)
                                    set("durationMs", chunkDuration.toDouble())
                                    set("width", chunkWidth)
                                    set("height", chunkHeight)
                                    from("itemId") {
                                        set("itemId", chunkUri.toString())
                                    }
                                }.build()

                                val newMediaItem = mediaItem::class.java.dataBuilder {
                                    from(mediaItem)
                                    set("item", newItem)
                                    set("order", index.toDouble())
                                }.build()

                                // Invoke the original method for each chunk
                                sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                                delay(500)
                            }
                        } catch (e: Exception) {
                            context.log.error("Failed to split and send video", e)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showToast("Failed to process video.")
                            }
                        } finally {
                            tempDir.deleteRecursively()
                            isSplitting = false
                        }
                    }
                }
            }.onFailure {
                context.log.error("Error in GalleryVideoSplitting hook", it)
            }
        }
    }
}