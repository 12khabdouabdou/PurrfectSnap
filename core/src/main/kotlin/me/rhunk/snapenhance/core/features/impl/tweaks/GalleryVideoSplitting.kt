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

        // Subscribe to SendMessageWithContentEvent (same as SendOverride)
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe
            
            // Same checks as SendOverride
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }
            
            val localMessageContent = event.messageContent
            
            // Only process EXTERNAL_MEDIA (same check as SendOverride)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && 
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) {
                return@subscribe
            }

            // Prevent story replies (same as SendOverride)
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            context.log.verbose("GalleryVideoSplitting: Processing EXTERNAL_MEDIA message")

            // Check for multiple media (same as SendOverride)
            if ((messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                context.log.verbose("GalleryVideoSplitting: Multiple media detected, skipping")
                return@subscribe
            }

            // Get video duration - try multiple paths (following SendOverride's approach)
            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)
            context.log.verbose("GalleryVideoSplitting: Video duration from proto (3,3,5,1,1,15) = $videoDuration ms")
            
            // If not found, try getting from field 8 (seconds) or 99 (milliseconds)
            if (videoDuration == null || videoDuration <= 0) {
                val durationSeconds = messageProtoReader.getVarInt(3, 3, 5, 2, 8)
                val durationMs = messageProtoReader.getVarInt(3, 3, 5, 2, 99)
                
                videoDuration = when {
                    durationMs != null && durationMs > 0 -> durationMs
                    durationSeconds != null && durationSeconds > 0 -> durationSeconds * 1000
                    else -> null
                }
                context.log.verbose("GalleryVideoSplitting: Duration from field 8/99 = $videoDuration ms")
            }

            // Cancel the original send (same as SendOverride)
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
                    showDurationInputDialog(conversations, messageProtoReader)
                }
                return@subscribe
            }

            // Check if video needs splitting (>10 seconds)
            if (videoDuration <= 10000) {
                context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                // Don't re-invoke - we already canceled. User needs to send again manually
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), will split")

            // Show duration dialog
            context.runOnUiThread {
                showDurationDialog(conversations, videoDuration, messageProtoReader)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun showDurationInputDialog(
        conversations: List<SnapUUID>,
        messageProtoReader: ProtoReader
    ) {
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            var videoDurationInput by remember { mutableStateOf("") }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            
            AlertDialog(
                onDismissRequest = { 
                    alertDialog.dismiss()
                    context.log.verbose("GalleryVideoSplitting: User canceled duration input - send canceled")
                },
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
                        // Show snap duration dialog
                        showDurationDialog(conversations, durationMs, messageProtoReader)
                    }) {
                        Text("Continue")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        alertDialog.dismiss()
                        context.log.verbose("GalleryVideoSplitting: User canceled - send canceled")
                    }) {
                        Text(context.translation["button.cancel"])
                    }
                }
            )
        }.show()
    }

    private fun showDurationDialog(
        conversations: List<SnapUUID>,
        totalDuration: Long,
        messageProtoReader: ProtoReader
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
                        context.log.verbose("GalleryVideoSplitting: User canceled - send canceled")
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
                            splitAndSendVideo(conversations, snapDuration, messageProtoReader)
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
        conversations: List<SnapUUID>,
        snapDuration: Int?,
        messageProtoReader: ProtoReader
    ) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            // Get the media URI from the event's message content
            // We need to access the original event data stored somewhere
            // For now, we'll try to get it from the messageProtoReader
            val mediaUriFromProto = messageProtoReader.getString(3, 3, 2)
            
            var mediaUriStr: String? = mediaUriFromProto
            context.log.verbose("GalleryVideoSplitting: Found URI in proto (3,3,2): $mediaUriStr")

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

            // Get hasSound from original proto (same as SendOverride)
            val hasSound = messageProtoReader.getVarInt(3, 3, 5, 2, 5) ?: 1
            context.log.verbose("GalleryVideoSplitting: Original hasSound = $hasSound")

            // Send each chunk using SendOverride's method
            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")
                
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                    
                    context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} - Duration: ${chunkDuration}ms")

                    // Build SNAP format message content (following SendOverride's pattern)
                    val snapContent = ProtoWriter().apply {
                        from(11) { // SNAP content structure
                            from(5) { // snapDocPlayback
                                from(1) {
                                    from(1) {
                                        addVarInt(2, 0) // overlay type
                                        addVarInt(12, 0)
                                        addVarInt(15, 0) // will be overridden by duration
                                    }
                                    addVarInt(6, 1) // media type: video
                                }
                                from(2) {}
                            }
                            from(22) {} // app source
                        }
                    }.toByteArray()

                    // Apply SendOverride's transformations
                    val finalContent = ProtoEditor(snapContent).apply {
                        edit(11, 5, 2) {
                            arrayOf(6, 7, 8).forEach { remove(it) }
                            addVarInt(5, hasSound) // hasSound from original
                            // Set snap duration (same logic as SendOverride)
                            if (snapDuration != null) {
                                addVarInt(8, snapDuration / 1000)
                                if (snapDuration / 1000 <= 0) {
                                    addVarInt(99, snapDuration)
                                }
                            } else {
                                addBuffer(6, byteArrayOf())
                            }
                        }

                        // Set app source (same as SendOverride)
                        edit(11, 22) {
                            remove(4)
                            addVarInt(4, 5) // APP_SOURCE_CAMERA
                        }
                    }.toByteArray()

                    context.log.verbose("GalleryVideoSplitting: Sending chunk ${index + 1} as SNAP with duration: $snapDuration ms")
                    
                    // Create local media reference with chunk URI
                    val localMediaRefBytes = chunkUri.toString().toByteArray()
                    
                    sendChunkAsSnap(conversations, finalContent, localMediaRefBytes)
                    
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

    private fun sendChunkAsSnap(conversations: List<SnapUUID>, messageContent: ByteArray, localMediaReference: ByteArray) {
        val sendMessageWithContentMethod = context.classCache.conversationManager.declaredMethods.first { 
            it.name == "sendMessageWithContent" 
        }

        // Use SendOverride's template with SNAP content type and save policy
        val localMessageContentTemplate = """
        {
            "mAllowsTranscription": false,
            "mBotMention": false,
            "mContent": [${messageContent.joinToString(",")}],
            "mContentType": "SNAP",
            "mIncidentalAttachments": [],
            "mLocalMediaReferences": [{"mId": [${localMediaReference.joinToString(",")}]}],
            "mPlatformAnalytics": {
                "mAttemptId": null,
                "mContent": null,
                "mMetricsMessageMediaType": "VIDEO",
                "mMetricsMessageType": "SNAP",
                "mReactionSource": "NONE"
            },
            "mSavePolicy": "PROHIBITED"
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

        // Use CallbackBuilder to create proper callback (same as SendOverride and MessageSender)
        val callback = CallbackBuilder(sendMessageCallback)
            .override("onSuccess", callback = { 
                context.log.verbose("GalleryVideoSplitting: Chunk sent successfully")
            })
            .override("onError", callback = { 
                context.log.error("GalleryVideoSplitting: Failed to send chunk: ${it.arg(0)}")
            })
            .build()

        val conversationManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()

        sendMessageWithContentMethod.invoke(
            conversationManager,
            messageDestinations.instanceNonNull(),
            localMessageContent,
            callback
        )
    }
}
