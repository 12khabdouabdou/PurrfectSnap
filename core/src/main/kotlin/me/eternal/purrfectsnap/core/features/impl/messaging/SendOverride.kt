package me.eternal.purrfectsnap.core.features.impl.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import me.eternal.purrfectsnap.bridge.task.TaskListener
import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.ui.createComposeAlertDialog
import me.eternal.purrfectsnap.common.util.protobuf.ProtoEditor
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.common.util.protobuf.ProtoWriter
import me.eternal.purrfectsnap.core.event.events.impl.MediaUploadEvent
import me.eternal.purrfectsnap.core.event.events.impl.NativeUnaryCallEvent
import me.eternal.purrfectsnap.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfectsnap.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.features.impl.experiments.MediaFilePicker
import me.eternal.purrfectsnap.core.messaging.MessageSender
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayPalette
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayTheme
import me.eternal.purrfectsnap.core.wrapper.impl.MessageContent
import me.eternal.purrfectsnap.core.wrapper.impl.MessageDestinations
import me.eternal.purrfectsnap.core.util.ktx.getObjectFieldOrNull
import me.eternal.purrfectsnap.core.util.ktx.setObjectField
import me.eternal.purrfectsnap.core.util.CallbackBuilder
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.Hooker
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import me.eternal.purrfectsnap.mapper.impl.CallbackMapper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalMaterial3Api::class)
class SendOverride : Feature("Send Override") {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "scheduled_send"
        private val internalMultipartSend = ThreadLocal.withInitial { false }
    }
    
    private var selectedType by mutableStateOf("SNAP")
    private var disableSplitForCurrentSend by mutableStateOf(false)
    private var customDuration by mutableFloatStateOf(10f)
    private var scheduledTime by mutableStateOf<Long?>(null)
    private var showClockPicker by mutableStateOf(false)
    private var clockPickerHour by mutableIntStateOf(12)
    private var clockPickerMinute by mutableIntStateOf(0)
    private var notificationIdCounter = 1000
    private val backgroundHookLock = Any()
    private var backgroundHookRefs = 0
    private var backgroundHooks: List<Hooker.HookHandle>? = null

    private fun extractMediaDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context.androidContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.recoverCatching {
            context.androidContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    private fun acquireScheduledSendBackground(): () -> Unit {
        if (!context.config.messaging.scheduledSendAllowRunningInBackground.get()) return {}
        var enableFailed = false
        synchronized(backgroundHookLock) {
            backgroundHookRefs++
            if (backgroundHookRefs == 1) {
                if (!enableScheduledSendBackgroundLocked()) {
                    backgroundHookRefs--
                    enableFailed = true
                }
            }
        }
        if (enableFailed) return {}
        var released = false
        return {
            synchronized(backgroundHookLock) {
                if (released) return@synchronized
                released = true
                if (backgroundHookRefs > 0) backgroundHookRefs--
                if (backgroundHookRefs == 0) {
                    backgroundHooks?.forEach { it.unhook() }
                    backgroundHooks = null
                }
            }
        }
    }

    private fun enableScheduledSendBackgroundLocked(): Boolean {
        return runCatching {
            val duplexClass = findClass("com.snapchat.client.duplex.DuplexClient\$CppProxy")
            val appStateMethod = duplexClass.methods.firstOrNull { it.name == "appStateChanged" } ?: return false
            val hooks = mutableListOf<Hooker.HookHandle>()
            hooks.addAll(
                duplexClass.hook("appStateChanged", HookStage.BEFORE) { param ->
                    if (param.arg<Any>(0).toString() == "INACTIVE") param.setResult(null)
                }
            )
            hooks.addAll(
                duplexClass.hookConstructor(HookStage.AFTER) { param ->
                    val activeValue = appStateMethod.parameterTypes[0].enumConstants?.firstOrNull { it.toString() == "ACTIVE" }
                        ?: return@hookConstructor
                    appStateMethod.invoke(param.thisObject(), activeValue)
                }
            )
            backgroundHooks = hooks
            true
        }.getOrElse {
            context.log.error("Failed to enable scheduled send background mode", it)
            false
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Scheduled Send",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notifications for scheduled snap sends"
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun showNotification(title: String, content: String) {
        val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(context.androidContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        notificationManager.notify(notificationIdCounter++, builder.build())
    }

    @OptIn(ExperimentalLayoutApi::class)
    override fun init() {
        createNotificationChannel()
        
        val stripMediaMetadata = context.config.messaging.stripMediaMetadata.get()
        var postSavePolicy: Int? = null

        val configOverrideType = context.config.messaging.galleryMediaSendOverride.mode.getNullable()?.toString()
        if (configOverrideType == null && stripMediaMetadata.isEmpty()) return

        context.event.subscribe(MediaUploadEvent::class) { event ->
            if (stripMediaMetadata.isNotEmpty() && 
                (event.localMessageContent.contentType == ContentType.NOTE || 
                 stripMediaMetadata.contains("remove_audio_note_duration") || 
                 stripMediaMetadata.contains("remove_audio_note_transcript_capability"))) {
                event.onMediaUploaded { result ->
                    if (result.messageContent.contentType == ContentType.NOTE) {
                        val contentReader = ProtoReader(result.messageContent.content!!)
                        result.messageContent.content = ProtoEditor(result.messageContent.content!!).apply {
                            val hasFullPath = contentReader.followPath(4, 4, 6, 1, 1) != null
                            val hasDirectPath = contentReader.followPath(6, 1, 1) != null
                            
                            if (stripMediaMetadata.contains("remove_audio_note_duration")) {
                                if (hasFullPath) {
                                    edit(4, 4, 6, 1, 1) { remove(13) }
                                }
                                if (hasDirectPath || !hasFullPath) {
                                    edit(6, 1, 1) { remove(13) }
                                }
                            }
                            if (stripMediaMetadata.contains("remove_audio_note_transcript_capability")) {
                                if (hasFullPath) {
                                    edit(4, 4, 6, 1) { remove(3) }
                                }
                                if (hasDirectPath || !hasFullPath) {
                                    edit(6, 1) { remove(3) }
                                }
                                runCatching {
                                    result.messageContent.instanceNonNull().setObjectField("mAllowsTranscription", false)
                                }
                            }
                        }.toByteArray()
                    }
                }
            }

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
                                            edit(5) { editEach(1) { remove(2) } }
                                        }
                                        if (stripMediaMetadata.contains("hide_snap_filters")) {
                                            remove(9)
                                            remove(11)
                                        }
                                        if (stripMediaMetadata.contains("hide_extras")) {
                                            remove(13)
                                            edit(5, 1) { remove(2) }
                                        }
                                    }
                                }
                                ContentType.NOTE -> {
                                    if (stripMediaMetadata.contains("remove_audio_note_duration")) {
                                        edit(6, 1, 1) { remove(13) }
                                    }
                                    if (stripMediaMetadata.contains("remove_audio_note_transcript_capability")) {
                                        edit(6, 1) { remove(3) }
                                    }
                                }
                                else -> {}
                            }
                        }

                        edit(11, 5, 2) { remove(99) }
                    }.toByteArray()
                }
            }
        }

        if (configOverrideType == null) return

        context.event.subscribe(NativeUnaryCallEvent::class, priority = 100) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
            postSavePolicy?.let { savePolicy ->
                context.log.verbose("postSavePolicy=$savePolicy")
                val protoReader = ProtoReader(event.buffer)
                event.buffer = ProtoEditor(event.buffer).apply {
                    if (protoReader.followPath(4) != null) {
                        edit(4) {
                            remove(7)
                            addVarInt(7, savePolicy)
                        }
                        if (savePolicy == 1/* PROHIBITED */) {
                            edit(6, 9) { remove(1) }
                        }
                    }
                    
                    val noteAtRoot = protoReader.followPath(6) != null
                    val noteNested = protoReader.followPath(4, 4, 6) != null
                    
                    if (noteAtRoot || noteNested) {
                        val hasNestedPath = if (noteNested) {
                            protoReader.followPath(4, 4, 6, 1, 1) != null
                        } else {
                            protoReader.followPath(6, 1, 1) != null
                        }
                        
                        if (noteNested) {
                            if (hasNestedPath) {
                                edit(4, 4, 6, 1, 1) { remove(7); addVarInt(7, savePolicy) }
                            } else {
                                edit(4, 4, 6, 1) { remove(7); addVarInt(7, savePolicy) }
                            }
                        } else {
                            if (hasNestedPath) {
                                edit(6, 1, 1) { remove(7); addVarInt(7, savePolicy) }
                            } else {
                                edit(6, 1) { remove(7); addVarInt(7, savePolicy) }
                            }
                        }
                    }

                    val snapAtRoot = protoReader.followPath(11) != null
                    val snapNested = protoReader.followPath(4, 4, 11) != null
                    if (snapAtRoot || snapNested) {
                        if (snapNested) {
                            edit(4, 4, 11) { remove(7); addVarInt(7, savePolicy) }
                        } else {
                            edit(11) { remove(7); addVarInt(7, savePolicy) }
                        }
                    }
                }.toByteArray()
            }
        }

        context.event.subscribe(UnaryCallEvent::class, priority = 100) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
        }

        context.event.subscribe(SendMessageWithContentEvent::class, priority = -100) { event ->
            if (internalMultipartSend.get() == true) return@subscribe
            postSavePolicy = null
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) return@subscribe
            val localMessageContent = event.messageContent
            
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && 
                localMessageContent.contentType != ContentType.SNAP &&
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) return@subscribe
            val includeCameraSnaps = context.config.messaging.galleryMediaSendOverride.includeCameraSnaps.get()
            if (localMessageContent.contentType == ContentType.SNAP && !includeCameraSnaps) return@subscribe

            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            val conversationIds = event.destinations.conversations?.map { it.toString() } ?: return@subscribe
            if (conversationIds.isEmpty()) return@subscribe
            
            val recipientNames = conversationIds.mapNotNull { convId ->
                runCatching {
                    val dmParticipant = context.database.getDMOtherParticipant(convId)
                    if (dmParticipant != null) {
                        context.database.getFriendInfo(dmParticipant)?.displayName ?: context.database.getFriendInfo(dmParticipant)?.mutableUsername
                    } else {
                        context.database.getFeedEntryByConversationId(convId)?.feedDisplayName
                    }
                }.getOrNull()
            }.ifEmpty { listOf("Unknown") }
            
            val recipientName = recipientNames.joinToString(", ")
            event.canceled = true

            fun invokeOriginalAndRestoreResult(ev: SendMessageWithContentEvent) {
                val result = ev.adapter.invokeOriginal()
                ev.adapter.setResult(result)
                ev.canceled = false
            }

            val sendMessageCallbackClass by lazy {
                lateinit var result: Class<*>
                context.mappings.useMapper(CallbackMapper::class) {
                    result = callbacks.getClass("SendMessageCallback") ?: error("Failed to resolve SendMessageCallback")
                }
                result
            }

            fun cloneDestinations(source: MessageDestinations): Any {
                return context.gson.fromJson(
                    context.gson.toJson(source.instanceNonNull()),
                    context.classCache.messageDestinations
                )
            }

            val sendMessageWithContentMethod by lazy {
                sequence {
                    var current: Class<*>? = context.classCache.conversationManager
                    while (current != null && current != Any::class.java && current != Object::class.java) {
                        yield(current)
                        current = current.superclass
                    }
                }.flatMap { it.declaredMethods.asSequence() }
                    .first { it.name == "sendMessageWithContent" }
            }

            fun applyOverride(
                targetMessageContent: MessageContent,
                targetReader: ProtoReader,
                overrideType: String,
                snapDurationMs: Int?
            ): Boolean {
                val bypassLimit = context.config.experimental.nativeHooks.valdiHooks.bypassCameraRollLimit.get()
                if (overrideType != "ORIGINAL" && !bypassLimit && (targetReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Default.WarningAmber,
                        context.translation["gallery_media_send_override.multiple_media_toast"]
                    )
                    return false
                }

                when (overrideType) {
                    "SNAP", "SAVEABLE_SNAP" -> {
                        val savePolicyValue = if (overrideType == "SAVEABLE_SNAP") 2 else 1
                        postSavePolicy = savePolicyValue
                        val extras = targetReader.followPath(3, 3, 13)?.getBuffer()

                        if (targetMessageContent.contentType != ContentType.SNAP) {
                            targetMessageContent.content = ProtoWriter().apply {
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
                                    extras?.let { addBuffer(13, it) }
                                    from(22) {}
                                }
                            }.toByteArray()
                        }

                        targetMessageContent.contentType = ContentType.SNAP
                        targetMessageContent.content = ProtoEditor(targetMessageContent.content!!).apply {
                            edit(11, 5, 2) {
                                arrayOf(6, 7, 8).forEach { remove(it) }
                                addVarInt(5, targetReader.getVarInt(3, 3, 5, 2, 5) ?: targetReader.getVarInt(11, 5, 2, 5) ?: 1)
                                if (snapDurationMs != null && overrideType != "SAVEABLE_SNAP") {
                                    addVarInt(8, snapDurationMs / 1000)
                                    if (snapDurationMs / 1000 <= 0) {
                                        addVarInt(99, snapDurationMs)
                                    }
                                } else {
                                    addBuffer(6, byteArrayOf())
                                }
                            }
                            edit(11, 22) {
                                remove(4)
                                addVarInt(4, 5) // APP_SOURCE_CAMERA
                            }
                            edit(11) {
                                remove(7)
                                addVarInt(7, savePolicyValue)
                            }
                        }.toByteArray()
                    }
                    "NOTE" -> {
                        val shouldPreventSave = context.config.messaging.unsaveableMessages.note.get()
                        if (shouldPreventSave) postSavePolicy = 1
                        targetMessageContent.contentType = ContentType.NOTE
                        val stripMeta = context.config.messaging.stripMediaMetadata.get()
                        val omitTranscript = stripMeta.contains("remove_audio_note_transcript_capability")
                        val protoDurationMs = targetReader.getVarInt(3, 3, 5, 1, 1, 15)?.toLong()
                            ?: targetReader.getVarInt(3, 3, 5, 2, 8)?.toLong()?.times(1000)
                            ?: (context.feature(MediaFilePicker::class).lastMediaDuration ?: 0).toLong()
                        val durationForProto = minOf(protoDurationMs, MessageSender.VOICE_NOTE_MAX_DURATION_MS)
                        val audioNoteProto = MessageSender.audioNoteProto(
                            durationForProto,
                            if (omitTranscript) null else Locale.getDefault().toLanguageTag()
                        )
                        
                        targetMessageContent.content = if (shouldPreventSave) {
                            val audioReader = ProtoReader(audioNoteProto)
                            val hasNestedPath = audioReader.followPath(6, 1, 1) != null
                            ProtoEditor(audioNoteProto).apply {
                                if (hasNestedPath) {
                                    edit(6, 1, 1) { remove(7); addVarInt(7, 1) }
                                } else {
                                    edit(6, 1) { remove(7); addVarInt(7, 1) }
                                }
                            }.toByteArray()
                        } else {
                            audioNoteProto
                        }
                    }
                }

                if (postSavePolicy != null) {
                    try {
                        val savePolicyEnumClass = runCatching {
                            Class.forName(
                                "com.snapchat.client.messaging.SavePolicy",
                                false,
                                targetMessageContent.instanceNonNull().javaClass.classLoader
                            )
                        }.getOrNull()

                        if (savePolicyEnumClass != null && savePolicyEnumClass.isEnum) {
                            @Suppress("UNCHECKED_CAST")
                            val enumClass = savePolicyEnumClass as Class<out Enum<*>>
                            val policyName = when (postSavePolicy) {
                                1 -> "PROHIBITED"
                                2 -> "VIEWER_SAVABLE"
                                else -> null
                            }
                            if (policyName != null) {
                                val policyEnum = runCatching { java.lang.Enum.valueOf(enumClass, policyName) }.getOrNull()
                                if (policyEnum != null) {
                                    targetMessageContent.instanceNonNull().setObjectField("mSavePolicy", policyEnum)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        context.log.warn("SendOverride: Failed to set mSavePolicy: ${e.message}")
                    }
                }
                return true
            }

            fun sendMedia(overrideType: String, snapDurationMs: Int?): Boolean {
                context.log.verbose("SendOverride: sendMedia triggered with overrideType=$overrideType")
                val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                
                if (overrideType != "ORIGINAL" && mediaCount > 1) {
                    context.log.verbose("SendOverride: Handling physical multi-selection (mediaCount = $mediaCount)")
                    val originalJson = context.gson.toJson(localMessageContent.instanceNonNull())
                    val originalCallback = event.adapter.args().getOrNull(2)
                    val mediaBuffers = mutableListOf<ByteArray>()
                    messageProtoReader.followPath(3)?.eachBuffer { id, buffer ->
                        if (id == 3) mediaBuffers.add(buffer)
                    }
                    if (mediaBuffers.isEmpty()) return false

                    fun buildPartMessageContent(partIndex: Int): MessageContent {
                        val partContent = MessageContent(context.gson.fromJson(originalJson, context.classCache.localMessageContent))
                        val metadata = partContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata")
                        val refs = ArrayList(partContent.localMediaReferences ?: arrayListOf())
                        val contentRefs = (metadata?.getObjectFieldOrNull("mContentReferences") as? ArrayList<*>)?.toCollection(ArrayList())
                        val encryptionRefs = (metadata?.getObjectFieldOrNull("mRemoteMediaEncryption") as? ArrayList<*>)?.toCollection(ArrayList())
                        
                        partContent.content = ProtoEditor(partContent.content!!).apply {
                            edit(3) {
                                remove(3)
                                addBuffer(3, mediaBuffers[partIndex])
                            }
                        }.toByteArray()
                        
                        if (partIndex < refs.size) partContent.localMediaReferences = arrayListOf(refs[partIndex])
                        metadata?.let {
                            if (contentRefs != null && partIndex < contentRefs.size) {
                                it.setObjectField("mContentReferences", arrayListOf(contentRefs[partIndex]))
                            }
                            if (encryptionRefs != null && partIndex < encryptionRefs.size) {
                                it.setObjectField("mRemoteMediaEncryption", arrayListOf(encryptionRefs[partIndex]))
                            }
                        }
                        return partContent
                    }

                    fun sendPart(partIndex: Int) {
                        postSavePolicy = null
                        val partContent = buildPartMessageContent(partIndex)
                        val partReader = ProtoReader(partContent.content ?: return)
                        if (!applyOverride(partContent, partReader, overrideType, snapDurationMs)) return

                        val callback = if (partIndex == mediaCount - 1) {
                            originalCallback
                        } else {
                            CallbackBuilder(sendMessageCallbackClass)
                                .override("onSuccess") { sendPart(partIndex + 1) }
                                .override("onError", shouldUnhook = false) {
                                    runCatching {
                                        originalCallback?.javaClass?.methods?.firstOrNull { method ->
                                            method.name == "onError" && method.parameterCount == 1
                                        }?.invoke(originalCallback, it.argNullable<Any>(0))
                                    }
                                }
                                .build()
                        }

                        if (partIndex == 0) {
                            event.adapter.setArg(1, partContent.instanceNonNull())
                            event.adapter.setArg(2, callback)
                            invokeOriginalAndRestoreResult(event)
                        } else {
                            internalMultipartSend.set(true)
                            try {
                                sendMessageWithContentMethod.invoke(
                                    context.feature(Messaging::class).conversationManager?.instanceNonNull(),
                                    cloneDestinations(event.destinations),
                                    partContent.instanceNonNull(),
                                    callback
                                )
                            } catch (e: Exception) {
                                context.log.error("SendOverride: Exception sending physical chunk $partIndex", e)
                            } finally {
                                internalMultipartSend.set(false)
                            }
                        }
                    }

                    sendPart(0)
                    return true
                }

                // ==========================================
                // VIRTUAL SPLIT LOGIC FOR LONG MEMORIES/GALLERY VIDEOS
                // ==========================================
                
                var rawDurationMs = messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15)?.toLong()
                    ?: messageProtoReader.getVarInt(3, 3, 5, 2, 8)?.toLong()?.times(1000) 
                    ?: messageProtoReader.getVarInt(11, 5, 2, 8)?.toLong()?.times(1000) 
                    ?: messageProtoReader.getVarInt(11, 5, 1, 1, 15)?.toLong()
                    ?: 0L

                context.log.verbose("SendOverride: Duration extracted from Protobuf = $rawDurationMs ms")

                if (rawDurationMs == 0L) {
                    val metadata = localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata")
                    val metaDuration = metadata?.getObjectFieldOrNull("mDurationMs") as? Number
                    rawDurationMs = metaDuration?.toLong() ?: 0L
                    context.log.verbose("SendOverride: Duration extracted from Metadata = $rawDurationMs ms (Metadata object was present: ${metadata != null})")
                }

                // NEW: Aggressive LocalMediaReference Physical Duration Hunt
                if (rawDurationMs == 0L) {
                    context.log.verbose("SendOverride: Attempting aggressive LocalMediaReference extraction...")
                    runCatching {
                        val refs = localMessageContent.localMediaReferences
                        context.log.verbose("SendOverride: localMediaReferences count = ${refs?.size}")
                        refs?.forEachIndexed { index, ref ->
                            ref.javaClass.declaredFields.forEach { f ->
                                f.isAccessible = true
                                val v = f.get(ref)
                                if (v is Uri) {
                                    val d = extractMediaDuration(v)
                                    context.log.verbose("SendOverride: extracted duration from Uri (${f.name}) = $d")
                                    if (d != null && d > rawDurationMs) rawDurationMs = d
                                } else if (v is String && (v.startsWith("content://") || v.startsWith("file://") || v.startsWith("/"))) {
                                    val parseUri = if (v.startsWith("/")) Uri.fromFile(File(v)) else Uri.parse(v)
                                    val d = extractMediaDuration(parseUri)
                                    context.log.verbose("SendOverride: extracted duration from String Uri (${f.name}) = $d")
                                    if (d != null && d > rawDurationMs) rawDurationMs = d
                                }
                            }
                        }
                    }.onFailure {
                        context.log.warn("SendOverride: Failed LocalMediaReference extraction: ${it.message}")
                    }
                }

                // NEW: Extreme Reflection Dump (If it still fails, this shows us where Snapchat hid it)
                if (rawDurationMs == 0L) {
                    context.log.verbose("SendOverride: CRITICAL - Duration is still 0. Dumping MessageContent fields:")
                    runCatching {
                        localMessageContent.instanceNonNull().javaClass.declaredFields.forEach { f ->
                            f.isAccessible = true
                            val v = f.get(localMessageContent.instanceNonNull())
                            context.log.verbose("SendOverride: localMessageContent.${f.name} = $v")
                        }
                    }
                }

                context.log.verbose("SendOverride: Final Evaluated Duration = $rawDurationMs ms")

                val chunkDurationMs = 10_000L
                val shouldVirtualSplit = (overrideType == "SNAP" || overrideType == "SAVEABLE_SNAP") && rawDurationMs > chunkDurationMs

                context.log.verbose("SendOverride: shouldVirtualSplit = $shouldVirtualSplit (disableSplitForCurrentSend = $disableSplitForCurrentSend)")

                if (shouldVirtualSplit && disableSplitForCurrentSend == false) {
                    context.log.verbose("SendOverride: Virtual split condition met. Splitting video.")
                    val originalJson = context.gson.toJson(localMessageContent.instanceNonNull())
                    val originalCallback = event.adapter.args().getOrNull(2)
                    val totalChunks = kotlin.math.ceil(rawDurationMs.toDouble() / chunkDurationMs).toInt()

                    context.log.verbose("SendOverride: Target virtual chunks = $totalChunks")

                    fun buildVirtualChunkContent(chunkIndex: Int): MessageContent {
                        val chunkContent = MessageContent(
                            context.gson.fromJson(originalJson, context.classCache.localMessageContent)
                        )
                        
                        val startMs = chunkIndex * chunkDurationMs
                        val endMs = minOf(startMs + chunkDurationMs, rawDurationMs)
                        val actualChunkDuration = endMs - startMs

                        chunkContent.content = ProtoEditor(chunkContent.content!!).apply {
                            edit(11, 5) {
                                edit(1) {
                                    edit(1) {
                                        remove(15)
                                        addVarInt(15, actualChunkDuration)
                                    }
                                }
                                edit(2) {
                                    remove(2) // startOffsetMs
                                    addVarInt(2, startMs)
                                    remove(3) // endOffsetMs
                                    addVarInt(3, endMs)
                                }
                            }
                        }.toByteArray()
                        
                        return chunkContent
                    }

                    fun sendVirtualChunk(chunkIndex: Int) {
                        context.log.verbose("SendOverride: Preparing virtual chunk $chunkIndex")
                        postSavePolicy = null
                        val chunkContent = runCatching { buildVirtualChunkContent(chunkIndex) }.getOrElse {
                            context.log.error("SendOverride: Failed to build virtual chunk $chunkIndex", it)
                            return
                        }
                        
                        val chunkReader = ProtoReader(chunkContent.content ?: return)
                        
                        if (!applyOverride(chunkContent, chunkReader, overrideType, null)) {
                            context.log.warn("SendOverride: applyOverride returned false for virtual chunk $chunkIndex")
                            return
                        }

                        val callback = if (chunkIndex == totalChunks - 1) {
                            originalCallback
                        } else {
                            CallbackBuilder(sendMessageCallbackClass)
                                .override("onSuccess") {
                                    context.log.verbose("SendOverride: Virtual chunk $chunkIndex success. Sending next.")
                                    sendVirtualChunk(chunkIndex + 1)
                                }
                                .override("onError", shouldUnhook = false) {
                                    context.log.error("SendOverride: Error sending virtual chunk $chunkIndex.")
                                    runCatching {
                                        originalCallback?.javaClass?.methods?.firstOrNull { method ->
                                            method.name == "onError" && method.parameterCount == 1
                                        }?.invoke(originalCallback, it.argNullable<Any>(0))
                                    }
                                }
                                .build()
                        }

                        if (chunkIndex == 0) {
                            event.adapter.setArg(1, chunkContent.instanceNonNull())
                            event.adapter.setArg(2, callback)
                            invokeOriginalAndRestoreResult(event)
                        } else {
                            internalMultipartSend.set(true)
                            try {
                                sendMessageWithContentMethod.invoke(
                                    context.feature(Messaging::class).conversationManager?.instanceNonNull(),
                                    cloneDestinations(event.destinations),
                                    chunkContent.instanceNonNull(),
                                    callback
                                )
                            } catch (e: Exception) {
                                context.log.error("SendOverride: Exception sending virtual chunk $chunkIndex", e)
                            } finally {
                                internalMultipartSend.set(false)
                            }
                        }
                    }

                    sendVirtualChunk(0)
                    return true
                }

                return applyOverride(localMessageContent, messageProtoReader, overrideType, snapDurationMs)
            }

            val resolvedOverrideType = MediaFilePicker.getQueuedOverrideType()
                ?: configOverrideType?.takeIf { it != "always_ask" }
            if (resolvedOverrideType != null) {
                if (MediaFilePicker.hasPendingSplitCleanup() || MediaFilePicker.getQueuedOverrideType() != null) {
                    event.addCallbackResult("onSuccess") {
                        context.runOnUiThread {
                            if (!MediaFilePicker.handleCurrentQueuedItemSuccess()) {
                                MediaFilePicker.clearQueuedSplitItems()
                            }
                        }
                    }
                    event.addCallbackResult("onError") {
                        MediaFilePicker.clearQueuedSplitItems()
                    }
                }
                if (sendMedia(resolvedOverrideType, 10000)) {
                    if (event.canceled) invokeOriginalAndRestoreResult(event)
                }
                return@subscribe
            }

            context.runOnUiThread {
                val recipientNameForTask = recipientName
                val mediaCount = messageProtoReader.followPath(3)?.getCount(3) ?: 0
                
                createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                    PurrfectOverlayTheme {
                        val mainTranslation = remember {
                            context.translation.getCategory("send_override_dialog")
                        }
                        val dialogShape = RoundedCornerShape(24.dp)
                        val dialogSurfaceColor = Color(0xFF2A2452)
                        val border = remember {
                            Brush.linearGradient(
                                listOf(
                                    PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.55f),
                                    PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.35f)
                                )
                            )
                        }
                        val dialogBackground = remember {
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF2A2452),
                                    Color(0xFF1A143A)
                                )
                            )
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
                                shape = RoundedCornerShape(18.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) Color(0xFF3E3478) else Color(0xFF2F2A5B),
                                    contentColor = Color.White
                                ),
                                border = if (selected) BorderStroke(1.dp, PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.6f)) else null
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = title,
                                        modifier = Modifier.size(28.dp),
                                        tint = if (selected) PurrfectOverlayPalette.glowSecondary else Color.White.copy(alpha = 0.9f)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        title,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        softWrap = true,
                                        lineHeight = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = dialogShape,
                            color = dialogSurfaceColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 18.dp,
                            border = BorderStroke(1.dp, border)
                        ) {
                            Column(
                                modifier = Modifier
                                    .background(dialogBackground, dialogShape)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val translation = remember {
                                    context.translation.getCategory("features.options.gallery_media_send_override")
                                }
                                var scheduleEnabled by remember { mutableStateOf(false) }

                                Text(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    text = "Send as ${translation[selectedType]}",
                                    modifier = Modifier.padding(5.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ActionTile(
                                        modifier = Modifier.weight(1f).height(92.dp),
                                        selected = selectedType == "ORIGINAL",
                                        icon = Icons.Filled.Photo,
                                        title = translation["ORIGINAL"]
                                    ) {
                                        selectedType = "ORIGINAL"
                                    }
                                    ActionTile(
                                        modifier = Modifier.weight(1f).height(92.dp),
                                        selected = selectedType == "SNAP" || selectedType == "SAVEABLE_SNAP",
                                        icon = Icons.Filled.PhotoCamera,
                                        title = translation["SNAP"]
                                    ) {
                                        selectedType = "SNAP"
                                    }
                                    ActionTile(
                                        modifier = Modifier.weight(1f).height(92.dp),
                                        selected = selectedType == "NOTE",
                                        icon = Icons.Filled.MusicNote,
                                        title = translation["NOTE"]
                                    ) {
                                        selectedType = "NOTE"
                                    }
                                }

                                fun convertDuration(duration: Float) = when {
                                    duration in -2f..-1f -> 100
                                    duration in -1f..-0f -> 250
                                    duration in -0f..1f -> 500
                                    duration >= 11f -> null
                                    else -> ((duration * 1000).toInt() / 1000) * 1000
                                }
                        
                                fun formatTimeText(ms: Long): String {
                                    val days = (ms / (24 * 60 * 60 * 1000)).toInt()
                                    val hours = ((ms / (60 * 60 * 1000)) % 24).toInt()
                                    val minutes = ((ms / (60 * 1000)) % 60).toInt()
                                    val seconds = ((ms / 1000) % 60).toInt()
                                    return buildString {
                                        if (days > 0) append("${days}d ")
                                        if (hours > 0 || days > 0) append("${hours}h ")
                                        if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
                                        append("${seconds}s")
                                    }
                                }

                                when (selectedType) {
                                    "SNAP", "SAVEABLE_SNAP" -> {
                                        fun toggleSaveable() {
                                            selectedType = if (selectedType == "SAVEABLE_SNAP") "SNAP" else "SAVEABLE_SNAP"
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                disableSplitForCurrentSend = !disableSplitForCurrentSend
                                            },
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = disableSplitForCurrentSend,
                                                onCheckedChange = { disableSplitForCurrentSend = it }
                                            )
                                            Text(text = mainTranslation["single_send_hint"], lineHeight = 15.sp)
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
                                                onCheckedChange = { toggleSaveable() }
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
                                                onValueChange = { customDuration = it },
                                                valueRange = -2f..11f,
                                            )
                                        }
                                    }
                                }
                                
                                if (mediaCount <= 1 && localMessageContent.contentType == ContentType.EXTERNAL_MEDIA && selectedType == "SNAP") {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                        color = Color(0xFF3E3478).copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, Color(0xFF3E3478))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = null,
                                                tint = PurrfectOverlayPalette.glowSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "Long gallery videos will be virtually split into multiple Snaps.",
                                                color = Color.White.copy(alpha = 0.8f),
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = scheduleEnabled,
                                        onCheckedChange = {
                                            scheduleEnabled = it
                                            if (!it) scheduledTime = null
                                        }
                                    )
                                    Text(text = mainTranslation["schedule"], modifier = Modifier.weight(1f))
                                    if (scheduleEnabled) {
                                        Button(onClick = { showClockPicker = true }) {
                                            scheduledTime?.let { time ->
                                                Text(text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(time))
                                            } ?: Text(context.translation["select"])
                                        }
                                    }
                                }

                                if (scheduleEnabled && showClockPicker) {
                                    val datePickerState = rememberDatePickerState(
                                        initialSelectedDateMillis = scheduledTime ?: System.currentTimeMillis()
                                    )
                                    val timePickerState = rememberTimePickerState(
                                        initialHour = clockPickerHour,
                                        initialMinute = clockPickerMinute
                                    )
                                    
                                    var showDatePickerDialog by remember { mutableStateOf(false) }
                                    var showTimePickerDialog by remember { mutableStateOf(false) }
                                    
                                    if (showDatePickerDialog) {
                                        DatePickerDialog(
                                            onDismissRequest = { showDatePickerDialog = false },
                                            confirmButton = {
                                                TextButton(onClick = { showDatePickerDialog = false }) {
                                                    Text(context.translation["button.ok"])
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showDatePickerDialog = false }) {
                                                    Text(context.translation["button.cancel"])
                                                }
                                            }
                                        ) {
                                            DatePicker(state = datePickerState)
                                        }
                                    }
                                    
                                    if (showTimePickerDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showTimePickerDialog = false },
                                            confirmButton = {
                                                TextButton(onClick = { 
                                                    clockPickerHour = timePickerState.hour
                                                    clockPickerMinute = timePickerState.minute
                                                    showTimePickerDialog = false 
                                                }) {
                                                    Text(context.translation["button.ok"])
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showTimePickerDialog = false }) {
                                                    Text(context.translation["button.cancel"])
                                                }
                                            },
                                            text = {
                                                TimePicker(state = timePickerState)
                                            }
                                        )
                                    }
                                    
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                mainTranslation["select_time"], 
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            
                                            OutlinedButton(
                                                onClick = { showDatePickerDialog = true },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.CalendarToday, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    datePickerState.selectedDateMillis?.let {
                                                        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(it)
                                                    } ?: context.translation.getOrNull("select_date") ?: "Select Date"
                                                )
                                            }
                                            
                                            OutlinedButton(
                                                onClick = { showTimePickerDialog = true },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Schedule, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(String.format("%02d:%02d", clockPickerHour, clockPickerMinute))
                                            }
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceEvenly
                                            ) {
                                                OutlinedButton(onClick = { showClockPicker = false }) {
                                                    Text(context.translation["button.cancel"])
                                                }
                                                Button(onClick = {
                                                    val selectedDateMillis = datePickerState.selectedDateMillis
                                                    if (selectedDateMillis == null) {
                                                        context.inAppOverlay.showStatusToast(
                                                            icon = Icons.Default.WarningAmber,
                                                            text = mainTranslation.getOrNull("select_date_first") ?: "Please select a date"
                                                        )
                                                        return@Button
                                                    }
                                                    
                                                    val calendar = Calendar.getInstance()
                                                    calendar.timeInMillis = selectedDateMillis
                                                    calendar.set(Calendar.HOUR_OF_DAY, clockPickerHour)
                                                    calendar.set(Calendar.MINUTE, clockPickerMinute)
                                                    calendar.set(Calendar.SECOND, 0)
                                                    calendar.set(Calendar.MILLISECOND, 0)
                                                    
                                                    if (calendar.timeInMillis <= System.currentTimeMillis()) {
                                                        context.inAppOverlay.showStatusToast(
                                                            icon = Icons.Default.WarningAmber,
                                                            text = mainTranslation.getOrNull("invalid_time") ?: "Please select a future time"
                                                        )
                                                        return@Button
                                                    }
                                                    
                                                    scheduledTime = calendar.timeInMillis
                                                    showClockPicker = false
                                                }) {
                                                    Text(context.translation["button.ok"])
                                                }
                                            }
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
                                        val finalSelectedType = selectedType
                                        if (disableSplitForCurrentSend && MediaFilePicker.hasOriginalUnsplitItem()) {
                                            MediaFilePicker.setQueuedOverrideType(finalSelectedType)
                                            if (!MediaFilePicker.sendOriginalUnsplitItem()) {
                                                MediaFilePicker.setQueuedOverrideType(null)
                                            }
                                            return@Button
                                        } else if (MediaFilePicker.hasPendingSplitCleanup()) {
                                            MediaFilePicker.setQueuedOverrideType(finalSelectedType)
                                            event.addCallbackResult("onSuccess") {
                                                context.runOnUiThread {
                                                    if (!MediaFilePicker.handleCurrentQueuedItemSuccess()) {
                                                        MediaFilePicker.clearQueuedSplitItems()
                                                    }
                                                }
                                            }
                                            event.addCallbackResult("onError") {
                                                MediaFilePicker.clearQueuedSplitItems()
                                            }
                                        }
                                        val delayMs = scheduledTime?.let { it - System.currentTimeMillis() }
                                        if (delayMs != null && delayMs > 0) {
                                            val taskHash = java.util.UUID.randomUUID().toString()
                                            val scheduledDateTime = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(scheduledTime)
                                            context.bridgeClient.getTaskInterface().createTask(
                                                "scheduled_send",
                                                scheduledDateTime,
                                                recipientNameForTask,
                                                taskHash
                                            )
                                            val scheduledTimeText = formatTimeText(delayMs)
                                            context.inAppOverlay.showStatusToast(
                                                icon = Icons.Filled.Schedule,
                                                text = context.translation.format("schedule_scheduled_for", "name" to recipientNameForTask, "time" to scheduledTimeText) ?: "Scheduled for $recipientNameForTask in $scheduledTimeText"
                                            )
                                            val releaseBackground = acquireScheduledSendBackground()
                                            
                                            event.addCallbackResult("onSuccess") {
                                                context.bridgeClient.getTaskInterface().successTask(taskHash)
                                            }
                                            event.addCallbackResult("onError") { 
                                                context.bridgeClient.getTaskInterface().failTask(taskHash, it.getOrNull(0)?.toString() ?: "Unknown error")
                                            }

                                            val job = context.coroutineScope.launch {
                                                val startTime = System.currentTimeMillis()
                                                while (true) {
                                                    val elapsed = System.currentTimeMillis() - startTime
                                                    val remaining = delayMs - elapsed
                                                    if (remaining <= 0) break
                                                    
                                                    val timeText = formatTimeText(remaining)
                                                    val progress = (elapsed * 100 / delayMs).toInt().coerceIn(0, 99)
                                                    
                                                    context.bridgeClient.getTaskInterface().updateTaskProgress(
                                                        taskHash,
                                                        context.translation.getOrNull("schedule_sending_in")?.replace("{time}", timeText) ?: "Sending in $timeText",
                                                        progress
                                                    )
                                                    delay(1000)
                                                }

                                                context.bridgeClient.getTaskInterface().updateTaskProgress(taskHash, "Sending...", 100)

                                                if (sendMedia(finalSelectedType, if (finalSelectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null)) {
                                                    if (event.canceled) {
                                                        invokeOriginalAndRestoreResult(event)
                                                    }
                                                    val successText = context.translation.format("schedule_sent_to", "name" to recipientNameForTask) ?: "Sent to $recipientNameForTask"
                                                    context.inAppOverlay.showStatusToast(
                                                        icon = Icons.Filled.CheckCircle,
                                                        text = successText
                                                    )
                                                    val notificationTitle = context.translation.getOrNull("schedule_sent") ?: "Scheduled snap sent"
                                                    val notificationContent = "$scheduledDateTime\n$recipientNameForTask"
                                                    showNotification(
                                                        notificationTitle,
                                                        notificationContent
                                                    )
                                                } else {
                                                    context.bridgeClient.getTaskInterface().failTask(taskHash, "Failed to send")
                                                    val failureText = context.translation.format("schedule_failed_to", "name" to recipientNameForTask) ?: "Failed to send to $recipientNameForTask"
                                                    context.inAppOverlay.showStatusToast(
                                                        icon = Icons.Filled.WarningAmber,
                                                        text = failureText
                                                    )
                                                    val failNotificationTitle = context.translation.getOrNull("schedule_failed") ?: "Scheduled snap failed"
                                                    val failNotificationContent = "$scheduledDateTime\n$recipientNameForTask"
                                                    showNotification(
                                                        failNotificationTitle,
                                                        failNotificationContent
                                                    )
                                                }
                                            }
                                            val listener = object : TaskListener.Stub() {
                                                override fun onCancel() {
                                                    job.cancel()
                                                }
                                                override fun onProgress(label: String, progress: Int) {}
                                                override fun onStateChange(status: String) {}
                                                override fun onSuccess() {}
                                            }
                                            context.bridgeClient.getTaskInterface().registerTaskListener(taskHash, listener)
                                            job.invokeOnCompletion { throwable ->
                                                releaseBackground()
                                                context.bridgeClient.getTaskInterface().unregisterTaskListener(taskHash, listener)
                                                if (throwable is CancellationException) {
                                                    context.inAppOverlay.showStatusToast(
                                                        icon = Icons.Filled.Cancel,
                                                        text = context.translation.format("schedule_cancelled_for", "name" to recipientNameForTask) ?: "Cancelled for $recipientNameForTask"
                                                    )
                                                }
                                            }
                                        } else {
                                            if (sendMedia(finalSelectedType, if (finalSelectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null)) {
                                                if (event.canceled) {
                                                    invokeOriginalAndRestoreResult(event)
                                                }
                                            }
                                        }
                                    }) {
                                        Text(if (scheduledTime != null) mainTranslation["schedule"] else context.translation["button.send"])
                                    }
                                }
                            }
                        }
                    }
                }.show()
            }
        }
    }
}
