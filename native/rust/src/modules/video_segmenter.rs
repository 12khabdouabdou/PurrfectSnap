use std::process::Command;
use std::path::Path;

/// Format milliseconds to FFmpeg time format (HH:MM:SS.mmm)
///
/// FFmpeg expects time in HH:MM:SS.mmm format for -ss and -to parameters
///
/// Examples:
///   0ms      → "00:00:00.000"
///   1000ms   → "00:00:01.000"
///   10000ms  → "00:00:10.000"
///   35000ms  → "00:00:35.000"
///   125500ms → "00:02:05.500"
pub fn format_time_ffmpeg(ms: i64) -> String {
    let total_seconds = ms / 1000;
    let milliseconds = ms % 1000;
    
    let hours = total_seconds / 3600;
    let minutes = (total_seconds % 3600) / 60;
    let seconds = total_seconds % 60;
    
    format!("{:02}:{:02}:{:02}.{:03}", hours, minutes, seconds, milliseconds)
}

/// Extract a video segment using FFmpeg
///
/// Uses lossless codec copy mode for fast extraction without re-encoding:
///   ffmpeg -i input -ss start -to end -c:v copy -c:a copy output
///
/// # Arguments
///
/// * `input_path` - Path to input video file
/// * `output_path` - Path to output segment file
/// * `start_ms` - Start time in milliseconds
/// * `end_ms` - End time in milliseconds
///
/// # Returns
///
/// * `Ok(())` - Segment extracted successfully
/// * `Err(String)` - Error message
///
/// # Examples
///
/// ```rust
/// extract_segment(
///     "/sdcard/video.mp4",
///     "/sdcard/segment_0.mp4",
///     0,
///     10000
/// )
/// ```
pub fn extract_segment(
    input_path: &str,
    output_path: &str,
    start_ms: i64,
    end_ms: i64,
) -> Result<(), String> {
    // Validate input file exists
    if !Path::new(input_path).exists() {
        return Err(format!("Input file not found: {}", input_path));
    }
    
    // Validate time range
    if start_ms < 0 || end_ms <= start_ms {
        return Err(format!(
            "Invalid time range: {}ms - {}ms",
            start_ms, end_ms
        ));
    }
    
    // Format times for FFmpeg
    let start_time = format_time_ffmpeg(start_ms);
    let end_time = format_time_ffmpeg(end_ms);
    
    println!("Extracting segment: {} → {} ({}-{})", 
        start_time, end_time, start_ms, end_ms);
    
    // Build and execute FFmpeg command
    let output = Command::new("ffmpeg")
        .args(&[
            "-hide_banner",           // Hide FFmpeg banner
            "-loglevel", "error",     // Only show errors
            "-i", input_path,         // Input file
            "-ss", &start_time,       // Seek to start
            "-to", &end_time,         // Seek to end
            "-c:v", "copy",           // Copy video codec (no re-encoding)
            "-c:a", "copy",           // Copy audio codec
            "-avoid_negative_ts", "make_zero",  // Handle negative timestamps
            "-y",                     // Overwrite output without asking
            output_path,              // Output file
        ])
        .output()
        .map_err(|e| {
            format!("Failed to execute FFmpeg: {}", e)
        })?;
    
    // Check if FFmpeg succeeded
    if output.status.success() {
        println!("Successfully extracted segment: {}", output_path);
        Ok(())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        Err(format!(
            "FFmpeg failed - stderr: {}, stdout: {}",
            stderr, stdout
        ))
    }
}

/// Extract multiple segments from a video
pub fn extract_segments(
    input_path: &str,
    output_dir: &str,
    segments: Vec<(i64, i64)>,
) -> Result<Vec<String>, String> {
    let mut results = Vec::new();
    
    for (index, (start_ms, end_ms)) in segments.iter().enumerate() {
        let output_path = format!(
            "{}/segment_{:02}.mp4",
            output_dir, index
        );
        
        match extract_segment(input_path, &output_path, *start_ms, *end_ms) {
            Ok(_) => results.push(output_path),
            Err(e) => return Err(e),
        }
    }
    
    Ok(results)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_format_time_ffmpeg_zero() {
        assert_eq!(format_time_ffmpeg(0), "00:00:00.000");
    }

    #[test]
    fn test_format_time_ffmpeg_one_second() {
        assert_eq!(format_time_ffmpeg(1000), "00:00:01.000");
    }

    #[test]
    fn test_format_time_ffmpeg_ten_seconds() {
        assert_eq!(format_time_ffmpeg(10000), "00:00:10.000");
    }

    #[test]
    fn test_format_time_ffmpeg_thirty_five_seconds() {
        assert_eq!(format_time_ffmpeg(35000), "00:00:35.000");
    }

    #[test]
    fn test_format_time_ffmpeg_with_milliseconds() {
        assert_eq!(format_time_ffmpeg(125500), "00:02:05.500");
    }

    #[test]
    fn test_format_time_ffmpeg_with_hours() {
        assert_eq!(format_time_ffmpeg(3661500), "01:01:01.500");
    }

    #[test]
    fn test_extract_segment_validation_missing_file() {
        let result = extract_segment(
            "/nonexistent/file.mp4",
            "/tmp/output.mp4",
            0,
            10000
        );
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("not found"));
    }

    #[test]
    fn test_extract_segment_validation_invalid_time() {
        let result = extract_segment(
            "/valid/file.mp4",
            "/tmp/output.mp4",
            10000,
            5000,  // end < start
        );
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Invalid"));
    }
}
