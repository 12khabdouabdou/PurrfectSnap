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
import me.rhunk.snapenhance.core.wrapper.impl.MessageContent
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import me.rhunk.snapenhance.mapper.impl.CallbackMapper
import me.rhunk.snapenhance.core.features.impl.experiments.MediaFilePicker
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)

    private val mediaFilePicker by lazy { context.feature(MediaFilePicker::class) }

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

        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            context.log.verbose("GalleryVideoSplitting: FFmpegKit found")
        } catch (e: ClassNotFoundException) {
            context.log.error("GalleryVideoSplitting: FFmpegKit not found! Feature disabled.", e)
            return
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe

            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }

            val localMessageContent: MessageContent = event.messageContent

            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA &&
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) {
                return@subscribe
            }

            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            context.log.verbose("GalleryVideoSplitting: Processing EXTERNAL_MEDIA message")
            context.log.verbose("GalleryVideoSplitting: Full proto:\n$messageProtoReader")

            if ((messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                context.log.verbose("GalleryVideoSplitting: Multiple media detected, skipping")
                return@subscribe
            }

            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)
            if (videoDuration == null || videoDuration <= 0) {
                videoDuration = mediaFilePicker.lastMediaDuration?.toLong() ?: 0L
                context.log.verbose("GalleryVideoSplitting: Using fallback duration from MediaFilePicker: $videoDuration ms")
            } else {
                context.log.verbose("GalleryVideoSplitting: Video duration from proto (3,3,5,1,1,15) = $videoDuration ms")
            }

            event.canceled = true
            context.log.verbose("GalleryVideoSplitting: Original send CANCELED")

            val conversations: List<SnapUUID> = event.destinations.conversations?.map { SnapUUID(it) } ?: emptyList()
            if (conversations.isEmpty()) {
                context.log.error("GalleryVideoSplitting: No conversations to send to!")
                return@subscribe
            }

            if (videoDuration <= 10000) {
                context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                event.invokeOriginal()
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), will split")

            context.runOnUiThread {
                showDurationDialog(event, conversations, videoDuration, messageProtoReader)
            }
        }

        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun showDurationDialog(
        event: SendMessageWithContentEvent,
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
                    }) {
                        Text(context.translation["button.cancel"])
                    }
                    Button(onClick = {
                        alertDialog.dismiss()
                        val snapDuration = convertDuration(customDuration)
                        context.log.verbose("GalleryVideoSplitting: User selected snap duration: $snapDuration ms")

                        context.coroutineScope.launch {
                            isSplitting = true
                            splitAndSendVideo(event, conversations, snapDuration, messageProtoReader)
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
        messageProtoReader: ProtoReader
    ) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }

        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            val localMessageContent: MessageContent = event.messageContent
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

            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open input stream")

            context.log.verbose("GalleryVideoSplitting: Video cached (${cachedVideo.length()} bytes)")

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

            val hasSound = messageProtoReader.getVarInt(3, 3, 5, 2, 5) ?: 1
            context.log.verbose("GalleryVideoSplitting: Original hasSound = $hasSound")

            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")

                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)

                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920

                    val chunkContent = ProtoWriter().apply {
                        from(3) {
                            from(3) {
                                addString(2, chunkUri.toString())
                                from(5) {
                                    from(1) {
                                        from(1) {
                                            addVarInt(2, 0)
                                            addVarInt(12, 0)
                                            addVarInt(15, snapDuration?.toLong() ?: chunkDuration)
                                            addVarInt(16, chunkWidth)
                                            addVarInt(17, chunkHeight)
                                        }
                                        addVarInt(6, 1)
                                    }
                                    from(2) {
                                        addVarInt(5, hasSound)
                                        if (snapDuration != null) {
                                            if (snapDuration >= 1000) {
                                                addVarInt(8, snapDuration / 1000)
                                            } else {
                                                addVarInt(99, snapDuration.toLong())
                                            }
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
            it.mPhoneNumbers = ArrayList()
            it.stories = ArrayList()
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
