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

                // Parse message content
                val messageProtoReader = ProtoReader(content)
                
                // Check if it's a single media item
                val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                context.log.verbose("GalleryVideoSplitting: Media count = $mediaCount")
                
                if (mediaCount != 1) {
                    context.log.verbose("GalleryVideoSplitting: Not single media ($mediaCount items), skipping")
                    return@subscribe
                }

                // Get video duration
                val videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: 0
                context.log.verbose("GalleryVideoSplitting: Video duration = ${videoDuration}ms")
                
                if (videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Not a video or no duration, skipping")
                    return@subscribe
                }

                // If video is <= 10 seconds, no need to split
                if (videoDuration <= 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: VIDEO > 10s detected! Duration: ${videoDuration}ms - Will split")
                
                // Extract media URI from the proto
                val contentUriStr = messageProtoReader.getString(3, 3, 2)
                if (contentUriStr == null) {
                    context.log.error("GalleryVideoSplitting: Could not extract content URI from proto")
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

                // Start async splitting process
                context.log.verbose("GalleryVideoSplitting: Launching coroutine...")
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
                        val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
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

                        // Send each chunk using MessageSender
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
                                
                                context.log.verbose("GalleryVideoSplitting: Chunk metadata - Duration: ${chunkDuration}ms, Size: ${chunkWidth}x${chunkHeight}")

                                // Create EXTERNAL_MEDIA content for this chunk
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

                                context.log.verbose("GalleryVideoSplitting: Sending chunk ${index + 1}")
                                sendChunkMessage(conversations, chunkContent)
                                
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
            it.conversations = conversations.toCollection(ArrayList<Any>())
            it.mPhoneNumbers = arrayListOf<Any>()
            it.stories = arrayListOf<Any>()
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
