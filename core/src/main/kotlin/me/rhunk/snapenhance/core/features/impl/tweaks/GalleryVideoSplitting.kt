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
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.wrapper.impl.MessageDestinations
import me.rhunk.snapenhance.mapper.impl.CallbackMapper
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            return
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) {
                return@subscribe
            }

            try {
                val messageContent = event.messageContent
                val contentType = messageContent.contentType
                
                // Only process EXTERNAL_MEDIA (gallery videos)
                if (contentType != ContentType.EXTERNAL_MEDIA) {
                    return@subscribe
                }

                // Get local media references
                val localMediaRefs = messageContent.instanceNonNull()
                    .getObjectField("mLocalMediaReferences") as? List<*> ?: return@subscribe
                
                if (localMediaRefs.isEmpty()) return@subscribe
                
                val mediaRef = localMediaRefs.firstOrNull() ?: return@subscribe
                val mediaIdBytes = mediaRef.getObjectField("mId") as? ByteArray ?: return@subscribe
                val mediaUriStr = String(mediaIdBytes)
                val mediaUri = Uri.parse(mediaUriStr)

                // Check if it's a video and get duration
                val retriever = MediaMetadataRetriever()
                val videoDuration: Long
                val isVideo: Boolean
                
                try {
                    retriever.setDataSource(context.androidContext, mediaUri)
                    val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    isVideo = mimeType?.startsWith("video/") == true
                    
                    if (!isVideo) {
                        return@subscribe
                    }
                    
                    videoDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    
                    // If video is 10 seconds or less, let it proceed normally
                    if (videoDuration <= 10000) {
                        context.log.verbose("Video duration ${videoDuration}ms is <= 10s, not splitting")
                        return@subscribe
                    }
                } catch (e: Exception) {
                    context.log.error("Failed to check video metadata", e)
                    return@subscribe
                } finally {
                    retriever.release()
                }

                // Cancel the original send - we'll handle it
                event.canceled = true
                
                context.log.verbose("Splitting video of ${videoDuration}ms duration")

                // Process video splitting asynchronously
                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}")
                        .apply { mkdirs() }
                    
                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info,
                                "Splitting video into 10s segments..."
                            )
                        }

                        // Copy video to cache
                        val cachedVideo = File(tempDir, "input.mp4")
                        context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                            cachedVideo.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IllegalStateException("Failed to read video")

                        // Split video using FFmpeg
                        val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                        }

                        val outputFiles = tempDir.listFiles()
                            ?.filter { it.name.startsWith("split_") && it.extension == "mp4" }
                            ?.sortedBy { it.name }
                            ?: emptyList()
                            
                        if (outputFiles.isEmpty()) {
                            throw IllegalStateException("No output files generated")
                        }

                        context.log.verbose("Split into ${outputFiles.size} segments")

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info,
                                "Sending ${outputFiles.size} segments..."
                            )
                        }

                        // Get callback class - same pattern as SendOverride
                        val sendMessageCallback by lazy {
                            lateinit var result: Class<*>
                            context.mappings.useMapper(CallbackMapper::class) {
                                result = callbacks.getClass("SendMessageCallback") ?: return@useMapper
                            }
                            result
                        }

                        // Send each segment
                        for ((index, file) in outputFiles.withIndex()) {
                            val chunkUri = Uri.fromFile(file)
                            val chunkUriBytes = chunkUri.toString().toByteArray()
                            
                            context.log.verbose("Sending segment ${index + 1}/${outputFiles.size}")

                            // Get video metadata for this chunk
                            val chunkRetriever = MediaMetadataRetriever()
                            val chunkDuration: Long
                            val chunkWidth: Int
                            val chunkHeight: Int
                            
                            try {
                                chunkRetriever.setDataSource(context.androidContext, chunkUri)
                                chunkDuration = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                                chunkWidth = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                                chunkHeight = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                            } finally {
                                chunkRetriever.release()
                            }

                            // Build the message content protobuf for this chunk
                            val chunkContent = ProtoWriter().apply {
                                from(3) { // External media container
                                    from(3) { // Media item
                                        from(5) { // Media metadata
                                            from(1) { // Playback info
                                                from(1) { // Playback details
                                                    addVarInt(2, 0) // Media type: VIDEO
                                                    addVarInt(12, 0) // Unknown
                                                    addVarInt(15, chunkDuration) // Duration in ms
                                                }
                                                addVarInt(6, 1) // Has playback
                                            }
                                            from(2) { // Timing info
                                                addVarInt(5, 1) // Has audio (assume yes)
                                            }
                                        }
                                        from(7) { // Dimensions
                                            addVarInt(1, chunkWidth)
                                            addVarInt(2, chunkHeight)
                                        }
                                        from(8) { // Media reference
                                            addBuffer(1, chunkUriBytes)
                                        }
                                    }
                                }
                            }.toByteArray()

                            // Create message content JSON template
                            val messageContentJson = """
                            {
                                "mAllowsTranscription": false,
                                "mBotMention": false,
                                "mContent": [${chunkContent.joinToString(",")}],
                                "mContentType": "EXTERNAL_MEDIA",
                                "mIncidentalAttachments": [],
                                "mLocalMediaReferences": [{"mId": [${chunkUriBytes.joinToString(",")}]}],
                                "mPlatformAnalytics": {
                                    "mAttemptId": null,
                                    "mContent": null,
                                    "mMetricsMessageMediaType": "VIDEO",
                                    "mMetricsMessageType": "MEDIA",
                                    "mReactionSource": "NONE"
                                },
                                "mSavePolicy": "PROHIBITED"
                            }
                            """.trimIndent()

                            val localMessageContent = context.gson.fromJson(
                                messageContentJson,
                                context.classCache.localMessageContent
                            )

                            // Create callback for this message - FIX: Use callback parameter
                            val callback = CallbackBuilder(sendMessageCallback)
                                .override("onSuccess", callback = {
                                    context.log.verbose("Segment ${index + 1} sent successfully")
                                })
                                .override("onError", callback = { param ->
                                    context.log.error("Failed to send segment ${index + 1}: ${param.arg<Any>(0)}")
                                })
                                .build()

                            // Send the message
                            val sendMessageMethod = context.classCache.conversationManager
                                .declaredMethods
                                .first { it.name == "sendMessageWithContent" }
                            
                            val conversationManager = context.feature(Messaging::class)
                                .conversationManager?.instanceNonNull()

                            sendMessageMethod.invoke(
                                conversationManager,
                                event.destinations.instanceNonNull(),
                                localMessageContent,
                                callback
                            )

                            // Delay between sends to avoid rate limiting
                            if (index < outputFiles.size - 1) {
                                delay(500)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info,
                                "Successfully sent ${outputFiles.size} segments!"
                            )
                        }
                        
                    } catch (e: Exception) {
                        context.log.error("Failed to split and send video", e)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.Info,
                                "Failed to split video: ${e.message}"
                            )
                        }
                        // On failure, send the original video
                        withContext(Dispatchers.Main) {
                            event.invokeOriginal()
                        }
                    } finally {
                        // Cleanup temp files
                        tempDir.deleteRecursively()
                        isSplitting = false
                    }
                }
                
            } catch (e: Exception) {
                context.log.error("Error in GalleryVideoSplitting", e)
            }
        }
    }
}
