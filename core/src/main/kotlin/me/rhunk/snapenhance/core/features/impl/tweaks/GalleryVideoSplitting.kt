package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.util.protobuf.ProtoEditor
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.util.ktx.setObjectField
import java.io.File

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false

    override fun init() {
        context.log.verbose("GalleryVideoSplitting: Initializing feature")

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, skipping event")
                return@subscribe
            }

            if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
                return@subscribe
            }

            val localMessageContent = event.messageContent
            context.log.verbose("GalleryVideoSplitting: Event triggered, contentType=${localMessageContent.contentType}")

            // Only process EXTERNAL_MEDIA (gallery videos)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA) {
                return@subscribe
            }

            // Check if it's a video by reading the protobuf
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            
            val hasSound = messageProtoReader.getVarInt(3, 3, 5, 2, 5)
            context.log.verbose("GalleryVideoSplitting: hasSound = $hasSound")
            
            if (hasSound == null || hasSound == 0L) {
                context.log.verbose("GalleryVideoSplitting: Not a video (hasSound=$hasSound)")
                return@subscribe
            }

            // Get the content URI from LocalMediaReferences
            val contentInstance = localMessageContent.instanceNonNull()
            
            val localMediaReferences = contentInstance.getObjectField("mLocalMediaReferences") as? ArrayList<*> ?: run {
                context.log.error("GalleryVideoSplitting: mLocalMediaReferences is null or not ArrayList")
                return@subscribe
            }
            
            if (localMediaReferences.isEmpty()) {
                context.log.error("GalleryVideoSplitting: mLocalMediaReferences is empty")
                return@subscribe
            }
            
            val localMediaRef = localMediaReferences.first() ?: run {
                context.log.error("GalleryVideoSplitting: First media reference is null")
                return@subscribe
            }
            
            context.log.verbose("GalleryVideoSplitting: LocalMediaReference class: ${localMediaRef.javaClass.name}")
            
            // The URI should be in the LocalMediaReference object
            // Let's find it by inspecting all fields
            val mediaRefFields = localMediaRef.javaClass.declaredFields
            var contentUri: Uri? = null
            
            for (field in mediaRefFields) {
                field.isAccessible = true
                try {
                    val value = field.get(localMediaRef)
                    context.log.verbose("GalleryVideoSplitting: MediaRef field ${field.name} = ${value?.javaClass?.simpleName}")
                    
                    if (value is Uri) {
                        contentUri = value
                        context.log.verbose("GalleryVideoSplitting: Found URI in field ${field.name}: $value")
                        break
                    } else if (value?.javaClass?.simpleName == "Uri") {
                        contentUri = value as Uri
                        context.log.verbose("GalleryVideoSplitting: Found URI in field ${field.name}: $value")
                        break
                    }
                } catch (e: Exception) {
                    context.log.verbose("GalleryVideoSplitting: MediaRef field ${field.name} error: ${e.message}")
                }
            }
            
            if (contentUri == null) {
                context.log.error("GalleryVideoSplitting: Could not find URI in LocalMediaReference")
                return@subscribe
            }
            
            val mediaUri = contentUri
            context.log.verbose("GalleryVideoSplitting: Video URI: $mediaUri")
            val retriever = MediaMetadataRetriever()
            val durationMs: Long
            
            try {
                retriever.setDataSource(context.androidContext, mediaUri)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                context.log.verbose("GalleryVideoSplitting: Video duration from file: ${durationMs}ms")
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Failed to read video metadata", e)
                return@subscribe
            } finally {
                retriever.release()
            }

            if (durationMs <= 10000) {
                context.log.verbose("GalleryVideoSplitting: Video duration ${durationMs}ms is ≤10s, no split needed")
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video needs splitting! Duration: ${durationMs}ms")

            // Cancel the original send
            event.canceled = true

            // Start splitting in coroutine
            defer {
                isSplitting = true
                val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }

                try {
                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10s snaps...")
                    }

                    val cachedVideo = File(tempDir, "input.mp4")

                    // Copy video to cache
                    context.log.verbose("GalleryVideoSplitting: Copying video to cache")
                    context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                        cachedVideo.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Failed to open input stream")

                    context.log.verbose("GalleryVideoSplitting: Video cached (${cachedVideo.length()} bytes)")

                    // Execute FFmpeg to split video
                    val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                    context.log.verbose("GalleryVideoSplitting: Executing FFmpeg")

                    val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
                    
                    if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                        context.log.error("GalleryVideoSplitting: FFmpeg failed - code: ${session.returnCode}")
                        context.log.error("GalleryVideoSplitting: FFmpeg output: ${session.output}")
                        throw IllegalStateException("FFmpeg failed with code ${session.returnCode}")
                    }

                    val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
                    context.log.verbose("GalleryVideoSplitting: Split into ${outputFiles.size} chunks")

                    if (outputFiles.isEmpty()) {
                        throw IllegalStateException("FFmpeg produced no output files")
                    }

                    // Get the sendMessageWithContent method
                    val sendMessageMethod = context.classCache.conversationManager.declaredMethods
                        .firstOrNull { it.name == "sendMessageWithContent" }
                        ?: throw IllegalStateException("sendMessageWithContent method not found")

                    val conversationManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()
                        ?: throw IllegalStateException("ConversationManager not available")

                    // Send each chunk
                    for ((index, chunkFile) in outputFiles.withIndex()) {
                        context.log.verbose("GalleryVideoSplitting: Processing chunk ${index + 1}/${outputFiles.size}")

                        val chunkUri = Uri.fromFile(chunkFile)
                        val chunkRetriever = MediaMetadataRetriever()

                        try {
                            chunkRetriever.setDataSource(context.androidContext, chunkUri)
                            val chunkDuration = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            val chunkWidth = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                            val chunkHeight = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920

                            context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} - duration: ${chunkDuration}ms, size: ${chunkWidth}x${chunkHeight}")

                            // Create new protobuf content with updated duration and URI
                            val newContent = ProtoEditor(localMessageContent.content!!).apply {
                                edit(3, 3) {
                                    // Update duration
                                    edit(5, 1, 1) {
                                        remove(15)
                                        addVarInt(15, chunkDuration)
                                    }
                                    // Keep has_sound flag
                                    edit(5, 2) {
                                        remove(5)
                                        addVarInt(5, 1)
                                    }
                                }
                            }.toByteArray()

                            // Create new message content instance
                            val chunkMessageContent = context.gson.fromJson(
                                context.gson.toJson(contentInstance),
                                context.classCache.localMessageContent
                            )
                            
                            // Update the content
                            chunkMessageContent.setObjectField("mContent", newContent)
                            
                            // Update the LocalMediaReference with new URI
                            val chunkLocalMediaRef = context.gson.fromJson(
                                context.gson.toJson(localMediaRef),
                                localMediaRef.javaClass
                            )
                            
                            // Set the new URI in the media reference
                            chunkLocalMediaRef.javaClass.declaredFields.forEach { field ->
                                field.isAccessible = true
                                try {
                                    val value = field.get(chunkLocalMediaRef)
                                    if (value is Uri || value?.javaClass?.simpleName == "Uri") {
                                        field.set(chunkLocalMediaRef, chunkUri)
                                        context.log.verbose("GalleryVideoSplitting: Updated URI in field ${field.name}")
                                    }
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            }
                            
                            // Update the mLocalMediaReferences array
                            val newLocalMediaRefs = arrayListOf<Any>(chunkLocalMediaRef)
                            chunkMessageContent.setObjectField("mLocalMediaReferences", newLocalMediaRefs)

                            // Create a callback for this chunk
                            var callbackClass: Class<*>? = null
                            context.mappings.useMapper(me.rhunk.snapenhance.mapper.impl.CallbackMapper::class) {
                                callbackClass = callbacks.getClass("SendMessageCallback")
                            }
                            
                            val chunkCallback = callbackClass?.let { clazz ->
                                me.rhunk.snapenhance.core.util.CallbackBuilder(clazz)
                                    .override("onSuccess", callback = { 
                                        context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} uploaded successfully")
                                    })
                                    .override("onError", callback = { param ->
                                        context.log.error("GalleryVideoSplitting: Chunk ${index + 1} upload failed: ${param.arg<Any>(0)}")
                                    })
                                    .build()
                            }

                            // Send the chunk
                            sendMessageMethod.invoke(
                                conversationManager,
                                event.destinations.instanceNonNull(),
                                chunkMessageContent,
                                chunkCallback
                            )

                            context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} sent")
                            
                            // Delay between sends
                            delay(800)

                        } finally {
                            chunkRetriever.release()
                        }
                    }

                    context.log.verbose("GalleryVideoSplitting: All ${outputFiles.size} chunks sent successfully!")

                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(
                            Icons.Default.Info, 
                            "Video split sent! (${outputFiles.size} parts)"
                        )
                    }

                } catch (e: Exception) {
                    context.log.error("GalleryVideoSplitting: Failed to split video", e)
                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(
                            Icons.Default.Info, 
                            "Failed to split video: ${e.message}"
                        )
                    }
                } finally {
                    // Cleanup
                    runCatching { tempDir.deleteRecursively() }
                    isSplitting = false
                    context.log.verbose("GalleryVideoSplitting: Cleanup complete")
                }
            }
        }

        context.log.verbose("GalleryVideoSplitting: Feature initialized")
    }
}
