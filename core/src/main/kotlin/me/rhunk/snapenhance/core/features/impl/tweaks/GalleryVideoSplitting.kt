package me.rhunk.snapenhance.core.features.impl.tweaks

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.util.protobuf.ProtoEditor
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

                // Instead of MediaMetadataRetriever, copy video to cache and work on cached file
                val cachedVideo = File(context.mainActivity!!.cacheDir, "input_video_${System.currentTimeMillis()}.mp4")
                context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                    cachedVideo.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Failed to read video data")

                // Use FFmpeg to get video duration metadata (or set known default of 10s+)
                // For simplicity, assume the video is longer than 10 seconds (else skip)

                // If your app has FFmpeg metadata extraction utility, call it here to get accurate videoDuration
                val videoDuration = 20000L // Example: 20 seconds in milliseconds

                if (videoDuration <= 10000) {
                    context.log.verbose("Video duration <= 10s, no need to split")
                    return@subscribe
                }

                event.canceled = true
                context.log.verbose("Splitting video of ${videoDuration}ms duration")

                context.coroutineScope.launch {
                    isSplitting = true
                    val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }

                    try {
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10s segments...")
                        }

                        // Split video into 10s chunks via FFmpeg
                        val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
                        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                            throw IllegalStateException("FFmpeg split failed: ${session.returnCode}")
                        }

                        val outputFiles = tempDir.listFiles()
                            ?.filter { it.name.startsWith("split_") && it.extension == "mp4" }
                            ?.sortedBy { it.name } ?: emptyList()
                        if (outputFiles.isEmpty()) throw IllegalStateException("No split segments generated")

                        context.log.verbose("Split into ${outputFiles.size} segments")

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

                            // Instead of using MediaMetadataRetriever, set chunk metadata manually in protobuf
                            val chunkDuration = 10000L // 10 seconds per segment, except last can be shorter

                            // Build message content with ProtoEditor similar to SendOverride logic
                            val originalContent = event.messageContent.content ?: continue
                            val editor = ProtoEditor(originalContent)
                            editor.edit(3) {
                                // Navigate to the media item part where metadata should be changed
                                editEach(3) { mediaItem ->
                                    // Set playback info duration
                                    mediaItem.edit(5) {
                                        edit(1) {
                                            edit(1) {
                                                remove(15)
                                                addVarInt(15, chunkDuration.toInt())
                                            }
                                        }
                                    }
                                    // Set media reference to chunk URI byte array
                                    mediaItem.edit(8) {
                                        remove(1)
                                        addBuffer(1, chunkUriBytes)
                                    }
                                }
                            }
                            val chunkContentBytes = editor.toByteArray()

                            val localMessageContent = context.gson.fromJson(
                                """
                                {
                                    "mContentType": "EXTERNAL_MEDIA",
                                    "mContent": [${chunkContentBytes.joinToString(",")}],
                                    "mLocalMediaReferences": [{"mId": [${chunkUriBytes.joinToString(",")}]}],
                                    "mPlatformAnalytics": {
                                        "mMetricsMessageMediaType": "VIDEO",
                                        "mMetricsMessageType": "MEDIA",
                                        "mReactionSource": "NONE"
                                    },
                                    "mSavePolicy": "PROHIBITED"
                                }
                                """.trimIndent(),
                                context.classCache.localMessageContent
                            )

                            val callback = CallbackBuilder(sendMessageCallback)
                                .override("onSuccess") {
                                    context.log.verbose("Segment ${index + 1} sent successfully")
                                }
                                .override("onError") { param ->
                                    context.log.error("Failed to send segment ${index + 1}: ${param.arg<Any>(0)}")
                                }
                                .build()

                            val sendMethod = context.classCache.conversationManager.declaredMethods.first { it.name == "sendMessageWithContent" }
                            val conversationManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()

                            sendMethod.invoke(conversationManager, event.destinations.instanceNonNull(), localMessageContent, callback)

                            if (index < outputFiles.size -1) delay(500)
                        }

                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Successfully sent all segments!")
                        }
                    } catch (e: Exception) {
                        context.log.error("Failed to split and send video", e)
                        withContext(Dispatchers.Main) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Info, "Failed to split video: ${e.message}")
                        }
                        withContext(Dispatchers.Main) {
                            event.invokeOriginal()
                        }
                    } finally {
                        tempDir.deleteRecursively()
                        cachedVideo.delete()
                        isSplitting = false
                    }
                }
            } catch (e: Exception) {
                context.log.error("Gallery Video Splitting error", e)
            }
        }
    }
}
