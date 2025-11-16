package me.rhunk.snapenhance.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackageParam
import me.rhunk.snapenhance.core.event.EventBus
import me.rhunk.snapenhance.core.logger.Logger
import me.rhunk.snapenhance.feature.SegmentationTriggeredEvent
import me.rhunk.snapenhance.feature.SnapSelectedEvent
import me.rhunk.snapenhance.feature.VideoSegmentationFeature
import kotlin.math.ceil

/**
 * Hook for Snapchat upload mechanism
 * 
 * Intercepts Snapchat's video duration detection to identify long videos
 * and trigger segmentation. Uses Xposed framework for method hooking.
 * 
 * From BMAD analysis:
 * - Snapchat uses class AL1 (obfuscated) for video handling
 * - Method: getDurationMs() (signature: ()J)
 * - Uses MediaMetadataRetriever.extractMetadata(9)
 * - 10-second segments are hard-coded boundary
 */
class SnapUploadHook(
    private val feature: VideoSegmentationFeature,
    private val logger: Logger
) {
    
    companion object {
        private const val TAG = "SnapUploadHook"
        private const val SNAPCHAT_PACKAGE = "com.snapchat.android"
        private const val SEGMENT_THRESHOLD_MS = 10_000L
    }
    
    private var hookInstalled = false
    
    /**
     * Install hooks into Snapchat
     */
    fun install(loadPackageParam: XC_LoadPackageParam) {
        if (loadPackageParam.packageName != SNAPCHAT_PACKAGE) {
            return
        }
        
        logger.d(TAG, "Installing hooks into Snapchat ${loadPackageParam.packageName}")
        
        try {
            hookDurationDetection(loadPackageParam)
            hookInstalled = true
            logger.d(TAG, "Snapchat hooks installed successfully")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to install Snapchat hooks", e)
        }
    }
    
    /**
     * Uninstall hooks from Snapchat
     */
    fun uninstall() {
        if (hookInstalled) {
            // Note: Xposed doesn't provide a direct uninstall mechanism
            // Hooks are automatically removed when Xposed module is disabled
            hookInstalled = false
            logger.d(TAG, "Snapchat hooks marked for removal")
        }
    }
    
    /**
     * Hook AL1.getDurationMs() method
     * 
     * AL1 is an obfuscated Snapchat class that handles video uploads
     * The getDurationMs() method is called when determining if a video
     * needs special handling (e.g., long video flow)
     * 
     * From smali analysis (AL1.smali:869):
     *   .method public final getDurationMs()J
     *   ...
     *   const/16 v0, 0x9                    # METADATA_KEY_DURATION = 9
     *   invoke-virtual {p0, v0}, LAL1;->l(I)Ljava/lang/String;
     *   invoke-static {v0}, Ljava/lang/Long;->parseLong(Ljava/lang/String;)J
     *   return-wide v0
     */
    private fun hookDurationDetection(loadPackageParam: XC_LoadPackageParam) {
        try {
            // Find the AL1 class (video upload handler)
            val al1Class = findAL1Class(loadPackageParam.classLoader)
                ?: return logger.w(TAG, "Could not find AL1 class for hooking")
            
            logger.d(TAG, "Found AL1 class, installing getDurationMs hook")
            
            // Hook the getDurationMs method
            XposedHelpers.findAndHookMethod(
                al1Class,
                "getDurationMs",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val duration = param.result as? Long ?: return
                            
                            if (duration > SEGMENT_THRESHOLD_MS) {
                                val segmentCount = ceil(duration.toDouble() / SEGMENT_THRESHOLD_MS).toInt()
                                logger.i(TAG, "Long video detected: ${duration}ms " +
                                    "($segmentCount segments needed)")
                                
                                // Notify feature to handle segmentation
                                onLongVideoDetected(duration)
                            } else {
                                logger.d(TAG, "Normal video duration: ${duration}ms")
                            }
                        } catch (e: Exception) {
                            logger.e(TAG, "Error in getDurationMs hook", e)
                        }
                    }
                }
            )
            
            logger.d(TAG, "Successfully hooked AL1.getDurationMs()")
        } catch (e: Exception) {
            logger.e(TAG, "Error hooking duration detection", e)
        }
    }
    
    /**
     * Handle detection of long video
     */
    private fun onLongVideoDetected(duration: Long) {
        try {
            // Calculate segment count
            val segmentCount = ceil(duration.toDouble() / SEGMENT_THRESHOLD_MS).toInt()
            
            logger.i(TAG, "Video segmentation will be triggered: " +
                "$duration ms → $segmentCount segments")
            
            // Post event for feature to handle
            EventBus.post(SegmentationTriggeredEvent(
                segments = emptyList(),  // Will be populated by feature
                parentFile = null,       // Will be set when snap is selected
                timestamp = System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            logger.e(TAG, "Error handling long video detection", e)
        }
    }
    
    /**
     * Find the AL1 class in Snapchat
     * 
     * AL1 is an obfuscated class name. We try multiple strategies:
     * 1. Direct class load with known package
     * 2. Class name variations
     * 3. Signature-based discovery (future enhancement)
     */
    private fun findAL1Class(classLoader: ClassLoader): Class<*>? {
        return try {
            // Try Snapchat's main package
            logger.d(TAG, "Attempting to load AL1 from com.snapchat.android")
            classLoader.loadClass("com.snapchat.android.AL1")
        } catch (e: Exception) {
            try {
                // Try alternative package path
                logger.d(TAG, "Attempting to load AL1 from com.snap.upload")
                classLoader.loadClass("com.snap.upload.AL1")
            } catch (e: Exception) {
                try {
                    // Try without package prefix
                    logger.d(TAG, "Attempting to load AL1 without package")
                    classLoader.loadClass("AL1")
                } catch (e: Exception) {
                    logger.w(TAG, "Could not find AL1 class with standard methods")
                    null
                }
            }
        }
    }
}
