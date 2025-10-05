package me.rhunk.snapenhance.core.features.impl.experiments

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.rhunk.snapenhance.common.data.download.AudioStreamFormat
import me.rhunk.snapenhance.common.data.download.MediaDownloadSource
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.impl.downloader.MediaDownloader
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.hook.hookConstructor
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.util.ktx.getObjectFieldOrNull
import me.rhunk.snapenhance.core.util.media.HttpServer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class CallRecorder : Feature("Call Recorder") {
    private val httpServer = HttpServer(
        timeout = Integer.MAX_VALUE
    )
    private val participants = CopyOnWriteArrayList<String>()
    private val lock = Any()

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
        val remoteQueue = LinkedBlockingQueue<ByteArray>()
        val localQueue = LinkedBlockingQueue<ByteArray>()
        var callActive = false

        val startRecording: (format: AudioFormat) -> Unit = { format ->
            synchronized(lock) {
                if (callActive) return@synchronized
                callActive = true

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
                                val mixerThread = thread(start = false) {
                                    try {
                                        while (true) {
                                            val remoteData = remoteQueue.take()
                                            val localData = localQueue.take()

                                            if (remoteData.isEmpty() || localData.isEmpty()) {
                                                break
                                            }

                                            val maxSize = maxOf(remoteData.size, localData.size)
                                            val paddedRemote = if (remoteData.size < maxSize) remoteData.copyOf(maxSize) else remoteData
                                            val paddedLocal = if (localData.size < maxSize) localData.copyOf(maxSize) else localData
                                            val mixedData = ByteArray(maxSize * 2)

                                            for (i in 0 until maxSize step 2) {
                                                mixedData[i * 2] = paddedRemote[i]
                                                mixedData[i * 2 + 1] = paddedRemote[i + 1]
                                                mixedData[i * 2 + 2] = paddedLocal[i]
                                                mixedData[i * 2 + 3] = paddedLocal[i + 1]
                                            }
                                            outputStream.write(mixedData)
                                            outputStream.flush()
                                        }
                                    } catch (e: InterruptedException) {
                                        // expected
                                    } catch (t: Throwable) {
                                        context.log.error("Call recorder mixer thread failed", t)
                                    } finally {
                                        outputStream.close()
                                    }
                                }

                                override val onOpen: () -> Unit = {
                                    mixerThread.start()
                                }

                                override val readBytes: (byteArray: ByteArray) -> Int = { byteArray ->
                                    inputStream.read(byteArray)
                                }

                                override val onClose: () -> Unit = {
                                    context.log.verbose("Streaming url closed")
                                    callActive = false
                                    mixerThread.interrupt()
                                    remoteQueue.clear()
                                    localQueue.clear()
                                    inputStream.close()
                                }
                            }
                        }
                    }
                ) ?: return@synchronized

                context.log.verbose("streaming url = $streamUrl, sampleRate = ${format.sampleRate}, audioFormat = ${format.encoding}")

                context.feature(MediaDownloader::class).provideDownloadManagerClient(
                    UUID.randomUUID().toString(),
                    participants.mapNotNull { context.database.getFriendInfo(it)?.mutableUsername }.joinToString("-"),
                    System.currentTimeMillis(),
                    MediaDownloadSource.VOICE_CALL
                ).downloadStream(streamUrl, AudioStreamFormat(2, format.sampleRate, format.encoding)) // Stereo
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
            if (!callActive) return@hook
            val byteBuffer = param.arg<ByteBuffer>(0)
            val position = byteBuffer.position()
            val buffer = ByteArray(param.arg(1))
            byteBuffer.get(buffer)
            byteBuffer.position(position)
            remoteQueue.put(buffer)
        }

        AudioTrack::class.java.hook("release", HookStage.BEFORE) {
            if (callActive) {
                remoteQueue.put(ByteArray(0))
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
            if (!callActive) return@hook
            val buffer = param.arg<ByteArray>(0)
            val size = param.getResult() as? Int ?: return@hook
            if (size > 0) {
                localQueue.put(buffer.copyOf(size))
            }
        }

        AudioRecord::class.java.hook("release", HookStage.BEFORE) {
            if (callActive) {
                localQueue.put(ByteArray(0))
            }
        }
    }
}