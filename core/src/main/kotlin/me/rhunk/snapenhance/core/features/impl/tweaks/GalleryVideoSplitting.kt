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
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
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
        if (context.config.messaging.splitVideoIntoTenSecondSnaps.get() != true) return

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) return@subscribe

            try {
                val messageContent = event.messageContent
                val contentType = messageContent.contentType
                if (contentType != ContentType.EXTERNAL_MEDIA) return@subscribe

                val localMediaRefs = messageContent.instanceNonNull()?.getObjectField("mLocalMediaReferences") as? List<*>
                if (localMediaRefs.isNullOrEmpty()) return@subscribe

                val mediaRef = localMediaRefs.firstOrNull() ?: return@subscribe
                val mediaIdBytes = mediaRef.getObjectField("mId") as? ByteArray ?: return@subscribe
                val mediaUriStr = String(mediaIdBytes)
                val mediaUri = Uri.parse(mediaUriStr)

                val cachedVideo = File(context.mainActivity?.cacheDir, "input_video_${System.currentTimeMillis()}.mp4")
                cachedVideo?.let { cacheFile ->
                    context.mainActivity?.contentResolver?.openInputStream(mediaUri)?.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: return@subscribe
                } ?: return@subscribe

                val videoDuration = 20000L // Fixed duration or get dynamically as per your logic
                if (videoDuration <= 10000) return@subscribe

                event.canceled = true
                isSplitting = true

                context.coroutineScope.launch {
                    val tempDir = File(context.mainActivity?.cacheDir, "split_video_${System.currentTimeMillis()}")?.apply { mkdirs() }
                    if (tempDir == null) {
                        isSplitting = false
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video into 10s segments...")
                    }

                    val command = "-i \"${cachedVideo?.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
                    val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
                    if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                        isSplitting = false
                        throw IllegalStateException("FFmpeg split failed: ${session.returnCode}")
                    }

                    val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") && it.extension == "mp4" }?.sortedBy { it.name } ?: emptyList()
                    if (outputFiles.isEmpty()) {
                        isSplitting = false
                        throw IllegalStateException("No video segments found")
                    }

                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(Icons.Default.Info, "Sending ${outputFiles.size} video segments...")
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
                        val chunkDuration = if (index == outputFiles.lastIndex) 5000L else 10000L

                        val editor = ProtoEditor(event.messageContent.content ?: byteArrayOf())
                        editor.edit(3) {
                            editEach(3) {
                                edit(5) {
                                    edit(1) {
                                        edit(1) {
                                            remove(15)
                                            addVarInt(15, chunkDuration.toInt())
                                        }
                                        addVarInt(6, 1)
                                    }
                                    edit(2) {
                                        addVarInt(5, 1)
                                    }
                                }
                                edit(7) { /* Video dimensions can be added here */ }
                                edit(8) {
                                    remove(1)
                                    addBuffer(1, chunkUriBytes)
                                }
                            }
                        }
                        val chunkContent = editor.toByteArray()
                        val localMessageContent = context.gson.fromJson(
                            """
                            {
                                "mContentType":"EXTERNAL_MEDIA",
                                "mContent": [${chunkContent.joinToString(",")}],
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
                                context.log.verbose("Sent segment ${index + 1}")
                            }
                            .override("onError") { param ->
                                context.log.error("Failed to send segment ${index + 1}: ${param.arg<Any>(0)}")
                            }
                            .build()

                        val sendMethod = context.classCache.conversationManager.declaredMethods.first { it.name == "sendMessageWithContent" }
                        val convManager = context.feature(Messaging::class).conversationManager?.instanceNonNull()

                        sendMethod.invoke(convManager, event.destinations.instanceNonNull(), localMessageContent, callback)

                        if (index < outputFiles.lastIndex) delay(500)
                    }

                    withContext(Dispatchers.Main) {
                        context.inAppOverlay.showStatusToast(Icons.Default.Info, "Successfully sent all segments")
                    }

                    tempDir.deleteRecursively()
                    cachedVideo?.delete()
                    isSplitting = false
                }
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting error", e)
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Error splitting video: ${e.message}")
                }
                event.invokeOriginal()
                isSplitting = false
            }
        }
    }
}
