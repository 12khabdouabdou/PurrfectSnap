package me.rhunk.snapenhance.core.features.impl.tweaks

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.coroutines.suspendCoroutine
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.event.events.impl.ActivityResultEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.experiments.MediaFilePicker
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import me.rhunk.snapenhance.core.wrapper.impl.MessageDestinations
import me.rhunk.snapenhance.core.messaging.MessageSender
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.random.Random

/**
 * GalleryVideoSplitting: Splits videos >10s into customizable SNAP chunks
 * 
 * Features (Snapchat 12.51 recreation + enhancements):
 * - Configurable chunk duration (5s, 10s, 15s, or custom)
 * - Preview showing chunk count and file sizes before sending
 * - Support for both direct messages and story uploads
 * - Optimized batch sending with configurable delays
 * - Per-chunk snap duration control (quick-snap, 3s, 5s, unlimited)
 * 
 * Architecture (following Snapchat SendToFragment pattern):
 * 1. Early interception: Subscribe to SendMessageWithContentEvent
 * 2. Detect EXTERNAL_MEDIA video >10s, cancel event
 * 3. Show UI dialog to confirm splitting with preview
 * 4. Launch file picker for user to re-select the video
 * 5. Use FFmpeg to split into configurable chunks
 * 6. Send chunks directly via MessageSender.sendCustomChatMessage()
 * 7. Build SNAP proto structure directly in ProtoWriter lambda
 * 8. Manage disposables with CompositeDisposable pattern (like SendToFragment does)
 */
class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)
    private var chunkDurationSeconds by mutableIntStateOf(10)
    private var totalChunksPreview by mutableIntStateOf(0)
    private var totalSizePreview by mutableLongStateOf(0L)
    private var enableQuickSend by mutableStateOf(false)
    
    // Track pending operations (matching Snapchat's CompositeDisposable pattern)
    private val pendingChunks = mutableListOf<File>()
    private var tempDir: File? = null
    private var pendingPickerRequest: Pair<Int, (data: Uri) -> Unit>? = null
    
    // Media package metadata columns (from Snapchat's MediaPackageFileProvider)
    companion object {
        private const val MEDIA_COLUMN_DISPLAY_NAME = "_display_name"
        private const val MEDIA_COLUMN_SIZE = "_size"
        private const val MEDIA_COLUMN_PATH = "_data"
        private const val MEDIA_COLUMN_MIME = "mime_type"
    }

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

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            context.log.verbose("GalleryVideoSplitting: SendMessageWithContentEvent triggered")
            
            // Early check: if this is an EXTERNAL_MEDIA video, try to split it BEFORE SendOverride processes it
            // This ensures we have access to the original gallery URI in Snapchat's process context
            val localMessageContent = event.messageContent
            if (localMessageContent.contentType == ContentType.EXTERNAL_MEDIA &&
                !event.canceled &&
                event.destinations.stories?.isNotEmpty() != true) {
                try {
                    val messageProtoReader = ProtoReader(localMessageContent.content ?: byteArrayOf())
                    if (!messageProtoReader.contains(7)) { // not a story reply
                        val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                        if (mediaCount == 1) {
                            val mediaType = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 6)
                            if (mediaType == null || mediaType == 1L) { // video or unknown type (might be video)
                                var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) 
                                    ?: messageProtoReader.getVarInt(11, 5, 2, 5)?.let { it * 1000 }
                                    ?: context.feature(MediaFilePicker::class).lastMediaDuration
                                
                                if (videoDuration != null && videoDuration > 10000) {
                                    context.log.verbose("GalleryVideoSplitting: Early interception: found video ${videoDuration}ms > 10s, attempting split")
                                    event.canceled = true
                                    context.runOnUiThread {
                                        showDurationDialog(event, videoDuration, messageProtoReader)
                                    }
                                    return@subscribe
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    context.log.verbose("GalleryVideoSplitting: Early interception check failed: ${e.message}")
                }
            }
        }

        // Listen for ActivityResultEvent to handle re-pick results
        context.event.subscribe(ActivityResultEvent::class) { ev ->
            val pending = pendingPickerRequest
            if (pending == null) return@subscribe
            if (ev.requestCode != pending.first) return@subscribe
            val handler = pending.second
            pendingPickerRequest = null
            ev.canceled = true
            val data = ev.intent?.data
            if (data != null) {
                try {
                    // For OPEN_DOCUMENT we may want to persist permission
                    try {
                        val flags = (ev.intent?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        if (flags != 0) {
                            context.mainActivity?.contentResolver?.takePersistableUriPermission(data, flags)
                        }
                    } catch (_: Exception) {}

                    handler(data)
                } catch (e: Exception) {
                    context.log.error("GalleryVideoSplitting: Error handling picker result", e)
                }
            } else {
                context.log.verbose("GalleryVideoSplitting: Picker returned no data")
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    // Removed buildSnapProtoForChunk - proto is now built directly in sendSnapChunk lambda
    // This follows Snapchat's pattern from SendToFragment (build in ProtoWriter context, not pre-serialized)

    private suspend fun sendChunksAsSnaps(destinations: Any, chunks: List<File>) {
        val destinationsWrapper = MessageDestinations(destinations)
        val conversationUUIDs = destinationsWrapper.conversations?.map { SnapUUID(it.toString()) } ?: emptyList()
        
        if (conversationUUIDs.isEmpty()) {
            context.log.error("GalleryVideoSplitting: No conversations to send to")
            return
        }
        
        val messageSender = MessageSender(context)
        
        context.log.verbose("GalleryVideoSplitting: Starting to send ${chunks.size} chunks to ${conversationUUIDs.size} conversation(s)")
        
        var successCount = 0
        var failureCount = 0
        
        for ((index, chunkFile) in chunks.withIndex()) {
            context.log.verbose("GalleryVideoSplitting: Sending chunk ${index + 1}/${chunks.size}")
            
            val sentSuccessfully = sendSnapChunk(messageSender, conversationUUIDs, chunkFile)
            if (sentSuccessfully) {
                successCount++
                context.log.verbose("GalleryVideoSplitting: Chunk ${index + 1} sent successfully")
            } else {
                failureCount++
                context.log.error("GalleryVideoSplitting: Failed to send chunk ${index + 1}")
            }
            
            // Delay between sends to avoid overwhelming the network
            if (index < chunks.size - 1) {
                delay(1000)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Done sending chunks: $successCount successful, $failureCount failed")
        cleanupChunks()
        
        withContext(Dispatchers.Main) {
            if (failureCount == 0) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info,
                    "$successCount snap(s) sent successfully!"
                )
            } else {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.WarningAmber,
                    "$successCount sent, $failureCount failed"
                )
            }
        }
    }

    private suspend fun sendSnapChunk(messageSender: MessageSender, conversations: List<SnapUUID>, chunkFile: File): Boolean {
        return suspendCoroutine { continuation ->
            try {
                val chunkUri = Uri.fromFile(chunkFile)
                context.log.verbose("GalleryVideoSplitting: Sending chunk as SNAP with URI: $chunkUri")

                val onError: (Any) -> Unit = { err ->
                    context.log.error("GalleryVideoSplitting: Failed to send chunk: $err")
                    try { continuation.resume(false) } catch (_: Exception) {}
                }
                
                val onSuccess: () -> Unit = {
                    context.log.verbose("GalleryVideoSplitting: Chunk sent successfully")
                    try { continuation.resume(true) } catch (_: Exception) {}
                }

                // Build the message inside the lambda to ensure proper proto structure
                messageSender.sendCustomChatMessage(
                    conversations,
                    ContentType.SNAP,
                    {
                        try {
                            // Extract width/height from chunk
                            val retriever = MediaMetadataRetriever()
                            val chunkWidth: Int
                            val chunkHeight: Int
                            try {
                                retriever.setDataSource(chunkFile.absolutePath)
                                chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                                chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                            } finally {
                                retriever.release()
                            }

                            val snapDurationMs = convertDuration(customDuration)
                            val hasSound = 1L

                            // Build SNAP message structure directly in the proto writer
                            // Path 11 = snap metadata container
                            from(11) {
                                // Path 11, 5 = media attributes
                                from(5) {
                                    // Path 11, 5, 1 = media info (dimensions, type)
                                    from(1) {
                                        from(1) {
                                            addVarInt(2, 0)      // overlay type
                                            addVarInt(12, 0)
                                            addVarInt(15, 0)
                                            addVarInt(16, chunkWidth)
                                            addVarInt(17, chunkHeight)
                                        }
                                        addVarInt(6, 1)  // media type: video
                                    }
                                    // Path 11, 5, 2 = duration and playback info
                                    from(2) {
                                        addVarInt(5, hasSound)
                                        if (snapDurationMs != null) {
                                            addVarInt(8, snapDurationMs / 1000)
                                            if (snapDurationMs / 1000 <= 0) {
                                                addVarInt(99, snapDurationMs.toLong())
                                            }
                                        } else {
                                            addBuffer(6, byteArrayOf())
                                        }
                                    }
                                }
                                // Path 11, 22 = app source
                                from(22) {
                                    addVarInt(4, 5)  // APP_SOURCE_CAMERA
                                }
                            }
                        } catch (e: Exception) {
                            // If building failed, log and rethrow so onError path runs
                            context.log.error("GalleryVideoSplitting: Failed while constructing SNAP proto in lambda", e)
                            throw e
                        }
                    },
                    onError = onError,
                    onSuccess = onSuccess
                )
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Exception while sending snap chunk", e)
                try { continuation.resume(false) } catch (_: Exception) {}
            }
        }
    }


    private fun convertDuration(duration: Float): Int? {
        return when {
            duration <= -2f -> 100       // ≤ -2: 100ms (quick snap)
            duration <= -1f -> 250       // -2 to -1: 250ms
            duration <= 0f -> 500        // -1 to 0: 500ms
            duration >= 11f -> null      // ≥ 11: unlimited (video duration)
            else -> (duration * 1000).toInt()  // 0 to 11: duration in ms
        }
    }

    private fun showDurationInputDialog(
        event: SendMessageWithContentEvent,
        messageProtoReader: ProtoReader
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
                        showDurationDialog(event, videoDurationMs, messageProtoReader)
                    }) {
                        Text(context.translation["button.confirm"] ?: "Confirm")
                    }
                }
            }
        }.show()
    }

    private fun showDurationDialog(
        event: SendMessageWithContentEvent,
        totalDuration: Long,
        messageProtoReader: ProtoReader
    ) {
        // Calculate preview chunks and size
        val estimatedChunks = (totalDuration + (chunkDurationSeconds * 1000 - 1)) / (chunkDurationSeconds * 1000)
        totalChunksPreview = estimatedChunks.toInt()
        
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

                // Video duration display
                Text(
                    text = "Video duration: ${(totalDuration / 1000.0).toDuration(DurationUnit.SECONDS).toString(DurationUnit.SECONDS, 1)}",
                    fontSize = 14.sp
                )

                // Chunk duration selector
                Column {
                    Text(
                        text = "Chunk duration: $chunkDurationSeconds seconds",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 15).forEach { duration ->
                            Button(
                                onClick = {
                                    chunkDurationSeconds = duration
                                    totalChunksPreview = ((totalDuration + (duration * 1000 - 1)) / (duration * 1000)).toInt()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (chunkDurationSeconds == duration) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text("${duration}s", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Preview information
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Preview",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Chunks: $totalChunksPreview",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "This video will be split into $totalChunksPreview chunks and sent separately as snaps.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Snap duration slider for each chunk
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

                // Quick send toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Send immediately",
                        fontSize = 12.sp
                    )
                    Checkbox(
                        checked = enableQuickSend.value,
                        onCheckedChange = { enableQuickSend.value = it }
                    )
                }

                // Action buttons
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

                        // Launch the re-pick file flow
                        launchRepickPicker { pickedUri ->
                            context.coroutineScope.launch {
                                val success = splitFromPickedUri(pickedUri, event)
                                if (success && pendingChunks.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        context.inAppOverlay.showStatusToast(
                                            Icons.Default.Info,
                                            "Sending ${pendingChunks.size} chunks as snaps..."
                                        )
                                    }
                                    // Send all chunks directly via MessageSender (await completion)
                                    sendChunksAsSnaps(event.destinations, pendingChunks.toList())
                                }
                            }
                        }
                    }) {
                        Text(context.translation["button.send"])
                    }
                }
            }
        }.show()
    }

    private fun launchRepickPicker(onPicked: (Uri) -> Unit) {
        val requestCode = Random.nextInt(1, 65535)
        pendingPickerRequest = requestCode to onPicked
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            }
            context.mainActivity?.startActivityForResult(Intent.createChooser(intent, context.translation["pick_media"] ?: "Select video"), requestCode)
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to launch picker", e)
        }
    }

    private suspend fun splitFromPickedUri(pickedUri: Uri, event: SendMessageWithContentEvent): Boolean {
        tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
        val cachedVideo = File(tempDir!!, "input.mp4")
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Preparing video for split...")
            }

            // Copy using the activity's resolver (should have permission because picker granted it)
            val resolver = context.mainActivity!!.contentResolver
            resolver.openInputStream(pickedUri)?.use { input ->
                cachedVideo.outputStream().use { out -> input.copyTo(out) }
            } ?: run {
                context.log.error("GalleryVideoSplitting: Picker returned URI but could not open input stream: $pickedUri")
                return false
            }

            // Validate file was actually copied (following Snapchat's MediaPackageFileProvider validation pattern)
            if (!cachedVideo.exists() || cachedVideo.length() <= 0L) {
                context.log.error("GalleryVideoSplitting: Video file validation failed - file doesn't exist or is empty")
                return false
            }

            // Extract actual duration from the cached video file
            val retriever = MediaMetadataRetriever()
            val actualDurationMs: Long
            val videoWidth: Int
            val videoHeight: Int
            try {
                retriever.setDataSource(cachedVideo.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                actualDurationMs = durationStr?.toLongOrNull() ?: 0L
                videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                context.log.verbose("GalleryVideoSplitting: Extracted video metadata: ${actualDurationMs}ms, ${videoWidth}x${videoHeight}")
            } finally {
                retriever.release()
            }

            if (actualDurationMs <= 0L) {
                context.log.error("GalleryVideoSplitting: Could not extract valid duration from picked video")
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Could not read video duration")
                }
                return false
            }

            context.log.verbose("GalleryVideoSplitting: Picked video cached (${cachedVideo.length()} bytes), starting FFmpeg split with ${chunkDurationSeconds}s chunks...")

            // Use re-encode (libx264) to ensure correct GOP splitting; stream copy often produces single file on GOP boundaries
            val command = "-i ${cachedVideo.absolutePath} -c:v libx264 -preset ultrafast -c:a aac -f segment -segment_time $chunkDurationSeconds -reset_timestamps 1 ${tempDir!!.absolutePath}/split_%03d.mp4"
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                context.log.error("GalleryVideoSplitting: FFmpeg failed: ${session.output}")
                return false
            }

            val outputFiles = tempDir!!.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            if (outputFiles.isEmpty()) {
                context.log.error("GalleryVideoSplitting: FFmpeg produced no output files")
                return false
            }

            // Validate all chunks exist and have content
            val allValid = outputFiles.all { file ->
                val isValid = file.exists() && file.length() > 0L
                if (!isValid) context.log.warn("GalleryVideoSplitting: Chunk ${file.name} is invalid (missing or empty)")
                isValid
            }
            
            if (!allValid) {
                context.log.error("GalleryVideoSplitting: Some chunks failed validation")
                return false
            }

            pendingChunks.clear()
            pendingChunks.addAll(outputFiles)
            context.log.verbose("GalleryVideoSplitting: Prepared ${pendingChunks.size} chunks from picked video")
            return true
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: splitFromPickedUri failed", e)
            tempDir?.deleteRecursively()
            tempDir = null
            return false
        }
    }

    private fun cleanupChunks() {
        pendingChunks.clear()
        isSplitting = false
        tempDir?.deleteRecursively()
        tempDir = null
        context.log.verbose("GalleryVideoSplitting: Cleanup complete")
        context.runOnUiThread {
            context.inAppOverlay.showStatusToast(
                Icons.Default.Info,
                "All chunks sent successfully!"
            )
        }
    }
}
