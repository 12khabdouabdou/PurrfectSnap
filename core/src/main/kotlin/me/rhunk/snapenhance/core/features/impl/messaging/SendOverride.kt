package me.rhunk.snapenhance.core.features.impl.messaging

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import me.rhunk.snapenhance.core.event.events.impl.MediaUploadEvent
import me.rhunk.snapenhance.core.event.events.impl.NativeUnaryCallEvent
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.experiments.MediaFilePicker
import me.rhunk.snapenhance.core.messaging.MessageSender
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
import java.io.File
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration


class SendOverride : Feature("Send Override") {
    private var selectedType by mutableStateOf("SNAP")
    private var customDuration by mutableFloatStateOf(10f)
    
    @Volatile
    private var isSplitting = false

    @OptIn(ExperimentalLayoutApi::class)
    override fun init() {
        val stripMediaMetadata = context.config.messaging.stripMediaMetadata.get()
        var postSavePolicy: Int? = null

        val configOverrideType = context.config.messaging.galleryMediaSendOverride.getNullable()
        val enableVideoSplitting = context.config.messaging.splitVideoIntoTenSecondSnaps.get()
        
        if (configOverrideType == null && stripMediaMetadata.isEmpty() && !enableVideoSplitting) return

        context.event.subscribe(MediaUploadEvent::class) { event ->
            ProtoReader(event.localMessageContent.content!!).followPath(11, 5)?.let { snapDocPlayback ->
                event.onMediaUploaded { result ->
                    result.messageContent.content = ProtoEditor(result.messageContent.content!!).apply {
                        edit(11, 5) {
                            edit(1) {
                                edit(1) {
                                    snapDocPlayback.getVarInt(2, 99)?.let { customDuration ->
                                        remove(15)
                                        addVarInt(15, customDuration)
                                    }
                                    remove(27)
                                    remove(26)
                                    addBuffer(26, byteArrayOf())
                                }
                            }

                            // set back the original snap duration
                            snapDocPlayback.getByteArray(2)?.let {
                                val originalHasSound = firstOrNull(2)?.toReader()?.getVarInt(5)
                                remove(2)
                                addBuffer(2, it)

                                originalHasSound?.let { hasSound ->
                                    edit(2) {
                                        remove(5)
                                        addVarInt(5, hasSound)
                                    }
                                }
                            }
                        }

                        if (stripMediaMetadata.isNotEmpty()) {
                            when (result.messageContent.contentType) {
                                ContentType.SNAP, ContentType.EXTERNAL_MEDIA -> {
                                    edit(*(if (result.messageContent.contentType == ContentType.SNAP) intArrayOf(11) else intArrayOf(3, 3))) {
                                        if (stripMediaMetadata.contains("hide_caption_text")) {
                                            edit(5) {
                                                editEach(1) {
                                                    remove(2)
                                                }
                                            }
                                        }
                                        if (stripMediaMetadata.contains("hide_snap_filters")) {
                                            remove(9)
                                            remove(11)
                                        }
                                        if (stripMediaMetadata.contains("hide_extras")) {
                                            remove(13)
                                            edit(5, 1) {
                                                remove(2)
                                            }
                                        }
                                    }
                                }
                                ContentType.NOTE -> {
                                    if (stripMediaMetadata.contains("remove_audio_note_duration")) {
                                        edit(6, 1, 1) {
                                            remove(13)
                                        }
                                    }
                                    if (stripMediaMetadata.contains("remove_audio_note_transcript_capability")) {
                                        edit(6, 1) {
                                            remove(3)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }

                        edit(11, 5, 2) {
                            remove(99)
                        }
                    }.toByteArray()
                }
            }
        }

        if (configOverrideType == null && !enableVideoSplitting) return

        context.event.subscribe(NativeUnaryCallEvent::class) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
            postSavePolicy?.let { savePolicy ->
                context.log.verbose("postSavePolicy=$savePolicy")
                event.buffer = ProtoEditor(event.buffer).apply {
                    edit(4) {
                        remove(7)
                        addVarInt(7, savePolicy)
                    }

                    // remove Keep Snaps in Chat ability
                    if (savePolicy == 1/* PROHIBITED */) {
                        edit(6, 9) {
                            remove(1)
                        }
                    }
                }.toByteArray()
            }
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            if (isSplitting) {
                context.log.verbose("Already splitting, allowing send through")
                return@subscribe
            }
            
            postSavePolicy = null
            
            // === EXTENSIVE DEBUG LOGGING ===
            context.log.verbose("\n" + "=".repeat(80))
            context.log.verbose("SendMessageWithContentEvent FIRED")
            context.log.verbose("=".repeat(80))
            
            val localMessageContent = event.messageContent
            context.log.verbose("Content Type: ${localMessageContent.contentType}")
            context.log.verbose("Content Type Name: ${localMessageContent.contentType.name}")
            
            // Log ALL instance fields
            context.log.verbose("\n--- Message Content Instance Fields ---")
            runCatching {
                val instance = localMessageContent.instanceNonNull()
                val fields = instance.javaClass.declaredFields
                context.log.verbose("Found ${fields.size} fields in ${instance.javaClass.name}")
                
                for (field in fields) {
                    field.isAccessible = true
                    val value = field.get(instance)
                    context.log.verbose("  ${field.name} (${field.type.simpleName}) = $value")
                    
                    // If value is an object, inspect its fields too
                    if (value != null && !field.type.isPrimitive && !field.type.name.startsWith("java.lang")) {
                        context.log.verbose("    └─ Inspecting ${value.javaClass.simpleName}:")
                        runCatching {
                            val subFields = value.javaClass.declaredFields
                            for (subField in subFields.take(10)) { // Limit to first 10
                                subField.isAccessible = true
                                val subValue = subField.get(value)
                                context.log.verbose("      ${subField.name} = $subValue")
                                
                                if (subValue.toString().contains("content://")) {
                                    context.log.verbose("      *** URI FOUND IN ${field.name}.${subField.name} ***")
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                context.log.error("Failed to inspect message content", it)
            }
            
            // Log destinations
            context.log.verbose("\n--- Destinations ---")
            context.log.verbose("Conversations: ${event.destinations.conversations?.size ?: 0}")
            context.log.verbose("Stories: ${event.destinations.stories?.size ?: 0}")
            
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) return@subscribe
            val localMessageContent = event.messageContent
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) return@subscribe

            //prevent story replies
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            // Check if video splitting is enabled and video is >10s
            if (enableVideoSplitting && localMessageContent.contentType == ContentType.EXTERNAL_MEDIA) {
                var videoUri: Uri? = null
                
                context.log.verbose("=== Attempting to extract video URI ===")
                
                // Method 1: Check all proto paths
                val protoPaths = listOf(
                    intArrayOf(3, 3, 3),
                    intArrayOf(3, 3, 10),
                    intArrayOf(3, 3, 1),
                    intArrayOf(3, 1),
                )
                
                for (path in protoPaths) {
                    val uriStr = messageProtoReader.getString(*path)
                    context.log.verbose("Proto path ${path.contentToString()}: $uriStr")
                    if (!uriStr.isNullOrEmpty() && uriStr.startsWith("content://")) {
                        videoUri = Uri.parse(uriStr)
                        context.log.verbose("✓ Found URI via proto path ${path.contentToString()}")
                        break
                    }
                }
                
                // Method 2: Inspect external metadata object fields
                if (videoUri == null) {
                    localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata")?.let { metadata ->
                        context.log.verbose("Inspecting external metadata object...")
                        runCatching {
                            val fields = metadata.javaClass.declaredFields
                            for (field in fields) {
                                field.isAccessible = true
                                val value = field.get(metadata)
                                
                                if (value is Uri && value.toString().startsWith("content://")) {
                                    videoUri = value
                                    context.log.verbose("✓ Found URI in metadata field: ${field.name} = $videoUri")
                                    break
                                } else if (value is String && value.startsWith("content://")) {
                                    videoUri = Uri.parse(value)
                                    context.log.verbose("✓ Found URI string in metadata field: ${field.name} = $videoUri")
                                    break
                                }
                            }
                        }.onFailure {
                            context.log.error("Failed to inspect external metadata", it)
                        }
                    }
                }
                
                // Method 3: Inspect message content instance fields
                if (videoUri == null) {
                    context.log.verbose("Inspecting message content object...")
                    runCatching {
                        val fields = localMessageContent.instanceNonNull().javaClass.declaredFields
                        for (field in fields) {
                            field.isAccessible = true
                            val value = field.get(localMessageContent.instanceNonNull())
                            
                            if (value is Uri && value.toString().startsWith("content://")) {
                                videoUri = value
                                context.log.verbose("✓ Found URI in content field: ${field.name} = $videoUri")
                                break
                            } else if (value is String && value.startsWith("content://")) {
                                videoUri = Uri.parse(value)
                                context.log.verbose("✓ Found URI string in content field: ${field.name} = $videoUri")
                                break
                            }
                        }
                    }.onFailure {
                        context.log.error("Failed to inspect message content", it)
                    }
                }
                
                context.log.verbose("Final video URI: $videoUri")
                
                if (videoUri != null) {
                    // Get video info
                    val (videoDurationMs, isVideo) = runCatching {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(context.androidContext, videoUri)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        retriever.release()
                        
                        context.log.verbose("Media info: duration=${duration}ms, mimeType=$mimeType")
                        
                        Pair(duration, mimeType?.startsWith("video/") == true)
                    }.getOrElse { 
                        context.log.error("Failed to read video metadata", it)
                        Pair(0L, false)
                    }
                    
                    if (isVideo && videoDurationMs > 10000) {
                        context.log.verbose("✓ Video >10s detected (${videoDurationMs}ms), initiating split")
                        
                        // Cancel original send and split
                        event.canceled = true
                        context.coroutineScope.launch {
                            isSplitting = true
                            splitAndSendVideo(videoUri.toString(), videoDurationMs, event)
                        }
                        return@subscribe
                    } else {
                        context.log.verbose("Video ≤10s or not a video (duration=${videoDurationMs}ms, isVideo=$isVideo)")
                    }
                } else {
                    context.log.warn("✗ Could not extract video URI from any source")
                    context.log.verbose("Proto structure dump:")
                    context.log.verbose(messageProtoReader.toString())
                }
            }

            event.canceled = true

            fun sendMedia(overrideType: String, snapDurationMs: Int?): Boolean {
                if (overrideType != "ORIGINAL" && (messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Default.WarningAmber,
                        context.translation["gallery_media_send_override.multiple_media_toast"]
                    )
                    return false
                }

                when (overrideType) {
                    "SNAP", "SAVEABLE_SNAP" -> {
                        postSavePolicy = if (overrideType == "SAVEABLE_SNAP") 3 /* VIEW_SESSION */ else 1 /* PROHIBITED */

                        val extras = messageProtoReader.followPath(3, 3, 13)?.getBuffer()

                        if (localMessageContent.contentType != ContentType.SNAP) {
                            localMessageContent.content = ProtoWriter().apply {
                                from(11) {
                                    from(5) {
                                        from(1) {
                                            from(1) {
                                                addVarInt(2, 0)
                                                addVarInt(12, 0)
                                                addVarInt(15, 0)
                                            }
                                            addVarInt(6, 1)
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

                        localMessageContent.contentType = ContentType.SNAP
                        localMessageContent.content = ProtoEditor(localMessageContent.content!!).apply {
                            edit(11, 5, 2) {
                                arrayOf(6, 7, 8).forEach { remove(it) }
                                addVarInt(5, messageProtoReader.getVarInt(3, 3, 5, 2, 5) ?: messageProtoReader.getVarInt(11, 5, 2, 5) ?: 1)
                                // set snap duration
                                if (snapDurationMs != null) {
                                    addVarInt(8, snapDurationMs / 1000)
                                    if (snapDurationMs / 1000 <= 0) {
                                        addVarInt(99, snapDurationMs)
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
                    }
                    "NOTE" -> {
                        localMessageContent.contentType = ContentType.NOTE
                        localMessageContent.content =
                            MessageSender.audioNoteProto(
                                messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: context.feature(MediaFilePicker::class).lastMediaDuration ?: 0,
                                Locale.getDefault().toLanguageTag()
                            )
                    }
                }

                return true
            }

            if (configOverrideType != null && configOverrideType != "always_ask") {
                if (sendMedia(configOverrideType, 10)) {
                    event.invokeOriginal()
                }
                return@subscribe
            }

            if (configOverrideType == "always_ask") {
                context.runOnUiThread {
                    createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                        val mainTranslation = remember {
                            context.translation.getCategory("send_override_dialog")
                        }

                        @Composable
                        fun ActionTile(
                            modifier: Modifier = Modifier,
                            selected: Boolean = false,
                            icon: ImageVector,
                            title: String,
                            onClick: () -> Unit
                        ) {
                            Card(
                                modifier = modifier,
                                onClick = onClick,
                                elevation = if (selected) CardDefaults.elevatedCardElevation(disabledElevation = 3.dp) else CardDefaults.cardElevation(),
                                colors = if (selected) CardDefaults.elevatedCardColors() else CardDefaults.cardColors()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .size(75.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(icon, contentDescription = title, modifier = Modifier
                                        .size(32.dp)
                                        .padding(4.dp))
                                    Text(title, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp, fontWeight = FontWeight.Light, softWrap = true, lineHeight = 14.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val translation = remember {
                                context.translation.getCategory("features.options.gallery_media_send_override")
                            }

                            Text(fontSize = 20.sp, fontWeight = FontWeight.Medium, text = "Send as ${
                                translation[selectedType]}", modifier = Modifier.padding(5.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ActionTile(selected = selectedType == "ORIGINAL", icon = Icons.Filled.Photo, title =
                                translation["ORIGINAL"]) {
                                    selectedType = "ORIGINAL"
                                }
                                ActionTile(selected = selectedType == "SNAP" || selectedType == "SAVEABLE_SNAP", icon = Icons.Filled.PhotoCamera, title = translation["SNAP"]) {
                                    selectedType = "SNAP"
                                }
                                ActionTile(selected = selectedType == "NOTE", icon = Icons.Filled.MusicNote, title = translation["NOTE"]) {
                                    selectedType = "NOTE"
                                }
                            }

                            fun convertDuration(duration: Float): Int? {
                                return when  {
                                    duration in -2f..-1f -> 100
                                    duration in -1f..-0f -> 250
                                    duration in -0f..1f -> 500
                                    duration >= 11f -> null
                                    else -> ((duration * 1000).toInt() / 1000) * 1000
                                }
                            }

                            when (selectedType) {
                                "SNAP", "SAVEABLE_SNAP" -> {
                                    fun toggleSaveable() {
                                        selectedType = if (selectedType == "SAVEABLE_SNAP") "SNAP" else "SAVEABLE_SNAP"
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            toggleSaveable()
                                        },
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ){
                                        Checkbox(
                                            checked = selectedType == "SAVEABLE_SNAP",
                                            onCheckedChange = {
                                                toggleSaveable()
                                            }
                                        )
                                        Text(text = mainTranslation["saveable_snap_hint"], lineHeight = 15.sp)
                                    }
                                    Column(
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text(
                                            text = mainTranslation.format("duration",
                                                "duration" to (convertDuration(customDuration)?.toDuration(DurationUnit.MILLISECONDS)?.toString(DurationUnit.SECONDS, 2) ?: mainTranslation["unlimited_duration"])
                                            )
                                        )
                                        Slider(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = selectedType != "SAVEABLE_SNAP",
                                            value = customDuration,
                                            onValueChange = {
                                                customDuration = it
                                            },
                                            valueRange = -2f..11f,
                                        )
                                    }
                                }
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
                                    if (sendMedia(selectedType, if (selectedType != "SAVEABLE_SNAP" ) convertDuration(customDuration) else null)) {
                                        event.invokeOriginal()
                                    }
                                }) {
                                    Text(context.translation["button.send"])
                                }
                            }
                        }
                    }.show()
                }
            }
        }
    }

    private suspend fun splitAndSendVideo(contentUriStr: String, originalDurationMs: Long, originalEvent: SendMessageWithContentEvent) {
        context.log.verbose("=== splitAndSendVideo START ===")
        context.log.verbose("URI: $contentUriStr, Duration: ${originalDurationMs}ms")
        
        val tempDir = File(context.mainActivity!!.cacheDir, "split_video_${System.currentTimeMillis()}").apply { mkdirs() }
        
        try {
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Splitting ${originalDurationMs/1000}s video..."
                )
            }

            val mediaUri = Uri.parse(contentUriStr)
            val cachedVideo = File(tempDir, "input.mp4")

            context.mainActivity!!.contentResolver.openInputStream(mediaUri)?.use { input ->
                cachedVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open input stream")

            context.log.verbose("Cached video: ${cachedVideo.length()} bytes")

            val command = "-i \"${cachedVideo.absolutePath}\" -c copy -f segment -segment_time 10 -reset_timestamps 1 -break_non_keyframes 1 \"${tempDir.absolutePath}/split_%03d.mp4\""
            context.log.verbose("Executing FFmpeg: $command")
            
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed: ${session.returnCode}")
            }

            val outputFiles = tempDir.listFiles()?.filter { it.name.startsWith("split_") }?.sortedBy { it.name } ?: emptyList()
            context.log.verbose("Generated ${outputFiles.size} split files")
            
            if (outputFiles.isEmpty()) {
                throw IllegalStateException("No output files generated")
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Info, 
                    "Sending ${outputFiles.size} clips...",
                    durationMs = 2000
                )
            }

            // Send each clip
            for ((index, file) in outputFiles.withIndex()) {
                context.log.verbose("Sending clip ${index + 1}/${outputFiles.size}")
                
                val chunkUri = Uri.fromFile(file)
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context.androidContext, chunkUri)
                    val chunkDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val chunkWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 1080L
                    val chunkHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 1920L
                    
                    context.log.verbose("Chunk: ${chunkDuration}ms, ${chunkWidth}x${chunkHeight}")

                    // Rebuild proto with new chunk data
                    originalEvent.messageContent.content = ProtoEditor(originalEvent.messageContent.content!!).apply {
                        edit(3, 3) {
                            remove(3)
                            addString(3, chunkUri.toString())
                        }
                        
                        edit(3, 3, 5, 1, 1) {
                            remove(15)
                            addVarInt(15, chunkDuration)
                            remove(12)
                            addVarInt(12, chunkWidth)
                            remove(13)
                            addVarInt(13, chunkHeight)
                        }
                    }.toByteArray()

                    // Update external metadata URI
                    originalEvent.messageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata")?.let { metadata ->
                        runCatching {
                            metadata.javaClass.getDeclaredField("mContentUri").apply {
                                isAccessible = true
                                set(metadata, chunkUri)
                            }
                        }
                    }

                    // Send this chunk
                    originalEvent.invokeOriginal()
                    context.log.verbose("✓ Sent clip ${index + 1}")
                    
                    if (index < outputFiles.size - 1) {
                        delay(1000)
                    }
                } finally {
                    retriever.release()
                }
            }

            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.CheckCircle, 
                    "Sent ${outputFiles.size} clips!"
                )
            }
        } catch (e: Exception) {
            context.log.error("Failed to split video", e)
            withContext(Dispatchers.Main) {
                context.inAppOverlay.showStatusToast(
                    Icons.Default.Error, 
                    "Failed: ${e.message}"
                )
            }
        } finally {
            tempDir.deleteRecursively()
            isSplitting = false
            context.log.verbose("=== splitAndSendVideo END ===")
        }
    }
}
