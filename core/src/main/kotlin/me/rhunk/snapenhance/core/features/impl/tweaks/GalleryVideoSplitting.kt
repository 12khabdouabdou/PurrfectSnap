package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.util.CallbackBuilder
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.wrapper.AbstractWrapper
import me.rhunk.snapenhance.core.wrapper.impl.MessageDestinations
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import me.rhunk.snapenhance.mapper.impl.CallbackMapper
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)

    private val sendMessageCallback by lazy {
        var result: Class<*>? = null
        context.mappings.useMapper(CallbackMapper::class) {
            result = callbacks.getClass("SendMessageCallback")
        }
        result ?: throw IllegalStateException("SendMessageCallback class not found")
    }

    override fun init() {
        context.log.verbose("GalleryVideoSplitting: Initializing...")
        
        // Check if feature is enabled
        val isEnabled = context.config.messaging.splitVideoIntoTenSecondSnaps.get()
        context.log.verbose("GalleryVideoSplitting: Config enabled = $isEnabled")
        
        if (!isEnabled) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config, aborting init")
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

        context.log.verbose("GalleryVideoSplitting: Subscribing to SendMessageWithContentEvent")

        // Subscribe to SendMessageWithContentEvent
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            context.log.verbose("GalleryVideoSplitting: Event received!")
            
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, ignoring")
                return@subscribe
            }

            // Prevent story replies
            val hasStories = event.destinations.stories?.isNotEmpty() == true
            val hasConversations = event.destinations.conversations?.isNotEmpty() == true
            context.log.verbose("GalleryVideoSplitting: hasStories=$hasStories, hasConversations=$hasConversations")
            
            if (hasStories && !hasConversations) {
                context.log.verbose("GalleryVideoSplitting: Story reply detected, skipping")
                return@subscribe
            }

            try {
                val localMessageContent = event.messageContent
                val contentType = localMessageContent.contentType
                context.log.verbose("GalleryVideoSplitting: Content type = $contentType")
                
                // Only process EXTERNAL_MEDIA (gallery videos)
                if (contentType != ContentType.EXTERNAL_MEDIA) {
                    context.log.verbose("GalleryVideoSplitting: Not EXTERNAL_MEDIA, skipping")
                    return@subscribe
                }

                val content = localMessageContent.content
                if (content == null) {
                    context.log.verbose("GalleryVideoSplitting: Content is null, skipping")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Parsing message proto (${content.size} bytes)")

                // Parse message content (same as SendOverride)
                val messageProtoReader = ProtoReader(content)
                
                // Debug: print full proto structure
                context.log.verbose("GalleryVideoSplitting: Full message proto structure:\n${messageProtoReader}")
                
                // Prevent story replies (same check as SendOverride)
                if (messageProtoReader.contains(7)) {
                    context.log.verbose("GalleryVideoSplitting: Story reply proto detected, skipping")
                    return@subscribe
                }
                
                // Check if multiple media items (same as SendOverride)
                val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                context.log.verbose("GalleryVideoSplitting: Media count = $mediaCount")
                
                if (mediaCount != 1) {
                    context.log.verbose("GalleryVideoSplitting: Multiple media not supported ($mediaCount items), skipping")
                    return@subscribe
                }

                // Get video duration using snapDocPlayback path (same as SendOverride MediaUploadEvent)
                val snapDocPlayback = messageProtoReader.followPath(3, 3, 5)
                if (snapDocPlayback == null) {
                    context.log.verbose("GalleryVideoSplitting: No snapDocPlayback found at path 3,3,5")
                    return@subscribe
                }
                
                context.log.verbose("GalleryVideoSplitting: Found snapDocPlayback, checking for video duration")
                context.log.verbose("GalleryVideoSplitting: snapDocPlayback proto structure:\n${snapDocPlayback}")
                
                // ===== DURATION DETECTION FIX =====
                var videoDuration: Long? = null
                
                // Try method 1: Get duration from proto field 1,1,15 (works for videos <=10s)
                videoDuration = snapDocPlayback.getVarInt(1, 1, 15)
                context.log.verbose("GalleryVideoSplitting: Duration from proto (1,1,15) = $videoDuration")
                
                // Try method 2: Get from field 2 which contains original snap duration (works for all videos)
                if (videoDuration == null || videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Trying to extract duration from field 2 (original snap duration)")
                    
                    snapDocPlayback.getByteArray(2)?.let { originalSnapData ->
                        try {
                            val originalSnapReader = ProtoReader(originalSnapData)
                            context.log.verbose("GalleryVideoSplitting: Original snap data proto:\n${originalSnapReader}")
                            
                            // Field 8 contains duration in seconds
                            val durationSeconds = originalSnapReader.getVarInt(8)
                            if (durationSeconds != null && durationSeconds > 0) {
                                videoDuration = durationSeconds * 1000 // Convert to milliseconds
                                context.log.verbose("GalleryVideoSplitting: Duration from field 2->8 (seconds) = $durationSeconds s = ${videoDuration}ms")
                            }
                            
                            // Field 99 contains duration in milliseconds (for very short videos)
                            if (videoDuration == null || videoDuration <= 0) {
                                val durationMs = originalSnapReader.getVarInt(99)
                                if (durationMs != null && durationMs > 0) {
                                    videoDuration = durationMs
                                    context.log.verbose("GalleryVideoSplitting: Duration from field 2->99 (ms) = ${videoDuration}ms")
                                }
                            }
                        } catch (e: Exception) {
                            context.log.error("GalleryVideoSplitting: Failed to parse field 2 data", e)
                        }
                    }
                }
                
                // Try method 3: Extract from actual file if proto methods failed
                if (videoDuration == null || videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Proto duration missing/invalid, extracting from file...")
                    
                    // Get content URI from snapDocPlayback
                    val contentUriStr = snapDocPlayback.getString(1, 2)
                    
                    if (contentUriStr != null) {
                        context.log.verbose("GalleryVideoSplitting: Found content URI in proto: $contentUriStr")
                        
                        val retriever = MediaMetadataRetriever()
                        try {
                            val uri = Uri.parse(contentUriStr)
                            
                            // Use FileDescriptor for content URIs (safer)
                            context.mainActivity!!.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                retriever.setDataSource(pfd.fileDescriptor)
                                
                                videoDuration = retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION
                                )?.toLongOrNull()
                                
                                context.log.verbose("GalleryVideoSplitting: Extracted duration from file = ${videoDuration}ms")
                            }
                        } catch (e: Exception) {
                            context.log.error("GalleryVideoSplitting: Failed to extract duration from file", e)
                        } finally {
                            retriever.release()
                        }
                    } else {
                        context.log.verbose("GalleryVideoSplitting: No content URI found in proto at 1,2")
                        
                        // Fallback: try mLocalMediaReferences
                        val localMediaReferencesObj = localMessageContent.instanceNonNull()
                            .getObjectField("mLocalMediaReferences")
                        
                        if (localMediaReferencesObj is List<*>) {
                            val localMediaReferences = localMediaReferencesObj
                            
                            if (localMediaReferences.isNotEmpty()) {
                                val firstRef = localMediaReferences.first()
                                val mediaIdObj = firstRef?.getObjectField("mId")
                                
                                if (mediaIdObj is ByteArray) {
                                    val mediaId = mediaIdObj
                                    val mediaUriString = String(mediaId)
                                    context.log.verbose("GalleryVideoSplitting: Found URI in mLocalMediaReferences: $mediaUriString")
                                    
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        val uri = Uri.parse(mediaUriString)
                                        
                                        // Use FileDescriptor for content URIs (safer)
                                        context.mainActivity!!.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                            retriever.setDataSource(pfd.fileDescriptor)
                                            
                                            videoDuration = retriever.extractMetadata(
                                                MediaMetadataRetriever.METADATA_KEY_DURATION
                                            )?.toLongOrNull()
                                            
                                            context.log.verbose("GalleryVideoSplitting: Extracted duration from mLocalMediaReferences = ${videoDuration}ms")
                                        }
                                    } catch (e: Exception) {
                                        context.log.error("GalleryVideoSplitting: Failed to extract from mLocalMediaReferences", e)
                                    } finally {
                                        retriever.release()
                                    }
                                }
                            }
                        }
                    }
                }
                
               
                
                // ===== END DURATION FIX =====
                
                // Capture the final duration value for smart casting
                val finalDuration = videoDuration
                
                context.log.verbose("GalleryVideoSplitting: Final video duration = ${finalDuration}ms")

                // Check if we could determine the duration
                val actualDuration = if (finalDuration == null || finalDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Could not determine video duration, asking user")
                    
                    // Cancel the original send and show dialog to ask user for duration
                    event.canceled = true
                    
                    // Extract media URI
                    val contentUriStr = snapDocPlayback.getString(1, 2)
                    if (contentUriStr == null) {
                        context.log.error("GalleryVideoSplitting: Could not extract content URI")
                        return@subscribe
                    }
                    
                    val mediaUri = Uri.parse(contentUriStr)
                    val conversations = event.destinations.conversations?.map { 
                        SnapUUID(it) 
                    } ?: emptyList()
                    
                    if (conversations.isEmpty()) {
                        context.log.error("GalleryVideoSplitting: No conversations to send to!")
                        return@subscribe
                    }
                    
                    // Show dialog to ask user for video duration
                    context.runOnUiThread {
                        showDurationInputDialog(mediaUri, conversations)
                    }
                    return@subscribe
                } else {
                    finalDuration
                }

                // If video is <= 10 seconds, no need to split
                if (actualDuration <= 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video is ${actualDuration}ms (<= 10s), no split needed")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: VIDEO > 10s detected! Duration: ${actualDuration}ms - Will split")
                
                // Extract media URI from proto path 3,3,5,1,2 (same as SendOverride checks)
                val contentUriStr = snapDocPlayback.getString(1, 2)
                if (contentUriStr == null) {
                    context.log.error("GalleryVideoSplitting: Could not extract content URI from snapDocPlayback")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Content URI = $contentUriStr")
                
                // Cancel the original send - we'll send chunks instead
                event.canceled = true
                context.log.verbose("GalleryVideoSplitting: Original send CANCELED")

                val mediaUri = Uri.parse(contentUriStr)
                
                val conversations = event.destinations.conversations?.map { 
                    SnapUUID(it) 
                } ?: emptyList()

                context.log.verbose("GalleryVideoSplitting: Will send to ${conversations.size} conversations")

                if (conversations.isEmpty()) {
                    context.log.error("GalleryVideoSplitting: No conversations to send to!")
                    return@subscribe
                }

                // Show dialog to choose split duration (like SendOverride)
                context.runOnUiThread {
                    showDurationDialog(mediaUri, conversations, actualDuration)
                }
                
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Error in event handler", e)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun showDurationInputDialog(mediaUri: Uri, conversations: List<SnapUUID>) {
        var videoDurationInput by mutableStateOf("")
        var errorMessage by mutableStateOf<String?>(null)
        
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Video Duration Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Could not detect video duration automatically. Please enter the video duration in seconds:",
                    fontSize = 14.sp
                )

                OutlinedTextField(
                    value = videoDurationInput,
                    onValueChange = { 
                        videoDurationInput = it
                        errorMessage = null
                    },
                    label = { Text("Duration (seconds)") },
                    placeholder = { Text("e.g., 15") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = errorMessage != null
                )
                
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
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
                        val durationSeconds = videoDurationInput.toIntOrNull()
                        if (durationSeconds == null || durationSeconds <= 0) {
                            errorMessage = "Please enter a valid positive number"
                            return@Button
                        }
                        
                        val durationMs = durationSeconds * 1000L
                        context.log.verbose("GalleryVideoSplitting: User entered duration: ${durationMs}ms")
                        
                        if (durationMs <= 10000) {
                            errorMessage = "Video must be longer than 10 seconds to split"
                            return@Button
                        }
                        
                        alertDialog.dismiss()
                        // Show snap duration dialog
                        showDurationDialog(mediaUri, conversations, durationMs)
                    }) {
                        Text("Continue")
                    }
                }
            }
        }.show()
    }

    private fun showDurationDialog(mediaUri: Uri, conversations: List<SnapUUID>, totalDuration: Long) {
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            val mainTranslation = remember {
                context.translation.getCategory("send_override_dialog")
            }

            fun convertDuration(duration: Float): Int? {
                return when {
                    duration in -2f..-1f -> 100
                    duration in -1f..-0f -> 250
                    duration in -0f..1f -> 500
                    duration >= 11f -> null
                    else -> ((duration * 1000).toInt() / 1000) * 1000
                }
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
                        val snapDuration = convertDuration(customDuration)
                        context.log.verbose("GalleryVideoSplitting: User selected snap duration: $snapDuration ms")
                        
                        // Start async splitting process
                        context.coroutineScope.launch {
                            isSplitting = true
                            splitAndSendVideo(mediaUri, conversations, snapDuration)
                            isSplitting = false
                        }
                    }) {
                        Text(context.translation["button.send"])
                    }
                }
            }
        }.show()
    }

    private suspend fun splitAndSendVideo(mediaUri: Uri, conversations: List<SnapUUID>, snapDuration: Int?) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
            context.log.verbose("GalleryVideoSplitting: Created temp dir: $absolutePath")
        }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10s chunks...")
            }

            val cachedVideo = File(tempDir, "input.mp4")

            // Copy video to cache
            context.log.verbose("GalleryVideoSplitting: Copying video to cache...")
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    val bytesWritten = input.copyTo(output)
                    context.log.verbose("GalleryVideoSplitting: Copied $bytesWritten bytes")
                }
            } ?: throw IllegalStateException("Failed to open input stream for media URI")
            
            context.log.verbose("GalleryVideoSplitting: Video cached (${cachedVideo.length()} bytes)")

            // Split with FFmpeg
            val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
            context.log.verbose("GalleryVideoSplitting: FFmpeg command: $command")
            
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                val output = session.output
                context.log.error("GalleryVideoSplitting: FFmpeg failed with code ${session.returnCode}")
                context.log.error("GalleryVideoSplitting: FFmpeg output: $output")
                throw IllegalStateException("FFmpeg failed: $output")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} files")
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("FFmpeg produced no output files")
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} video chunks...")
            }

            // Send each chunk
            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")
                
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    
                    // Get chunk metadata using MediaMetadataRetriever
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                    val hasSound = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.toIntOrNull() ?: 1
                    
                    context.log.verbose("GalleryVideoSplitting: Chunk metadata - Duration: ${chunkDuration}ms, Size: ${chunkWidth}x${chunkHeight}, Sound: $hasSound")

                    // Create EXTERNAL_MEDIA content proto structure for this chunk
                    val chunkContent = ProtoWriter().apply {
                        from(3) {
                            from(3) {
                                addString(2, chunkUri.toString())
                                from(5) {
                                    from(1) {
                                        from(1) {
                                            // Use custom snap duration if provided
                                            if (snapDuration != null) {
                                                addVarInt(15, snapDuration.toLong())
                                            } else {
                                                addVarInt(15, chunkDuration)
                                            }
                                            addVarInt(16, chunkWidth)
                                            addVarInt(17, chunkHeight)
                                        }
                                    }
                                    from(2) {
                                        addVarInt(5, hasSound)
                                        // Add snap duration in seconds (if provided)
                                        if (snapDuration != null && snapDuration >= 1000) {
                                            addVarInt(8, snapDuration / 1000)
                                        }
                                        // Add millisecond precision for very short durations
                                        if (snapDuration != null && snapDuration < 1000) {
                                            addVarInt(99, snapDuration.toLong())
                                        }
                                    }
                                }
                            }
                        }
                    }.toByteArray()

                    context.log.verbose("GalleryVideoSplitting: Sending chunk ${index + 1} with snap duration: $snapDuration ms")
                    sendChunk(conversations, chunkContent)
                    
                    delay(1500)
                    
                } finally {
                    retriever.release()
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Successfully sent ${outputFiles.size} chunks!")
            }
            context.log.verbose("GalleryVideoSplitting: All chunks sent successfully")
            
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed to split video: ${e.message}")
            }
        } finally {
            context.log.verbose("GalleryVideoSplitting: Cleaning up temp dir")
            tempDir.deleteRecursively()
        }
    }

    private fun sendChunk(conversations: List<SnapUUID>, messageContent: ByteArray) {
        context.log.verbose("GalleryVideoSplitting: sendChunk called")
        
        val sendMessageWithContentMethod = context.classCache.conversationManager.declaredMethods.first { 
            it.name == "sendMessageWithContent" 
        }

        val localMessageContentTemplate = """
        {
            "mAllowsTranscription": false,
            "mBotMention": false,
            "mContent": [${messageContent.joinToString(",")}],
            "mContentType": "EXTERNAL_MEDIA",
            "mIncidentalAttachments": [],
            "mLocalMediaReferences": [],
            "mPlatformAnalytics": {
                "mAttemptId": null,
                "mContent": null,
                "mMetricsMessageMediaType": "VIDEO",
                "mMetricsMessageType": "SNAP",
                "mReactionSource": "NONE"
            },
            "mSavePolicy": "LIFETIME"
        }
        """.trimIndent()

        val localMessageContent = context.gson.fromJson(
            localMessageContentTemplate, 
            context.classCache.localMessageContent
        )

        val messageDestinations = MessageDestinations(
            AbstractWrapper.newEmptyInstance(context.classCache.messageDestinations)
        ).also {
            it.conversations = ArrayList(conversations)
            it.mPhoneNumbers = ArrayList<Any>()
            it.stories = ArrayList<Any>()
        }

        val callback = CallbackBuilder(sendMessageCallback).build()

        val conversationManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()

        sendMessageWithContentMethod.invoke(
            conversationManager,
            messageDestinations.instanceNonNull(),
            localMessageContent,
            callback
        )

        context.log.verbose("GalleryVideoSplitting: sendChunk complete")
    }
}
