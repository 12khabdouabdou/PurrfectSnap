package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.common.util.protobuf.ProtoEditor
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.experiments.MediaFilePicker
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
import me.rhunk.snapenhance.core.wrapper.impl.MessageContent
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)
    
    // Queue to hold pending chunks
    private val pendingChunks = mutableListOf<ChunkData>()
    private var currentChunkIndex = 0
    
    data class ChunkData(
        val uri: Uri,
        val duration: Long,
        val width: Int,
        val height: Int,
        val hasSound: Long
    )

    override fun init() {
        context.log.verbose("GalleryVideoSplitting: Initializing...")
        
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config")
            return
        }
        
        // Check FFmpeg availability
        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            context.log.verbose("GalleryVideoSplitting: FFmpegKit found")
        } catch (e: ClassNotFoundException) {
            context.log.error("GalleryVideoSplitting: FFmpegKit not found! Feature disabled.", e)
            return
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            // Handle pending chunks first (same pattern as SendOverride modifies and invokes original)
            if (pendingChunks.isNotEmpty() && currentChunkIndex < pendingChunks.size) {
                val chunk = pendingChunks[currentChunkIndex]
                context.log.verbose("GalleryVideoSplitting: Sending chunk ${currentChunkIndex + 1}/${pendingChunks.size}")
                
                // Modify the event's message content (same as SendOverride does)
                modifyEventForChunk(event, chunk)
                
                currentChunkIndex++
                
                // If more chunks remain, schedule next send
                if (currentChunkIndex < pendingChunks.size) {
                    defer {
                        delay(1500)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info,
                                "Sending chunk ${currentChunkIndex + 1}/${pendingChunks.size}..."
                            )
                        }
                    }
                } else {
                    // All chunks sent, cleanup
                    pendingChunks.clear()
                    currentChunkIndex = 0
                    isSplitting = false
                    defer {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info,
                                "All chunks sent successfully!"
                            )
                        }
                    }
                }
                
                // Let the modified event proceed
                return@subscribe
            }
            
            if (isSplitting) return@subscribe
            
            // Same checks as SendOverride
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }
            
            val localMessageContent = event.messageContent
            
            // Only process EXTERNAL_MEDIA
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && 
                MessageContent(localMessageContent.instanceNonNull()).getObjectFieldOrNull("mExternalContentMetadata") == null) {
                return@subscribe
            }

            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            // Check for multiple media
            if ((messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                return@subscribe
            }

            // Get video duration from proto first, then fallback to MediaFilePicker
            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)
            if (videoDuration == null || videoDuration <= 0) {
                videoDuration = context.feature(MediaFilePicker::class).lastMediaDuration
            }

            // Check if video needs splitting (>10 seconds)
            if (videoDuration == null || videoDuration <= 10000) {
                // Video is short enough, let it proceed normally
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), will split")

            // Cancel the original send
            event.canceled = true

            // Show duration dialog
            context.runOnUiThread {
                showDurationDialog(event, videoDuration, messageProtoReader)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun modifyEventForChunk(event: SendMessageWithContentEvent, chunk: ChunkData) {
        val localMessageContent = event.messageContent
        val snapDurationMs = convertDuration(customDuration)
        
        // Get extras from original (same as SendOverride)
        val originalProtoReader = ProtoReader(localMessageContent.content ?: byteArrayOf())
        val extras = originalProtoReader.followPath(3, 3, 13)?.getBuffer()
        
        // Build SNAP content (same structure as SendOverride uses)
        localMessageContent.content = ProtoWriter().apply {
            from(11) {
                from(5) {
                    from(1) {
                        from(1) {
                            addVarInt(2, 0) // overlay type
                            addVarInt(12, 0)
                            addVarInt(15, snapDurationMs?.toLong() ?: chunk.duration) // duration
                            addVarInt(16, chunk.width)
                            addVarInt(17, chunk.height)
                        }
                        addVarInt(6, 1) // media type: video
                    }
                    from(2) {
                        addVarInt(5, chunk.hasSound) // hasSound
                        if (snapDurationMs != null) {
                            addVarInt(8, snapDurationMs / 1000) // duration in seconds
                            if (snapDurationMs / 1000 <= 0) {
                                addVarInt(99, snapDurationMs.toLong()) // duration in ms
                            }
                        } else {
                            addBuffer(6, byteArrayOf())
                        }
                    }
                }
                extras?.let {
                    addBuffer(13, it)
                }
                from(22) {
                    addVarInt(4, 5) // APP_SOURCE_CAMERA
                }
            }
        }.toByteArray()
        
        // Change content type to SNAP (same as SendOverride)
        localMessageContent.contentType = ContentType.SNAP
        
        // Update the local media reference with the chunk URI
        val messageContentWrapper = MessageContent(localMessageContent.instanceNonNull())
        val localMediaReferencesObj = messageContentWrapper.getObjectFieldOrNull("mLocalMediaReferences")
        
        if (localMediaReferencesObj is MutableList<*>) {
            localMediaReferencesObj.clear()
            // Create new media reference with chunk URI
            val mediaReferenceClass = localMediaReferencesObj.javaClass.genericSuperclass
                .let { it.toString().substringAfter('<').substringBefore('>') }
                .let { context.androidContext.classLoader.loadClass(it) }
            
            val newReference = mediaReferenceClass.newInstance()
            mediaReferenceClass.getDeclaredField("mId").apply {
                isAccessible = true
                set(newReference, chunk.uri.toString().toByteArray())
            }
            
            (localMediaReferencesObj as MutableList<Any>).add(newReference)
        }
        
        context.log.verbose("GalleryVideoSplitting: Modified event for chunk at ${chunk.uri}")
    }

    private fun convertDuration(duration: Float): Int? {
        return when {
            duration in -2f..-1f -> 100
            duration in -1f..-0f -> 250
            duration in -0f..1f -> 500
            duration >= 11f -> null
            else -> ((duration * 1000).toInt() / 1000) * 1000
        }
    }

    private fun showDurationDialog(
        event: SendMessageWithContentEvent,
        totalDuration: Long,
        messageProtoReader: ProtoReader
    ) {
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            val mainTranslation = remember {
                context.translation.getCategory("send_override_dialog")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Split Video",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Video duration: ${(totalDuration / 1000.0).toDuration(DurationUnit.SECONDS).toString(DurationUnit.SECONDS, 1)}",
                    fontSize = 14.sp
                )

                Text(
                    text = "This video will be split into 10-second chunks and sent separately.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        text = mainTranslation.format(
                            "duration",
                            "duration" to (convertDuration(customDuration)?.toDuration(DurationUnit.MILLISECONDS)
                                ?.toString(DurationUnit.SECONDS, 2)
                                ?: mainTranslation["unlimited_duration"])
                        ),
                        fontSize = 14.sp
                    )
                    Slider(
                        modifier = Modifier.fillMaxWidth(),
                        value = customDuration,
                        onValueChange = {
                            customDuration = it
                        },
                        valueRange = -2f..11f,
                    )
                    Text(
                        text = "Snap duration for each chunk",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        alertDialog.dismiss()
                    }) {
                        Text(context.translation["button.cancel"])
                    }
                    Button(onClick = {
                        alertDialog.dismiss()
                        
                        // Start async splitting process using defer (SnapEnhance's coroutine helper)
                        context.coroutineScope.launch {
                            isSplitting = true
                            val success = splitAndPrepareChunks(event, messageProtoReader)
                            
                            if (success && pendingChunks.isNotEmpty()) {
                                currentChunkIndex = 0
                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info,
                                        "Sending chunk 1/${pendingChunks.size}..."
                                    )
                                }
                                // Send first chunk by invoking the original event
                                event.invokeOriginal()
                            } else {
                                isSplitting = false
                            }
                        }
                    }) {
                        Text(context.translation["button.send"])
                    }
                }
            }
        }.show()
    }

    private suspend fun splitAndPrepareChunks(
        event: SendMessageWithContentEvent,
        messageProtoReader: ProtoReader
    ): Boolean {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            // Get the media URI from mLocalMediaReferences
            val localMessageContent = event.messageContent
            val messageContentWrapper = MessageContent(localMessageContent.instanceNonNull())
            val localMediaReferencesObj = messageContentWrapper.getObjectFieldOrNull("mLocalMediaReferences")

            var mediaUriStr: String? = null
            
            if (localMediaReferencesObj is List<*> && localMediaReferencesObj.isNotEmpty()) {
                val firstRef = localMediaReferencesObj.first()
                val mediaIdObj = firstRef?.let { 
                    it.javaClass.getDeclaredField("mId").apply { isAccessible = true }.get(it) 
                }
                if (mediaIdObj is ByteArray) {
                    mediaUriStr = String(mediaIdObj)
                }
            }

            if (mediaUriStr == null) {
                context.log.error("GalleryVideoSplitting: Could not extract media URI")
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed to get video URI")
                }
                return false
            }

            val mediaUri = Uri.parse(mediaUriStr)
            val cachedVideo = File(tempDir, "input.mp4")

            // Copy video to cache
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open input stream")

            // Split with FFmpeg
            val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed: ${session.output}")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("FFmpeg produced no output files")
            }

            // Get metadata from original proto
            val hasSound = messageProtoReader.getVarInt(3, 3, 5, 2, 5) ?: 1

            // Prepare chunk data for all chunks
            pendingChunks.clear()
            for (file in outputFiles) {
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                    
                    pendingChunks.add(ChunkData(chunkUri, chunkDuration, chunkWidth, chunkHeight, hasSound))
                } finally {
                    retriever.release()
                }
            }

            context.log.verbose("GalleryVideoSplitting: Prepared ${pendingChunks.size} chunks")
            return true
            
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed: ${e.message}")
            }
            pendingChunks.clear()
            return false
        }
    }
}
