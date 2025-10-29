package me.rhunk.snapenhance.core.features.impl.tweaks

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import java.io.File
import java.lang.reflect.Method
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)

    override fun init() {
        context.log.verbose("GalleryVideoSplitting: Initializing...")
        
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

        val actionHandlerClass = findClass("com.snap.memories.composer.ChatMediaDrawerActionHandler")
        val sendItemsMethod: Method = actionHandlerClass.methods.firstOrNull { it.name == "sendItems" }
            ?: run {
                context.log.error("GalleryVideoSplitting: Could not find sendItems method, feature disabled.")
                return
            }

        sendItemsMethod.hook(HookStage.BEFORE) { param ->
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, skipping")
                return@hook
            }

            try {
                val mediaItems = param.arg<List<Any?>>(1)
                if (mediaItems.size != 1) {
                    context.log.verbose("GalleryVideoSplitting: Multiple or no media items (${mediaItems.size}), skipping")
                    return@hook
                }

                val mediaItem = mediaItems.first() ?: return@hook
                val item = mediaItem.getObjectField("item") ?: return@hook
                val itemType = item.getObjectField("type")?.toString()

                context.log.verbose("GalleryVideoSplitting: Media item type: $itemType")

                if (itemType == "VIDEO") {
                    // Get video duration
                    val durationMs = item.getObjectField("durationMs") as? Double
                    context.log.verbose("GalleryVideoSplitting: Video duration: ${durationMs}ms")

                    // Check if video needs splitting (>10 seconds)
                    if (durationMs == null || durationMs <= 10000.0) {
                        context.log.verbose("GalleryVideoSplitting: Video too short or duration unknown, not splitting")
                        return@hook
                    }

                    // Cancel original send
                    param.setResult(null)

                    // Get content URI
                    val contentUriStr = item.getObjectField("contentUri")?.toString()
                    if (contentUriStr == null) {
                        context.log.error("GalleryVideoSplitting: Could not get content URI")
                        context.runOnUiThread {
                            context.inAppOverlay.showStatusToast(
                                Icons.Default.WarningAmber,
                                "Failed to get video URI"
                            )
                        }
                        return@hook
                    }

                    context.log.verbose("GalleryVideoSplitting: Content URI: $contentUriStr")

                    // Show duration selection dialog
                    context.runOnUiThread {
                        showDurationDialog(
                            param = param,
                            contentUri = contentUriStr,
                            originalItem = item,
                            originalMediaItem = mediaItem,
                            videoDuration = durationMs.toLong()
                        )
                    }
                }
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Error in sendItems hook", e)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun convertDuration(duration: Float): Int? {
        return when {
            duration in -2f..-1f -> 100
            duration in -1f..-0f -> 250
            duration in -0f..1f -> 500
            duration >= 11f -> null
            else -> ((duration * 1000).toInt() / 1000) * 1000
        }
    }

    private fun showDurationInputDialog(
        param: Any,
        contentUri: String,
        originalItem: Any,
        originalMediaItem: Any
    ) {
        var durationInput by mutableStateOf("")
        var errorMessage by mutableStateOf<String?>(null)
        
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Video Duration Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Could not automatically detect video duration. Please enter the duration in seconds:",
                    fontSize = 14.sp
                )

                OutlinedTextField(
                    value = durationInput,
                    onValueChange = { 
                        durationInput = it
                        errorMessage = null
                    },
                    label = { Text("Duration (seconds)") },
                    placeholder = { Text("e.g., 30") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        alertDialog.dismiss()
                    }) {
                        Text(context.translation["button.cancel"])
                    }
                    Button(onClick = {
                        val duration = durationInput.toIntOrNull()
                        if (duration == null || duration <= 0) {
                            errorMessage = "Please enter a valid positive number"
                            return@Button
                        }
                        
                        if (duration <= 10) {
                            errorMessage = "Video must be longer than 10 seconds to split"
                            return@Button
                        }
                        
                        alertDialog.dismiss()
                        val videoDurationMs = duration * 1000L
                        context.log.verbose("GalleryVideoSplitting: User input duration = ${videoDurationMs}ms")
                        
                        // Show the main duration dialog with the user-provided duration
                        showDurationDialog(
                            param = param,
                            contentUri = contentUri,
                            originalItem = originalItem,
                            originalMediaItem = originalMediaItem,
                            videoDuration = videoDurationMs
                        )
                    }) {
                        Text(context.translation["button.confirm"] ?: "Confirm")
                    }
                }
            }
        }.show()
    }

    private fun showDurationDialog(
        param: Any,
        contentUri: String,
        originalItem: Any,
        originalMediaItem: Any,
        videoDuration: Long
    ) {
        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            val mainTranslation = remember {
                context.translation.getCategory("send_override_dialog")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Split Video",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Video duration: ${(videoDuration / 1000.0).toDuration(DurationUnit.SECONDS).toString(DurationUnit.SECONDS, 1)}",
                    fontSize = 14.sp
                )

                Text(
                    text = "This video will be split into 10-second chunks and sent separately as snaps.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        text = mainTranslation.format(
                            "duration",
                            "duration" to (convertDuration(customDuration)?.toDuration(DurationUnit.MILLISECONDS)
                                ?.toString(DurationUnit.SECONDS, 2)
                                ?: mainTranslation["unlimited_duration"])
                        ),
                        fontSize = 14.sp
                    )
                    Slider(
                        modifier = Modifier.fillMaxWidth(),
                        value = customDuration,
                        onValueChange = {
                            customDuration = it
                        },
                        valueRange = -2f..11f,
                    )
                    Text(
                        text = "Snap duration for each chunk",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        alertDialog.dismiss()
                    }) {
                        Text(context.translation["button.cancel"])
                    }
                    Button(onClick = {
                        alertDialog.dismiss()
                        
                        // Start async splitting and sending process
                        context.coroutineScope.launch {
                            splitAndSendVideo(
                                param = param,
                                contentUri = contentUri,
                                originalItem = originalItem,
                                originalMediaItem = originalMediaItem
                            )
                        }
                    }) {
                        Text(context.translation["button.send"])
                    }
                }
            }
        }.show()
    }

    private suspend fun splitAndSendVideo(
        param: Any,
        contentUri: String,
        originalItem: Any,
        originalMediaItem: Any
    ) {
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            isSplitting = true
            
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            val mediaUri = Uri.parse(contentUri)
            val cachedVideo = File(tempDir, "input.mp4")

            // Copy video to cache
            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open input stream for URI: $mediaUri")

            context.log.verbose("GalleryVideoSplitting: Video cached (${cachedVideo.length()} bytes), starting FFmpeg split...")

            // Split with FFmpeg - 10 second segments
            val command = "-i ${cachedVideo.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 ${tempDir.absolutePath}/split_%03d.mp4"
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.output}")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("FFmpeg produced no output files")
            }

            context.log.verbose("GalleryVideoSplitting: FFmpeg created ${outputFiles.size} chunks")

            // Get conversation IDs and action handler
            val conversationIds = param.arg<List<Any>>(0)
            val actionHandler = param.thisObject<Any>()
            val sendItemsMethod = actionHandler.javaClass.methods.first { it.name == "sendItems" }

            val snapDurationMs = convertDuration(customDuration)

            // Send each chunk
            for ((index, chunkFile) in outputFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(
                        Icons.Default.Info,
                        "Sending chunk ${index + 1}/${outputFiles.size}..."
                    )
                }

                val chunkUri = Uri.fromFile(chunkFile)
                val retriever = MediaMetadataRetriever()
                
                try {
                    retriever.setDataSource(chunkFile.absolutePath)
                    
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 1080.0
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 1920.0

                    // Create new item for this chunk (same structure as original)
                    val newItem = originalItem.javaClass.dataBuilder {
                        set("type", originalItem.getObjectField("type"))
                        set("encryptionInfo", originalItem.getObjectField("encryptionInfo"))
                        set("contentUri", chunkUri.toString())
                        set("durationMs", snapDurationMs?.toDouble() ?: chunkDuration.toDouble())
                        set("width", chunkWidth)
                        set("height", chunkHeight)
                        from("itemId", new = true) {
                            set("itemId", chunkUri.toString())
                        }
                    }

                    val newMediaItem = originalMediaItem.javaClass.dataBuilder {
                        set("thumbnail", originalMediaItem.getObjectField("thumbnail"))
                        set("item", newItem)
                        set("order", index.toDouble())
                    }

                    // Send this chunk
                    sendItemsMethod.invoke(actionHandler, conversationIds, listOf(newMediaItem))
                    
                    // Delay between sends
                    delay(1500)
                    
                } finally {
                    retriever.release()
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info,
                    "All ${outputFiles.size} chunks sent successfully!"
                )
            }
            
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split and send video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.WarningAmber,
                    "Failed: ${e.message}"
                )
            }
        } finally {
            tempDir.deleteRecursively()
            isSplitting = false
        }
    }
}
