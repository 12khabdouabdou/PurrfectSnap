package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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
            if (isSplitting) return@subscribe
            
            // Skip if sending to stories only
            if (event.destinations.stories?.isNotEmpty() == true && 
                event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }

            val localMessageContent = event.messageContent
            
            // Only process EXTERNAL_MEDIA (gallery videos)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                return@subscribe
            }

            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            
            // Skip story replies
            if (messageProtoReader.contains(7)) return@subscribe

            // Check if it's a video by looking at the media type
            val mediaType = messageProtoReader.getVarInt(3, 3, 5, 2, 5)
            val hasVideo = mediaType == 1L // 1 = video with sound or video only

            if (!hasVideo) return@subscribe

            // Get video duration to check if splitting is needed
            val videoDurationMs = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: return@subscribe
            
            // Only split if longer than 10 seconds
            if (videoDurationMs <= 10000) return@subscribe

            // Cancel the original send
            event.canceled = true

            context.coroutineScope.launch {
                isSplitting = true
                try {
                    splitAndSendVideo(event, messageProtoReader, videoDurationMs)
                } catch (e: Exception) {
                    context.log.error("Failed to split and send video", e)
                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(
                            Icons.Default.Info,
                            "Failed to split video: ${e.message}"
                        )
                    }
                } finally {
                    isSplitting = false
                }
            }
        }
    }

    private suspend fun splitAndSendVideo(
        event: SendMessageWithContentEvent,
        messageProtoReader: ProtoReader,
        videoDurationMs: Long
    ) {
        val tempDir = File(
            context.mainActivity!!.cacheDir,
            "split_video_${System.currentTimeMillis()}"
        ).apply { mkdirs() }

        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info,
                    "Splitting video..."
                )
            }

            // Get the media reference to access the actual file
            val localMediaReferences = event.messageContent.instanceNonNull()
                .getObjectField("mLocalMediaReferences") as? List<*>
                ?: throw IllegalStateException("No local media references found")

            if (localMediaReferences.isEmpty()) {
                throw IllegalStateException("Empty media references")
            }

            val mediaReference = localMediaReferences.first() ?: throw IllegalStateException("Null media reference")
            val mediaId = mediaReference.getObjectField("mId") as? ByteArray
                ?: throw IllegalStateException("No media ID found")

            // Use the media manager to get the actual file URI
            val mediaPath = getMediaPath(mediaId)
                ?: throw IllegalStateException("Could not resolve media path")

            val cachedVideo = File(tempDir, "input.mp4")
            
            withContext(Dispatchers.IO) {
                File(mediaPath).copyTo(cachedVideo, overwrite = true)
            }

            // Split video using FFmpeg
            val outputPattern = File(tempDir, "split_%03d.mp4").absolutePath
            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"$outputPattern\""

            val session = withContext(Dispatchers.IO) {
                FFmpegKit.execute(command)
            }

            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed: ${session.failStackTrace}")
            }

            val outputFiles = tempDir.listFiles()
                ?.filter { it.name.startsWith("split_") && it.extension == "mp4" }
                ?.sortedBy { it.name }
                ?: emptyList()

            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No output files created")
            }

            context.log.verbose("Split video into ${outputFiles.size} chunks")

            // Send each chunk
            for ((index, file) in outputFiles.withIndex()) {
                sendVideoChunk(
                    file = file,
                    destinations = event.destinations,
                    originalContent = event.messageContent.content!!,
                    messageProtoReader = messageProtoReader,
                    index = index,
                    totalChunks = outputFiles.size
                )

                if (index < outputFiles.size - 1) {
                    delay(800)
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info,
                    "Sent ${outputFiles.size} video chunks"
                )
            }

        } finally {
            withContext(Dispatchers.IO) {
                tempDir.deleteRecursively()
            }
        }
    }

    private suspend fun sendVideoChunk(
        file: File,
        destinations: MessageDestinations,
        originalContent: ByteArray,
        messageProtoReader: ProtoReader,
        index: Int,
        totalChunks: Int
    ) {
        val retriever = MediaMetadataRetriever()

        try {
            withContext(Dispatchers.IO) {
                retriever.setDataSource(file.absolutePath)
            }

            val chunkDuration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            // Copy the file to a temporary location accessible by Snapchat
            val mediaUri = Uri.fromFile(file)
            
            // Create new message content with the chunk
            val newContent = ProtoEditor(originalContent).apply {
                edit(3, 3, 5, 1, 1) {
                    remove(15)
                    addVarInt(15, chunkDuration)
                }
            }.toByteArray()

            // Create a new message with the updated content
            val localMessageContent = context.gson.fromJson(
                createMessageContentJson(newContent, mediaUri),
                context.classCache.localMessageContent
            )

            // Send the message
            val sendMessageWithContentMethod = context.classCache.conversationManager
                .declaredMethods.first { it.name == "sendMessageWithContent" }

            withContext(Dispatchers.Main) {
                sendMessageWithContentMethod.invoke(
                    context.feature(Messaging::class).conversationManager?.instanceNonNull(),
                    destinations.instanceNonNull(),
                    localMessageContent,
                    createSendCallback(index + 1, totalChunks)
                )
            }

        } finally {
            retriever.release()
        }
    }

    private fun getMediaPath(mediaId: ByteArray): String? {
        // Try to resolve the media path from Snapchat's media cache
        return runCatching {
            val cacheDir = context.androidContext.cacheDir
            val mediaDir = File(cacheDir, "media")
            
            // Convert byte array to hex string for filename
            val hexId = mediaId.joinToString("") { "%02x".format(it) }
            
            // Look for the file in common cache locations
            val possiblePaths = listOf(
                File(mediaDir, hexId),
                File(mediaDir, "$hexId.mp4"),
                File(cacheDir, "tmp/$hexId"),
                File(cacheDir, "tmp/$hexId.mp4")
            )
            
            possiblePaths.firstOrNull { it.exists() }?.absolutePath
        }.getOrNull()
    }

    private fun createMessageContentJson(content: ByteArray, mediaUri: Uri): String {
        return """
        {
            "mAllowsTranscription": false,
            "mBotMention": false,
            "mContent": [${content.joinToString(",")}],
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
    }

    private fun createSendCallback(chunkNumber: Int, totalChunks: Int): Any {
        lateinit var callbackClass: Class<*>
        
        context.mappings.useMapper(CallbackMapper::class) {
            callbackClass = callbacks.getClass("SendMessageCallback") ?: return@useMapper
        }

        return CallbackBuilder(callbackClass)
            .override("onSuccess") {
                context.log.verbose("Sent chunk $chunkNumber/$totalChunks successfully")
            }
            .override("onError") { param ->
                context.log.error("Failed to send chunk $chunkNumber/$totalChunks: ${param.arg<Any>(0)}")
            }
            .build()
    }
}
