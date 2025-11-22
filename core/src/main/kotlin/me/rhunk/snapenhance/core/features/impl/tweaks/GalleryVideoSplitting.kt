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
    
    // Queue to hold pending chunks
    private val pendingChunks = mutableListOf<File>()
    private var currentChunkIndex = 0
    private var tempDir: File? = null

    override fun init() {
        context.log.verbose("GalleryVideoSplitting: Initializing...")
        
        if (!context.config.messaging.splitVideoIntoTenSecondSnaps.get()) {
            context.log.verbose("GalleryVideoSplitting: Feature disabled in config")
            return
        }
        
        // Cleanup orphaned temp directories from previous sessions
        context.coroutineScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = context.androidContext.cacheDir
                val splitDirs = cacheDir.listFiles { file ->
                    file.isDirectory && file.name.startsWith("split_video_")
                } ?: emptyArray()
                
                splitDirs.forEach { dir ->
                    context.log.verbose("GalleryVideoSplitting: Cleaning up orphaned directory: ${dir.name}")
                    dir.deleteRecursively()
                }
                
                if (splitDirs.isNotEmpty()) {
                    context.log.verbose("GalleryVideoSplitting: Cleaned up ${splitDirs.size} orphaned directories")
                }
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Failed to cleanup orphaned directories", e)
            }
        }
        
        // Check FFmpeg availability
        // FFmpeg is now handled by the Manager app via Bridge

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            context.log.verbose("GalleryVideoSplitting: SendMessageWithContentEvent triggered")
            
            // Handle pending chunks first (modify and let it send like SendOverride does)
            if (isSplitting && pendingChunks.isNotEmpty() && currentChunkIndex < pendingChunks.size) {
                val chunkFile = pendingChunks[currentChunkIndex]
                context.log.verbose("GalleryVideoSplitting: Processing chunk ${currentChunkIndex + 1}/${pendingChunks.size}")
                
                // Modify the event's message content to use this chunk
                modifyEventForChunk(event, chunkFile)
                
                currentChunkIndex++
                
                // Schedule next chunk or cleanup
                if (currentChunkIndex < pendingChunks.size) {
                    context.coroutineScope.launch {
                        delay(1500)
                        withContext(Dispatchers.Main) {
                            if (context.config.messaging.showSplittingToast.get()) {
                                context.inAppOverlay.showStatusToast(
                                    Icons.Default.Info,
                                    "Sending chunk ${currentChunkIndex + 1}/${pendingChunks.size}..."
                                )
                            }
                        }
                    }
                } else {
                    // All chunks sent, cleanup
                    context.coroutineScope.launch {
                        delay(2000) // Wait a bit before cleanup
                        cleanupChunks()
                    }
                }
                
                // Let the modified event proceed (like SendOverride does)
                return@subscribe
            }
            
            if (isSplitting) {
                context.log.verbose("GalleryVideoSplitting: Already splitting, skipping")
                return@subscribe
            }
            
            // Same checks as SendOverride
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) {
                context.log.verbose("GalleryVideoSplitting: Story detected, skipping")
                return@subscribe
            }
            
            val localMessageContent = event.messageContent
            context.log.verbose("GalleryVideoSplitting: Content type = ${localMessageContent.contentType}")
            
            // Only process EXTERNAL_MEDIA (same check as SendOverride)
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

            // Check for multiple media (same as SendOverride)
            val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
            context.log.verbose("GalleryVideoSplitting: Media count = $mediaCount")
            if (mediaCount != 1) {
                context.log.verbose("GalleryVideoSplitting: Media count is not 1, skipping")
                return@subscribe
            }

            // Check if it's a video by checking media type (1 = VIDEO)
            // If the field doesn't exist, we'll still try to process based on duration
            val mediaType = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 6)
            context.log.verbose("GalleryVideoSplitting: Media type = $mediaType")
            
            // If media type exists and it's NOT a video, skip
            if (mediaType != null && mediaType != 1L) {
                context.log.verbose("GalleryVideoSplitting: Not a video (type=$mediaType), skipping")
                return@subscribe
            }

            // Get video duration using the same pattern as SendOverride
            // Try path 3,3,5,1,1,15 first, then 11,5,2,5, then MediaFilePicker
            var videoDuration = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) 
                ?: messageProtoReader.getVarInt(11, 5, 2, 5)?.let { it * 1000 } // Convert seconds to ms if found
            
            context.log.verbose("GalleryVideoSplitting: Duration from proto = $videoDuration ms")
            
            // Fallback to MediaFilePicker if not found in proto
            if (videoDuration == null || videoDuration <= 0) {
                videoDuration = context.feature(MediaFilePicker::class).lastMediaDuration
                context.log.verbose("GalleryVideoSplitting: Duration from MediaFilePicker = $videoDuration ms")
            }

            // If still no duration, ask user to input manually
            if (videoDuration == null || videoDuration <= 0) {
                context.log.verbose("GalleryVideoSplitting: No duration found, asking user for input")
                event.canceled = true
                context.runOnUiThread {
                    showDurationInputDialog(event, messageProtoReader)
                }
                return@subscribe
            }

            // Validate video duration (max 5 minutes)
            val MAX_DURATION_MS = 5 * 60 * 1000L // 5 minutes
            if (videoDuration > MAX_DURATION_MS) {
                context.log.verbose("GalleryVideoSplitting: Video too long (${videoDuration}ms > ${MAX_DURATION_MS}ms), rejecting")
                event.canceled = true
                context.runOnUiThread {
                    context.inAppOverlay.showStatusToast(
                        Icons.Default.WarningAmber,
                        "Video too long (max 5 minutes)"
                    )
                }
                return@subscribe
            }

            // Check if video needs splitting (>10 seconds)
            if (videoDuration <= 10000) {
                context.log.verbose("GalleryVideoSplitting: Video too short (${videoDuration}ms), not splitting")
                return@subscribe
            }

            context.log.verbose("GalleryVideoSplitting: Video is ${videoDuration}ms (> 10s), will split")

            // Cancel the original send (like SendOverride does)
            event.canceled = true

            // Show duration dialog
            context.runOnUiThread {
                showDurationDialog(event, videoDuration, messageProtoReader)
            }
        }
        
        context.log.verbose("GalleryVideoSplitting: Initialization complete")
    }

    private fun modifyEventForChunk(event: SendMessageWithContentEvent, chunkFile: File) {
        val localMessageContent = event.messageContent
        val snapDurationMs = convertDuration(customDuration)
        
        // Get the chunk URI
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
        
        // Get extras and hasSound from original proto (same as SendOverride)
        val originalProtoReader = ProtoReader(localMessageContent.content ?: byteArrayOf())
        val extras = originalProtoReader.followPath(3, 3, 13)?.getBuffer()
        val hasSound = originalProtoReader.getVarInt(3, 3, 5, 2, 5) 
            ?: originalProtoReader.getVarInt(11, 5, 2, 5) 
            ?: 1L
        
        // Build SNAP content (exactly like SendOverride does)
        if (localMessageContent.contentType != ContentType.SNAP) {
            localMessageContent.content = ProtoWriter().apply {
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
                    extras?.let {
                        addBuffer(13, it)
                    }
                    from(22) {}
                }
            }.toByteArray()
        }
        
        // Change content type to SNAP (like SendOverride)
        localMessageContent.contentType = ContentType.SNAP
        
        // Use ProtoEditor to set snap duration (exact same pattern as SendOverride)
        localMessageContent.content = ProtoEditor(localMessageContent.content!!).apply {
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
        
        // Update mLocalMediaReferences with the chunk URI
        val messageContentWrapper = MessageContent(localMessageContent.instanceNonNull())
        val localMediaReferencesObj = messageContentWrapper.getObjectFieldOrNull("mLocalMediaReferences")
        
        if (localMediaReferencesObj is MutableList<*>) {
            try {
                localMediaReferencesObj.clear()
                
                // Get the class type for media reference
                val mediaReferenceClass = context.androidContext.classLoader
                    .loadClass("com.snapchat.client.messaging.LocalMediaReference")
                
                val newReference = mediaReferenceClass.newInstance()
                
                // Set the mId field with chunk URI as bytes
                mediaReferenceClass.getDeclaredField("mId").apply {
                    isAccessible = true
                    set(newReference, chunkUri.toString().toByteArray())
                }
                
                @Suppress("UNCHECKED_CAST")
                (localMediaReferencesObj as MutableList<Any>).add(newReference)
                
                context.log.verbose("GalleryVideoSplitting: Updated mLocalMediaReferences with chunk URI: $chunkUri")
            } catch (e: Exception) {
                context.log.error("GalleryVideoSplitting: Failed to update mLocalMediaReferences", e)
            }
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
                        
                        // Start async splitting process
                        context.coroutineScope.launch {
                            val success = splitAndPrepareChunks(event, messageProtoReader)
                            
                            if (success && pendingChunks.isNotEmpty()) {
                                isSplitting = true
                                currentChunkIndex = 0
                                withContext(Dispatchers.Main) {
                                    if (context.config.messaging.showSplittingToast.get()) {
                                        context.inAppOverlay.showStatusToast(
                                            Icons.Default.Info,
                                            "Sending chunk 1/${pendingChunks.size}..."
                                        )
                                    }
                                }
                                // Trigger the first send by invoking original
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

    private fun getMediaUri(event: SendMessageWithContentEvent): Uri? {
        try {
            // Extract content URI from mLocalMediaReferences (Java object field, NOT protobuf)
            val localMessageContent = event.messageContent
            
            // Access mLocalMediaReferences directly using reflection (bypassing MessageContent wrapper)
            val mLocalMediaReferencesField = localMessageContent.instanceNonNull().javaClass.getDeclaredField("mLocalMediaReferences")
            mLocalMediaReferencesField.isAccessible = true
            val localMediaReferencesObj = mLocalMediaReferencesField.get(localMessageContent.instanceNonNull())

            if (localMediaReferencesObj is List<*>) {
                if (localMediaReferencesObj.isNotEmpty()) {
                    val firstRef = localMediaReferencesObj.first()
                    
                    // Try to get mId field
                    val mIdField = firstRef?.javaClass?.getDeclaredField("mId")
                    if (mIdField != null) {
                        mIdField.isAccessible = true
                        val mediaIdObj = mIdField.get(firstRef)
                        
                        if (mediaIdObj is ByteArray) {
                            return Uri.parse(String(mediaIdObj))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to extract media URI", e)
        }
        return null
    }

    private fun getVideoDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context.androidContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
        } catch (e: Exception) {
            context.log.error("GalleryVideoSplitting: Failed to get video duration", e)
            0
        } finally {
            retriever.release()
        }
    }

    private suspend fun splitAndPrepareChunks(
        event: SendMessageWithContentEvent,
        messageProtoReader: ProtoReader
    ): Boolean {
        tempDir = File(context.androidContext.cacheDir, "split_video_${System.currentTimeMillis()}").apply { 
            mkdirs()
        }
        
        try {
            withContext(Dispatchers.Main) {
                if (context.config.messaging.showSplittingToast.get()) {
                    context.inAppOverlay.showStatusToast(Icons.Default.Info, "Splitting video...")
                }
            }

            val mediaUri = getMediaUri(event)
            if (mediaUri == null) {
                context.log.error("GalleryVideoSplitting: Could not extract media URI")
                withContext(Dispatchers.Main) {
                    context.inAppOverlay.showStatusToast(
                        Icons.Default.WarningAmber, 
                        "Failed to get video URI. Check logs for details."
                    )
                }
                return false
            }

            context.log.verbose("GalleryVideoSplitting: Using media URI: $mediaUri")
            
            // Open PFD for the input video
            val inputPfd = context.androidContext.contentResolver.openFileDescriptor(mediaUri, "r")
            if (inputPfd == null) {
                context.log.error("GalleryVideoSplitting: Failed to open PFD for URI: $mediaUri")
                return false
            }

            context.log.verbose("GalleryVideoSplitting: Video opened, requesting split from Bridge...")

            // Request split via Bridge
            val splitPfds = context.bridgeClient.splitMedia(inputPfd, "mp4", 10)
            inputPfd.close()

            if (splitPfds.isEmpty()) {
                throw IllegalStateException("Bridge returned no split files")
            }

            context.log.verbose("GalleryVideoSplitting: Bridge returned ${splitPfds.size} chunks")

            // Save PFDs to local temp files
            pendingChunks.clear()
            splitPfds.forEachIndexed { index, pfd ->
                val chunkFile = File(tempDir!!, "split_$index.mp4")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    chunkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                pendingChunks.add(chunkFile)
            }

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
            if (context.config.messaging.showSplittingToast.get()) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info,
                    "All chunks sent successfully!"
                )
            }
        }
    }
}
