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
import java.lang.reflect.Modifier

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        // Find the abstract/interface class first
        val actionHandlerClass = runCatching {
            findClass("com.snap.composer.memories.ChatMediaDrawerActionHandler")
        }.recoverCatching {
            findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        }.getOrElse {
            context.log.error("Could not find ChatMediaDrawerActionHandler")
            return
        }

        context.log.info("Found abstract class: ${actionHandlerClass.name}")

        // Now find the concrete implementation
        val implementationClass = findImplementation(actionHandlerClass)
        if (implementationClass == null) {
            context.log.error("Could not find concrete implementation of ${actionHandlerClass.name}")
            return
        }

        context.log.info("Found implementation: ${implementationClass.name}")

        val sendItemsMethod: Method = implementationClass.methods.firstOrNull { 
            it.name == "sendItems" && !Modifier.isAbstract(it.modifiers)
        } ?: run {
            context.log.error("Could not find concrete sendItems method in ${implementationClass.name}")
            return
        }

        context.log.info("Hooking sendItems method in ${implementationClass.name}")

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                if (mediaItems.size != 1) return@hook

                val mediaItem = mediaItems.first() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: return@hook
                val itemType = item.getObjectField("type")?.toString()

                if (itemType != "VIDEO") return@hook

                // Get content URI and check duration BEFORE canceling the call
                val contentUriStr = item.getObjectField("contentUri")?.toString() 
                    ?: run {
                        context.log.error("Content URI not found")
                        return@hook
                    }
                
                val mediaUri = Uri.parse(contentUriStr)
                val retriever = MediaMetadataRetriever()
                val durationMs = try {
                    retriever.setDataSource(context.androidContext, mediaUri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    context.log.error("Failed to read video duration", e)
                    0L
                } finally {
                    retriever.release()
                }

                context.log.info("Video duration: ${durationMs}ms")

                // Only split videos longer than 10 seconds
                if (durationMs <= 10000) {
                    context.log.info("Video is ${durationMs}ms, skipping split")
                    return@hook
                }

                // Now cancel the original call and start splitting
                param.setResult(null)

                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                    
                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting ${durationMs / 1000}s video...")
                        }

                        val cachedVideo = File(tempDir, "input.mp4")

                        context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                            cachedVideo.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IllegalStateException("Failed to open input stream")

                        // Fixed FFmpeg command without escaped quotes
                        val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 -avoid_negative_ts make_zero ${tempDir.absolutePath}/split_%03d.mp4"
                        context.log.info("FFmpeg command: $command")
                        
                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            context.log.error("FFmpeg failed: ${session.output}")
                            throw IllegalStateException("FFmpeg failed with code ${session.returnCode}")
                        }

                        val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                        
                        context.log.info("Created ${outputFiles.size} chunks")
                        
                        if (outputFiles.isEmpty()) {
                            throw IllegalStateException("FFmpeg produced no output files")
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} snaps...")
                        }

                        val conversationIds = param.arg<List<Any>>(0)
                        val actionHandler = param.thisObject<Any>()

                        for ((index, file) in outputFiles.withIndex()) {
                            val chunkRetriever = MediaMetadataRetriever()
                            
                            try {
                                val chunkUri = Uri.fromFile(file)
                                chunkRetriever.setDataSource(context.androidContext, chunkUri)
                                val chunkDuration = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val chunkWidth = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                                val chunkHeight = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                                context.log.info("Chunk $index: ${chunkDuration}ms, ${chunkWidth}x${chunkHeight}")

                                // Build new item with correct dataBuilder syntax
                                val newItem = item.javaClass.dataBuilder {
                                    // Copy all fields from original
                                    set("type", item.getObjectField("type"))
                                    set("encryptionInfo", item.getObjectField("encryptionInfo"))
                                    
                                    // Override with chunk-specific values
                                    set("contentUri", chunkUri.toString())
                                    set("durationMs", chunkDuration.toDouble())
                                    set("width", chunkWidth)
                                    set("height", chunkHeight)
                                    
                                    // Create new itemId
                                    from("itemId") {
                                        set("itemId", "split_${System.currentTimeMillis()}_$index")
                                    }
                                }

                                val newMediaItem = mediaItem.javaClass.dataBuilder {
                                    set("thumbnail", mediaItem.getObjectField("thumbnail"))
                                    set("item", newItem)
                                    set("order", index.toDouble())
                                }

                                sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                                delay(800)
                                
                            } catch (e: Exception) {
                                context.log.error("Failed to send chunk $index", e)
                            } finally {
                                chunkRetriever.release()
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sent ${outputFiles.size} snaps!")
                        }
                        
                    } catch (e: Exception) {
                        context.log.error("Failed to split and send video", e)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed: ${e.message}")
                        }
                    } finally {
                        tempDir.deleteRecursively()
                        isSplitting = false
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error in GalleryVideoSplitting hook", e)
            }
        }
    }

    private fun findImplementation(interfaceClass: Class<*>): Class<*>? {
        try {
            context.log.info("Searching for implementations of ${interfaceClass.name}...")
            val dexFile = dalvik.system.DexFile(context.androidContext.applicationInfo.sourceDir)
            val entries = dexFile.entries()
            
            val candidates = mutableListOf<Class<*>>()
            
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                
                // Look for classes in the same package or related packages
                if (!className.contains("composer", ignoreCase = true) && 
                    !className.contains("memories", ignoreCase = true)) {
                    continue
                }
                
                runCatching {
                    val clazz = findClass(className)
                    
                    // Check if this class implements or extends our interface
                    if (interfaceClass.isAssignableFrom(clazz) && 
                        clazz != interfaceClass &&
                        !Modifier.isAbstract(clazz.modifiers) &&
                        !Modifier.isInterface(clazz.modifiers)) {
                        
                        // Check if it has a concrete sendItems method
                        val hasSendItems = clazz.methods.any { 
                            it.name == "sendItems" && !Modifier.isAbstract(it.modifiers)
                        }
                        
                        if (hasSendItems) {
                            context.log.info("  ✓ Found candidate: $className")
                            candidates.add(clazz)
                        }
                    }
                }
            }
            
            if (candidates.isEmpty()) {
                context.log.error("No concrete implementations found")
                return null
            }
            
            // Prefer classes with "Impl" or specific naming patterns
            return candidates.firstOrNull { it.simpleName.contains("Impl") }
                ?: candidates.firstOrNull()
            
        } catch (e: Exception) {
            context.log.error("Failed to find implementation", e)
            return null
        }
    }
}
