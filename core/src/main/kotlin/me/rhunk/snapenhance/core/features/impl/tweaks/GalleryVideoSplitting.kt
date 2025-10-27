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
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
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
            
            // Skip if sending to stories only (same as SendOverride)
            if (event.destinations.stories?.isNotEmpty() == true && 
                event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }

            val localMessageContent = event.messageContent
            
            // Only process EXTERNAL_MEDIA (same as SendOverride)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && 
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) {
                return@subscribe
            }

            // Prevent story replies (same as SendOverride)
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            // Check if it's a video
            val mediaType = messageProtoReader.getVarInt(3, 3, 5, 2, 5)
            if (mediaType != 1L) return@subscribe // 1 = video

            // Get video duration
            val videoDurationMs = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: return@subscribe
            
            // Only split if longer than 10 seconds
            if (videoDurationMs <= 10000) return@subscribe

            // Check for multiple media items (same check as SendOverride)
            if ((messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                return@subscribe
            }

            // Cancel the original send (same as SendOverride)
            event.canceled = true

            context.coroutineScope.launch {
                isSplitting = true
                try {
                    if (splitAndSendVideo(event, messageProtoReader, videoDurationMs)) {
                        // Successfully split and sent
                    } else {
                        // Failed, send original
                        withContext(Dispatchers.Main) {
                            event.invokeOriginal()
                        }
                    }
                } catch (e: Exception) {
                    context.log.error("Failed to split video", e)
                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(
                            Icons.Default.Info,
                            "Failed to split video, sending original"
                        )
                        event.invokeOriginal()
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
    ): Boolean {
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

            // Get the actual video file from local media references
            val localMediaReferences = event.messageContent.instanceNonNull()
                .getObjectFieldOrNull("mLocalMediaReferences") as? List<*>
                ?: return false

            if (localMediaReferences.isEmpty()) return false

            val mediaReference = localMediaReferences.first() ?: return false
            val mediaId = mediaReference.getObjectFieldOrNull("mId") as? ByteArray ?: return false

            // Try to resolve the actual file path
            val mediaPath = resolveMediaPath(mediaId) ?: return false

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
                return false
            }

            val outputFiles = tempDir.listFiles()
                ?.filter { it.name.startsWith("split_") && it.extension == "mp4" }
                ?.sortedBy { it.name }
                ?: return false

            if (outputFiles.isEmpty()) return false

            context.log.verbose("Split video into ${outputFiles.size} chunks")

            // Store original content
            val originalContent = event.messageContent.content!!

            // Send each chunk by modifying the event and calling invokeOriginal
            for ((index, file) in outputFiles.withIndex()) {
                // Get chunk duration
                val chunkDuration = withContext(Dispatchers.IO) {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(file.absolutePath)
                        retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                    }
                }

                // Update the message content duration to match the chunk
                event.messageContent.content = ProtoEditor(originalContent).apply {
                    edit(3, 3, 5, 1, 1) {
                        remove(15)
                        addVarInt(15, chunkDuration)
                    }
                }.toByteArray()

                // Update the local media reference to point to the chunk file
                // This is tricky - we need to update the media reference
                val chunkUri = Uri.fromFile(file)
                
                // Create new media reference ID for this chunk
                val chunkMediaId = "chunk_${index}_${System.currentTimeMillis()}".toByteArray()
                
                // Try to update the media reference
                runCatching {
                    val mediaRef = localMediaReferences.first()
                    mediaRef?.javaClass?.getDeclaredField("mId")?.let { field ->
                        field.isAccessible = true
                        field.set(mediaRef, chunkMediaId)
                    }
                }

                // Send this chunk
                withContext(Dispatchers.Main) {
                    event.invokeOriginal()
                }

                context.log.verbose("Sent chunk ${index + 1}/${outputFiles.size}")

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

            return true

        } finally {
            withContext(Dispatchers.IO) {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun resolveMediaPath(mediaId: ByteArray): String? {
        return runCatching {
            val hexId = mediaId.joinToString("") { "%02x".format(it) }
            
            val baseDirs = listOf(
                context.androidContext.cacheDir,
                context.androidContext.filesDir,
                context.mainActivity?.cacheDir,
                context.mainActivity?.filesDir
            ).filterNotNull()

            val possibleSubPaths = listOf(
                "media",
                "tmp", 
                "media_cache",
                "external_media",
                ""
            )

            val possibleExtensions = listOf("", ".mp4", ".mov")

            for (baseDir in baseDirs) {
                for (subPath in possibleSubPaths) {
                    for (ext in possibleExtensions) {
                        val dir = if (subPath.isEmpty()) baseDir else File(baseDir, subPath)
                        val file = File(dir, "$hexId$ext")
                        if (file.exists() && file.isFile) {
                            return@runCatching file.absolutePath
                        }
                    }
                }
            }
            
            null
        }.getOrNull()
    }
}
