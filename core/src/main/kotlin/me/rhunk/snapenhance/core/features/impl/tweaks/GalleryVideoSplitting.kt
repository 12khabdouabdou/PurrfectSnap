package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.util.CallbackBuilder
import me.rhunk.snapenhance.core.wrapper.AbstractWrapper
import me.rhunk.snapenhance.core.wrapper.impl.MessageDestinations
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import me.rhunk.snapenhance.mapper.impl.CallbackMapper
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    private val sendMessageCallback by lazy {
        lateinit var result: Class<*>
        context.mappings.useMapper(CallbackMapper::class) {
            result = callbacks.getClass("SendMessageCallback") ?: return@useMapper
        }
        result
    }

    override fun init() {
        context.log.verbose("GalleryVideoSplitting: Initializing...")

        val isEnabled = context.config.messaging.splitVideoIntoTenSecondSnaps.get()
        context.log.verbose("GalleryVideoSplitting: Config enabled = $isEnabled")

        if (!isEnabled) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config, aborting init")
            return
        }

        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            context.log.verbose("GalleryVideoSplitting: FFmpegKit found")
        } catch (e: ClassNotFoundException) {
            context.log.error("GalleryVideoSplitting: FFmpegKit not found! Feature disabled.", e)
            return
        }

        context.log.verbose("GalleryVideoSplitting: Subscribing to SendMessageWithContentEvent")

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            context.log.verbose("GalleryVideoSplitting: Event received!")

            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, ignoring")
                return@subscribe
            }

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

                if (contentType != ContentType.EXTERNAL_MEDIA) {
                    context.log.verbose("GalleryVideoSplitting: Not EXTERNAL_MEDIA, skipping")
                    return@subscribe
                }

                val content = localMessageContent.content
                if (content == null) {
                    context.log.verbose("GalleryVideoSplitting: Content is null, skipping")
                    return@subscribe
                }

                val messageProtoReader = ProtoReader(content)
                val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                context.log.verbose("GalleryVideoSplitting: Media count = $mediaCount")

                if (mediaCount != 1) {
                    context.log.verbose("GalleryVideoSplitting: Not single media ($mediaCount items), skipping")
                    return@subscribe
                }

                // --- FIX START ---
                // Try to get duration from proto first
                var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: 0
                context.log.verbose("GalleryVideoSplitting: Proto duration = ${videoDuration}ms")

                // Extract URI early so we can use it for fallback
                val contentUriStr = messageProtoReader.getString(3, 3, 2)
                if (contentUriStr == null) {
                    context.log.error("GalleryVideoSplitting: Could not extract content URI from proto")
                    return@subscribe
                }

                // If duration is missing or truncated due to Snapchat’s limiter, use MediaMetadataRetriever
                if (videoDuration <= 0 || videoDuration >= 9999) {
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(context.androidContext, Uri.parse(contentUriStr))
                        val realDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        retriever.release()
                        if (realDuration > 0) {
                            videoDuration = realDuration
                            context.log.verbose("GalleryVideoSplitting: Using real duration from metadata: ${videoDuration}ms")
                        } else {
                            context.log.verbose("GalleryVideoSplitting: Could not retrieve real duration, still using proto value ($videoDuration)")
                        }
                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: Failed to read video duration via MediaMetadataRetriever", e)
                    }
                }
                // --- FIX END ---

                if (videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Not a video or no duration, skipping")
                    return@subscribe
                }

                if (videoDuration <= 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: VIDEO > 10s detected! Duration: ${videoDuration}ms - Will split")

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

                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply {
                        mkdirs()
                        context.log.verbose("GalleryVideoSplitting: Created temp dir: $absolutePath")
                    }

                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10s chunks...")
                        }

                        val cachedVideo = File(tempDir, "input.mp4")

                        context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                            cachedVideo.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IllegalStateException("Failed to open input stream for media URI")

                        val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                        context.log.verbose("GalleryVideoSplitting: FFmpeg command: $command")

                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            throw IllegalStateException("FFmpeg failed: ${session.output}")
                        }

                        val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                        context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} files")

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} chunks...")
                        }

                        for ((index, file) in outputFiles.withIndex()) {
                            context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")

                            val chunkUri = Uri.fromFile(file)
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context.androidContext, chunkUri)
                                val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                                val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                                val hasSound = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.toIntOrNull() ?: 1

                                val chunkContent = ProtoWriter().apply {
                                    from(3) {
                                        from(3) {
                                            addString(2, chunkUri.toString())
                                            from(5) {
                                                from(1) {
                                                    from(1) {
                                                        addVarInt(15, chunkDuration)
                                                        addVarInt(16, chunkWidth)
                                                        addVarInt(17, chunkHeight)
                                                    }
                                                }
                                                from(2) {
                                                    addVarInt(5, hasSound)
                                                }
                                            }
                                        }
                                    }
                                }.toByteArray()

                                sendChunkMessage(conversations, chunkContent)
                                delay(1500)

                            } finally {
                                retriever.release()
                            }
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Successfully sent ${outputFiles.size} chunks!")
                        }

                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed to split video: ${e.message}")
                        }
                    } finally {
                        tempDir.deleteRecursively()
                        isSplitting = false
                    }
                }

            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Error in event handler", e)
            }
        }

        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun sendChunkMessage(conversations: List<SnapUUID>, messageContent: ByteArray) {
        context.log.verbose("GalleryVideoSplitting: sendChunkMessage called")

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
            it.conversations = conversations.toCollection(ArrayList<SnapUUID>())
            it.mPhoneNumbers = arrayListOf()
            it.stories = arrayListOf()
        }

        val callback = CallbackBuilder(sendMessageCallback).build()
        val conversationManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()

        sendMessageWithContentMethod.invoke(
            conversationManager,
            messageDestinations.instanceNonNull(),
            localMessageContent,
            callback
        )

        context.log.verbose("GalleryVideoSplitting: sendChunkMessage complete")
    }
}
