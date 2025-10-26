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
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        context.log.info("Gallery Video Splitting feature initialized")

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            // Prevent recursive splitting
            if (isSplitting) return@subscribe
            
            // Skip stories (only handle direct messages)
            if (event.destinations.stories?.isNotEmpty() == true && 
                event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }

            val localMessageContent = event.messageContent
            
            // Only handle external media (gallery videos)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                return@subscribe
            }

            // Parse the protobuf message
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            
            // Check if it's a video (field 3.3.5.2.5 == 1 means VIDEO type)
            val isVideo = messageProtoReader.getVarInt(3, 3, 5, 2, 5) == 1L
            if (!isVideo) return@subscribe

            // Extract video URI from protobuf
            val contentUriBytes = messageProtoReader.getByteArray(3, 3, 5, 1, 1, 2) ?: return@subscribe
            val contentUri = String(contentUriBytes)
            val videoUri = Uri.parse(contentUri)
            
            context.log.info("Found gallery video: $contentUri")
            
            // Check video duration
            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(context.androidContext, videoUri)
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
                context.log.info("Video is under 10 seconds, not splitting")
                return@subscribe
            }

            // Cancel the original send
            event.canceled = true
            context.log.info("Splitting video into 10-second chunks")
            
            // Start splitting in background
            context.coroutineScope.launch {
                splitAndSendVideo(event, videoUri, durationMs)
            }
        }
    }

    private suspend fun splitAndSendVideo(
        originalEvent: SendMessageWithContentEvent,
        videoUri: Uri,
        durationMs: Long
    ) {
        isSplitting = true
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}")
            .apply { mkdirs() }
        
        try {
            // Show progress toast
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Splitting ${durationMs / 1000}s video..."
                )
            }

            // Copy video to cache (FFmpeg needs file path)
            val inputVideo = File(tempDir, "input.mp4")
            context.mainActivity!!.contentResolver.openInputStream(videoUri)?.use { input ->
                inputVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Failed to open video stream")

            context.log.info("Video copied to ${inputVideo.absolutePath}")

            // Split video using FFmpeg
            // -c copy: don't re-encode (fast, preserves quality)
            // -f segment: segment output format
            // -segment_time 10: 10 second segments
            // -reset_timestamps 1: reset timestamps for each chunk
            val command = "-i ${inputVideo.absolutePath} -c copy -f segment -segment_time 10 " +
                    "-reset_timestamps 1 -avoid_negative_ts make_zero ${tempDir.absolutePath}/chunk_%03d.mp4"
            
            context.log.info("Running FFmpeg: $command")
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                context.log.error("FFmpeg output: ${session.output}")
                throw Exception("FFmpeg failed with code ${session.returnCode}")
            }

            // Get all chunk files
            val chunks = tempDir.listFiles()
                ?.filter { it.name.startsWith("chunk_") }
                ?.sortedBy { it.name }
                ?: throw Exception("No video chunks created")
            
            context.log.info("Created ${chunks.size} video chunks")

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Sending ${chunks.size} snaps..."
                )
            }

            // Send each chunk sequentially
            for ((index, chunkFile) in chunks.withIndex()) {
                sendChunk(originalEvent, chunkFile, index)
                // Delay between sends to avoid rate limiting
                delay(800)
            }
            
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Successfully sent ${chunks.size} snaps!"
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
            // Clean up temp files
            tempDir.deleteRecursively()
            isSplitting = false
        }
    }

    private fun sendChunk(
        originalEvent: SendMessageWithContentEvent,
        chunkFile: File,
        index: Int
    ) {
        try {
            val chunkUri = Uri.fromFile(chunkFile)
            
            // Read chunk metadata
            val retriever = MediaMetadataRetriever()
            val (duration, width, height, hasAudio) = try {
                retriever.setDataSource(context.androidContext, chunkUri)
                val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                val a = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                ChunkMetadata(d, w, h, a)
            } finally {
                retriever.release()
            }

            context.log.info("Sending chunk $index: ${duration}ms, ${width}x${height}, audio=$hasAudio")

            // Get original protobuf content
            val originalProto = originalEvent.messageContent.content!!
            
            // Update protobuf with chunk-specific data
            val updatedProto = ProtoEditor(originalProto).apply {
                // Update video metadata in field 3.3.5.1.1
                edit(3, 3, 5, 1, 1) {
                    // Update URI
                    remove(2)
                    addString(2, chunkUri.toString())
                    // Update duration
                    remove(15)
                    addVarInt(15, duration)
                    // Update width
                    remove(11)
                    addVarInt(11, width.toLong())
                    // Update height
                    remove(12)
                    addVarInt(12, height.toLong())
                }
                // Update audio flag in field 3.3.5.2
                edit(3, 3, 5, 2) {
                    remove(5)
                    addVarInt(5, if (hasAudio) 1L else 0L)
                }
            }.toByteArray()

            // Update the message content with chunk data
            originalEvent.messageContent.content = updatedProto
            
            // Send this chunk using the original event
            originalEvent.invokeOriginal()
            
            context.log.info("Chunk $index sent successfully")
            
        } catch (e: Exception) {
            context.log.error("Failed to send chunk $index", e)
            // Continue with remaining chunks even if one fails
        }
    }

    private data class ChunkMetadata(
        val duration: Long,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean
    )
}
