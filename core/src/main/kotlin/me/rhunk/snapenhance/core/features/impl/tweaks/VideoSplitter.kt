package me.rhunk.snapenhance.core.features.impl.tweaks

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import me.rhunk.snapenhance.core.features.Feature
import java.io.File
import java.io.InputStream

class VideoSplitter : Feature("Video Splitter") {

    override fun init() {
        // Init logic if needed
    }

    /**
     * Main entry point: Split video at URI and output chunks to cache.
     * @return List of split video files (chunk_000.mp4, chunk_001.mp4, ...)
     */
    fun split(sourceUri: Uri): List<File> {
        val androidContext = context.androidContext
        val inputStream: InputStream? = androidContext.contentResolver.openInputStream(sourceUri)
        
        if (inputStream == null) {
            context.log.error("VideoSplitter: Could not open input stream for URI: $sourceUri")
            return emptyList()
        }

        // 1. Copy stream to a temporary source file
        val tempDir = File(androidContext.cacheDir, "video_splitter").apply { mkdirs() }
        // Clean up old session
        tempDir.listFiles()?.forEach { it.delete() }
        
        val sourceFile = File(tempDir, "source_video.mp4")
        sourceFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        
        // 2. Run FFmpeg command
        // -c copy: Stream copy (fast)
        // -f segment: Split into segments
        // -segment_time 10: 10 seconds each
        // -reset_timestamps 1: Essential for playback
        val outputPattern = File(tempDir, "chunk_%03d.mp4").absolutePath
        val command = "ffmpeg -i ${sourceFile.absolutePath} -c copy -f segment -segment_time 10 -reset_timestamps 1 $outputPattern"
        
        try {
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                context.log.error("VideoSplitter: FFmpeg failed with exit code $exitCode")
                // Read error stream
                val errorMsg = process.errorStream.bufferedReader().readText()
                context.log.error("VideoSplitter: FFmpeg stderr: $errorMsg")
                return emptyList()
            }
        } catch (e: Exception) {
            context.log.error("VideoSplitter: Exception during FFmpeg execution", e)
            return emptyList()
        }

        // 3. Collect results
        // Sort by name to ensure 000, 001, 002 order
        return tempDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".mp4") }
            ?.sortedBy { it.name }
            ?.toList() ?: emptyList()
    }

    /**
     * Sends the list of files sequentially using the existing ChatMediaDrawer.sendItems method.
     */
    fun sequentialSend(files: List<File>, sendFunction: (File) -> Unit) {
        runOnUiThread {
             context.mainActivity?.let {
                 context.inAppOverlay.showStatusToast(Icons.Default.Upload, "Sending ${files.size} parts...")
             }
        }

        files.forEachIndexed { index, file ->
            try {
                context.log.verbose("VideoSplitter: Sending chunk ${index + 1}/${files.size}: ${file.name}")
                sendFunction(file)
                
                // Wait a bit between sends to ensure order and prevent rate limits/race conditions
                if (index < files.size - 1) {
                   Thread.sleep(1500) // 1.5s delay
                }
            } catch (e: Exception) {
                context.log.error("VideoSplitter: Failed to send chunk ${file.name}", e)
            }
        }
    }
}
