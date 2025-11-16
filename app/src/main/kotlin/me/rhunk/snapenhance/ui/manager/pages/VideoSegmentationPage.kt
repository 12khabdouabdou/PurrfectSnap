package me.rhunk.snapenhance.ui.manager.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import me.rhunk.snapenhance.feature.VideoSegmentationConfig

/**
 * Settings page for video segmentation feature
 * 
 * Allows users to:
 * - Enable/disable the feature
 * - Configure segmentation threshold (5-30 seconds)
 * - Toggle automatic upload of segments
 */
@Composable
fun VideoSegmentationPage(
    initialConfig: VideoSegmentationConfig = VideoSegmentationConfig(),
    onConfigChange: (VideoSegmentationConfig) -> Unit
) {
    var enabled by remember { mutableStateOf(initialConfig.enabled) }
    var threshold by remember { mutableStateOf(initialConfig.thresholdSeconds.toFloat()) }
    var autoUpload by remember { mutableStateOf(initialConfig.autoUpload) }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                "Video Auto-Segmentation",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "Automatically split long videos into shorter segments for upload",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Feature Toggle
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Enable Feature",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Activate video segmentation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            onConfigChange(VideoSegmentationConfig(
                                enabled = newValue,
                                thresholdSeconds = threshold.toInt(),
                                autoUpload = autoUpload
                            ))
                        }
                    )
                }
            }
        }
        
        // Threshold Slider (only shown when enabled)
        if (enabled) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Segmentation Threshold",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Slider(
                                value = threshold,
                                onValueChange = { newValue ->
                                    threshold = newValue
                                    onConfigChange(VideoSegmentationConfig(
                                        enabled = enabled,
                                        thresholdSeconds = newValue.toInt(),
                                        autoUpload = autoUpload
                                    ))
                                },
                                valueRange = 5f..30f,
                                steps = 25,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${threshold.toInt()}s",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(40.dp)
                            )
                        }
                        
                        Text(
                            "Videos longer than ${threshold.toInt()} seconds will be automatically split",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Auto-Upload Toggle (only shown when enabled)
        if (enabled) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Auto-Upload Segments",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Segments upload automatically after creation",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoUpload,
                            onCheckedChange = { newValue ->
                                autoUpload = newValue
                                onConfigChange(VideoSegmentationConfig(
                                    enabled = enabled,
                                    thresholdSeconds = threshold.toInt(),
                                    autoUpload = newValue
                                ))
                            }
                        )
                    }
                }
            }
        }
        
        // Info Card
        if (enabled) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Info",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    "How it works",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "1. Detects videos longer than the threshold\n" +
                                    "2. Automatically splits into equal segments\n" +
                                    "3. Each segment is uploaded separately\n" +
                                    "4. Recipient sees a single snap",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Preview for Compose preview
@Composable
fun VideoSegmentationPagePreview() {
    VideoSegmentationPage(onConfigChange = {})
}
```

---

## PART 5: RUST NATIVE MODULE

### File: native/rust/src/lib.rs

```rust
use std::process::Command;
use std::path::Path;

pub mod modules {
    pub mod video_segmenter;
}

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};

/// Extract a video segment using FFmpeg
/// 
/// Called from Kotlin via JNI
/// 
/// #[no_mangle] - Don't mangle the function name for JNI
/// extern "C" - C calling convention
#[no_mangle]
pub extern "C" fn Java_me_rhunk_snapenhance_native_FFmpegWrapper_nativeExtractSegment(
    env: JNIEnv,
    _class: JClass,
    input_path: JString,
    output_path: JString,
    start_ms: jlong,
    end_ms: jlong,
) -> jboolean {
    // Convert Java strings to Rust strings
    let input = match env.get_string(&input_path) {
        Ok(s) => s.into(),
        Err(e) => {
            eprintln!("Failed to get input path: {}", e);
            return 0;
        }
    };
    
    let output = match env.get_string(&output_path) {
        Ok(s) => s.into(),
        Err(e) => {
            eprintln!("Failed to get output path: {}", e);
            return 0;
        }
    };
    
    // Call Rust implementation
    match modules::video_segmenter::extract_segment(&input, &output, start_ms, end_ms) {
        Ok(_) => 1,  // true
        Err(e) => {
            eprintln!("FFmpeg error: {}", e);
            0  // false
        }
    }
}
