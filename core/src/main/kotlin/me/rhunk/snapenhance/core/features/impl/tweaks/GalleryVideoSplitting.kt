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
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        context.log.info("Initializing Gallery Video Splitting using SendMessageWithContentEvent")

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
            
            // Check if media is video type (field 3.3.5.2.5 should be 1 for video)
            val isVideo = messageProtoReader.getVarInt(3, 3, 5, 2, 5) == 1L
            if (!isVideo) return@subscribe

            // Get the media URI from external metadata
            val externalMetadata = runCatching {
                localMessageContent.instanceNonNull().getObjectField("mExternalContentMetadata")
            }.getOrNull() ?: return@subscribe
            
            val contentUri = runCatching {
                externalMetadata.getObjectField("contentUri")?.toString()
            }.getOrNull() ?: return@subscribe
            
            val mediaUri = Uri.parse(contentUri)
            
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

            context.log.info("Gallery video duration: ${durationMs}ms")

            // Only split videos longer than 10 seconds
            if (durationMs <= 10000) {
                context.log.info("Video is ${durationMs}ms, not splitting")
                return@subscribe
            }

            // Cancel the original send
            event.canceled = true
            context.log.info("Canceling original send to split video")

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
                        sendVideoChunk(event, externalMetadata, file, index)
                        delay(800) // Delay between sends
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

    private suspend fun sendVideoChunk(
        originalEvent: SendMessageWithContentEvent, 
        originalExternalMetadata: Any,
        videoFile: File, 
        index: Int
    ) {
        try {
            val chunkUri = Uri.fromFile(videoFile)
            val retriever = MediaMetadataRetriever()
            
            val (chunkDuration, width, height) = try {
                retriever.setDataSource(context.androidContext, chunkUri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                Triple(duration, w, h)
            } finally {
                retriever.release()
            }

            context.log.info("Sending chunk $index: ${chunkDuration}ms, ${width}x${height}")

            // Clone the external metadata with updated values
            val newExternalMetadata = runCatching {
                val metadataClass = originalExternalMetadata.javaClass
                val constructor = metadataClass.constructors.firstOrNull()
                    ?: throw IllegalStateException("No constructor found")
                
                val instance = constructor.newInstance()
                
                // Copy fields using reflection
                metadataClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    when (field.name) {
                        "contentUri", "mContentUri" -> field.set(instance, chunkUri.toString())
                        "durationMs", "mDurationMs" -> field.set(instance, chunkDuration)
                        "width", "mWidth" -> field.set(instance, width)
                        "height", "mHeight" -> field.set(instance, height)
                        else -> {
                            // Copy other fields as-is
                            runCatching { field.set(instance, field.get(originalExternalMetadata)) }
                        }
                    }
                }
                
                instance
            }.getOrElse { e ->
                context.log.error("Failed to clone metadata", e)
                throw e
            }

            // Create new message content with updated metadata
            val originalContent = originalEvent.messageContent
            val newMessageContent = runCatching {
                val contentClass = originalContent.javaClass
                val instance = contentClass.newInstance()
                
                // Copy all fields from original
                contentClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    if (field.name == "mExternalContentMetadata") {
                        field.set(instance, newExternalMetadata)
                    } else {
                        runCatching { field.set(instance, field.get(originalContent.instanceNonNull())) }
                    }
                }
                
                instance
            }.getOrElse { e ->
                context.log.error("Failed to create new message content", e)
                throw e
            }

            // Wrap in the MessageContent wrapper if needed
            val wrappedContent = originalContent.javaClass.constructors.firstOrNull()
                ?.newInstance(newMessageContent) ?: newMessageContent

            // Create new event with the chunk
            val chunkEvent = SendMessageWithContentEvent(
                destinations = originalEvent.destinations,
                messageContent = wrappedContent as me.rhunk.snapenhance.common.data.MessageContent
            )

            // Send the chunk
            chunkEvent.invokeOriginal()
            
        } catch (e: Exception) {
            context.log.error("Failed to send chunk $index", e)
            // Don't rethrow - try to send remaining chunks
        }
    }
}
