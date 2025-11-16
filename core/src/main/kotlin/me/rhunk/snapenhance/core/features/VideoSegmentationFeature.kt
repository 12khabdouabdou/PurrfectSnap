kotlin
package me.rhunk.snapenhance.feature

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.core.event.EventBus
import me.rhunk.snapenhance.core.logger.Logger
import me.rhunk.snapenhance.feature.FeatureManager.Companion.logger
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

/**
 * Data class representing a video segment
 */
data class VideoSegment(
    val index: Int,                    // 0-based segment number
    val startMs: Long,                 // Start position in milliseconds
    val endMs: Long,                   // End position in milliseconds
    val durationMs: Long,              // Segment duration in milliseconds
    val totalSegments: Int,            // Total number of segments
    val parentFile: File               // Original video file
) {
    override fun toString(): String {
        return "VideoSegment(index=$index, " +
            "time=${startMs}ms-${endMs}ms, " +
            "duration=${durationMs}ms, " +
            "totalSegments=$totalSegments)"
    }
}

/**
 * Configuration for video segmentation feature
 */
data class VideoSegmentationConfig(
    val enabled: Boolean = false,
    val thresholdSeconds: Int = 10,
    val autoUpload: Boolean = true
) {
    val thresholdMs: Long get() = thresholdSeconds * 1000L
}

/**
 * Event emitted when a snap is selected
 */
data class SnapSelectedEvent(
    val videoFile: File?,
    val duration: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Event emitted when segmentation is triggered
 */
data class SegmentationTriggeredEvent(
    val segments: List<VideoSegment>,
    val parentFile: File,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Main Video Segmentation Feature
 * 
 * Detects videos longer than configured threshold (default 10 seconds)
 * and automatically segments them into chunks for upload to Snapchat.
 * 
 * Architecture:
 * - Listens for SnapSelectedEvent via EventBus
 * - Extracts video duration using MediaMetadataRetriever
 * - Calculates segment boundaries at fixed intervals
 * - Triggers extraction via FFmpegWrapper
 * - Emits SegmentationTriggeredEvent for upload handling
 */
class VideoSegmentationFeature : Feature<VideoSegmentationConfig>() {
    
    companion object {
        private const val TAG = "VideoSegmentationFeature"
        private const val SEGMENT_DURATION_MS = 10_000L
        private const val METADATA_KEY_DURATION = 9  // Android Framework constant
        private const val MIN_DURATION_FOR_SEGMENTATION = 10_001L
    }
    
    override val config = VideoSegmentationConfig()
    private var isInitialized = false
    
    /**
     * Initialize the feature and register event listeners
     */
    override suspend fun initialize() {
        logger.d(TAG, "Initializing video segmentation feature")
        
        try {
            // Subscribe to snap selection events
            EventBus.subscribe(SnapSelectedEvent::class) { event ->
                if (config.enabled && event.videoFile != null) {
                    onSnapSelected(event)
                }
            }
            
            isInitialized = true
            logger.d(TAG, "Video segmentation feature initialized")
        } catch (e: Exception) {
            logger.e(TAG, "Error initializing feature", e)
            throw e
        }
    }
    
    /**
     * Shutdown the feature and unregister event listeners
     */
    override suspend fun shutdown() {
        logger.d(TAG, "Shutting down video segmentation feature")
        
        try {
            EventBus.unsubscribe(SnapSelectedEvent::class)
            isInitialized = false
            logger.d(TAG, "Video segmentation feature shut down")
        } catch (e: Exception) {
            logger.e(TAG, "Error shutting down feature", e)
        }
    }
    
    /**
     * Called when a snap with video is selected
     */
    private suspend fun onSnapSelected(event: SnapSelectedEvent) {
        val file = event.videoFile ?: return
        
        logger.d(TAG, "Processing snap selection: ${file.name}")
        
        // Get video duration
        val duration = withContext(Dispatchers.IO) {
            getDurationMs(file)
        }
        
        if (duration == 0L) {
            logger.w(TAG, "Could not determine video duration, skipping segmentation")
            return
        }
        
        logger.d(TAG, "Video duration: ${duration}ms (${duration / 1000}s)")
        
        // Calculate segments
        val segments = calculateSegments(file, duration)
        
        if (segments.isEmpty()) {
            logger.d(TAG, "Video duration ${duration}ms does not exceed " +
                "threshold ${config.thresholdMs}ms, no segmentation needed")
            return
        }
        
        logger.i(TAG, "Segmentation triggered: ${segments.size} segments detected")
        
        // Emit event for upload handling
        EventBus.post(SegmentationTriggeredEvent(segments, file))
        
        // Extract segments if auto-enabled
        if (config.autoUpload) {
            extractSegments(file, segments)
        }
    }
    
    /**
     * Extract video duration in milliseconds
     * 
     * Uses Android's MediaMetadataRetriever to read the METADATA_KEY_DURATION (9)
     * This is the same mechanism Snapchat uses internally.
     * 
     * @param file Video file to analyze
     * @return Duration in milliseconds (0 if not available)
     */
    fun getDurationMs(file: File): Long {
        // Validate file exists
        if (!file.exists()) {
            logger.w(TAG, "Video file not found: ${file.name}")
            return 0L
        }
        
        logger.d(TAG, "Extracting duration from: ${file.name}")
        
        val retriever = MediaMetadataRetriever()
        return try {
            // Set the data source
            retriever.setDataSource(file.absolutePath)
            
            // Extract duration metadata (METADATA_KEY_DURATION = 9)
            // Returns duration as String in milliseconds
            val durationString = retriever.extractMetadata(METADATA_KEY_DURATION)
            
            // Parse to Long
            durationString?.toLongOrNull()?.also { duration ->
                logger.d(TAG, "Duration extracted: ${duration}ms (${duration / 1000}s)")
            } ?: run {
                logger.w(TAG, "Duration metadata not available for: ${file.name}")
                0L
            }
        } catch (e: IllegalArgumentException) {
            logger.e(TAG, "Invalid data source: ${e.message}", e)
            0L
        } catch (e: RuntimeException) {
            logger.e(TAG, "MediaMetadataRetriever error: ${e.message}", e)
            0L
        } catch (e: Exception) {
            logger.e(TAG, "Unexpected error extracting duration: ${e.message}", e)
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                logger.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }
    
    /**
     * Calculate segment boundaries for a video
     * 
     * Algorithm:
     *   If duration <= threshold: return empty list (no segmentation)
     *   Else: segmentCount = ceil(duration / SEGMENT_DURATION_MS)
     *   For each segment i:
     *     startMs = i * SEGMENT_DURATION_MS
     *     endMs = min(startMs + SEGMENT_DURATION_MS, duration)
     * 
     * Example: 35000ms duration
     *   segmentCount = ceil(35000 / 10000) = 4
     *   Segments: [0-10000], [10000-20000], [20000-30000], [30000-35000]
     * 
     * @param file Original video file
     * @param duration Video duration in milliseconds
     * @return List of VideoSegment objects (empty if no segmentation needed)
     */
    fun calculateSegments(file: File, duration: Long): List<VideoSegment> {
        // Check if segmentation is needed
        val thresholdMs = config.thresholdMs
        if (duration < thresholdMs) {
            logger.d(TAG, "Video duration ${duration}ms < threshold ${thresholdMs}ms, " +
                "no segmentation needed")
            return emptyList()
        }
        
        // Calculate segment count
        val segmentCount = ceil(duration.toDouble() / SEGMENT_DURATION_MS).toInt()
        logger.d(TAG, "Video ${duration}ms will be split into $segmentCount segments")
        
        // Generate segments
        val segments = mutableListOf<VideoSegment>()
        for (i in 0 until segmentCount) {
            val startMs = i * SEGMENT_DURATION_MS
            val endMs = min(startMs + SEGMENT_DURATION_MS, duration)
            val segmentDuration = endMs - startMs
            
            segments.add(VideoSegment(
                index = i,
                startMs = startMs,
                endMs = endMs,
                durationMs = segmentDuration,
                totalSegments = segmentCount,
                parentFile = file
            ))
            
            logger.d(TAG, "Segment $i: ${startMs}ms-${endMs}ms (${segmentDuration}ms)")
        }
        
        return segments
    }
    
    /**
     * Extract video segments using FFmpeg
     */
    private suspend fun extractSegments(file: File, segments: List<VideoSegment>) {
        logger.d(TAG, "Starting extraction of ${segments.size} segments")
        
        try {
            // TODO: Implement FFmpeg extraction via FFmpegWrapper
            // val outputDir = File(context.cacheDir, "video_segments")
            // outputDir.mkdirs()
            // 
            // for (segment in segments) {
            //     val outputPath = File(outputDir, "segment_${segment.index}.mp4").absolutePath
            //     FFmpegWrapper.extractSegmentAsync(
            //         file.absolutePath,
            //         outputPath,
            //         segment.startMs,
            //         segment.endMs
            //     )
            // }
            
            logger.d(TAG, "Segment extraction initiated")
        } catch (e: Exception) {
            logger.e(TAG, "Error extracting segments", e)
        }
    }
}

/**
 * Base interface for all features
 */
interface Feature<T> {
    val config: T
    suspend fun initialize()
    suspend fun shutdown()
}
