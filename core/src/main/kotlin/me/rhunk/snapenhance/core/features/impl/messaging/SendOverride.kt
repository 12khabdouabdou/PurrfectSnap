package me.rhunk.snapenhance.core.features.impl.messaging

import android.media.MediaMetadataRetriever
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.MessagingRuleType
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.util.media.FFmpegProcessor
import me.rhunk.snapenhance.core.util.media.PreviewUtils
import java.io.File
import java.util.concurrent.Executors

class SendOverride : Feature("Send Override", loadParams = FeatureLoadParams.INIT_SYNC) {

    private val executor = Executors.newSingleThreadExecutor()

    override fun init() {
        // Subscribe to the SendMessage event
        event.subscribe(SendMessageWithContentEvent::class) { event ->
            val messageContent = event.messageContent
            
            // Only proceed if Send Override is enabled globally or for this specific rule
            if (!context.config.messaging.sendOverride.get()) return@subscribe

            // Retrieve the override file (the file you selected from gallery/files)
            // Note: The implementation of how 'selectedFile' is stored might vary slightly 
            // based on your specific SE version. Assuming standard retrieval here.
            val overrideFile = context.features.find { it is me.rhunk.snapenhance.core.features.impl.ui.MediaFilePicker }
                ?.let { (it as me.rhunk.snapenhance.core.features.impl.ui.MediaFilePicker).currentFile } 
                ?: return@subscribe

            if (!overrideFile.exists()) return@subscribe

            // Check if we should split the video
            // Ensure you added 'splitLongVideos' to your MessagingConfig!
            if (context.config.messaging.splitLongVideos.get() && isVideo(overrideFile)) {
                
                val duration = getVideoDuration(overrideFile)
                // If video is longer than 10.5 seconds (buffer for 10s limit)
                if (duration > 10500) {
                    // 1. Cancel the original single message send
                    event.canceled = true
                    
                    // 2. Start the splitting and sending process in background
                    executor.submit {
                        splitAndSend(overrideFile, event)
                    }
                    return@subscribe
                }
            }

            // Standard SendOverride logic (for images or short videos)
            // This replaces the content of the message with your override file
            messageContent.content = PreviewUtils.readBytes(overrideFile)
            
            // Set type based on file extension
            if (isVideo(overrideFile)) {
                messageContent.contentType = ContentType.VIDEO
            } else {
                messageContent.contentType = ContentType.IMAGE
            }
        }
    }

    /**
     * Logic to split the video and resend individual chunks.
     */
    private fun splitAndSend(originalFile: File, originalEvent: SendMessageWithContentEvent) {
        val ffmpeg = context.feature(FFmpegProcessor::class)
        
        if (!ffmpeg.isDownloaded()) {
            context.log.error("SendOverride: FFmpeg not downloaded. Cannot split video.")
            return
        }

        val durationMs = getVideoDuration(originalFile)
        // Snap default is ~10s. We use 10000ms.
        val chunkDurationMs = 10000L 
        val chunkCount = (durationMs / chunkDurationMs) + (if (durationMs % chunkDurationMs > 0) 1 else 0)

        context.log.verbose("SendOverride: Splitting video of ${durationMs}ms into $chunkCount parts.")

        for (i in 0 until chunkCount) {
            val startSec = i * 10
            val outputFile = File(context.androidContext.cacheDir, "se_split_${System.currentTimeMillis()}_$i.mp4")

            // FFmpeg command: -i [input] -ss [start] -t 10 -c copy [output]
            // -c copy is fast but might be inaccurate on keyframes. 
            // Switch to "-c:v libx264 -preset ultrafast" if you get black frames.
            val command = mutableListOf(
                "-i", originalFile.absolutePath,
                "-ss", "$startSec",
                "-t", "10",
                "-c", "copy",
                outputFile.absolutePath
            )

            ffmpeg.execute(command)

            if (outputFile.exists()) {
                // Send this specific chunk
                sendSingleChunk(outputFile, originalEvent)
                
                // Wait slightly to ensure message ordering in the chat
                Thread.sleep(800)
            } else {
                context.log.error("SendOverride: Failed to create split chunk $i")
            }
        }
    }

    /**
     * Re-invokes the Snapchat send infrastructure for a specific file chunk.
     */
    private fun sendSingleChunk(file: File, originalEvent: SendMessageWithContentEvent) {
        // We need to create a NEW event or manually invoke the adapter.
        // Since we are inside the feature, the easiest way is to invoke the 
        // original adapter call that triggered the event, but with modified data.
        
        // Note: Because we canceled the original event, the native call didn't happen.
        // We effectively need to duplicate the logic that 'SendMessageWithContentEvent' wraps.
        
        // Access the adapter or conversation manager from the event
        val adapter = originalEvent.adapter
        val messageContent = originalEvent.messageContent
        val dests = originalEvent.destinations

        // Update content to the new chunk
        val newContent = messageContent.copy()
        newContent.content = PreviewUtils.readBytes(file)
        newContent.contentType = ContentType.VIDEO

        // Manually trigger the send via the adapter
        // This requires the 'sendMessage' method on the adapter to be accessible.
        // If 'adapter' is the SnapMessagingAdapter, you might need to check its available methods.
        
        try {
            adapter.sendMessage(
                dests,
                newContent,
                originalEvent.callback // Use original callback (might fire only once though)
            )
        } catch (e: Exception) {
            context.log.error("SendOverride: Failed to send chunk", e)
        }
        
        // Cleanup cache file
        try { file.delete() } catch (_: Exception) {}
    }

    private fun isVideo(file: File): Boolean {
        return file.name.endsWith(".mp4", ignoreCase = true) || 
               file.name.endsWith(".mkv", ignoreCase = true) ||
               file.name.endsWith(".mov", ignoreCase = true)
    }

    private fun getVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
