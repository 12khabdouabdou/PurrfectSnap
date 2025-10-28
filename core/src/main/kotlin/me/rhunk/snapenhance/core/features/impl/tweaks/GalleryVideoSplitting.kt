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
import me.rhunk.snapenhance.common.util.protobuf.ProtoEditor
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.NativeUnaryCallEvent
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    @Volatile
    private var currentPostSavePolicy: Int? = null

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config")
            return
        }

        context.log.verbose("GalleryVideoSplitting: Initializing...")

        // Hook NativeUnaryCallEvent to set save policy (same as SendOverride)
        context.event.subscribe(NativeUnaryCallEvent::class) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
            
            currentPostSavePolicy?.let { savePolicy ->
                context.log.verbose("GalleryVideoSplitting: Setting save policy=$savePolicy")
                event.buffer = ProtoEditor(event.buffer).apply {
                    edit(4) {
                        remove(7)
                        addVarInt(7, savePolicy)
                    }

                    // Remove Keep Snaps in Chat ability if prohibited
                    if (savePolicy == 1/* PROHIBITED */) {
                        edit(6, 9) {
                            remove(1)
                        }
                    }
                }.toByteArray()
                currentPostSavePolicy = null // Reset after use
            }
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, skipping")
                return@subscribe
            }

            try {
                // Only process conversations, not stories
                if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) {
                    context.log.verbose("GalleryVideoSplitting: Story message, skipping")
                    return@subscribe
                }

                val localMessageContent = event.messageContent
                
                // Only handle external media (gallery videos)
                if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                    return@subscribe
                }

                val messageProto = ProtoReader(localMessageContent.content ?: return@subscribe)
                
                // Prevent processing story replies
                if (messageProto.contains(7)) {
                    context.log.verbose("GalleryVideoSplitting: Story reply, skipping")
                    return@subscribe
                }

                // Check if it's a video by looking for video metadata
                val mediaProto = messageProto.followPath(3, 3) ?: run {
                    context.log.verbose("GalleryVideoSplitting: No media proto found")
                    return@subscribe
                }

                // Check media count - only handle single videos
                val mediaCount = mediaProto.getCount(5)
                if (mediaCount != 1) {
                    context.log.verbose("GalleryVideoSplitting: Media count = $mediaCount, only single videos supported")
                    return@subscribe
                }

                // Get video duration
                val videoDurationMs = mediaProto.followPath(5, 1, 1)?.getVarInt(15) ?: run {
                    context.log.verbose("GalleryVideoSplitting: No video duration found, not a video")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Found video with duration ${videoDurationMs}ms")

                // Only split if longer than 10 seconds
                if (videoDurationMs < 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video is shorter than 10 seconds, skipping")
                    return@subscribe
                }

                // Get the media URI
                val mediaUri = mediaProto.followPath(5, 1)?.getString(2) ?: run {
                    context.log.error("GalleryVideoSplitting: Could not find media URI")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Media URI = $mediaUri")

                // Get conversation IDs before canceling
                val conversationIds = event.destinations.conversations?.map { 
                    SnapUUID(it)
                } ?: emptyList()

                if (conversationIds.isEmpty()) {
                    context.log.error("GalleryVideoSplitting: No conversation IDs found")
                    return@subscribe
                }

                // Get extras from original message to preserve them
                val extras = messageProto.followPath(3, 3, 13)?.getBuffer()

                // Cancel the original message send
                event.canceled = true
                context.log.verbose("GalleryVideoSplitting: Canceling original send and starting split process")

                // Start the splitting process asynchronously
                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
                    
                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10-second snaps...")
                        }

                        val uri = Uri.parse(mediaUri)
                        val cachedVideo = File(tempDir, "input.mp4")

                        // Copy video to temp location
                        context.mainActivity!!.contentResolver.openInputStream(uri)?.use { input ->
                            cachedVideo.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IllegalStateException("Failed to open input stream")

                        context.log.verbose("GalleryVideoSplitting: Video cached to ${cachedVideo.absolutePath}")

                        // Split video using FFmpeg
                        val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
                        context.log.verbose("GalleryVideoSplitting: Executing FFmpeg: $command")

                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            val errorMsg = "FFmpeg failed with code ${session.returnCode}"
                            context.log.error("GalleryVideoSplitting: $errorMsg")
                            context.log.error("GalleryVideoSplitting: ${session.output}")
                            throw IllegalStateException(errorMsg)
                        }

                        val outputFiles = tempDir.listFiles()
                            ?.filter { it.name.startsWith("split_") && it.extension == "mp4" }
                            ?.sortedBy { it.name }
                            ?: emptyList()

                        if (outputFiles.isEmpty()) {
                            throw IllegalStateException("FFmpeg produced no output files")
                        }

                        context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} chunks")

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info, 
                                "Sending ${outputFiles.size} snaps..."
                            )
                        }

                        // Send each chunk as a SNAP using SendOverride logic
                        for ((index, file) in outputFiles.withIndex()) {
                            context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")
                            
                            val chunkUri = Uri.fromFile(file)
                            val retriever = MediaMetadataRetriever()
                            
                            try {
                                retriever.setDataSource(context.androidContext, chunkUri)
                                val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val hasSound = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.toIntOrNull() ?: 1
                                
                                context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} duration: ${chunkDuration}ms, hasSound: $hasSound")

                                // Construct SNAP message content using SendOverride logic
                                val snapContent = ProtoWriter().apply {
                                    from(11) {
                                        from(5) {
                                            from(1) {
                                                from(1) {
                                                    addVarInt(2, 0)
                                                    addVarInt(12, 0)
                                                    // Set snap duration (max 10 seconds)
                                                    val snapDurationSeconds = minOf(chunkDuration / 1000, 10)
                                                    addVarInt(15, snapDurationSeconds)
                                                }
                                                addVarInt(6, 1)
                                            }
                                            from(2) {
                                                addVarInt(5, hasSound.toLong())
                                                // Set view duration to match video duration
                                                val viewDurationSeconds = minOf(chunkDuration / 1000, 10)
                                                addVarInt(8, viewDurationSeconds)
                                            }
                                        }
                                        // Add extras if they exist
                                        extras?.let {
                                            addBuffer(13, it)
                                        }
                                        from(22) {
                                            // Set app source to camera
                                            addVarInt(4, 5) // APP_SOURCE_CAMERA
                                        }
                                    }
                                }.toByteArray()

                                // Create local message content JSON template
                                val messageContentJson = """
                                {
                                    "mAllowsTranscription": false,
                                    "mBotMention": false,
                                    "mContent": [${snapContent.joinToString(",")}],
                                    "mContentType": "SNAP",
                                    "mIncidentalAttachments": [],
                                    "mLocalMediaReferences": [{"mId": []}],
                                    "mPlatformAnalytics": {
                                        "mAttemptId": null,
                                        "mContent": null,
                                        "mMetricsMessageMediaType": "NO_MEDIA",
                                        "mMetricsMessageType": "TEXT",
                                        "mReactionSource": "NONE"
                                    },
                                    "mSavePolicy": "PROHIBITED"
                                }
                                """.trimIndent()

                                // Set save policy for this message
                                currentPostSavePolicy = 1 // PROHIBITED

                                // Parse the message content
                                val localMessageContent = context.gson.fromJson(
                                    messageContentJson,
                                    context.classCache.localMessageContent
                                )

                                // Create message destinations
                                val messageDestinations = context.classCache.messageDestinations.newInstance()
                                messageDestinations.javaClass.getDeclaredField("mConversations").apply {
                                    isAccessible = true
                                    set(messageDestinations, ArrayList(conversationIds.map { it.instanceNonNull() }))
                                }
                                messageDestinations.javaClass.getDeclaredField("mPhoneNumbers").apply {
                                    isAccessible = true
                                    set(messageDestinations, arrayListOf<Any>())
                                }
                                messageDestinations.javaClass.getDeclaredField("mStories").apply {
                                    isAccessible = true
                                    set(messageDestinations, arrayListOf<Any>())
                                }

                                // Create callback for send result
                                val callbackClass = context.mappings.useMapper(me.rhunk.snapenhance.mapper.impl.CallbackMapper::class) {
                                    callbacks.getClass("SendMessageCallback")
                                } ?: throw IllegalStateException("SendMessageCallback class not found")

                                val callback = java.lang.reflect.Proxy.newProxyInstance(
                                    callbackClass.classLoader,
                                    arrayOf(callbackClass)
                                ) { _, method, _ ->
                                    when (method.name) {
                                        "onSuccess" -> {
                                            context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} sent successfully")
                                        }
                                        "onError" -> {
                                            context.log.error("GalleryVideoSplitting: Failed to send chunk ${index + 1}")
                                        }
                                    }
                                    null
                                }

                                // Send the message
                                val conversationManager = context.feature(me.rhunk.snapenhance.core.features.impl.messaging.Messaging::class).conversationManager?.instanceNonNull()
                                    ?: throw IllegalStateException("ConversationManager not found")

                                val sendMessageMethod = context.classCache.conversationManager.declaredMethods
                                    .first { it.name == "sendMessageWithContent" }

                                sendMessageMethod.invoke(
                                    conversationManager,
                                    messageDestinations,
                                    localMessageContent,
                                    callback
                                )

                                context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} send initiated")
                                
                                delay(1000) // Wait between sends to avoid rate limiting
                            } finally {
                                retriever.release()
                            }
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info, 
                                "Sent ${outputFiles.size} snaps!"
                            )
                        }
                        context.log.verbose("GalleryVideoSplitting: All chunks sent successfully")

                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info, 
                                "Failed to split video: ${e.message}"
                            )
                        }
                    } finally {
                        tempDir.deleteRecursively()
                        isSplitting = false
                        currentPostSavePolicy = null
                        context.log.verbose("GalleryVideoSplitting: Cleanup complete")
                    }
                }

            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Error in event handler", e)
            }
        }

        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }
}
