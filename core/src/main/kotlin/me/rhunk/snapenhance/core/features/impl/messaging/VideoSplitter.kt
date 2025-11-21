package me.rhunk.snapenhance.core.features.impl.messaging

import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import me.rhunk.snapenhance.core.util.local.FileUtil
import java.io.File
import me.rhunk.snapenhance.core.ModContext

class VideoSplitter(
    private val context: ModContext
) {
    fun split(uri: Uri): List<File> {
        context.log.verbose("Starting video split for URI: $uri")
        
        return try {
            val inputPath = FileUtil.getRealPathFromURI(context.androidContext, uri)
            if (inputPath == null) {
                context.log.error("Failed to resolve input path for URI: $uri")
                return emptyList()
            }

            val outputDir = File(context.androidContext.cacheDir, "video_chunks_${System.currentTimeMillis()}").apply { mkdirs() }
            val outputPathPattern = File(outputDir, "chunk_%03d.mp4").absolutePath

            val chunkLength = context.config.messaging.videoSplitting.chunkLength.get()
            // -map 0: Select all streams (video, audio)
            // -segment_time $chunkLength: Split every $chunkLength seconds
            // -f segment: Use segment muxer
            // -reset_timestamps 1: Reset timestamps for each segment so they start at 0
            val command = "-i \"$inputPath\" -c copy -map 0 -segment_time $chunkLength -f segment -reset_timestamps 1 \"$outputPathPattern\""
            
            context.log.verbose("Executing FFmpeg command: $command")
            val session = FFmpegKit.execute(command)

            if (session.returnCode.isValueSuccess) {
                val chunks = outputDir.listFiles()?.toList()?.sortedBy { it.name } ?: emptyList()
                context.log.verbose("Successfully split video into ${chunks.size} chunks")
                chunks
            } else {
                context.log.error("FFmpeg execution failed with return code: ${session.returnCode}")
                context.log.error("FFmpeg failure output: ${session.failStackTrace}")
                // Cleanup on failure
                outputDir.deleteRecursively()
                emptyList()
            }
        } catch (e: Exception) {
            context.log.error("Exception during video splitting", e)
            emptyList()
        }
    }
}
