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
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        context.log.info("Initializing Gallery Video Splitting")

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe
            
            // Skip if sending to stories only
            if (event.destinations.stories?.isNotEmpty() == true && 
                event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }

            val localMessageContent = event.messageContent
            
            // Only handle external media (gallery videos)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                return@subscribe
            }

            // Check if it's a video
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            
            // Check if media is video type
            val isVideo = messageProtoReader.getVarInt(3, 3, 5, 2, 5) == 1L
            if (!isVideo) return@subscribe

            // Get video URI from protobuf
            val contentUriBytes = messageProtoReader.getByteArray(3, 3, 5, 1, 1, 2) ?: return@subscribe
            val contentUri = String(contentUriBytes)
            val mediaUri = Uri.parse(contentUri)
            
            context.log.info("Found gallery video: $contentUri")
            
            // Check video duration
            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(context.androidContext, mediaUri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                context.log.error("Failed to read video duration", e)
                0L
            } finally {
                retriever.release()
            }

            context.log.info("Video duration: ${durationMs}ms")

            // Only split videos longer than 10 seconds
            if (durationMs <= 10000) {
                context.log.info("Video is ${durationMs}ms, not splitting")
                return@subscribe
            }

            // Cancel the original send
            event.canceled = true
            context.log.info("Canceling original send to split video")

            // Save original event data
            val conversationIds = event.destinations.conversations?.map { 
                SnapUUID(it.toString())
            } ?: emptyList()
            val originalContent = localMessageContent.content!!

            context.coroutineScope.launch {
                isSplitting = true
                val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}")
                    .apply { mkdirs() }
                
                try {
                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(
                            Icons.Default.Info, 
                            "Splitting ${durationMs / 1000}s video..."
                        )
                    }

                    // Copy video to temp location
                    val cachedVideo = File(tempDir, "input.mp4")
                    context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                        cachedVideo.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Failed to open input stream")

                    // Split video with FFmpeg
                    val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 " +
                            "-reset_timestamps 1 -avoid_negative_ts make_zero ${tempDir.absolutePath}/split_%03d.mp4"
                    
                    context.log.info("Executing FFmpeg: $command")
                    val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                    if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                        context.log.error("FFmpeg failed: ${session.output}")
                        throw IllegalStateException("FFmpeg failed with code ${session.returnCode}")
                    }

                    val outputFiles = tempDir.listFiles()
                        ?.filter { it.name.startsWith("split_") }
                        ?.sortedBy { it.name } 
                        ?: emptyList()
                    
                    context.log.info("Created ${outputFiles.size} video chunks")
                    
                    if (outputFiles.isEmpty()) {
                        throw IllegalStateException("FFmpeg produced no output files")
                    }

                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(
                            Icons.Default.Info, 
                            "Sending ${outputFiles.size} snaps..."
                        )
                    }
                    
                    // Send each chunk
                    for ((index, file) in outputFiles.withIndex()) {
                        sendVideoChunk(conversationIds, originalContent, file, index)
                        delay(800)
                    }
                    
                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(
                            Icons.Default.Info, 
                            "Sent ${outputFiles.size} snaps!"
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
                } finally {
                    tempDir.deleteRecursively()
                    isSplitting = false
                }
            }
        }
    }

    private fun sendVideoChunk(
        conversations: List<SnapUUID>,
        originalProtoContent: ByteArray,
        videoFile: File,
        index: Int
    ) {
        try {
            val chunkUri = Uri.fromFile(videoFile)
            val retriever = MediaMetadataRetriever()
            
            val (chunkDuration, width, height, hasAudio) = try {
                retriever.setDataSource(context.androidContext, chunkUri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                Tuple4(duration, w, h, if (audio) 1L else 0L)
            } finally {
                retriever.release()
            }

            context.log.info("Sending chunk $index: ${chunkDuration}ms, ${width}x${height}")

            // Build new protobuf with updated video chunk
            val newProtoContent = ProtoEditor(originalProtoContent).apply {
                // Update the video URI
                edit(3, 3, 5, 1, 1) {
                    remove(2)
                    addString(2, chunkUri.toString())
                }
                
                // Update video metadata (width, height, duration)
                edit(3, 3, 5, 1, 1) {
                    remove(15) // duration
                    addVarInt(15, chunkDuration)
                    remove(11) // width  
                    addVarInt(11, width.toLong())
                    remove(12) // height
                    addVarInt(12, height.toLong())
                }
                
                // Update has audio flag
                edit(3, 3, 5, 2) {
                    remove(5)
                    addVarInt(5, hasAudio)
                }
            }.toByteArray()

            // Create message content JSON template
            val localMessageContentTemplate = """
            {
                "mAllowsTranscription": false,
                "mBotMention": false,
                "mContent": [${newProtoContent.joinToString(",")}],
                "mContentType": "EXTERNAL_MEDIA",
                "mIncidentalAttachments": [],
                "mLocalMediaReferences": [],
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

            // Send the message
            val sendMessageWithContentMethod = context.classCache.conversationManager.declaredMethods.first { it.name == "sendMessageWithContent" }
            val localMessageContent = context.gson.fromJson(localMessageContentTemplate, context.classCache.localMessageContent)
            val messageDestinations = MessageDestinations(AbstractWrapper.newEmptyInstance(context.classCache.messageDestinations)).also {
                it.conversations = conversations.toCollection(ArrayList())
                it.mPhoneNumbers = arrayListOf<Any>()
                it.stories = arrayListOf<Any>()
            }

            val callback = CallbackBuilder(sendMessageCallback)
                .override("onSuccess", callback = {
                    context.log.info("Chunk $index sent successfully")
                })
                .override("onError", callback = { 
                    context.log.error("Failed to send chunk $index: ${it.arg<Any>(0)}")
                })
                .build()

            sendMessageWithContentMethod.invoke(
                context.feature(Messaging::class).conversationManager?.instanceNonNull(),
                messageDestinations.instanceNonNull(),
                localMessageContent,
                callback
            )
            
        } catch (e: Exception) {
            context.log.error("Failed to send chunk $index", e)
        }
    }

    private data class Tuple4<A, B, C, D>(
        val first: A, 
        val second: B, 
        val third: C, 
        val fourth: D
    )
}
