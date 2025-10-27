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
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) return

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe

            try {
                val messageContent = event.messageContent
                val contentType = messageContent.contentType
                if (contentType != ContentType.EXTERNAL_MEDIA) return@subscribe

                val localMediaRefs = messageContent.instanceNonNull()
                    .getObjectField("mLocalMediaReferences") as? List<*> ?: return@subscribe
                if (localMediaRefs.isEmpty()) return@subscribe

                val mediaRef = localMediaRefs.firstOrNull() ?: return@subscribe
                val mediaIdBytes = mediaRef.getObjectField("mId") as? ByteArray ?: return@subscribe
                val mediaUriStr = String(mediaIdBytes)
                val mediaUri = Uri.parse(mediaUriStr)

                val retriever = MediaMetadataRetriever()
                var videoDuration: Long = 0L
                var isVideo: Boolean = false

                try {
                    val pfd = context.androidContext.contentResolver.openFileDescriptor(mediaUri, "r")
                        ?: throw IllegalArgumentException("Cannot open file descriptor for URI: $mediaUri")
                    retriever.setDataSource(pfd.fileDescriptor)
                    pfd.close()

                    val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    isVideo = mimeType?.startsWith("video/") == true
                    if (!isVideo) return@subscribe

                    videoDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
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

                event.canceled = true
                context.log.verbose("Splitting video of ${videoDuration}ms")

                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }

                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10s segments...")
                        }

                        val cachedVideo = File(tempDir, "input.mp4")
                        context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                            cachedVideo.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Failed to read video from URI")

                        val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
                        }

                        val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") && it.extension == "mp4" }?.sortedBy { it.name } ?: emptyList()
                        if (outputFiles.isEmpty()) throw IllegalStateException("No output files generated.")

                        context.log.verbose("Split into ${outputFiles.size} segments.")
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} segments...")
                        }

                        val sendMessageCallback by lazy {
                            lateinit var result: Class<*>
                            context.mappings.useMapper(CallbackMapper::class) {
                                result = callbacks.getClass("SendMessageCallback") ?: return@useMapper
                            }
                            result
                        }

                        for ((index, file) in outputFiles.withIndex()) {
                            val chunkUri = Uri.fromFile(file)
                            val chunkUriBytes = chunkUri.toString().toByteArray()

                            val chunkRetriever = MediaMetadataRetriever()
                            var chunkDuration: Long = 10000L
                            var chunkWidth: Int = 1080
                            var chunkHeight: Int = 1920
                            try {
                                val pfdChunk = context.androidContext.contentResolver.openFileDescriptor(chunkUri, "r")
                                    ?: throw IllegalArgumentException("Cannot open file descriptor for chunk URI")
                                chunkRetriever.setDataSource(pfdChunk.fileDescriptor)
                                pfdChunk.close()

                                chunkDuration = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: chunkDuration
                                chunkWidth = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: chunkWidth
                                chunkHeight = chunkRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: chunkHeight
                            } finally {
                                chunkRetriever.release()
                            }

                            val chunkContent = ProtoWriter().apply {
                                from(3) {
                                    from(3) {
                                        from(5) {
                                            from(1) {
                                                from(1) {
                                                    addVarInt(2, 0)
                                                    addVarInt(12, 0)
                                                    addVarInt(15, chunkDuration.toInt())
                                                }
                                                addVarInt(6, 1)
                                            }
                                            from(2) {
                                                addVarInt(5, 1)
                                            }
                                        }
                                        from(7) {
                                            addVarInt(1, chunkWidth)
                                            addVarInt(2, chunkHeight)
                                        }
                                        from(8) {
                                            addBuffer(1, chunkUriBytes)
                                        }
                                    }
                                }
                            }.toByteArray()

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

                            val localMessageContent = context.gson.fromJson(messageContentJson, context.classCache.localMessageContent)

                            val callback = CallbackBuilder(sendMessageCallback)
                                .override("onSuccess") {
                                    context.log.verbose("Segment ${index + 1} sent successfully")
                                }
                                .override("onError") { param ->
                                    context.log.error("Failed to send segment ${index + 1}: ${param.arg<Any>(0)}")
                                }
                                .build()

                            val sendMethod = context.classCache.conversationManager.declaredMethods.first { it.name == "sendMessageWithContent" }
                            val convManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()

                            sendMethod.invoke(convManager, event.destinations.instanceNonNull(), localMessageContent, callback)

                            if (index < outputFiles.size - 1) {
                                delay(500)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Segments sent successfully!")
                        }
                    } catch (e: Exception) {
                        context.log.error("Failed to split/send video: ", e)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Error: ${e.message}")
                        }
                        event.invokeOriginal()
                    } finally {
                        tempDir.deleteRecursively()
                        isSplitting = false
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error in processing", e)
            }
        }
    }
}
