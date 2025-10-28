package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.wrapper.impl.MessageContent
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config")
            return
        }

        context.log.verbose("GalleryVideoSplitting: Initializing...")

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, ignoring")
                return@subscribe
            }

            try {
                val destinations = event.destinations
                val messageContent = event.messageContent

                // Only process DMs, not stories
                val hasStories = destinations.stories?.isNotEmpty() == true
                val hasConversations = destinations.conversations?.isNotEmpty() == true
                
                context.log.verbose("GalleryVideoSplitting: Event received! hasStories=$hasStories, hasConversations=$hasConversations")

                if (hasStories && !hasConversations) {
                    context.log.verbose("GalleryVideoSplitting: Story only, skipping")
                    return@subscribe
                }

                val contentType = messageContent.contentType
                context.log.verbose("GalleryVideoSplitting: Content type = $contentType")

                // Only process EXTERNAL_MEDIA
                if (contentType.name != "EXTERNAL_MEDIA") {
                    context.log.verbose("GalleryVideoSplitting: Not EXTERNAL_MEDIA, skipping")
                    return@subscribe
                }

                val content = messageContent.content
                if (content == null || content.isEmpty()) {
                    context.log.verbose("GalleryVideoSplitting: Content is null or empty")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Parsing message proto (${content.size} bytes)")
                val messageProtoReader = ProtoReader(content)
                context.log.verbose("GalleryVideoSplitting: Full message proto structure:\n${messageProtoReader}")

                // Check media count
                val mediaCount = messageProtoReader.followPath(3, 3)?.getCount(3) ?: 0
                context.log.verbose("GalleryVideoSplitting: Media count = $mediaCount")

                if (mediaCount != 1) {
                    context.log.verbose("GalleryVideoSplitting: Multiple media items, skipping")
                    return@subscribe
                }

                // Get the snapDocPlayback structure
                val snapDocPlayback = messageProtoReader.followPath(3, 3, 5)
                if (snapDocPlayback == null) {
                    context.log.verbose("GalleryVideoSplitting: No snapDocPlayback found")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: snapDocPlayback proto structure:\n${snapDocPlayback}")

                // Try to get video duration from multiple possible locations
                var videoDuration: Long? = null
                
                // Location 1: 1,1,15 (most common for videos with duration set)
                videoDuration = snapDocPlayback.getVarInt(1, 1, 15)
                context.log.verbose("GalleryVideoSplitting: Duration at 1,1,15 = $videoDuration")

                // Location 2: Try getting from 2,8 (alternative location)
                if (videoDuration == null) {
                    val altDuration = snapDocPlayback.getVarInt(2, 8)
                    if (altDuration != null && altDuration > 0) {
                        videoDuration = altDuration * 1000 // Convert seconds to ms
                        context.log.verbose("GalleryVideoSplitting: Duration at 2,8 = ${altDuration}s (${videoDuration}ms)")
                    }
                }

                // Check if it's actually a video by looking at media type (2 field: 0=video, 1=photo)
                val mediaType = snapDocPlayback.getVarInt(1, 1, 2)
                context.log.verbose("GalleryVideoSplitting: Media type at 1,1,2 = $mediaType (0=video, 1=photo)")

                if (mediaType != 0L) {
                    context.log.verbose("GalleryVideoSplitting: Not a video (mediaType=$mediaType), skipping")
                    return@subscribe
                }

                // If we still don't have duration, try to extract from the actual media reference
                if (videoDuration == null || videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: No duration in proto, trying to extract from local media reference")
                    
                    val localMediaReferences = messageContent.instanceNonNull().getObjectField("mLocalMediaReferences") as? List<*>
                    if (localMediaReferences != null && localMediaReferences.isNotEmpty()) {
                        val firstRef = localMediaReferences.first()
                        val mediaId = firstRef?.getObjectField("mId") as? ByteArray
                        
                        if (mediaId != null) {
                            val mediaIdString = String(mediaId)
                            context.log.verbose("GalleryVideoSplitting: Found local media reference: $mediaIdString")
                            
                            // Try to parse as URI and get duration
                            try {
                                val uri = Uri.parse(mediaIdString)
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(context.androidContext, uri)
                                    val extractedDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                    if (extractedDuration != null && extractedDuration > 0) {
                                        videoDuration = extractedDuration
                                        context.log.verbose("GalleryVideoSplitting: Extracted duration from file = ${videoDuration}ms")
                                    }
                                } finally {
                                    retriever.release()
                                }
                            } catch (e: Exception) {
                                context.log.error("GalleryVideoSplitting: Failed to extract duration from media reference", e)
                            }
                        }
                    }
                }

                if (videoDuration == null || videoDuration <= 0) {
                    context.log.verbose("GalleryVideoSplitting: Could not determine video duration, skipping")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Final video duration = ${videoDuration}ms")

                // Only split if video is longer than 10 seconds
                if (videoDuration <= 10000) {
                    context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (<= 10s), no split needed")
                    return@subscribe
                }

                context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), will split and cancel original send")
                event.canceled = true

                context.coroutineScope.launch {
                    isSplitting = true
                    performVideoSplit(messageContent, destinations.conversations?.toList() ?: emptyList())
                }

            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Error in event handler", e)
            }
        }

        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private suspend fun performVideoSplit(messageContent: MessageContent, conversationIds: List<Any>) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Processing video for splitting...")
            }

            // Extract the video URI from local media references
            val localMediaReferences = messageContent.instanceNonNull().getObjectField("mLocalMediaReferences") as? List<*>
            if (localMediaReferences == null || localMediaReferences.isEmpty()) {
                throw IllegalStateException("No local media references found")
            }

            val firstRef = localMediaReferences.first()
            val mediaId = firstRef?.getObjectField("mId") as? ByteArray
                ?: throw IllegalStateException("Media ID not found")

            val mediaUriString = String(mediaId)
            val mediaUri = Uri.parse(mediaUriString)
            context.log.verbose("GalleryVideoSplitting: Extracting video from URI: $mediaUriString")

            val cachedVideo = File(tempDir, "input.mp4")
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open input stream for media URI")

            context.log.verbose("GalleryVideoSplitting: Video cached to: ${cachedVideo.absolutePath}")

            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
            context.log.verbose("GalleryVideoSplitting: Running FFmpeg: $command")

            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.failStackTrace}")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name }
                ?: throw IllegalStateException("FFmpeg produced no output files")

            context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} chunks")

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} video parts...")
            }

            // Find the ChatMediaDrawerActionHandler to send items
            val actionHandlerClass = findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
            val sendItemsMethod = actionHandlerClass.methods.first { it.name == "sendItems" }

            // We need to get an instance of the action handler - this is tricky
            // For now, let's use the message sender approach instead
            context.log.warn("GalleryVideoSplitting: Sending split videos via action handler not implemented yet")
            context.log.warn("GalleryVideoSplitting: Split files ready at: ${tempDir.absolutePath}")
            
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Video split into ${outputFiles.size} parts but auto-send not ready yet"
                )
            }

        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed: ${e.message}")
            }
        } finally {
            // Don't delete yet for debugging
            // tempDir.deleteRecursively()
            isSplitting = false
        }
    }
}
