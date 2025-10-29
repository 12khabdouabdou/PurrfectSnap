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
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
        } catch (e: ClassNotFoundException) {
            context.log.error("FFmpegKit not found! Feature disabled.", e)
            return
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe

            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) return@subscribe

            val localMessageContent: MessageContent = event.messageContent
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA &&
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) return@subscribe

            val protoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (protoReader.contains(7)) return@subscribe

            var videoDuration: Long = protoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: 0L
            if (videoDuration <= 0L) {
                videoDuration = mediaFilePicker.lastMediaDuration ?: 0L
            }
            event.canceled = true

            val conversations: List<SnapUUID> = event.destinations.conversations?.map { SnapUUID(it) } ?: emptyList()
            if (conversations.isEmpty()) return@subscribe

            if (videoDuration <= 10000) {
                event.invokeOriginal()
                return@subscribe
            }

            context.runOnUiThread {
                showDurationDialog(event, conversations, videoDuration, protoReader)
            }
        }
    }

    private fun showDurationDialog(
        event: SendMessageWithContentEvent,
        conversations: List<SnapUUID>,
        totalDuration: Long,
        protoReader: ProtoReader
    ) {
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            val mainTranslation = remember { context.translation.getCategory("send_override_dialog") }
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
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Split Video", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Video duration: ${(totalDuration / 1000.0).toDuration(DurationUnit.SECONDS).toString(DurationUnit.SECONDS, 1)}")
                Text("This video will be split into 10-second chunks and sent separately.")
                Column(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        mainTranslation.format(
                            "duration",
                            "duration" to (convertDuration(customDuration)?.toDuration(DurationUnit.MILLISECONDS)
                                ?.toString(DurationUnit.SECONDS, 2) ?: mainTranslation["unlimited_duration"])
                        )
                    )
                    Slider(
                        value = customDuration,
                        onValueChange = { customDuration = it },
                        valueRange = -2f..11f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Snap duration for each chunk", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { alertDialog.dismiss() }) {
                        Text(mainTranslation["button.cancel"])
                    }
                    Button(onClick = {
                        alertDialog.dismiss()
                        val snapDuration = convertDuration(customDuration)
                        context.log.verbose("User selected snap duration: $snapDuration ms")
                        context.coroutineScope.launch {
                            isSplitting = true
                            splitAndSendVideo(event, conversations, snapDuration, protoReader)
                            isSplitting = false
                        }
                    }) {
                        Text(mainTranslation["button.send"])
                    }
                }
            }
        }.show()
    }

    private suspend fun splitAndSendVideo(
        event: SendMessageWithContentEvent,
        conversations: List<SnapUUID>,
        snapDuration: Int?,
        protoReader: ProtoReader
    ) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }
            val localMessageContent: MessageContent = event.messageContent
            val mediaUriStr = getMediaUriString(localMessageContent)
            if (mediaUriStr == null) {
                context.log.error("Could not extract media URI")
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed to get video URI")
                }
                return
            }

            val mediaUri = Uri.parse(mediaUriStr)
            val cachedVideo = File(tempDir, "input.mp4")
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Failed to open input stream")

            val command =
                "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
            context.log.verbose("FFmpeg command: $command")

            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed: ${session.output}")
            }

            val outputFiles: List<File> = tempDir.listFiles { _, name -> name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            context.log.verbose("Split into ${outputFiles.size} files")

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} chunks...")
            }

            val hasSound = protoReader.getVarInt(3, 3, 5, 2, 5) ?: 1L
            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("Processing chunk ${index + 1}/${outputFiles.size}")
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920

                    val chunkContent = buildChunkProto(chunkUri, chunkDuration, chunkWidth, chunkHeight, snapDuration, hasSound)

                    sendChunk(conversations, chunkContent)
                    delay(1500)
                } finally {
                    retriever.release()
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sent ${outputFiles.size} chunks!")
            }
        } catch (e: Exception) {
            context.log.error("Failed to split and send video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed: ${e.message}")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun getMediaUriString(localMessageContent: MessageContent): String? {
        val protoReader = ProtoReader(localMessageContent.content ?: return null)
        val localMediaRef = protoReader.followPath(3)?.getBuffer()
        return localMediaRef?.let { bytes -> String(bytes, Charsets.UTF_8) }
    }

    private fun buildChunkProto(
        chunkUri: Uri,
        chunkDuration: Long,
        chunkWidth: Int,
        chunkHeight: Int,
        snapDuration: Int?,
        hasSound: Long
    ): ByteArray {
        return ProtoWriter().apply {
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
    }

    private fun sendChunk(conversations: List<SnapUUID>, messageContent: ByteArray) {
        val sendMethod = context.classCache.conversationManager.declaredMethods.first { it.name == "sendMessageWithContent" }
        val messageJson = """
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
        """
        val localMessageContent = context.gson.fromJson(messageJson, context.classCache.localMessageContent)
        val destinations = MessageDestinations(
            AbstractWrapper.newEmptyInstance(context.classCache.messageDestinations)
        ).apply {
            conversations.let {
                this.conversations = ArrayList<SnapUUID>(it)
            }
            this.mPhoneNumbers = ArrayList<Any>()
            this.stories = ArrayList<Any>()
        }
        val callback = CallbackBuilder(sendMessageCallback).build()
        val conversationManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()
        sendMethod.invoke(conversationManager, destinations.instanceNonNull(), localMessageContent, callback)
    }
}
