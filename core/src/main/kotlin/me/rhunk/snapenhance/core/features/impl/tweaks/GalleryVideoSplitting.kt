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
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            return
        }
        
        // Check FFmpeg availability
        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
        } catch (e: ClassNotFoundException) {
            context.log.error("GalleryVideoSplitting: FFmpegKit not found!", e)
            return
        }

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

            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            
            // Prevent story replies
            if (messageProtoReader.contains(7)) return@subscribe

            // Check for multiple media
            if ((messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                return@subscribe
            }

            // Check if it's a VIDEO - field 6 in path 3,3,5,1 indicates media type
            // 0 = photo, 1 = video
            val mediaType = messageProtoReader.getVarInt(3, 3, 5, 1, 6)
            context.log.verbose("GalleryVideoSplitting: Media type (field 6) = $mediaType (0=photo, 1=video)")
            
            if (mediaType != 1L) {
                context.log.verbose("GalleryVideoSplitting: Not a video, skipping")
                return@subscribe
            }

            // Get video duration - try multiple paths
            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)
            if (videoDuration == null) {
                // Try alternative path used by SendOverride
                videoDuration = messageProtoReader.getVarInt(3, 3, 5, 2, 8)?.let { it * 1000 }
            }
            
            context.log.verbose("GalleryVideoSplitting: Video duration = $videoDuration ms")

            // Get URI from mLocalMediaReferences
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
                    context.log.verbose("GalleryVideoSplitting: Found URI: $mediaUriStr")
                }
            }

            if (mediaUriStr == null) {
                context.log.verbose("GalleryVideoSplitting: No URI found, skipping")
                return@subscribe
            }

            // If we still don't have duration, try to extract from file
            if (videoDuration == null || videoDuration <= 0) {
                context.log.verbose("GalleryVideoSplitting: Extracting duration from file...")
                
                try {
                    // Parse URI and strip query parameters for MediaMetadataRetriever
                    val baseUri = Uri.parse(mediaUriStr.substringBefore('?'))
                    val retriever = MediaMetadataRetriever()
                    
                    try {
                        retriever.setDataSource(context.androidContext, baseUri)
                        videoDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        context.log.verbose("GalleryVideoSplitting: Extracted duration = $videoDuration ms")
                    } finally {
                        retriever.release()
                    }
                } catch (e: Exception) {
                    context.log.error("GalleryVideoSplitting: Failed to extract duration", e)
                }
            }

            // Check if video needs splitting
            if (videoDuration == null || videoDuration <= 10000) {
                if (videoDuration == null) {
                    context.log.verbose("GalleryVideoSplitting: Could not determine duration")
                } else {
                    context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                }
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), will split")

            // Cancel the original send
            event.canceled = true

            val conversations = event.destinations.conversations?.map { SnapUUID(it) } ?: emptyList()
            if (conversations.isEmpty()) {
                context.log.error("GalleryVideoSplitting: No conversations to send to!")
                return@subscribe
            }

            // Show duration dialog
            context.runOnUiThread {
                showDurationDialog(event, conversations, videoDuration, messageProtoReader, mediaUriStr)
            }
        }
    }

    private fun showDurationDialog(
        event: SendMessageWithContentEvent,
        conversations: List<SnapUUID>,
        totalDuration: Long,
        messageProtoReader: ProtoReader,
        mediaUriStr: String
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
                    }) {
                        Text(context.translation["button.cancel"])
                    }
                    Button(onClick = {
                        alertDialog.dismiss()
                        val snapDuration = convertDuration(customDuration)
                        
                        // Start async splitting process
                        context.coroutineScope.launch {
                            isSplitting = true
                            splitAndSendVideo(conversations, snapDuration, messageProtoReader, mediaUriStr)
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
        messageProtoReader: ProtoReader,
        mediaUriStr: String
    ) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            // Parse URI and strip query parameters
            val cleanUriStr = mediaUriStr.substringBefore('?')
            val mediaUri = Uri.parse(cleanUriStr)
            context.log.verbose("GalleryVideoSplitting: Using clean URI: $cleanUriStr")
            
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

            // Get metadata from original proto
            val hasSound = messageProtoReader.getVarInt(3, 3, 5, 2, 5) ?: 1
            val originalWidth = messageProtoReader.getVarInt(3, 3, 5, 2, 6)?.toInt()
            val originalHeight = messageProtoReader.getVarInt(3, 3, 5, 2, 7)?.toInt()

            // Send each chunk
            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")
                
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                    val chunkWidth = originalWidth ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                    val chunkHeight = originalHeight ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                    
                    context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} - Duration: ${chunkDuration}ms, Size: ${chunkWidth}x${chunkHeight}")

                    // Build content matching EXTERNAL_MEDIA structure
                    val chunkContent = ProtoWriter().apply {
                        from(3) {
                            from(3) {
                                from(5) {
                                    from(1) {
                                        from(1) {
                                            addVarInt(2, 1) // attachment type
                                            from(5) {
                                                addVarInt(1, chunkWidth)
                                                addVarInt(2, chunkHeight)
                                            }
                                            addVarInt(15, chunkDuration)
                                            addVarInt(16, chunkWidth)
                                            addVarInt(17, chunkHeight)
                                        }
                                        addVarInt(6, 1) // media type: video
                                    }
                                    from(2) {
                                        addVarInt(5, hasSound)
                                        addVarInt(6, chunkWidth)
                                        addVarInt(7, chunkHeight)
                                        if (snapDuration != null) {
                                            if (snapDuration >= 1000) {
                                                addVarInt(8, snapDuration / 1000)
                                            } else {
                                                addVarInt(99, snapDuration.toLong())
                                            }
                                        } else {
                                            addVarInt(8, chunkDuration / 1000)
                                        }
                                    }
                                }
                                from(22) {
                                    addVarInt(4, 1)
                                }
                            }
                        }
                    }.toByteArray()

                    // Send with local media reference containing the chunk URI
                    sendChunk(conversations, chunkContent, chunkUri.toString().toByteArray())
                    
                    delay(1500)
                    
                } finally {
                    retriever.release()
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sent ${outputFiles.size} chunks!")
            }
            
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed: ${e.message}")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun sendChunk(conversations: List<SnapUUID>, messageContent: ByteArray, localMediaRefBytes: ByteArray) {
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
            "mLocalMediaReferences": [{"mId": [${localMediaRefBytes.joinToString(",")}]}],
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

        val callback = CallbackBuilder(sendMessageCallback)
            .override("onSuccess") {
                context.log.verbose("GalleryVideoSplitting: Chunk sent successfully")
            }
            .override("onError") { param ->
                context.log.error("GalleryVideoSplitting: Failed to send chunk: ${param.arg<Any>(0)}")
            }
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
