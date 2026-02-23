package me.eternal.purrfectsnap.core.features.impl.downloader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.core.ui.InAppOverlay
import me.eternal.purrfectsnap.bridge.call.CallDownloadSession
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.features.impl.messaging.Messaging
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import me.eternal.purrfectsnap.core.util.ktx.getObjectFieldOrNull
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class CallRecorder : Feature("Call Recorder") {
    private var wasInCall = false
    private var callDownloadSession: CallDownloadSession? = null
    private val streams = ConcurrentHashMap<Int, CallStreamWrapper>()

    private val uiState get() = context.inAppOverlay.callRecorderState
    private val callRecorderConfig get() = context.config.downloader.callRecorder

    inner class CallStreamWrapper(
        private val audioFormat: AudioFormat,
        private val startTimestamp: Long = System.currentTimeMillis(),
    ) {
        private var stream: OutputStream? = null

        fun write(buffer: ByteArray) {
            if (!uiState.isRecording || callDownloadSession == null) return
            
            if (stream == null) {
                runCatching {
                    stream = ParcelFileDescriptor.AutoCloseOutputStream(
                        callDownloadSession?.createStream(
                            System.currentTimeMillis(),
                            audioFormat.channelCount,
                            audioFormat.sampleRate,
                            audioFormat.encoding
                        ) ?: return
                    )
                }
            }
            runCatching { stream?.write(buffer) }
        }

        fun close() {
            runCatching { stream?.close() }
            stream = null
        }
    }

    private fun finalizeSession() {
        val session = callDownloadSession ?: return
        context.log.verbose("Finalizing call recording session")
        runCatching { session.end() }
        callDownloadSession = null
        streams.values.forEach { it.close() }
    }

    private fun startManualRecording() {
        if (!uiState.isRecording) {
            uiState.isRecording = true
            uiState.recordingStartTime = System.currentTimeMillis()
            
            // Initialize call download session if not already started
            if (callDownloadSession == null) {
                context.log.verbose("Starting call recorder session: ${uiState.currentAuthor}")
                callDownloadSession = context.bridgeClient.startCallDownload(System.currentTimeMillis(), uiState.currentAuthor)
            }
            
            ensureSessionStarted()
        }
    }
    
    private fun stopRecording() {
        if (uiState.isRecording) {
            uiState.isRecording = false
            finalizeSession()
        }
    }

    private fun onCallStarted(conversationId: String) {
        if (wasInCall) return
        wasInCall = true
        
        val author = (if (context.database.getConversationType(conversationId) == 1) {
            context.database.getFeedEntryByConversationId(conversationId)?.feedDisplayName
        } else {
            context.database.getDMOtherParticipant(conversationId)?.let { context.database.getFriendInfo(it)?.mutableUsername }
        }) ?: "unknown"
        
        context.log.verbose("Call started for: $author")
        
        uiState.currentAuthor = author
        
        if (callRecorderConfig.callRecorderUi.get()) {
            uiState.offsetX = 0f
            uiState.offsetY = 0f
            uiState.isMinimized = false
            uiState.lastInteractionTime = System.currentTimeMillis()
            uiState.showOverlay = true
        }

        if (callRecorderConfig.autoStartRecording.get()) {
            startManualRecording()
        }
    }

    private fun onCallEnded() {
        context.log.verbose("onCallEnded cleanup. wasInCall=$wasInCall, showOverlay=${uiState.showOverlay}")
        wasInCall = false
        finalizeSession()
        streams.clear()
        
        // Hide overlay UI (don't reset offsets here to avoid jumping during animation)
        uiState.isRecording = false
        uiState.showOverlay = false
    }

    private fun detectCallState() {
        // Primary hook: TalkCore native state updates
        val talkCoreNames = listOf(
            "com.snapchat.talkcorev3.TalkCore\$CppProxy",
            "com.snapchat.talkcorev4.TalkCore\$CppProxy",
            "com.snapchat.talkcore.TalkCore\$CppProxy"
        )
        
        talkCoreNames.forEach { className ->
            runCatching {
                findClass(className).apply {
                    hook("updateTSCallingSession", HookStage.BEFORE) { param ->
                        val params = param.arg<Any>(0)
                        val conversationId = params.getObjectFieldOrNull("mConversationId")?.toString() ?: return@hook
                        val inCall = params.getObjectFieldOrNull("mInCall") as? Boolean ?: false
                        context.log.verbose("updateTSCallingSession: inCall=$inCall convo=$conversationId", "CallRecorder")
                        if (inCall) onCallStarted(conversationId) else onCallEnded()
                    }
                    hook("disposeTSCallingSession", HookStage.BEFORE) { 
                        context.log.verbose("disposeTSCallingSession triggered", "CallRecorder")
                        onCallEnded() 
                    }
                }
            }
        }

        // Legacy/Generic hook: TSCallingStateUpdateParams constructor
        runCatching {
            findClass("com.snapchat.talkcorev3.TSCallingStateUpdateParams").hookConstructor(HookStage.AFTER) { param ->
                val instance = param.thisObject<Any>()
                val conversationId = instance.getObjectFieldOrNull("mConversationId")?.toString() ?: return@hookConstructor
                val inCall = instance.getObjectFieldOrNull("mInCall") as? Boolean ?: false
                if (inCall) onCallStarted(conversationId) else onCallEnded()
            }
        }
    }

    private fun checkStreamsAndCleanup() {
        // If all audio streams are released, the call is likely over
        if (streams.isEmpty() && wasInCall) {
            context.coroutineScope.launch {
                delay(200)
                if (streams.isEmpty() && wasInCall) {
                    context.log.verbose("Call end detected via stream release", "CallRecorder")
                    onCallEnded()
                }
            }
        }
    }

    private fun ensureSessionStarted() {
        if (callDownloadSession != null) return
        val conversationId = context.feature(Messaging::class).openedConversationUUID?.toString()
            ?: context.feature(Messaging::class).lastFocusedConversationId
            ?: "unknown"
        onCallStarted(conversationId)
    }

    private fun clampCopyRange(offset: Int, requestedLength: Int, maxLength: Int): Pair<Int, Int>? {
        if (requestedLength <= 0 || maxLength <= 0) return null
        val safeOffset = offset.coerceAtLeast(0).coerceAtMost(maxLength)
        val available = (maxLength - safeOffset).coerceAtLeast(0)
        val safeLength = requestedLength.coerceAtMost(available)
        if (safeLength <= 0) return null
        return safeOffset to safeLength
    }

    private fun copyAudioRecordByteBuffer(data: ByteBuffer, bytesRead: Int): ByteArray? {
        if (bytesRead <= 0) return null
        val currentPosition = data.position()
        val start = (currentPosition - bytesRead).coerceAtLeast(0)
        val end = currentPosition.coerceAtMost(data.limit())
        if (end <= start) return null
        return ByteArray(end - start).also { out ->
            val dup = data.duplicate()
            dup.position(start)
            dup.limit(end)
            dup.get(out)
        }
    }

    override fun init() {
        if (callRecorderConfig.callRecorder.getNullable() == null) return
        
        // Listen for UI control events
        context.event.subscribe(InAppOverlay.CallRecorderControlEvent::class) { event ->
            if (event.start) startManualRecording() else stopRecording()
        }
        
        detectCallState()

        val recorderConfig = callRecorderConfig.callRecorder.get()

        AudioRecord::class.java.apply {
            if (recorderConfig == "only_record_others") return@apply
            hookConstructor(HookStage.AFTER) { param ->
                val attributes = runCatching { param.arg<AudioAttributes>(0) }.getOrNull()
                val isCall = attributes?.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION || 
                             attributes?.usage == AudioAttributes.USAGE_UNKNOWN ||
                             runCatching { param.arg<Int>(0) }.getOrNull() == 7 // 7 = VOICE_COMMUNICATION
                
                if (isCall) {
                    val format = AudioFormat.Builder()
                        .setSampleRate(if (attributes != null) param.arg<AudioFormat>(1).sampleRate else param.arg(1))
                        .setChannelMask(if (attributes != null) param.arg<AudioFormat>(1).channelMask else param.arg(2))
                        .setEncoding(if (attributes != null) param.arg<AudioFormat>(1).encoding else param.arg(3))
                        .build()
                    streams[param.thisObject<Any>().hashCode()] = CallStreamWrapper(format)
                    ensureSessionStarted()
                }
            }

            hook("read", HookStage.AFTER) { param ->
                val result = param.getResult() as? Int ?: 0
                if (result <= 0) return@hook
                val wrapper = streams[param.thisObject<Any>().hashCode()] ?: return@hook
                
                val buffer = when (val data = param.arg<Any>(0)) {
                    is ByteBuffer -> copyAudioRecordByteBuffer(data, result) ?: return@hook
                    is ByteArray -> {
                        val offset = param.argNullable<Int>(1) ?: 0
                        val (safeOffset, safeLength) = clampCopyRange(offset, result, data.size) ?: return@hook
                        data.copyOfRange(safeOffset, safeOffset + safeLength)
                    }
                    is ShortArray -> {
                        val offset = param.argNullable<Int>(1) ?: 0
                        val (safeOffset, safeLength) = clampCopyRange(offset, result, data.size) ?: return@hook
                        ByteArray(safeLength * 2).also {
                            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data, safeOffset, safeLength)
                        }
                    }
                    else -> return@hook
                }
                wrapper.write(buffer)
            }

            hook("stop", HookStage.BEFORE) { checkStreamsAndCleanup() }
            hook("release", HookStage.BEFORE) { 
                streams.remove(it.thisObject<Any>().hashCode())?.close()
                checkStreamsAndCleanup()
            }
        }

        AudioTrack::class.java.apply {
            if (recorderConfig == "only_record_self") return@apply
            hookConstructor(HookStage.AFTER) { param ->
                val attributes = runCatching { param.arg<AudioAttributes>(0) }.getOrNull()
                val isCall = attributes?.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION || 
                             attributes?.usage == AudioAttributes.USAGE_UNKNOWN ||
                             runCatching { param.arg<Int>(0) }.getOrNull() in listOf(0, 7) // 0 = CALL, 7 = SCO
                
                if (isCall) {
                    val format = AudioFormat.Builder()
                        .setSampleRate(if (attributes != null) param.arg<AudioFormat>(1).sampleRate else param.arg(1))
                        .setChannelMask(if (attributes != null) param.arg<AudioFormat>(1).channelMask else param.arg(2))
                        .setEncoding(if (attributes != null) param.arg<AudioFormat>(1).encoding else param.arg(3))
                        .build()
                    streams[param.thisObject<Any>().hashCode()] = CallStreamWrapper(format)
                    ensureSessionStarted()
                }
            }

            hook("write", HookStage.BEFORE) { param ->
                val wrapper = streams[param.thisObject<Any>().hashCode()] ?: return@hook
                val data = param.arg<Any>(0)

                val buffer = when (data) {
                    is ByteBuffer -> {
                        val requestedSize = param.argNullable<Int>(1) ?: data.remaining()
                        val safeSize = requestedSize.coerceAtMost(data.remaining()).coerceAtLeast(0)
                        if (safeSize <= 0) return@hook
                        ByteArray(safeSize).also {
                            val pos = data.position()
                            data.get(it, 0, safeSize)
                            data.position(pos)
                        }
                    }
                    is ByteArray -> {
                        val offset = param.argNullable<Int>(1) ?: 0
                        val requestedSize = param.argNullable<Int>(2) ?: data.size
                        val (safeOffset, safeLength) = clampCopyRange(offset, requestedSize, data.size) ?: return@hook
                        data.copyOfRange(safeOffset, safeOffset + safeLength)
                    }
                    is ShortArray -> {
                        val offset = param.argNullable<Int>(1) ?: 0
                        val requestedSize = param.argNullable<Int>(2) ?: data.size
                        val (safeOffset, safeLength) = clampCopyRange(offset, requestedSize, data.size) ?: return@hook
                        ByteArray(safeLength * 2).also {
                            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data, safeOffset, safeLength)
                        }
                    }
                    else -> return@hook
                }
                wrapper.write(buffer)
            }

            hook("stop", HookStage.BEFORE) { checkStreamsAndCleanup() }
            hook("release", HookStage.BEFORE) { 
                streams.remove(it.thisObject<Any>().hashCode())?.close()
                checkStreamsAndCleanup()
            }
        }
    }
}
