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
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.common.util.protobuf.ProtoEditor
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.experiments.MediaFilePicker
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
import me.rhunk.snapenhance.core.wrapper.impl.MessageContent
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class GalleryVideoSplitting : Feature("Gallery Video Splitting") {
    @Volatile
    private var isSplitting = false
    
    private var customDuration by mutableFloatStateOf(10f)
    
    // Queue to hold pending chunks with associated data
    private data class ChunkData(
        val file: File,
        val originalEvent: SendMessageWithContentEvent,
        val messageProtoReader: ProtoReader
    )
    
    private val pendingChunks = mutableListOf<ChunkData>()
    private var currentChunkIndex = 0
    private var tempDir: File? = null
    private var originalVideoFile: File? = null

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
            
            // Handle pending chunks
            if (isSplitting && pendingChunks.isNotEmpty() && currentChunkIndex < pendingChunks.size) {
                val chunkData = pendingChunks[currentChunkIndex]
                context.log.verbose("GalleryVideoSplitting: Processing chunk ${currentChunkIndex + 1}/${pendingChunks.size}")
                
                if (isSameDestination(event, chunkData.originalEvent)) {
                    modifyEventForChunk(event, chunkData.file, chunkData.messageProtoReader)
                    
                    currentChunkIndex++
                    
                    if (currentChunkIndex < pendingChunks.size) {
                        context.coroutineScope.launch {
                            delay(1500)
                            withContext(Dispatchers.Main) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info,
                                    "Sending chunk ${currentChunkIndex + 1}/${pendingChunks.size}..."
                                )
                            }
                            try {
                                chunkData.originalEvent.invokeOriginal()
                            } catch (e: Exception) {
                                context.log.error("GalleryVideoSplitting: Failed to send next chunk", e)
                                cleanupChunks()
                            }
                        }
                    } else {
                        context.coroutineScope.launch {
                            delay(2000)
                            cleanupChunks()
                        }
                    }
                    
                    return@subscribe
                }
            }
            
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, skipping")
                return@subscribe
            }
            
            // Skip stories
            if (event.destinations.stories?.isNotEmpty() == true && 
                event.destinations.conversations?.isEmpty() == true) {
                context.log.verbose("GalleryVideoSplitting: Story detected, skipping")
                return@subscribe
            }
            
            val localMessageContent = event.messageContent
            context.log.verbose("GalleryVideoSplitting: Content type = ${localMessageContent.contentType}")
            
            // Only process EXTERNAL_MEDIA
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && 
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) {
                context.log.verbose("GalleryVideoSplitting: Not EXTERNAL_MEDIA, skipping")
                return@subscribe
            }

            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            
            if (messageProtoReader.contains(7)) {
                context.log.verbose("GalleryVideoSplitting: Story reply detected, skipping")
                return@subscribe
            }

            // Check for single media only
            val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
            context.log.verbose("GalleryVideoSplitting: Media count = $mediaCount")
            if (mediaCount != 1) {
                context.log.verbose("GalleryVideoSplitting: Media count is not 1, skipping")
                return@subscribe
            }

            // Check if it's a video (type 1 = VIDEO)
            val mediaType = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 6)
            context.log.verbose("GalleryVideoSplitting: Media type = $mediaType")
            
            if (mediaType != null && mediaType != 1L) {
                context.log.verbose("GalleryVideoSplitting: Not a video (type=$mediaType), skipping")
                return@subscribe
            }

            // Get video duration
            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) 
                ?: messageProtoReader.getVarInt(11, 5, 2, 5)?.let { it * 1000 }
            
            context.log.verbose("GalleryVideoSplitting: Duration from proto = $videoDuration ms")
            
            // Fallback to MediaFilePicker
            if (videoDuration == null || videoDuration <= 0) {
                videoDuration = context.feature(MediaFilePicker::class).lastMediaDuration
                context.log.verbose("GalleryVideoSplitting: Duration from MediaFilePicker = $videoDuration ms")
            }

            // Ask for manual input if needed
            if (videoDuration == null || videoDuration <= 0) {
                context.log.verbose("GalleryVideoSplitting: No duration found, asking user")
                event.canceled = true
                context.runOnUiThread {
                    showDurationInputDialog(event, messageProtoReader)
                }
                return@subscribe
            }

            // Check if video needs splitting (>10 seconds)
            if (videoDuration <= 10000) {
                context.log.verbose("GalleryVideoSplitting: Video too short (${videoDuration}ms)")
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms, will split")

            // Cancel original send
            event.canceled = true

            // Show duration configuration dialog
            context.runOnUiThread {
                showDurationDialog(event, videoDuration, messageProtoReader)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun isSameDestination(event1: SendMessageWithContentEvent, event2: SendMessageWithContentEvent): Boolean {
        return event1.destinations.conversations == event2.destinations.conversations &&
               event1.destinations.stories == event2.destinations.stories
    }

    private fun modifyEventForChunk(
        event: SendMessageWithContentEvent, 
        chunkFile: File,
        originalProtoReader: ProtoReader
    ) {
        val localMessageContent = event.messageContent
        val snapDurationMs = convertDuration(customDuration)
        
        val chunkUri = Uri.fromFile(chunkFile)
        
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
        
        // Get extras and hasSound from original
        val extras = originalProtoReader.followPath(3, 3, 13)?.getBuffer()
        val hasSound = originalProtoReader.getVarInt(3, 3, 5, 2, 5) 
            ?: originalProtoReader.getVarInt(11, 5, 2, 5) 
            ?: 1L
        
        // Build SNAP content
        if (localMessageContent.contentType != ContentType.SNAP) {
            localMessageContent.content = ProtoWriter().apply {
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
                            addVarInt(6, 1) // video
                        }
                        from(2) {}
                    }
                    extras?.let { addBuffer(13, it) }
                    from(22) {}
                }
            }.toByteArray()
        }
        
        localMessageContent.contentType = ContentType.SNAP
        
        // Set snap duration
        localMessageContent.content = ProtoEditor(localMessageContent.content!!).apply {
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
                addVarInt(4, 5) // APP_SOURCE_CAMERA
            }
        }.toByteArray()
        
        // Update mLocalMediaReferences
        updateLocalMediaReferences(localMessageContent, chunkUri)
    }

    private fun updateLocalMediaReferences(localMessageContent: Any, chunkUri: Uri) {
        try {
            val messageContentWrapper = MessageContent(localMessageContent.instanceNonNull())
            val localMediaReferencesObj = messageContentWrapper.localMediaReferences
            
            if (localMediaReferencesObj is MutableList<*>) {
                localMediaReferencesObj.clear()
                
                val mediaReferenceClass = context.androidContext.classLoader
                    .loadClass("com.snapchat.client.messaging.LocalMediaReference")
                
                val newReference = mediaReferenceClass.newInstance()
                
                // Convert URI to string and then to ByteArray (same as SendOverride)
                val uriString = chunkUri.toString()
                
                mediaReferenceClass.getDeclaredField("mId").apply {
                    isAccessible = true
                    set(newReference, uriString.toByteArray())
                }
                
                @Suppress("UNCHECKED_CAST")
                (localMediaReferencesObj as MutableList<Any>).add(newReference)
                
                context.log.verbose("GalleryVideoSplitting: Updated mLocalMediaReferences with URI: $uriString")
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to update media references", e)
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
                    text = "Could not detect video duration. Enter duration in seconds:",
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
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { alertDialog.dismiss() }) {
                        Text(context.translation["button.cancel"])
                    }
                    Button(onClick = {
                        val duration = durationInput.toIntOrNull()
                        when {
                            duration == null || duration <= 0 -> {
                                errorMessage = "Enter a valid positive number"
                            }
                            duration <= 10 -> {
                                errorMessage = "Video must be longer than 10 seconds"
                            }
                            else -> {
                                alertDialog.dismiss()
                                showDurationDialog(event, duration * 1000L, messageProtoReader)
                            }
                        }
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

            val estimatedChunks = ((totalDuration / 1000.0) / 10).toInt() + 1

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
                    text = "Duration: ${(totalDuration / 1000.0).toDuration(DurationUnit.SECONDS).toString(DurationUnit.SECONDS, 1)}",
                    fontSize = 14.sp
                )

                Text(
                    text = "Will create ~$estimatedChunks snaps of 10 seconds each",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
                        onValueChange = { customDuration = it },
                        valueRange = -2f..11f,
                    )
                    Text(
                        text = "Snap view duration",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { alertDialog.dismiss() }) {
                        Text(context.translation["button.cancel"])
                    }
                    Button(onClick = {
                        alertDialog.dismiss()
                        
                        context.coroutineScope.launch {
                            val success = splitAndPrepareChunks(event, messageProtoReader)
                            
                            if (success && pendingChunks.isNotEmpty()) {
                                isSplitting = true
                                currentChunkIndex = 0
                                withContext(Dispatchers.Main) {
                                    context.inAppOverlay.showStatusToast(
                                        Icons.Default.Info,
                                        "Sending chunk 1/${pendingChunks.size}..."
                                    )
                                }
                                event.invokeOriginal()
                            }
                        }
                    }) {
                        Text(context.translation["button.send"])
                    }
                }
            }
        }.show()
    }

    private suspend fun splitAndPrepareChunks(
        event: SendMessageWithContentEvent,
        messageProtoReader: ProtoReader
    ): Boolean {
        cleanupChunks()
        
        tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
            }

            // Try multiple approaches to get the video file
            var videoFile: File? = null
            
            // Approach 1: Use intercepted video file if available
            if (interceptedVideoFile?.exists() == true && interceptedVideoFile?.length() ?: 0 > 0) {
                videoFile = interceptedVideoFile
                context.log.verbose("GalleryVideoSplitting: Using intercepted video: ${videoFile?.absolutePath}")
            }
            
            // Approach 2: Check MediaFilePicker
            if (videoFile == null || !videoFile.exists()) {
                val mediaFilePicker = context.feature(MediaFilePicker::class)
                val duration = mediaFilePicker.lastMediaDuration
                if (duration != null) {
                    context.log.verbose("GalleryVideoSplitting: MediaFilePicker has duration: $duration ms")
                    // MediaFilePicker stores stream, not file - we need to use intercepted file
                }
            }

            // Approach 2: Look for cached files in Snapchat's cache directories
            if (videoFile == null || !videoFile.exists()) {
                context.log.verbose("GalleryVideoSplitting: Searching Snapchat cache directories...")
                
                val cacheDirs = listOf(
                    context.mainActivity!!.cacheDir,
                    context.mainActivity!!.externalCacheDir,
                    File(context.mainActivity!!.filesDir, "media_cache"),
                    File(context.mainActivity!!.filesDir, "pending_media")
                )
                
                // Look for recently modified video files
                val recentFiles = cacheDirs.flatMap { dir ->
                    if (dir?.exists() == true) {
                        dir.walkTopDown()
                            .filter { it.isFile && (it.extension == "mp4" || it.extension == "mov") }
                            .filter { System.currentTimeMillis() - it.lastModified() < 60000 } // Within last minute
                            .sortedByDescending { it.lastModified() }
                            .toList()
                    } else emptyList()
                }
                
                if (recentFiles.isNotEmpty()) {
                    videoFile = recentFiles.first()
                    context.log.verbose("GalleryVideoSplitting: Found cached video: ${videoFile.absolutePath}")
                }
            }

            // Approach 3: Try to extract file path from LocalMediaReference object
            if (videoFile == null || !videoFile.exists()) {
                context.log.verbose("GalleryVideoSplitting: Trying to extract file from LocalMediaReference")
                videoFile = extractFileFromMediaReference(event)
            }

            // Approach 4: Try to query Android's MediaStore for the original file
            if (videoFile == null || !videoFile.exists()) {
                context.log.verbose("GalleryVideoSplitting: Trying MediaStore query")
                videoFile = findVideoFromMediaStore()
            }

            // Approach 5: Try accessing Snapchat's content provider with ParcelFileDescriptor
            if (videoFile == null || !videoFile.exists()) {
                context.log.verbose("GalleryVideoSplitting: Trying content provider with ParcelFileDescriptor")
                
                val mediaUriStr = extractMediaUri(event)
                if (mediaUriStr != null) {
                    try {
                        val mediaUri = Uri.parse(mediaUriStr)
                        val snapContext = context.androidContext
                        
                        // Try using ParcelFileDescriptor for better access
                        val pfd = snapContext.contentResolver.openFileDescriptor(mediaUri, "r")
                        if (pfd != null) {
                            videoFile = File(tempDir!!, "input_original.mp4")
                            val inputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                            inputStream.use { input ->
                                videoFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            
                            if (videoFile.exists() && videoFile.length() > 0) {
                                context.log.verbose("GalleryVideoSplitting: Copied via ParcelFileDescriptor (${videoFile.length()} bytes)")
                            } else {
                                videoFile = null
                            }
                        }
                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: ParcelFileDescriptor approach failed", e)
                        videoFile = null
                    }
                }
            }

            // Approach 6: Last resort - try openInputStream with Snapchat's context
            if (videoFile == null || !videoFile.exists()) {
                context.log.verbose("GalleryVideoSplitting: Last resort - openInputStream")
                
                val mediaUriStr = extractMediaUri(event)
                if (mediaUriStr != null) {
                    try {
                        val mediaUri = Uri.parse(mediaUriStr)
                        val snapContext = context.androidContext
                        
                        videoFile = File(tempDir!!, "input_original.mp4")
                        snapContext.contentResolver.openInputStream(mediaUri)?.use { input ->
                            videoFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        if (videoFile?.exists() == true && videoFile.length() > 0) {
                            context.log.verbose("GalleryVideoSplitting: Copied from URI (${videoFile.length()} bytes)")
                        } else {
                            videoFile = null
                        }
                    } catch (e: Exception) {
                        context.log.error("GalleryVideoSplitting: openInputStream failed", e)
                        videoFile = null
                    }
                }
            }

            if (videoFile == null || !videoFile.exists() || videoFile.length() == 0L) {
                context.log.error("GalleryVideoSplitting: Could not locate video file")
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(
                        Icons.Default.WarningAmber,
                        "Cannot find video file. Try using a different video source."
                    )
                }
                return false
            }
            
            originalVideoFile = videoFile
            context.log.verbose("GalleryVideoSplitting: Using video file: ${videoFile.absolutePath} (${videoFile.length()} bytes)")

            // Split with FFmpeg
            val inputPath = videoFile.absolutePath.replace("\\", "/")
            val outputPattern = File(tempDir!!, "split_%03d.mp4").absolutePath.replace("\\", "/")
            
            val command = "-i \"$inputPath\" -c copy -f segment -segment_time 10 -reset_timestamps 1 \"$outputPattern\""
            
            context.log.verbose("GalleryVideoSplitting: FFmpeg command: $command")
            
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                val errorOutput = session.output ?: "No output"
                context.log.error("GalleryVideoSplitting: FFmpeg failed: $errorOutput")
                throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
            }

            val outputFiles = tempDir!!.listFiles()
                ?.filter { it.name.startsWith("split_") && it.name.endsWith(".mp4") }
                ?.sortedBy { it.name } 
                ?: emptyList()
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No output files created")
            }

            context.log.verbose("GalleryVideoSplitting: Created ${outputFiles.size} chunks")

            // Store chunks
            pendingChunks.clear()
            pendingChunks.addAll(outputFiles.map { 
                ChunkData(it, event, messageProtoReader) 
            })

            return true
            
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to split", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.WarningAmber,
                    "Split failed: ${e.message?.take(50)}"
                )
            }
            cleanupChunks()
            return false
        }
    }

    private fun extractMediaUri(event: SendMessageWithContentEvent): String? {
        try {
            val localMessageContent = event.messageContent
            val messageContentWrapper = MessageContent(localMessageContent.instanceNonNull())
            val localMediaReferencesObj = messageContentWrapper.localMediaReferences

            if (localMediaReferencesObj is List<*> && localMediaReferencesObj.isNotEmpty()) {
                val firstRef = localMediaReferencesObj.first() ?: return null
                
                val mIdField = firstRef.javaClass.getDeclaredField("mId")
                mIdField.isAccessible = true
                val mediaIdObj = mIdField.get(firstRef)
                
                if (mediaIdObj is ByteArray) {
                    return String(mediaIdObj)
                }
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: URI extraction failed", e)
        }
        
        return null
    }

    private fun extractFileFromMediaReference(event: SendMessageWithContentEvent): File? {
        try {
            val localMessageContent = event.messageContent
            val messageContentWrapper = MessageContent(localMessageContent.instanceNonNull())
            val localMediaReferencesObj = messageContentWrapper.localMediaReferences

            if (localMediaReferencesObj is List<*> && localMediaReferencesObj.isNotEmpty()) {
                val firstRef = localMediaReferencesObj.first() ?: return null
                
                // Log all fields to find where the file path might be stored
                context.log.verbose("GalleryVideoSplitting: Examining LocalMediaReference fields:")
                firstRef.javaClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    try {
                        val value = field.get(firstRef)
                        context.log.verbose("  ${field.name} (${field.type.simpleName}): $value")
                        
                        // Check if any field contains a file path
                        when (value) {
                            is String -> {
                                if (value.startsWith("/") || value.contains("file://")) {
                                    val potentialFile = File(value.replace("file://", ""))
                                    if (potentialFile.exists() && potentialFile.length() > 0) {
                                        context.log.verbose("GalleryVideoSplitting: Found file via field ${field.name}: ${potentialFile.absolutePath}")
                                        return potentialFile
                                    }
                                }
                            }
                            is File -> {
                                if (value.exists() && value.length() > 0) {
                                    context.log.verbose("GalleryVideoSplitting: Found File object: ${value.absolutePath}")
                                    return value
                                }
                            }
                            is Uri -> {
                                if (value.scheme == "file") {
                                    val file = File(value.path ?: return@forEach)
                                    if (file.exists() && file.length() > 0) {
                                        context.log.verbose("GalleryVideoSplitting: Found file via Uri: ${file.absolutePath}")
                                        return file
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        context.log.verbose("  ${field.name}: <error accessing>")
                    }
                }
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: File extraction failed", e)
        }
        
        return null
    }

    private fun findVideoFromMediaStore(): File? {
        try {
            val projection = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DATA,
                android.provider.MediaStore.Video.Media.DATE_MODIFIED
            )
            
            val cursor = context.mainActivity!!.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${android.provider.MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)
                    val dateIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATE_MODIFIED)
                    
                    val filePath = it.getString(dataIndex)
                    val dateModified = it.getLong(dateIndex)
                    
                    // Check if video was modified in last 2 minutes
                    if (System.currentTimeMillis() / 1000 - dateModified < 120) {
                        val file = File(filePath)
                        if (file.exists() && file.length() > 0) {
                            context.log.verbose("GalleryVideoSplitting: Found via MediaStore: ${file.absolutePath}")
                            return file
                        }
                    }
                }
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: MediaStore query failed", e)
        }
        
        return null
    }

    private fun cleanupChunks() {
        pendingChunks.clear()
        currentChunkIndex = 0
        isSplitting = false
        
        tempDir?.let { dir ->
            try {
                dir.deleteRecursively()
                context.log.verbose("GalleryVideoSplitting: Cleaned up temp directory")
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Cleanup failed", e)
            }
        }
        tempDir = null
        originalVideoFile = null
        
        context.runOnUiThread {
            context.inAppOverlay.showStatusToast(
                Icons.Default.Info,
                "All chunks sent!"
            )
        }
    }
}
