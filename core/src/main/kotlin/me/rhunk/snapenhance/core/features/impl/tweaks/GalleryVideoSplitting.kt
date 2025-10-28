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

    // ✅ Fixed type mismatch (lazy property now returns Class<*> properly)
    private val sendMessageCallback: Class<*> by lazy {
        var clazz: Class<*>? = null
        context.mappings.useMapper(CallbackMapper::class) {
            clazz = callbacks.getClass("SendMessageCallback")
        }
        clazz ?: throw IllegalStateException("SendMessageCallback not found in mappings")
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
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, ignoring")
                return@subscribe
            }

            val hasStories = event.destinations.stories?.isNotEmpty() == true
            val hasConversations = event.destinations.conversations?.isNotEmpty() == true

            if (hasStories && !hasConversations) {
                context.log.verbose("GalleryVideoSplitting: Story reply detected, skipping")
                return@subscribe
            }

            try {
                val localMessageContent = event.messageContent
                val contentType = localMessageContent.contentType
                if (contentType != ContentType.EXTERNAL_MEDIA) {
                    context.log.verbose("GalleryVideoSplitting: Not EXTERNAL_MEDIA, skipping")
                    return@subscribe
                }

                val content = localMessageContent.content ?: return@subscribe
                val messageProtoReader = ProtoReader(content)

                if (messageProtoReader.contains(7)) {
                    context.log.verbose("GalleryVideoSplitting: Story reply proto detected, skipping")
                    return@subscribe
                }

                val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                if (mediaCount != 1) {
                    context.log.verbose("GalleryVideoSplitting: Multiple media not supported ($mediaCount items), skipping")
                    return@subscribe
                }

                val snapDocPlayback = messageProtoReader.followPath(3, 3, 5)
                if (snapDocPlayback == null) {
                    context.log.verbose("GalleryVideoSplitting: No snapDocPlayback found at path 3,3,5")
                    return@subscribe
                }

                // ✅ Snapchat time limiter workaround
                // Try to extract duration, fall back to MediaMetadataRetriever if missing
                var videoDuration = snapDocPlayback.getVarInt(1, 1, 15)
                if (videoDuration == null || videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: No proto duration (Snapchat likely removed it), using metadata fallback.")

                    val uriString = snapDocPlayback.getString(1, 2)
                    if (uriString != null) {
                        val uri = Uri.parse(uriString)
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context.androidContext, uri)
                            videoDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        } catch (e: Exception) {
                            context.log.error("GalleryVideoSplitting: Metadata retriever failed to get duration", e)
                        } finally {
                            retriever.release()
                        }
                    }
                }

                if (videoDuration == null || videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Unable to determine video duration, skipping")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Video duration = ${videoDuration}ms")

                if (videoDuration <= 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video <=10s, no split needed")
                    return@subscribe
                }

                val contentUriStr = snapDocPlayback.getString(1, 2)
                if (contentUriStr == null) {
                    context.log.error("GalleryVideoSplitting: Could not extract content URI from snapDocPlayback")
                    return@subscribe
                }

                event.canceled = true
                val mediaUri = Uri.parse(contentUriStr)
                val conversations = event.destinations.conversations?.map { SnapUUID(it) } ?: emptyList()
                if (conversations.isEmpty()) {
                    context.log.error("GalleryVideoSplitting: No conversations to send to!")
                    return@subscribe
                }

                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }

                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10s chunks...")
                        }

                        val cachedVideo = File(tempDir, "input.mp4")

                        context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                            cachedVideo.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Failed to open input stream for media URI")

                        val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            throw IllegalStateException("FFmpeg failed: ${session.output}")
                        }

                        val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                        if (outputFiles.isEmpty()) throw IllegalStateException("FFmpeg produced no output files")

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} chunks...")
                        }

                        for ((index, file) in outputFiles.withIndex()) {
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
            it.conversations = conversations.toCollection(ArrayList())
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
    }
}
