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
import me.rhunk.snapenhance.core.features.impl.experiments.MediaFilePicker
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)

    // Lazily get MediaFilePicker feature for fallback duration
    private val mediaFilePicker by lazy { context.feature(MediaFilePicker::class) }

    private val sendMessageCallback by lazy {
        var result: Class<*>? = null
        context.mappings.useMapper(CallbackMapper::class) { result = callbacks.getClass("SendMessageCallback") }
        result ?: throw IllegalStateException("SendMessageCallback class not found")
    }

    override fun init() {
        // Check if feature enabled
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        // Check FFmpeg presence
        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
        } catch (e: ClassNotFoundException) {
            context.log.error("FFmpegKit not found! Feature disabled.", e)
            return
        }

        // Subscribe to message send events
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe

            // Skip if only story
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) return@subscribe

            val localMsg = event.messageContent
            if (localMsg.contentType != ContentType.EXTERNAL_MEDIA && localMsg.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) return@subscribe

            val protoReader = ProtoReader(localMsg.content ?: return@subscribe)
            if (protoReader.contains(7)) return@subscribe

            // Cancel original send event
            event.canceled = true

            // Extract or fallback video duration
            var videoDuration = protoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: 0L
            if (videoDuration <= 0L) {
                videoDuration = mediaFilePicker.lastMediaDuration ?: 0L
            }
            val conversations = event.destinations.conversations?.map { SnapUUID(it) } ?: emptyList()
            if (conversations.isEmpty()) return@subscribe

            // Show user dialog if needed
            if (videoDuration <= 10000L) {
                // No splitting needed
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
            val translation = remember { context.translation.getCategory("send_override_dialog") }
            fun convertDuration(d: Float): Int? {
                return when {
                    d in -2f..-1f -> 100
                    d in -1f..-0f -> 250
                    d in -0f..1f -> 500
                    d >= 11f -> null
                    else -> ((d * 1000).toInt() / 1000) * 1000
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
                        translation.format(
                            "duration",
                            "duration" to (convertDuration(customDuration)?.toDuration(DurationUnit.MILLISECONDS)
                                ?.toString(DurationUnit.SECONDS, 2) ?: translation["unlimited_duration"])
                        )
                    )
                    Slider(
                        value = customDuration,
                        onValueChange = { customDuration = it },
                        valueRange = -2f..11f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Snap duration for each chunk")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { alertDialog.dismiss() }) {
                        Text(translation["button.cancel"])
                    }
                    Button(onClick = {
                        alertDialog.dismiss()
                        val snapDuration = convertDuration(customDuration)
                        context.log.verbose("User selected snap duration: $snapDuration ms")
                        // Launch split and send in coroutine
                        context.coroutineScope.launch {
                            isSplitting = true
                            splitAndSendVideo(event, conversations, snapDuration, protoReader)
                            isSplitting = false
                        }
                    }) {
                        Text(translation["button.send"])
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
            val localMsg = event.messageContent
            val mediaUriStr = getMediaUriString(localMsg)
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
            } ?: throw IllegalStateException("Failed to read media URI input stream")
            // Split with ffmpeg
            val cmd = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) throw IllegalStateException("FFmpeg failed: ${session.output}")
            val files = tempDir.listFiles { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${files.size} chunks...")
            }
            val hasSound = protoReader.getVarInt(3, 3, 5, 2, 5) ?: 1
            for ((i, file) in files.withIndex()) {
                val uri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context.androidContext, uri)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                    val chunkContent = buildChunkProto(uri, durationMs, width, height, snapDuration, hasSound)
                    sendChunk(conversations, chunkContent)
                    delay(1500)
                } finally {
                    retriever.release()
                }
            }
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sent ${files.size} chunks!")
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

    private fun getMediaUriString(localMsg: ContentType) : String? {
        val contentBytes = localMsg.content ?: return null
        val protoReader = ProtoReader(contentBytes)
        val mediaRefBytes = protoReader.followPath(3)?.getBuffer()
        val mediaUriStr = mediaRefBytes?.joinToString("") { "%02x".format(it) }
        return mediaUriStr
    }

    private fun buildChunkProto(
        uri: Uri,
        durationMs: Long,
        width: Int,
        height: Int,
        snapDuration: Int?,
        hasSound: Long
    ): ByteArray {
        return ProtoWriter().apply {
            from(3) {
                addString(2, uri.toString())
                from(5) {
                    from(1) {
                        addVarInt(2, 0)
                        addVarInt(12, 0)
                        addVarInt(15, snapDuration?.toLong() ?: durationMs)
                        addVarInt(16, width)
                        addVarInt(17, height)
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
        val localMsg = context.gson.fromJson(messageJson, context.classCache.localMessageContent)
        val destinations = MessageDestinations(
            AbstractWrapper.newEmptyInstance(context.classCache.messageDestinations)
        ).apply {
            this.conversations = ArrayList(conversations)
            this.mPhoneNumbers = ArrayList()
            this.stories = ArrayList()
        }
        val callback = CallbackBuilder(sendMessageCallback).build()
        val convoManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()
        sendMethod.invoke(convoManager, destinations.instanceNonNull(), localMsg, callback)
    }
}
