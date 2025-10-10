package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    private var isSplitting = false

    override fun init() {
        val actionHandlerClass = findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        val sendItemsMethod = actionHandlerClass.methods.first { it.name == "sendItems" }

        hook(sendItemsMethod, HookStage.BEFORE) { param ->
            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return@hook

            val mediaItems = param.arg<List<*>>(1)
            if (mediaItems.size != 1) return@hook

            val mediaItem = mediaItems.first()!!
            val item = mediaItem.javaClass.getMethod("getItem").invoke(mediaItem)
            val isVideo = item.javaClass.getMethod("getType").invoke(item).toString() == "VIDEO"

            if (isVideo) {
                param.cancelled = true
                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = java.io.File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                    val retriever = MediaMetadataRetriever()
                    try {
                        context.runOnUiThread {
                            context.inAppOverlay.showToast("Processing video...")
                        }

                        val mediaUri = android.net.Uri.parse(item.javaClass.getMethod("getContentUri").invoke(item).toString())
                        val cachedVideo = java.io.File(tempDir, "input.mp4")

                        context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                            cachedVideo.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        val command = "-i ${cachedVideo.absolutePath} -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            context.log.error("FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}")
                            context.runOnUiThread {
                                context.inAppOverlay.showToast("Failed to split video.")
                            }
                            return@launch
                        }

                        val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                        if (outputFiles.isEmpty()) {
                            context.log.error("FFmpeg produced no output files.")
                            return@launch
                        }

                        val conversationIds = param.arg<List<*>>(0)

                        for ((index, file) in outputFiles.withIndex()) {
                            val chunkUri = android.net.Uri.fromFile(file)
                            retriever.setDataSource(context.androidContext, chunkUri)
                            val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                            val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                            val newItem = item.javaClass.dataBuilder {
                                from(item)
                                set("_contentUri", chunkUri.toString())
                                set("_durationMs", chunkDuration.toDouble())
                                set("_width", chunkWidth)
                                set("_height", chunkHeight)
                                from("_itemId") {
                                    set("_itemId", chunkUri.toString())
                                }
                            }.build()

                            val newMediaItem = mediaItem.javaClass.dataBuilder {
                                from(mediaItem)
                                set("_item", newItem)
                                set("_order", index.toDouble())
                            }.build()

                            param.invokeOriginal(conversationIds, listOf(newMediaItem))
                            delay(500)
                        }
                    } finally {
                        retriever.release()
                        tempDir.deleteRecursively()
                        isSplitting = false
                    }
                }
            }
        }
    }
}