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
import me.rhunk.snapenhance.core.util.hook.hookConstructor
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    private var hooked = false

    override fun init() {
        // Hook the interface to catch any implementation being created
        val handlerInterface = findClass("com.snap.composer.memories.ChatMediaDrawerActionHandler")
        
        context.log.info("Searching for implementations of ChatMediaDrawerActionHandler...")
        
        // Hook all constructors of classes in the composer.memories package
        context.androidContext.packageManager.getPackageInfo(
            context.androidContext.packageName, 
            android.content.pm.PackageManager.GET_META_DATA
        )
        
        // Search for implementation classes
        val possibleClasses = listOf(
            "com.snap.composer.memories.ChatMediaDrawerActionHandlerImpl",
            "com.snap.composer.memories.DefaultChatMediaDrawerActionHandler",
            "com.snap.composer.memories.ChatMediaDrawerViewModel",
            "com.snap.composer.memories.ChatMediaDrawerPresenter"
        )
        
        for (className in possibleClasses) {
            try {
                val implClass = findClass(className)
                context.log.info("Found potential implementation: $className")
                hookImplementation(implClass)
            } catch (e: Exception) {
                // Class doesn't exist, continue
            }
        }
        
        // Fallback: Hook any class that gets cast to the interface
        handlerInterface.hookConstructor(HookStage.AFTER) { param ->
            if (!hooked) {
                context.log.info("Found implementation via constructor: ${param.thisObject.javaClass.name}")
                hookImplementation(param.thisObject.javaClass)
            }
        }

        context.log.info("✅ GalleryVideoSplitting initialized")
    }
    
    private fun hookImplementation(implClass: Class<*>) {
        if (hooked) return
        
        try {
            val sendItemsMethod = implClass.methods.firstOrNull { 
                it.name == "sendItems" && it.parameterTypes.size == 2 
            } ?: run {
                context.log.warn("sendItems not found in ${implClass.name}")
                return
            }
            
            context.log.info("Hooking sendItems in ${implClass.name}")
            
            sendItemsMethod.hook(HookStage.BEFORE) { param ->
                context.log.info("🔵 sendItems called in ${implClass.simpleName}")

                if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                    context.log.info("  Skipped: isSplitting=$isSplitting, enabled=${context.config.messaging.splitVideoIntoTenSecondSnaps.get()}")
                    return@hook
                }

                try {
                    val conversationIds = param.arg<List<Any>>(0)
                    val mediaItems = param.arg<List<Any?>>(1)
                    
                    context.log.info("  Conversations: ${conversationIds.size}, Media items: ${mediaItems.size}")

                    if (mediaItems.size != 1) return@hook

                    val mediaItem = mediaItems.first() ?: return@hook
                    val item = mediaItem.getObjectField("item") ?: return@hook
                    val itemType = item.getObjectField("type")?.toString()

                    context.log.info("  Item type: $itemType")

                    if (itemType == "VIDEO") {
                        context.log.info("  ✅ VIDEO detected! Starting split process...")
                        param.setResult(null)

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

                                val mediaUri = Uri.parse(contentUriStr)
                                val cachedVideo = File(tempDir, "input.mp4")

                                context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                                    cachedVideo.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                val inputPath = cachedVideo.absolutePath
                                val outputPattern = "${tempDir.absolutePath}/split_%03d.mp4"
                                val command = "-i $inputPath -c copy -f segment -segment_time 10 -reset_timestamps 1 $outputPattern"

                                context.log.info("  FFmpeg: $command")
                                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                    throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                                }

                                val outputFiles = tempDir.listFiles()
                                    ?.filter { it.name.startsWith("split_") }
                                    ?.sortedBy { it.name }
                                    ?: emptyList()

                                context.log.info("  Created ${outputFiles.size} segments")

                                val actionHandler = param.thisObject<Any>()

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
                                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed: ${e.message}")
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
            
            hooked = true
            context.log.info("✅ Successfully hooked ${implClass.simpleName}.sendItems")
            
        } catch (e: Exception) {
            context.log.error("Failed to hook ${implClass.name}", e)
        }
    }
}
