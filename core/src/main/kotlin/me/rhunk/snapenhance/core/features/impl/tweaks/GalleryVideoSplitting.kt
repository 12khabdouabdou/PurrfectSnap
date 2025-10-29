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
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.util.CallbackBuilder
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
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

        // Subscribe to SendMessageWithContentEvent
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe
            
            // Same checks as SendOverride
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }
            
            val localMessageContent = event.messageContent
            
            // Only process EXTERNAL_MEDIA
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && 
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) {
                return@subscribe
            }

            // Prevent story replies
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            context.log.verbose("GalleryVideoSplitting: Processing EXTERNAL_MEDIA message")

            // Check for multiple media
            if ((messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                context.log.verbose("GalleryVideoSplitting: Multiple media detected, skipping")
                return@subscribe
            }

            // Get the snapDocPlayback data (path 3, 3, 5 for EXTERNAL_MEDIA)
            val snapDocPlayback = messageProtoReader.followPath(3, 3, 5)
            if (snapDocPlayback == null) {
                context.log.error("GalleryVideoSplitting: Could not find snapDocPlayback at path 3,3,5")
                return@subscribe
            }

            // Extract the original snap duration buffer (field 2 in snapDocPlayback)
            val originalSnapDurationBuffer = snapDocPlayback.getByteArray(2)
            context.log.verbose("GalleryVideoSplitting: Original snap duration buffer: ${originalSnapDurationBuffer?.size ?: 0} bytes")

            // Get hasSound from the duration buffer
            val originalHasSound = originalSnapDurationBuffer?.let {
                ProtoReader(it).getVarInt(5)
            } ?: 1L
            context.log.verbose("GalleryVideoSplitting: Original hasSound = $originalHasSound")

            // Get video duration
            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)
            context.log.verbose("GalleryVideoSplitting: Video duration from proto = $videoDuration ms")

            // Cancel the original send
            event.canceled = true
            context.log.verbose("GalleryVideoSplitting: Original send CANCELED")

            val conversations = event.destinations.conversations?.map { SnapUUID(it) } ?: emptyList()
            if (conversations.isEmpty()) {
                context.log.error("GalleryVideoSplitting: No conversations to send to!")
                return@subscribe
            }

            // If duration is null or 0, ask user to input it
            if (videoDuration == null || videoDuration <= 0) {
                context.log.verbose("GalleryVideoSplitting: No valid duration found, asking user")
                context.runOnUiThread {
                    showDurationInputDialog(event, conversations, messageProtoReader, originalSnapDurationBuffer, originalHasSound)
                }
                return@subscribe
            }

            // Check if video needs splitting (>10 seconds)
            if (videoDuration <= 10000) {
                context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                // Re-invoke original send since we don't need to split
                event.invokeOriginal()
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), will split")

            // Show duration dialog
            context.runOnUiThread {
                showDurationDialog(event, conversations, videoDuration, messageProtoReader, originalSnapDurationBuffer, originalHasSound)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun showDurationInputDialog(
        event: SendMessageWithContentEvent,
        conversations: List<SnapUUID>,
        messageProtoReader: ProtoReader,
        originalSnapDurationBuffer: ByteArray?,
        originalHasSound: Long
    ) {
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            var videoDurationInput by remember { mutableStateOf("") }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            
            AlertDialog(
                onDismissRequest = { alertDialog.dismiss() },
                title = {
                    Text(
                        text = "Video Duration Required",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                            isError = errorMessage != null,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            )
                        )
                        
                        errorMessage?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
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
                        showDurationDialog(event, conversations, durationMs, messageProtoReader, originalSnapDurationBuffer, originalHasSound)
                    }) {
                        Text("Continue")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        alertDialog.dismiss()
                        // Re-enable original send when user cancels
                        event.canceled = false
                        event.invokeOriginal()
                    }) {
                        Text(context.translation["button.cancel"])
                    }
                }
            )
        }.show()
    }

    private fun showDurationDialog(
        event: SendMessageWithContentEvent,
        conversations: List<SnapUUID>,
        totalDuration: Long,
        messageProtoReader: ProtoReader,
        originalSnapDurationBuffer: ByteArray?,
        originalHasSound: Long
    ) {
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
                        // Re-enable original send when user cancels
                        event.canceled = false
                        event.invokeOriginal()
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
                            splitAndSendVideo(event, conversations, snapDuration, messageProtoReader, originalSnapDurationBuffer, originalHasSound)
                            isSplitting = false
                        }
                    }) {
                        Text(context.translation["button.send"])
                    }
                }
            }
        }.show()
    }

    private suspend fun splitAndSendVideo(
        event: SendMessageWithContentEvent,
        conversations: List<SnapUUID>,
        snapDuration: Int?,
        messageProtoReader: ProtoReader,
        originalSnapDurationBuffer: ByteArray?,
        originalHasSound: Long
    ) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            // Get the media URI from mLocalMediaReferences
            val localMessageContent = event.messageContent
            val localMediaReferencesObj = localMessageContent.instanceNonNull()
                .getObjectFieldOrNull("mLocalMediaReferences")

            var mediaUriStr: String? = null
            
            if (localMediaReferencesObj is List<*> && localMediaReferencesObj.isNotEmpty()) {
                val firstRef = localMediaReferencesObj.first()
                val mediaIdObj = firstRef?.let { 
                    it.javaClass.getDeclaredField("mId").apply { isAccessible = true }.get(it) 
                }
                if (mediaIdObj is ByteArray) {
                    mediaUriStr = String(mediaIdObj)
                    context.log.verbose("GalleryVideoSplitting: Found URI in mLocalMediaReferences: $mediaUriStr")
                }
            }

            if (mediaUriStr == null) {
                context.log.error("GalleryVideoSplitting: Could not extract media URI")
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed to get video URI")
                }
                return
            }

            val mediaUri = Uri.parse(mediaUriStr)
            val cachedVideo = File(tempDir, "input.mp4")

            // Copy video to cache
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open input stream")
            
            context.log.verbose("GalleryVideoSplitting: Video cached (${cachedVideo.length()} bytes)")

            // Split with FFmpeg
            val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
            context.log.verbose("GalleryVideoSplitting: FFmpeg command: $command")
            
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed: ${session.output}")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} files")
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("FFmpeg produced no output files")
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} chunks...")
            }

            // Send each chunk
            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")
                
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                    
                    context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} - Duration: ${chunkDuration}ms, Size: ${chunkWidth}x${chunkHeight}")

                    // Create the snap duration buffer with custom duration
                    val customSnapDurationBuffer = if (snapDuration != null && originalSnapDurationBuffer != null) {
                        // Clone and modify the original buffer with new duration
                        ProtoWriter().apply {
                            // Use the original buffer structure but update duration fields
                            val reader = ProtoReader(originalSnapDurationBuffer)
                            
                            addVarInt(5, originalHasSound) // preserve hasSound
                            
                            if (snapDuration >= 1000) {
                                addVarInt(8, (snapDuration / 1000).toLong()) // duration in seconds
                            } else {
                                addVarInt(99, snapDuration.toLong()) // duration in ms (custom field)
                            }
                            
                            // Copy other fields from original if they exist
                            reader.getVarInt(6)?.let { addVarInt(6, it) }
                            reader.getVarInt(7)?.let { addVarInt(7, it) }
                        }.toByteArray()
                    } else {
                        // Use original buffer as-is
                        originalSnapDurationBuffer
                    }

                    // Create message content (EXTERNAL_MEDIA format)
                    val chunkContent = ProtoWriter().apply {
                        from(3) {
                            from(3) {
                                addString(2, chunkUri.toString()) // Content URI
                                from(5) { // snapDocPlayback
                                    from(1) {
                                        from(1) {
                                            addVarInt(2, 0) // overlay type
                                            addVarInt(12, 0)
                                            addVarInt(15, chunkDuration) // video file duration
                                            addVarInt(16, chunkWidth)
                                            addVarInt(17, chunkHeight)
                                        }
                                        addVarInt(6, 1) // media type: video
                                    }
                                    // Use the custom or original snap duration buffer
                                    if (customSnapDurationBuffer != null) {
                                        addBuffer(2, customSnapDurationBuffer)
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
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sent ${outputFiles.size} chunks!")
            }
            context.log.verbose("GalleryVideoSplitting: All chunks sent successfully")
            
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed: ${e.message}")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun sendChunk(conversations: List<SnapUUID>, messageContent: ByteArray) {
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
    }
}
