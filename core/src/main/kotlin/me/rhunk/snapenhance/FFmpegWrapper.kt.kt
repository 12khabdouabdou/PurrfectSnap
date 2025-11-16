
package me.rhunk.snapenhance.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.core.logger.Logger
import java.io.File

/**
 * JNI wrapper for native FFmpeg segment extraction
 * 
 * Provides Kotlin-friendly coroutine-based access to the Rust
 * video_segmenter native module via JNI.
 */
object FFmpegWrapper {
    
    private const val TAG = "FFmpegWrapper"
    private const val NATIVE_LIB = "video_segmenter"
    
    private val logger: Logger by lazy { Logger.getInstance() }
    
    init {
        try {
            System.loadLibrary(NATIVE_LIB)
            logger.d(TAG, "Successfully loaded $NATIVE_LIB library")
        } catch (e: UnsatisfiedLinkError) {
            logger.e(TAG, "Failed to load $NATIVE_LIB library: ${e.message}", e)
            throw RuntimeException("Native library $NATIVE_LIB not available", e)
        }
    }
    
    /**
     * Extract a video segment asynchronously using FFmpeg
     * 
     * Performs lossless extraction using codec copy mode:
     *   ffmpeg -i input -ss start -to end -c:v copy -c:a copy output
     * 
     * @param inputPath Path to input video file
     * @param outputPath Path to output segment file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @return true if extraction succeeded, false otherwise
     * 
     * @throws IllegalArgumentException if arguments are invalid
     */
    suspend fun extractSegmentAsync(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        // Validate inputs
        require(inputPath.isNotEmpty()) { "Input path cannot be empty" }
        require(outputPath.isNotEmpty()) { "Output path cannot be empty" }
        require(startMs >= 0) { "Start time must be >= 0" }
        require(endMs > startMs) { "End time must be > start time" }
        require(File(inputPath).exists()) { "Input file not found: $inputPath" }
        
        try {
            logger.d(TAG, "Extracting segment: $inputPath [$startMs-$endMs]ms → $outputPath")
            
            // Call native function
            val success = nativeExtractSegment(inputPath, outputPath, startMs, endMs)
            
            if (success) {
                logger.d(TAG, "Successfully extracted segment: $outputPath")
                val outputFile = File(outputPath)
                val sizeKb = outputFile.length() / 1024
                logger.d(TAG, "Output file size: ${sizeKb}KB")
            } else {
                logger.e(TAG, "FFmpeg extraction failed for: $outputPath")
            }
            
            success
        } catch (e: Exception) {
            logger.e(TAG, "Error extracting segment: ${e.message}", e)
            false
        }
    }
    
    /**
     * Extract multiple segments from a video asynchronously
     * 
     * @param inputPath Path to input video file
     * @param outputDir Directory for output segments
     * @param segments List of (startMs, endMs) tuples
     * @return List of successfully extracted segment paths
     */
    suspend fun extractSegmentsAsync(
        inputPath: String,
        outputDir: String,
        segments: List<Pair<Long, Long>>
    ): List<String> = withContext(Dispatchers.IO) {
        val outputDirectory = File(outputDir)
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }
        
        val results = mutableListOf<String>()
        
        for ((index, segment) in segments.withIndex()) {
            val (startMs, endMs) = segment
            val outputPath = File(
                outputDir,
                "segment_${index.toString().padStart(2, '0')}.mp4"
            ).absolutePath
            
            if (extractSegmentAsync(inputPath, outputPath, startMs, endMs)) {
                results.add(outputPath)
            } else {
                logger.w(TAG, "Failed to extract segment $index")
            }
        }
        
        logger.d(TAG, "Extracted ${results.size}/${segments.size} segments")
        results
    }
    
    /**
     * Native function for segment extraction
     * 
     * Implemented in Rust (native/rust/src/lib.rs)
     * Calls FFmpeg via command-line execution
     *
     * @param inputPath Path to input video file
     * @param outputPath Path to output segment file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @return true on success, false on failure
     */
    private external fun nativeExtractSegment(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): Boolean
}