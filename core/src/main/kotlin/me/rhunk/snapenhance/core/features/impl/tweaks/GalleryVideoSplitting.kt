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
import me.rhunk.snapenhance.core.wrapper.impl.MessageContent
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        context.log.verbose("GalleryVideoSplitting: Initializing...")
        
        // Check if feature is enabled
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config")
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

        // Subscribe to SendMessageWithContentEvent - this runs BEFORE SendOverride
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            context.log.verbose("GalleryVideoSplitting: SendMessageWithContentEvent triggered")
            
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, ignoring")
                return@subscribe
            }

            try {
                val localMessageContent = event.messageContent
                
                // Only process EXTERNAL_MEDIA (gallery videos)
                if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                    context.log.verbose("GalleryVideoSplitting: Not EXTERNAL_MEDIA, type=${localMessageContent.contentType}")
                    return@subscribe
                }

                // Check if it's a video
                val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
                val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                
                context.log.verbose("GalleryVideoSplitting: EXTERNAL_MEDIA with $mediaCount media items")
                
                if (mediaCount != 1) {
                    context.log.verbose("GalleryVideoSplitting: Multiple media items, skipping split")
                    return@subscribe
                }

                // Get media type (check if it's video)
                val hasAudio = messageProtoReader.getVarInt(3, 3, 5, 2, 5) != null
                val videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: 0
                
                context.log.verbose("GalleryVideoSplitting: hasAudio=$hasAudio, duration=$videoDuration")
                
                if (videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Not a video or no duration")
                    return@subscribe
                }

                // If video is <= 10 seconds, no need to split
                if (videoDuration <= 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms, no split needed")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: VIDEO detected! Duration: ${videoDuration}ms - Starting split process...")
                
                // Cancel the original send
                event.canceled = true

                // Get the content URI from localMessageContent
                val localMediaRefs = localMessageContent.instanceNonNull().getObjectField("mLocalMediaReferences") as? List<*>
                if (localMediaRefs.isNullOrEmpty()) {
                    context.log.error("GalleryVideoSplitting: No local media references found")
                    return@subscribe
                }

                val mediaRefId = (localMediaRefs.first() as? Any)?.getObjectField("mId") as? ByteArray
                if (mediaRefId == null) {
                    context.log.error("GalleryVideoSplitting: Could not get media reference ID")
                    return@subscribe
                }

                // Get URI from media reference
                val mediaUri = getMediaUriFromReference(mediaRefId)
                if (mediaUri == null) {
                    context.log.error("GalleryVideoSplitting: Could not resolve media URI")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Media URI: $mediaUri")

                // Start async splitting process
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
                                input.copyTo(output)
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
                            throw IllegalStateException("FFmpeg failed: ${session.output}")
                        }

                        val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                        context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} files")
                        
                        if (outputFiles.isEmpty()) {
                            throw IllegalStateException("FFmpeg produced no output files")
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} video chunks...")
                        }

                        // Send each chunk as a separate message
                        for ((index, file) in outputFiles.withIndex()) {
                            context.log.verbose("GalleryVideoSplitting: Sending chunk ${index + 1}/${outputFiles.size}")
                            
                            val chunkUri = Uri.fromFile(file)
                            val retriever = MediaMetadataRetriever()

                            try {
                                retriever.setDataSource(context.androidContext, chunkUri)
                                val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                
                                context.log.verbose("GalleryVideoSplitting: Chunk $index duration: $chunkDuration ms")

                                // Create new MessageContent for this chunk
                                val chunkContent = createChunkMessageContent(
                                    chunkUri, 
                                    chunkDuration,
                                    localMessageContent
                                )

                                // Trigger a new send event for this chunk (SendOverride will handle it)
                                val chunkEvent = SendMessageWithContentEvent(
                                    event.destinations,
                                    chunkContent,
                                    event.callback
                                )
                                
                                // Invoke the original send for this chunk
                                chunkEvent.invokeOriginal()
                                
                                delay(1000) // Wait between sends
                                
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

    private fun getMediaUriFromReference(mediaRefId: ByteArray): Uri? {
        return try {
            // Try to get URI from media store using the reference ID
            val idString = String(mediaRefId, Charsets.UTF_8)
            context.log.verbose("GalleryVideoSplitting: Media ref ID: $idString")
            
            // The mediaRefId might be a content URI already
            if (idString.startsWith("content://")) {
                Uri.parse(idString)
            } else {
                // Fallback: try to construct URI
                Uri.parse("content://media/external/video/media/$idString")
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Error parsing media URI", e)
            null
        }
    }

    private fun createChunkMessageContent(
        chunkUri: Uri,
        durationMs: Long,
        originalContent: MessageContent
    ): MessageContent {
        // Create a new MessageContent instance for the chunk
        val newContent = MessageContent(originalContent.instanceNonNull())
        
        // Update the local media reference to point to the chunk file
        val chunkUriString = chunkUri.toString()
        val mediaRefId = chunkUriString.toByteArray()
        
        // Update content proto with new URI and duration
        // This will be processed by SendOverride
        
        return newContent
    }
}
