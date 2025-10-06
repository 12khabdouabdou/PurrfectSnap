package me.rhunk.snapenhance.core.features.impl.experiments

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.rhunk.snapenhance.common.data.download.AudioStreamFormat
import me.rhunk.snapenhance.common.data.download.DownloadRequest
import me.rhunk.snapenhance.common.data.download.FFmpegMixAudioRequest
import me.rhunk.snapenhance.common.data.download.MediaDownloadSource
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.downloader.MediaDownloader
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.hook.hookConstructor
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
import me.rhunk.snapenhance.core.util.media.HttpServer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class CallRecorder : Feature("Call Recorder") {
    private val httpServer = HttpServer(
        timeout = Integer.MAX_VALUE
    )
    private val participants = CopyOnWriteArrayList<String>()
    private val lock = Any()

    private fun getWavHeader(
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val header = ByteBuffer.allocate(44)
        header.order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(-1) // Placeholder for file size
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // Sub-chunk 1 size
        header.putShort(1) // Audio format (1 for PCM)
        header.putShort(numChannels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * numChannels * bitsPerSample / 8) // Byte rate
        header.putShort((numChannels * bitsPerSample / 8).toShort()) // Block align
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(-1) // Placeholder for data size
        return header.array()
    }


    override fun init() {
        if (!context.config.experimental.callRecorder.get()) return

        runCatching {
            findClass("com.snapchat.talkcorev3.CallingSessionState")
        }.getOrNull()?.hookConstructor(HookStage.AFTER) { param ->
            val instance = param.thisObject<Any>()
            val callingState = instance.getObjectFieldOrNull("mLocalUser")?.getObjectField("mCallingState")

            if (callingState.toString() == "IN_CALL") {
                participants.clear()
                participants.addAll((instance.getObjectField("mParticipants") as Map<*, *>).keys.map { it.toString() })
            }
        } ?: findClass("com.snapchat.talkcorev3.TSCallingStateUpdateParams").hookConstructor(HookStage.AFTER) { param ->
            val instance = param.thisObject<Any>()

            if (instance.getObjectFieldOrNull("mInCall") == true) {
                participants.clear()
                participants.addAll((instance.getObjectField("mParticipants") as Set<*>).map { it.toString() })
            }
        }

        if (context.config.experimental.callRecordingMode.get() == "mixed") {
            setupMixedRecording()
        } else {
            setupRemoteRecording()
        }
    }

    private fun setupRemoteRecording() {
        val streamHandlers = ConcurrentHashMap<Int, MutableList<(data: ByteArray) -> Unit>>() // audioTrack -> handlers

        AudioTrack::class.java.apply {
            getConstructor(
                AudioAttributes::class.java,
                AudioFormat::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).hook(HookStage.BEFORE) { param ->
                val audioAttributes = param.arg<AudioAttributes>(0)
                if (audioAttributes.usage != AudioAttributes.USAGE_VOICE_COMMUNICATION) return@hook
                val audioFormat = param.arg<AudioFormat>(1)
                val hashCode = param.thisObject<Any>().hashCode()

                lateinit var streamUrl: String
                streamUrl = httpServer.ensureServerStarted()?.putContent(
                    object : HttpServer.HttpContent() {
                        override val contentType: String = "audio/wav"
                        override val chunked: Boolean = true
                        override val contentLength: Long? = null
                        override val newBody: () -> HttpServer.HttpBody = {
                            object : HttpServer.HttpBody() {
                                val outputStream = PipedOutputStream()
                                val inputStream = PipedInputStream(outputStream)

                                val handler: (byteArray: ByteArray) -> Unit = handler@{ byteArray ->
                                    if (byteArray.isEmpty()) {
                                        httpServer.removeUrl(streamUrl)
                                        return@handler
                                    }
                                    runCatching {
                                        outputStream.write(byteArray)
                                        outputStream.flush()
                                    }.onFailure {
                                        context.log.warn("Failed to write to streaming url ${it.localizedMessage}")
                                    }
                                }

                                override val onOpen: () -> Unit = {
                                    streamHandlers.getOrPut(hashCode) { CopyOnWriteArrayList() }.add(handler)
                                    outputStream.write(getWavHeader(audioFormat.sampleRate, audioFormat.channelCount, 16))
                                }

                                override val readBytes: (byteArray: ByteArray) -> Int = { byteArray ->
                                    runBlocking {
                                        withTimeoutOrNull(3000L) {
                                            inputStream.read(byteArray)
                                        } ?: -1
                                    }
                                }

                                override val onClose: () -> Unit = {
                                    context.log.verbose("Streaming url closed")
                                    streamHandlers[hashCode]?.remove(handler)
                                    outputStream.close()
                                    inputStream.close()
                                }
                            }
                        }
                    }
                ) ?: return@hook

                context.log.verbose("streaming url = $streamUrl, sampleRate = ${audioFormat.sampleRate}, audioFormat = ${audioFormat.encoding}")

                context.feature(MediaDownloader::class).provideDownloadManagerClient(
                    UUID.randomUUID().toString(),
                    participants.mapNotNull { context.database.getFriendInfo(it)?.mutableUsername }.joinToString("-"),
                    System.currentTimeMillis(),
                    MediaDownloadSource.VOICE_CALL
                ).downloadStream(streamUrl, AudioStreamFormat(audioFormat.channelCount, audioFormat.sampleRate, audioFormat.encoding))
            }

            getMethod("write", ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).hook(HookStage.BEFORE) { param ->
                streamHandlers[param.thisObject<Any>().hashCode()]?.let { handlers ->
                    val byteBuffer = param.arg<ByteBuffer>(0)
                    val position = byteBuffer.position()
                    val buffer = ByteArray(param.arg(1))
                    byteBuffer.get(buffer)
                    byteBuffer.position(position)
                    handlers.forEach { it(buffer) }
                }
            }

            hook("release", HookStage.BEFORE) {
                streamHandlers.remove(it.thisObject<Any>().hashCode())?.forEach { it(ByteArray(0)) }
            }
        }
    }

    private fun setupMixedRecording() {
        class RecorderState {
            val callActive = AtomicBoolean(false)
            var audioFormat: AudioFormat? = null
            var localFile: File? = null
            var remoteFile: File? = null
            var localStream: FileOutputStream? = null
            var remoteStream: FileOutputStream? = null
            val remoteReleased = AtomicBoolean(false)
            val localReleased = AtomicBoolean(false)
        }

        val state = RecorderState()

        val endRecordingAndMix = {
            synchronized(lock) {
                if (!state.callActive.get() || !state.remoteReleased.get() || !state.localReleased.get()) {
                    return@synchronized
                }
                state.callActive.set(false)

                state.remoteStream?.close()
                state.localStream?.close()

                context.log.debug("Both streams released, triggering mix.")

                val localF = state.localFile
                val remoteF = state.remoteFile
                val audioF = state.audioFormat

                if (localF == null || remoteF == null || audioF == null) {
                    context.log.error("Missing state for mixing call recording.")
                    localF?.delete()
                    remoteF?.delete()
                    return@synchronized
                }

                val downloadManager = context.feature(MediaDownloader::class).provideDownloadManagerClient(
                    mediaIdentifier = UUID.randomUUID().toString(),
                    mediaAuthor = participants.mapNotNull { context.database.getFriendInfo(it)?.mutableUsername }.joinToString("-"),
                    creationTimestamp = System.currentTimeMillis(),
                    downloadSource = MediaDownloadSource.VOICE_CALL
                )

                downloadManager.addRequest(
                    DownloadRequest(
                        inputMedias = emptyArray(),
                        ffmpegMixAudioRequest = FFmpegMixAudioRequest(
                            localRawPath = localF.absolutePath,
                            remoteRawPath = remoteF.absolutePath,
                            sampleRate = audioF.sampleRate
                        ),
                        flags = DownloadRequest.Flags.FFMPEG_MIX_AUDIO
                    )
                )
            }
        }

        val startRecording: (format: AudioFormat) -> Unit = { format ->
            synchronized(lock) {
                if (state.callActive.getAndSet(true)) return@synchronized
                state.audioFormat = format
                try {
                    state.localFile = File.createTempFile("call_local_", ".raw", context.androidContext.cacheDir)
                    state.remoteFile = File.createTempFile("call_remote_", ".raw", context.androidContext.cacheDir)
                    state.localStream = FileOutputStream(state.localFile)
                    state.remoteStream = FileOutputStream(state.remoteFile)
                } catch (e: IOException) {
                    context.log.error("Failed to create temp files for call recording", e)
                    state.callActive.set(false)
                }
            }
        }

        AudioTrack::class.java.getConstructor(
            AudioAttributes::class.java,
            AudioFormat::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).hook(HookStage.BEFORE) { param ->
            val audioAttributes = param.arg<AudioAttributes>(0)
            if (audioAttributes.usage != AudioAttributes.USAGE_VOICE_COMMUNICATION) return@hook
            startRecording(param.arg(1))
        }

        AudioTrack::class.java.getMethod("write", ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).hook(HookStage.BEFORE) { param ->
            if (!state.callActive.get()) return@hook
            val byteBuffer = param.arg<ByteBuffer>(0)
            val position = byteBuffer.position()
            val buffer = ByteArray(param.arg(1))
            byteBuffer.get(buffer)
            byteBuffer.position(position)
            state.remoteStream?.write(buffer)
        }

        AudioTrack::class.java.hook("release", HookStage.BEFORE) {
            if (state.callActive.get()) {
                state.remoteReleased.set(true)
                endRecordingAndMix()
            }
        }

        AudioRecord::class.java.getConstructor(
            AudioAttributes::class.java,
            AudioFormat::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).hook(HookStage.BEFORE) { param ->
            val audioAttributes = param.arg<AudioAttributes>(0)
            if (audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT) return@hook
            startRecording(param.arg(1))
        }

        AudioRecord::class.java.getMethod("read", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).hook(HookStage.AFTER) { param ->
            if (!state.callActive.get()) return@hook
            val buffer = param.arg<ByteArray>(0)
            val size = param.getResult() as? Int ?: return@hook
            if (size > 0) {
                state.localStream?.write(buffer.copyOf(size))
            }
        }

        AudioRecord::class.java.hook("release", HookStage.BEFORE) {
            if (state.callActive.get()) {
                state.localReleased.set(true)
                endRecordingAndMix()
            }
        }
    }
}