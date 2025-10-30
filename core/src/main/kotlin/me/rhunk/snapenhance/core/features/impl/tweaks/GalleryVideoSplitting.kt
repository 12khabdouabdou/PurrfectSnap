package me.rhunk.snapenhance.core.features.impl.tweaks

import android.content.ContentResolver
import android.database.Cursor
import android.database.CursorWrapper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.util.ktx.getLongOrNull
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var currentVideoId: Long? = null
    private var currentVideoInputStream: InputStream? = null

    override fun init() {
        context.log.info("Initializing GalleryVideoSplitting feature")
        
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.info("Feature is disabled in config, skipping initialization")
            return
        }
        
        // Hook ContentResolver to intercept media access
        setupContentResolverHooks()
        context.log.info("ContentResolver hooks setup complete")
        
        // Try to find the ChatMediaDrawerActionHandler class
        val actionHandlerClass = runCatching {
            findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        }.getOrElse { error ->
            context.log.error("Failed to find ChatMediaDrawerActionHandler class", error)
            context.log.info("Trying alternative approach using onNextActivityCreate...")
            
            // Alternative approach: defer initialization until we can find the class
            onNextActivityCreate(defer = true) {
                tryAlternativeInit()
            }
            return
        }
        
        context.log.info("Found ChatMediaDrawerActionHandler class: ${actionHandlerClass.name}")
        
        val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
            ?: run {
                context.log.error("Could not find sendItems method, feature disabled.")
                context.log.info("Available methods: ${actionHandlerClass.methods.joinToString { it.name }}")
                return
            }
        context.log.info("Found sendItems method: ${sendItemsMethod.name}")
        
        hookSendItemsMethod(sendItemsMethod)
    }
    
    private fun tryAlternativeInit() {
        context.log.info("Attempting alternative initialization approach")
        
        // Try to find ChatMediaDrawer and hook its action handler
        runCatching {
            val chatMediaDrawerClass = findClass("com.snap.composer.memories.ChatMediaDrawer")
            context.log.info("Found ChatMediaDrawer class: ${chatMediaDrawerClass.name}")
            
            // Find the generic superclass that contains the action handler type
            val actionHandlerType = chatMediaDrawerClass.genericSuperclass?.let { superType ->
                context.log.verbose("Generic superclass: $superType")
                
                // Extract type arguments to find action handler
                if (superType is java.lang.reflect.ParameterizedType) {
                    superType.actualTypeArguments.getOrNull(1) as? Class<*>
                }
            }
            
            if (actionHandlerType != null && actionHandlerType is Class<*>) {
                context.log.info("Found action handler type: ${actionHandlerType.name}")
                
                val sendItemsMethod: Method? = actionHandlerType.methods.firstOrNull { method -> method.name == "sendItems" }
                if (sendItemsMethod != null) {
                    context.log.info("Found sendItems method via alternative approach")
                    hookSendItemsMethod(sendItemsMethod)
                } else {
                    context.log.error("Could not find sendItems method in action handler type")
                    context.log.info("Available methods: ${actionHandlerType.methods.joinToString { method -> method.name }}")
                }
            } else {
                context.log.error("Could not extract action handler type from ChatMediaDrawer")
            }
        }.onFailure { error ->
            context.log.error("Alternative initialization failed", error)
        }
    }
    
    private fun hookSendItemsMethod(sendItemsMethod: Method) {
        context.log.info("Hooking sendItems method: ${sendItemsMethod.declaringClass.name}.${sendItemsMethod.name}")

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            context.log.verbose("sendItems hook triggered - isSplitting: $isSplitting, config enabled: ${context.config.messaging.splitVideoIntoTenSecondSnaps.get()}")
            
            if (isSplitting || !context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@hook
            }

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                context.log.verbose("Media items count: ${mediaItems.size}")
                
                if (mediaItems.size != 1) {
                    context.log.verbose("Skipping: Multiple media items (${mediaItems.size})")
                    return@hook
                }

                val mediaItem = mediaItems.first() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: return@hook
                val itemType = item.getObjectField("type")?.toString()
                
                context.log.info("Media item type: $itemType")

                if (itemType == "VIDEO") {
                    context.log.info("Video detected, starting split process")
                    param.setResult(null) // Cancel original call

                    context.coroutineScope.launch {
                        isSplitting = true
                        context.log.info("isSplitting flag set to true")
                        
                        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                        context.log.info("Created temp directory: ${tempDir.absolutePath}")
                        
                        try {
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video...")
                            }

                            // Extract video ID from itemId
                            val itemId = item.getObjectField("itemId")?.getObjectField("itemId")?.toString()
                            context.log.info("Extracted itemId: $itemId")
                            
                            val videoId = itemId?.toLongOrNull()
                            context.log.info("Parsed videoId: $videoId")
                            
                            // Get the actual file URI from MediaStore
                            val mediaUri = if (videoId != null) {
                                context.log.info("Getting URI from MediaStore for videoId: $videoId")
                                getVideoUriFromMediaStore(videoId)
                            } else {
                                // Fallback to contentUri if itemId parsing fails
                                val contentUriStr = item.getObjectField("contentUri")?.toString()
                                context.log.warn("videoId is null, falling back to contentUri: $contentUriStr")
                                
                                if (contentUriStr.isNullOrEmpty()) {
                                    throw IllegalStateException("Could not determine video URI")
                                }
                                Uri.parse(contentUriStr)
                            }
                            
                            context.log.info("Final media URI: $mediaUri")

                            val cachedVideo = File(tempDir, "input.mp4")
                            context.log.info("Caching video to: ${cachedVideo.absolutePath}")

                            // Copy the video file to cache
                            context.androidContext.contentResolver.openInputStream(mediaUri)?.use { input ->
                                cachedVideo.outputStream().use { output ->
                                    val bytesCopied = input.copyTo(output)
                                    context.log.info("Copied $bytesCopied bytes to cache")
                                }
                            } ?: throw IllegalStateException("Failed to open input stream for media URI: $mediaUri")

                            context.log.info("Video cached successfully, starting FFmpeg split")
                            
                            // Split video using FFmpeg
                            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                            context.log.info("FFmpeg command: $command")
                            
                            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
                            context.log.info("FFmpeg return code: ${session.returnCode}")

                            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                                context.log.error("FFmpeg output: ${session.output}")
                                context.log.error("FFmpeg fail stack trace: ${session.failStackTrace}")
                                throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}")
                            }

                            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                            context.log.info("FFmpeg produced ${outputFiles.size} output files")
                            outputFiles.forEach { file ->
                                context.log.verbose("Output file: ${file.name} (${file.length()} bytes)")
                            }
                            
                            if (outputFiles.isEmpty()) throw IllegalStateException("FFmpeg produced no output files.")

                            val conversationIds = param.arg<List<Any>>(0)
                            context.log.info("Sending to ${conversationIds.size} conversation(s)")
                            
                            val actionHandler = param.thisObject<Any>()

                            // Send each chunk
                            for ((index, file) in outputFiles.withIndex()) {
                                context.log.info("Sending chunk ${index + 1}/${outputFiles.size}: ${file.name}")
                                sendVideoChunk(
                                    file = file,
                                    index = index,
                                    item = item,
                                    mediaItem = mediaItem,
                                    actionHandler = actionHandler,
                                    conversationIds = conversationIds,
                                    sendItemsMethod = sendItemsMethod
                                )
                                context.log.info("Chunk ${index + 1} sent successfully")
                                delay(500)
                            }
                            
                            context.log.info("All chunks sent successfully")
                        } catch (e: Exception) {
                            context.log.error("Failed to split and send video", e)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed to process video: ${e.message}")
                            }
                        } finally {
                            context.log.info("Cleaning up temp directory: ${tempDir.absolutePath}")
                            tempDir.deleteRecursively()
                            isSplitting = false
                            currentVideoId = null
                            currentVideoInputStream = null
                            context.log.info("Cleanup complete, isSplitting set to false")
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error in GalleryVideoSplitting hook", e)
            }
        }
    }

    private fun setupContentResolverHooks() {
        context.log.info("Setting up ContentResolver hooks")
        
        ContentResolver::class.java.apply {
            // Hook query to provide metadata for our split videos
            hook("query", HookStage.AFTER) { param ->
                val uri = param.arg<Uri>(0)
                context.log.verbose("ContentResolver.query called with URI: $uri")
                
                if (currentVideoId != null && uri.toString().endsWith(currentVideoId.toString())) {
                    context.log.info("Intercepting query for currentVideoId: $currentVideoId")
                    
                    param.setResult(object: CursorWrapper(param.getResult() as Cursor) {
                        override fun getLong(columnIndex: Int): Long {
                            val columnName = getColumnName(columnIndex)
                            val value = super.getLong(columnIndex)
                            context.log.verbose("CursorWrapper.getLong - column: $columnName, value: $value")
                            return value
                        }
                    })
                }
            }
            
            // Hook openInputStream to provide our split video files
            hook("openInputStream", HookStage.BEFORE) { param ->
                val uri = param.arg<Uri>(0)
                context.log.verbose("ContentResolver.openInputStream called with URI: $uri")
                
                if (currentVideoId != null && uri.toString().endsWith(currentVideoId.toString())) {
                    context.log.info("Intercepting openInputStream for currentVideoId: $currentVideoId")
                    
                    currentVideoInputStream?.let { stream ->
                        context.log.info("Providing intercepted input stream")
                        param.setResult(stream)
                        currentVideoInputStream = null
                    } ?: context.log.warn("currentVideoInputStream is null!")
                }
            }
        }
        
        context.log.info("ContentResolver hooks installed successfully")
    }

    private fun getVideoUriFromMediaStore(videoId: Long): Uri {
        context.log.info("Querying MediaStore for videoId: $videoId")
        
        // Query MediaStore to get the video URI
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media._ID} = ?"
        val selectionArgs = arrayOf(videoId.toString())
        
        context.log.verbose("Query projection: ${projection.joinToString()}")
        context.log.verbose("Query selection: $selection")
        context.log.verbose("Query selectionArgs: ${selectionArgs.joinToString()}")
        
        context.androidContext.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            context.log.info("Query returned cursor with ${cursor.count} rows")
            
            if (cursor.moveToFirst()) {
                val uri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoId.toString()
                )
                context.log.info("Found video in MediaStore, URI: $uri")
                return uri
            } else {
                context.log.warn("Cursor is empty, no video found with ID: $videoId")
            }
        } ?: context.log.warn("Query returned null cursor")
        
        // Fallback: construct URI directly
        val fallbackUri = Uri.withAppendedPath(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoId.toString()
        )
        context.log.warn("Using fallback URI: $fallbackUri")
        return fallbackUri
    }

    private suspend fun sendVideoChunk(
        file: File,
        index: Int,
        item: Any,
        mediaItem: Any,
        actionHandler: Any,
        conversationIds: List<Any>,
        sendItemsMethod: Method
    ) {
        context.log.info("Preparing to send chunk #$index: ${file.name} (${file.length()} bytes)")
        
        val chunkUri = Uri.fromFile(file)
        context.log.verbose("Chunk URI: $chunkUri")
        
        val retriever = MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(context.androidContext, chunkUri)
            context.log.verbose("MediaMetadataRetriever data source set successfully")
            
            val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
            val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

            context.log.info("Chunk metadata - duration: ${chunkDuration}ms, dimensions: ${chunkWidth}x$chunkHeight")

            // Store the video ID and prepare for MediaStore interception
            currentVideoId = file.absolutePath.hashCode().toLong()
            context.log.info("Generated currentVideoId: $currentVideoId (from hashCode of ${file.absolutePath})")
            
            currentVideoInputStream = file.inputStream()
            context.log.info("Created input stream for chunk file")

            val newItem = item.javaClass.dataBuilder {
                set("type", item.getObjectField("type"))
                set("encryptionInfo", item.getObjectField("encryptionInfo"))
                set("contentUri", chunkUri.toString())
                set("durationMs", chunkDuration.toDouble())
                set("width", chunkWidth)
                set("height", chunkHeight)
                from("itemId", new = true) {
                    set("itemId", currentVideoId.toString())
                    set("type", "VIDEO")
                }
            }
            context.log.verbose("Built newItem object")

            val newMediaItem = mediaItem.javaClass.dataBuilder {
                set("thumbnail", mediaItem.getObjectField("thumbnail"))
                set("item", newItem)
                set("order", index.toDouble())
            }
            context.log.verbose("Built newMediaItem object")

            context.log.info("Invoking sendItemsMethod for chunk #$index")
            sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
            context.log.info("sendItemsMethod invoked successfully for chunk #$index")
            
        } catch (e: Exception) {
            context.log.error("Error processing chunk #$index: ${file.name}", e)
            throw e
        } finally {
            retriever.release()
            context.log.verbose("MediaMetadataRetriever released for chunk #$index")
        }
    }
}
