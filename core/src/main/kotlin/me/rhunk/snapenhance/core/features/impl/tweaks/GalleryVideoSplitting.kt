package me.rhunk.snapenhance.core.features.impl.tweaks

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
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
import kotlin.coroutines.suspendCoroutine
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.common.util.protobuf.ProtoEditor
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.event.events.impl.ActivityResultEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.experiments.MediaFilePicker
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
import me.rhunk.snapenhance.core.wrapper.impl.MessageContent
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import me.rhunk.snapenhance.core.wrapper.impl.MessageDestinations
import me.rhunk.snapenhance.core.messaging.MessageSender
import android.util.Base64
import android.os.Environment
import java.io.FileOutputStream
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.random.Random

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)
    
    // Queue to hold pending chunks
    private val pendingChunks = mutableListOf<File>()
    private var currentChunkIndex = 0
    private var tempDir: File? = null
    private var pendingPickerRequest: Pair<Int, (data: Uri) -> Unit>? = null

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

    private fun buildSnapProtoForChunk(chunkFile: File): ByteArray? {
        try {
            context.log.verbose("GalleryVideoSplitting: Building SNAP proto for chunk ${chunkFile.name}")
            
            // Extract metadata from chunk
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
            
            context.log.verbose("GalleryVideoSplitting: Extracted chunk metadata: ${chunkWidth}x${chunkHeight}")
            
            val snapDurationMs = convertDuration(customDuration)
            val hasSound = 1L // Default to has sound
            
            // Build SNAP proto
            val snapProto = ProtoWriter().apply {
                from(11) {
                    from(5) {
                        from(1) {
                            from(1) {
                                addVarInt(2, 0) // overlay type
                                addVarInt(12, 0)
                                addVarInt(15, 0) // will be set below
                                addVarInt(16, chunkWidth)
                                addVarInt(17, chunkHeight)
                            }
                            addVarInt(6, 1) // media type: video
                        }
                        from(2) {}
                    }
                    from(22) {}
                }
            }.toByteArray()
            
            context.log.verbose("GalleryVideoSplitting: Built initial SNAP proto content")
            
            // Use ProtoEditor to set snap duration
            val finalProto = ProtoEditor(snapProto).apply {
                edit(11, 5, 2) {
                    arrayOf(6, 7, 8).forEach { remove(it) }
                    addVarInt(5, hasSound)
                    // set snap duration
                    if (snapDurationMs != null) {
                        addVarInt(8, snapDurationMs / 1000)
                        if (snapDurationMs / 1000 <= 0) {
                            addVarInt(99, snapDurationMs.toLong())
                        }
                    } else {
                        addBuffer(6, byteArrayOf())
                    }
                }
                
                // set app source
                edit(11, 22) {
                    remove(4)
                    addVarInt(4, 5) // APP_SOURCE_CAMERA
                }
            }.toByteArray()
            
            context.log.verbose("GalleryVideoSplitting: Set snap duration and app source")
            return finalProto
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to build SNAP proto for chunk", e)
            return null
        }
    }

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
            
            val snapProto = buildSnapProtoForChunk(chunkFile) ?: run {
                context.log.error("GalleryVideoSplitting: Failed to build SNAP proto for chunk ${index + 1}")
                failureCount++
                continue
            }
            
            val sentSuccessfully = sendSnapChunk(messageSender, conversationUUIDs, snapProto, chunkFile)
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

    private suspend fun sendSnapChunk(messageSender: MessageSender, conversations: List<SnapUUID>, snapProto: ByteArray, chunkFile: File): Boolean {
        return try {
            suspendCoroutine<Boolean> { continuation ->
                try {
                    val chunkUri = Uri.fromFile(chunkFile)
                    context.log.verbose("GalleryVideoSplitting: Sending chunk as SNAP with URI: $chunkUri")

                    // Capture continuation in a val for access in nested lambdas
                    val cont = continuation

                    // Use MessageSender.sendCustomChatMessage to send the prebuilt proto bytes.
                    // The lambda builds the ProtoWriter content for MessageSender; here we insert the
                    // Instead of injecting raw bytes, rebuild the SNAP proto inside the lambda
                    // so the MessageSender's ProtoWriter constructs the exact expected structure.
                    messageSender.sendCustomChatMessage(
                        conversations,
                        ContentType.SNAP,
                        {
                            try {
                                // Build inner SNAP proto based on chunk metadata
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

                                // Build the inner SNAP proto using a temporary ProtoWriter, then insert
                                // it into the outgoing message as buffer field 1.
                                val inner = ProtoWriter().apply {
                                    from(11) {
                                        from(5) {
                                            from(1) {
                                                from(1) {
                                                    addVarInt(2, 0)
                                                    addVarInt(12, 0)
                                                    addVarInt(15, 0)
                                                    addVarInt(16, chunkWidth)
                                                    addVarInt(17, chunkHeight)
                                                }
                                                addVarInt(6, 1)
                                            }
                                            from(2) {}
                                        }
                                        from(22) {}
                                    }
                                }.toByteArray()

                                val finalInner = ProtoEditor(inner).apply {
                                    edit(11, 5, 2) {
                                        arrayOf(6, 7, 8).forEach { remove(it) }
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

                                    edit(11, 22) {
                                        remove(4)
                                        addVarInt(4, 5)
                                    }
                                }.toByteArray()

                                // Insert the rebuilt inner SNAP proto into the message writer
                                addBuffer(1, finalInner)
                            } catch (e: Exception) {
                                // If building failed, log and rethrow so onError path runs
                                context.log.error("GalleryVideoSplitting: Failed while constructing inner SNAP proto", e)
                                throw e
                            }
                        },
                        onError = { err ->
                            context.log.error("GalleryVideoSplitting: Failed to send chunk: $err")
                            try { cont.resume(false) } catch (_: Exception) {}
                        },
                        onSuccess = {
                            context.log.verbose("GalleryVideoSplitting: Chunk sent successfully")
                            try { cont.resume(true) } catch (_: Exception) {}
                        }
                    )
                } catch (e: Exception) {
                    context.log.error("GalleryVideoSplitting: Exception while sending snap chunk", e)
                    try { continuation.resume(false) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: sendSnapChunk outer exception", e)
            false
        }
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
                    text = "Video duration: ${(totalDuration / 1000.0).toDuration(DurationUnit.SECONDS).toString(DurationUnit.SECONDS, 1)}",
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
                                        // Send all chunks directly via MessageSender
                                        sendChunksAsSnaps(event.destinations, pendingChunks)
                                    }
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

            context.log.verbose("GalleryVideoSplitting: Picked video cached (${cachedVideo.length()} bytes), starting FFmpeg split...")

            // Use re-encode (libx264) to ensure correct GOP splitting; stream copy often produces single file on GOP boundaries
            val command = "-i ${cachedVideo.absolutePath} -c:v libx264 -preset ultrafast -c:a aac -f segment -segment_time 10 -reset_timestamps 1 ${tempDir!!.absolutePath}/split_%03d.mp4"
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

    private suspend fun splitAndPrepareChunks(
        event: SendMessageWithContentEvent,
        messageProtoReader: ProtoReader
    ): Boolean {
        tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            // Extract video duration from protobuf (same logic as in init)
            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)
                ?: messageProtoReader.getVarInt(11, 5, 2, 5)?.let { it * 1000 }
                ?: context.feature(MediaFilePicker::class).lastMediaDuration
            
            context.log.verbose("GalleryVideoSplitting: Extracted duration from proto: ${videoDuration}ms")
            
            if (videoDuration == null || videoDuration <= 0) {
                context.log.warn("GalleryVideoSplitting: Could not get duration from proto, will rely on FFmpeg metadata")
                videoDuration = 0L // Will be determined by FFmpeg
            }

            // Extract content URI from mLocalMediaReferences (Java object field, NOT protobuf)
            val localMessageContent = event.messageContent
            
            context.log.verbose("GalleryVideoSplitting: localMessageContent class: ${localMessageContent.instanceNonNull().javaClass.name}")
            
            // Access mLocalMediaReferences directly using reflection (bypassing MessageContent wrapper)
            val mLocalMediaReferencesField = localMessageContent.instanceNonNull().javaClass.getDeclaredField("mLocalMediaReferences")
            mLocalMediaReferencesField.isAccessible = true
            val localMediaReferencesObj = mLocalMediaReferencesField.get(localMessageContent.instanceNonNull())

            var mediaUriStr: String? = null
            
            context.log.verbose("GalleryVideoSplitting: mLocalMediaReferences = $localMediaReferencesObj")
            context.log.verbose("GalleryVideoSplitting: mLocalMediaReferences type: ${localMediaReferencesObj?.javaClass?.name}")
            
            if (localMediaReferencesObj is List<*>) {
                context.log.verbose("GalleryVideoSplitting: mLocalMediaReferences is a List with ${localMediaReferencesObj.size} items")
                
                if (localMediaReferencesObj.isNotEmpty()) {
                    val firstRef = localMediaReferencesObj.first()
                    context.log.verbose("GalleryVideoSplitting: First reference type: ${firstRef?.javaClass?.name}")
                    
                    // Log all fields in the reference object
                    firstRef?.javaClass?.declaredFields?.forEach { field ->
                        field.isAccessible = true
                        try {
                            val value = field.get(firstRef)
                            context.log.verbose("  Reference field: ${field.name} (${field.type.simpleName}) = ${
                                if (value is ByteArray) "ByteArray[${value.size}]: ${String(value)}" 
                                else value
                            }")
                        } catch (e: Exception) {
                            context.log.verbose("  Reference field: ${field.name} - error: ${e.message}")
                        }
                    }
                    
                    // Try to get mId field
                    try {
                        val mIdField = firstRef?.javaClass?.getDeclaredField("mId")
                        if (mIdField != null) {
                            mIdField.isAccessible = true
                            val mediaIdObj = mIdField.get(firstRef)
                            context.log.verbose("GalleryVideoSplitting: mId type: ${mediaIdObj?.javaClass?.name}")
                            
                                if (mediaIdObj is ByteArray) {
                                    mediaUriStr = extractUriFromByteArray(mediaIdObj)
                                    if (mediaUriStr != null) {
                                        context.log.verbose("GalleryVideoSplitting: Successfully extracted URI from mId byte array: $mediaUriStr")
                                    } else {
                                        // fallback to raw string for logging/diagnostics
                                        val raw = try { String(mediaIdObj, Charsets.UTF_8) } catch (_: Exception) { "<binary>" }
                                        context.log.verbose("GalleryVideoSplitting: Could not find URI in mId byte array, raw: $raw")
                                    }
                                } else {
                                    context.log.error("GalleryVideoSplitting: mId is not ByteArray, it's ${mediaIdObj?.javaClass?.name}")
                                }
                        } else {
                            context.log.error("GalleryVideoSplitting: mId field not found in reference object")
                        }
                    } catch (e: NoSuchFieldException) {
                        context.log.error("GalleryVideoSplitting: NoSuchFieldException for mId: ${e.message}")
                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: Error accessing mId: ${e.message}", e)
                    }
                } else {
                    context.log.error("GalleryVideoSplitting: mLocalMediaReferences list is empty!")
                }
            } else {
                context.log.error("GalleryVideoSplitting: mLocalMediaReferences is not a List!")
                if (localMediaReferencesObj != null) {
                    context.log.verbose("GalleryVideoSplitting: It's a ${localMediaReferencesObj.javaClass.name}")
                }
            }

            if (mediaUriStr == null) {
                context.log.error("GalleryVideoSplitting: Could not extract media URI from mLocalMediaReferences")
                
                // Dump proto structure for analysis
                context.log.verbose("GalleryVideoSplitting: Full proto structure dumped to cache for analysis")
                dumpProtoToCache("full_proto", localMessageContent.content)
                
                // Check if width/height are in proto (which confirms video metadata exists)
                val width = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 5, 1)
                val height = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 5, 2)
                context.log.verbose("GalleryVideoSplitting: Width from proto: $width, Height: $height")
                
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(
                        Icons.Default.WarningAmber, 
                        "Failed to get video URI. Check logs for details."
                    )
                }
                return false
            }

            context.log.verbose("GalleryVideoSplitting: Using media URI: $mediaUriStr")
            
            // Parse URI and access via ContentResolver
            val mediaUri = Uri.parse(mediaUriStr)
            val cachedVideo = File(tempDir!!, "input.mp4")

            // Copy video to cache using ContentResolver. Try multiple resolvers (mainActivity then androidContext).
            val triedResolvers = mutableListOf<String>()
            val copied = tryCopyUriToFile(mediaUri, cachedVideo, triedResolvers)
            if (!copied) {
                // Attempt to dump the LocalMediaReference for analysis and provide helpful logs
                try {
                    val firstRef = (localMediaReferencesObj as? List<*>)?.firstOrNull()
                    if (firstRef != null) {
                        dumpLocalMediaReferenceToCache("localref", firstRef)
                    }
                } catch (e: Exception) {
                    context.log.verbose("GalleryVideoSplitting: Failed to dump LocalMediaReference: ${e.message}")
                }
                context.log.error("GalleryVideoSplitting: Tried resolvers: $triedResolvers but couldn't open URI: $mediaUri")
                throw IllegalStateException("Failed to open input stream for URI: $mediaUri")
            }

            context.log.verbose("GalleryVideoSplitting: Video cached (${cachedVideo.length()} bytes), starting FFmpeg split...")

            // Split with FFmpeg - 10 second segments using re-encode to ensure correct GOP splitting
            val command = "-i ${cachedVideo.absolutePath} -c:v libx264 -preset ultrafast -c:a aac -f segment -segment_time 10 -reset_timestamps 1 ${tempDir!!.absolutePath}/split_%03d.mp4"
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed with code ${session.returnCode}: ${session.output}")
            }

            val outputFiles = tempDir!!.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("FFmpeg produced no output files")
            }

            context.log.verbose("GalleryVideoSplitting: FFmpeg created ${outputFiles.size} chunks")

            // Store chunk files for sequential sending
            pendingChunks.clear()
            pendingChunks.addAll(outputFiles)

            context.log.verbose("GalleryVideoSplitting: Successfully prepared ${pendingChunks.size} chunks")
            return true
            
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.WarningAmber, "Failed: ${e.message}")
            }
            cleanupChunks()
            return false
        }
    }

    private fun cleanupChunks() {
        pendingChunks.clear()
        currentChunkIndex = 0
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

    // Helper to safely log very large strings by splitting into chunks.
    private fun safeLogLarge(prefix: String, content: String) {
        try {
            val chunkSize = 3000
            var i = 0
            while (i < content.length) {
                val end = kotlin.math.min(i + chunkSize, content.length)
                val part = content.substring(i, end)
                context.log.verbose("$prefix [$i..$end]: $part")
                i = end
            }
        } catch (e: Exception) {
            try {
                context.log.verbose("$prefix: (failed to log large content: ${e.message})")
            } catch (_: Exception) { }
        }
    }

    // Try to extract a usable URI string from a possibly-prefixed or binary mId byte array.
    private fun extractUriFromByteArray(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        try {
            // Try UTF-8 interpretation first
            val s = String(bytes, Charsets.UTF_8)
            // Look for the common URI schemes we care about
            val schemes = listOf("content://", "file://", "http://", "https://", "/")
            for (scheme in schemes) {
                val idx = s.indexOf(scheme)
                if (idx >= 0) {
                    // Trim off any trailing non-printable/control characters
                    var end = s.length
                    for (i in idx until s.length) {
                        val c = s[i]
                        if (c < ' ' && c != '\t') { end = i; break }
                    }
                    val candidate = s.substring(idx, end)
                    if (candidate.isNotBlank()) return candidate
                }
            }

            // As a fallback, try trimming surrounding control chars and return if it looks like a path
            val trimmed = s.trim { it <= ' ' }
            return if (trimmed.startsWith("content://") || trimmed.startsWith("file://") || trimmed.startsWith("/")) trimmed else null
        } catch (e: Exception) {
            try {
                context.log.verbose("GalleryVideoSplitting: extractUriFromByteArray failed: ${e.message}")
            } catch (_: Exception) { }
            return null
        }
    }

    private fun dumpProtoToCache(prefix: String, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        try {
            val cacheDir = context.mainActivity?.cacheDir ?: return
            val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val fileName = "${safePrefix}_${System.currentTimeMillis()}.b64"
            val outFile = File(cacheDir, fileName)
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            FileOutputStream(outFile).use { fos ->
                fos.write(b64.toByteArray(Charsets.UTF_8))
            }
            context.log.verbose("GalleryVideoSplitting: Wrote proto dump to ${outFile.absolutePath}")

            // Also log the dump (in chunks to avoid truncation limits)
            safeLogLarge("GalleryVideoSplitting: Proto dump (base64):", b64)

            // Also try to write a copy to external storage for easier adb access (/sdcard/PurrfectSnapDumps)
            try {
                val externalDir = File(Environment.getExternalStorageDirectory(), "PurrfectSnapDumps")
                externalDir.mkdirs()
                val externalFile = File(externalDir, fileName)
                FileOutputStream(externalFile).use { fos -> fos.write(b64.toByteArray(Charsets.UTF_8)) }
                context.log.verbose("GalleryVideoSplitting: Also wrote proto dump to ${externalFile.absolutePath}")
            } catch (e: Exception) {
                context.log.verbose("GalleryVideoSplitting: Failed to write proto dump to external storage: ${e.message}")
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to write proto dump", e)
        }
    }

    private fun dumpLocalMediaReferenceToCache(prefix: String, refObj: Any) {
        try {
            val cacheDir = context.mainActivity?.cacheDir ?: return
            val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val fileName = "${safePrefix}_${System.currentTimeMillis()}.txt"
            val outFile = File(cacheDir, fileName)
            val sb = StringBuilder()
            sb.append("LocalMediaReference dump:\n")
            sb.append("Class: ${refObj.javaClass.name}\n")
            refObj.javaClass.declaredFields.forEach { field ->
                field.isAccessible = true
                try {
                    val value = field.get(refObj)
                    val repr = when (value) {
                        is ByteArray -> "ByteArray[${value.size}]: ${String(value)}"
                        null -> "null"
                        else -> value.toString()
                    }
                    sb.append("${field.name} (${field.type.simpleName}) = $repr\n")
                } catch (e: Exception) {
                    sb.append("${field.name} - error: ${e.message}\n")
                }
            }
            FileOutputStream(outFile).use { fos ->
                fos.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            context.log.verbose("GalleryVideoSplitting: Wrote LocalMediaReference dump to ${outFile.absolutePath}")

            // Also log the LocalMediaReference dump (in chunks to avoid truncation limits)
            safeLogLarge("GalleryVideoSplitting: LocalMediaReference dump:", sb.toString())

            // Also try writing to external storage for adb pull convenience
            try {
                val externalDir = File(Environment.getExternalStorageDirectory(), "PurrfectSnapDumps")
                externalDir.mkdirs()
                val externalFile = File(externalDir, fileName)
                FileOutputStream(externalFile).use { fos -> fos.write(sb.toString().toByteArray(Charsets.UTF_8)) }
                context.log.verbose("GalleryVideoSplitting: Also wrote LocalMediaReference dump to ${externalFile.absolutePath}")
            } catch (e: Exception) {
                context.log.verbose("GalleryVideoSplitting: Failed to write LocalMediaReference dump to external storage: ${e.message}")
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to write LocalMediaReference dump", e)
        }
    }

    // Attempt several strategies to copy a content/file URI to a local destination file.
    private fun tryCopyUriToFile(mediaUri: Uri, destFile: File, triedResolvers: MutableList<String>): Boolean {
        val resolvers = listOfNotNull(context.mainActivity?.contentResolver, context.androidContext.contentResolver).distinct()

        // Primary attempts: prefer mainActivity's ContentResolver (may have ephemeral URI permissions)
        for (resolver in resolvers) {
            triedResolvers.add(resolver.toString())
            // 1) normal openInputStream
            try {
                resolver.openInputStream(mediaUri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                context.log.verbose("GalleryVideoSplitting: Copied media using openInputStream with resolver $resolver")
                return true
            } catch (e: Exception) {
                context.log.verbose("GalleryVideoSplitting: openInputStream failed on $resolver: ${e.message}")
            }

            // 2) openFileDescriptor
            try {
                val pfd = resolver.openFileDescriptor(mediaUri, "r")
                if (pfd != null) {
                    pfd.use { parcel ->
                        java.io.FileInputStream(parcel.fileDescriptor).use { fis -> destFile.outputStream().use { fos -> fis.copyTo(fos) } }
                    }
                    context.log.verbose("GalleryVideoSplitting: Copied media using openFileDescriptor with resolver $resolver")
                    return true
                }
            } catch (e: Exception) {
                context.log.verbose("GalleryVideoSplitting: openFileDescriptor failed on $resolver: ${e.message}")
            }

            // 3) try AssetFileDescriptor
            try {
                val afd = resolver.openAssetFileDescriptor(mediaUri, "r")
                if (afd != null) {
                    afd.use { asset ->
                        asset.createInputStream().use { input -> destFile.outputStream().use { out -> input.copyTo(out) } }
                    }
                    context.log.verbose("GalleryVideoSplitting: Copied media using openAssetFileDescriptor with resolver $resolver")
                    return true
                }
            } catch (e: Exception) {
                context.log.verbose("GalleryVideoSplitting: openAssetFileDescriptor failed on $resolver: ${e.message}")
            }

            // 4) try typed asset descriptor (some providers require a MIME hint)
            try {
                val typed = resolver.openTypedAssetFileDescriptor(mediaUri, "*/*", null)
                if (typed != null) {
                    typed.use { t -> t.createInputStream().use { i -> destFile.outputStream().use { o -> i.copyTo(o) } } }
                    context.log.verbose("GalleryVideoSplitting: Copied media using openTypedAssetFileDescriptor with resolver $resolver")
                    return true
                }
            } catch (e: Exception) {
                context.log.verbose("GalleryVideoSplitting: openTypedAssetFileDescriptor failed on $resolver: ${e.message}")
            }

            // 5) try acquiring provider client and use its file APIs as a last direct provider attempt
            try {
                val client = resolver.acquireContentProviderClient(mediaUri)
                if (client != null) {
                    triedResolvers.add("contentProviderClient:${client.toString()}")
                    try {
                        // attempt to open a ParcelFileDescriptor via the client
                        val pfd = client.openFile(mediaUri, "r")
                        if (pfd != null) {
                            pfd.use { parcel -> java.io.FileInputStream(parcel.fileDescriptor).use { fis -> destFile.outputStream().use { fos -> fis.copyTo(fos) } } }
                            context.log.verbose("GalleryVideoSplitting: Copied media via ContentProviderClient.openFile")
                            client.release()
                            return true
                        }
                    } catch (e: Exception) {
                        context.log.verbose("GalleryVideoSplitting: ContentProviderClient.openFile failed: ${e.message}")
                    }
                    try { client.release() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                context.log.verbose("GalleryVideoSplitting: acquireContentProviderClient failed: ${e.message}")
            }
        }

        // Try stripping query parameters (some providers reject queries)
        try {
            val stripped = Uri.parse(mediaUri.toString().substringBefore('?'))
            for (resolver in resolvers) {
                triedResolvers.add("stripped:${resolver}")
                try {
                    resolver.openInputStream(stripped)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                    context.log.verbose("GalleryVideoSplitting: Copied media using stripped URI with resolver $resolver")
                    return true
                } catch (e: Exception) {
                    context.log.verbose("GalleryVideoSplitting: stripped openInputStream failed on $resolver: ${e.message}")
                }
                try {
                    val pfd = resolver.openFileDescriptor(stripped, "r")
                    if (pfd != null) {
                        pfd.use { parcel -> java.io.FileInputStream(parcel.fileDescriptor).use { fis -> destFile.outputStream().use { fos -> fis.copyTo(fos) } } }
                        context.log.verbose("GalleryVideoSplitting: Copied media using stripped openFileDescriptor with resolver $resolver")
                        return true
                    }
                } catch (e: Exception) {
                    context.log.verbose("GalleryVideoSplitting: stripped openFileDescriptor failed on $resolver: ${e.message}")
                }
            }
        } catch (e: Exception) {
            context.log.verbose("GalleryVideoSplitting: failed to build stripped URI: ${e.message}")
        }

        // Try querying for a file path (MediaStore/_data style) as last resort
        try {
            for (resolver in resolvers) {
                triedResolvers.add("query:${resolver}")
                try {
                    resolver.query(mediaUri, arrayOf("_data"), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex("_data")
                            if (idx >= 0) {
                                val path = cursor.getString(idx)
                                if (!path.isNullOrBlank()) {
                                    val f = File(path)
                                    if (f.exists()) {
                                        f.inputStream().use { fis -> destFile.outputStream().use { fos -> fis.copyTo(fos) } }
                                        context.log.verbose("GalleryVideoSplitting: Copied media using _data path from resolver $resolver: $path")
                                        return true
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    context.log.verbose("GalleryVideoSplitting: query for _data failed on $resolver: ${e.message}")
                }
            }
        } catch (e: Exception) {
            context.log.verbose("GalleryVideoSplitting: _data query attempt failed: ${e.message}")
        }

        return false
    }
}
