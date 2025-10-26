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

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe
            
            if (event.destinations.stories?.isNotEmpty() == true && 
                event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }

            val localMessageContent = event.messageContent
            
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                return@subscribe
            }

            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            
            val isVideo = messageProtoReader.getVarInt(3, 3, 5, 2, 5) == 1L
            if (!isVideo) return@subscribe

            val contentUriBytes = messageProtoReader.getByteArray(3, 3, 5, 1, 1, 2) ?: return@subscribe
            val contentUri = String(contentUriBytes)
            val videoUri = Uri.parse(contentUri)
            
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

            if (durationMs <= 10000) {
                return@subscribe
            }

            event.canceled = true
            
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
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Splitting ${durationMs / 1000}s video..."
                )
            }

            val inputVideo = File(tempDir, "input.mp4")
            context.mainActivity!!.contentResolver.openInputStream(videoUri)?.use { input ->
                inputVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Failed to open video stream")

            val command = "-i ${inputVideo.absolutePath} -c copy -f segment -segment_time 10 " +
                    "-reset_timestamps 1 -avoid_negative_ts make_zero ${tempDir.absolutePath}/chunk_%03d.mp4"
            
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw Exception("FFmpeg failed: ${session.output}")
            }

            val chunks = tempDir.listFiles()
                ?.filter { it.name.startsWith("chunk_") }
                ?.sortedBy { it.name }
                ?: throw Exception("No chunks created")

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Sending ${chunks.size} snaps..."
                )
            }

            for ((index, chunkFile) in chunks.withIndex()) {
                sendChunk(originalEvent, chunkFile, index)
                delay(800)
            }
            
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Sent ${chunks.size} snaps!"
                )
            }
            
        } catch (e: Exception) {
            context.log.error("Split failed", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Failed: ${e.message}"
                )
            }
        } finally {
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

            val originalProto = originalEvent.messageContent.content!!
            
            val updatedProto = ProtoEditor(originalProto).apply {
                edit(3, 3, 5, 1, 1) {
                    remove(2)
                    addString(2, chunkUri.toString())
                    remove(15)
                    addVarInt(15, duration)
                    remove(11)
                    addVarInt(11, width.toLong())
                    remove(12)
                    addVarInt(12, height.toLong())
                }
                edit(3, 3, 5, 2) {
                    remove(5)
                    addVarInt(5, if (hasAudio) 1L else 0L)
                }
            }.toByteArray()

            originalEvent.messageContent.content = updatedProto
            originalEvent.invokeOriginal()
            
        } catch (e: Exception) {
            context.log.error("Failed to send chunk $index", e)
        }
    }

    private data class ChunkMetadata(
        val duration: Long,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean
    )
}
