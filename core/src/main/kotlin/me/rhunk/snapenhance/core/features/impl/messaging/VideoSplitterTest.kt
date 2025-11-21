package me.rhunk.snapenhance.core.features.impl.messaging

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import me.rhunk.snapenhance.core.ModContext
import me.rhunk.snapenhance.core.logger.CoreLogger
import me.rhunk.snapenhance.core.util.local.FileUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.contains
import java.io.File

class VideoSplitterTest {

    @Mock
    private lateinit var mockModContext: ModContext

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockLogger: CoreLogger

    @Mock
    private lateinit var mockUri: Uri

    @Mock
    private lateinit var mockFFmpegSession: FFmpegSession

    @Mock
    private lateinit var mockReturnCode: ReturnCode

    private lateinit var videoSplitter: VideoSplitter
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        cacheDir = File("build/tmp/test_cache")
        cacheDir.mkdirs()

        `when`(mockModContext.androidContext).thenReturn(mockContext)
        `when`(mockModContext.log).thenReturn(mockLogger)
        `when`(mockContext.cacheDir).thenReturn(cacheDir)

        // Mock FileUtil static method? 
        // FileUtil.getRealPathFromURI is static. Mockito-inline or PowerMock needed for static mocking.
        // Alternatively, we can assume FileUtil works if we pass a file URI, or we need to refactor VideoSplitter to take a FileUtil instance.
        // For now, let's try to use a real file path if possible or mock the static if we add mockito-inline.
        
        // Since we can't easily mock static methods with standard Mockito without extra config, 
        // and adding dependencies is already a hurdle, let's try to make the test work by creating a real file.
        
        videoSplitter = VideoSplitter(mockModContext)
    }

    @Test
    fun `test split with valid video`() {
        // Mock input path resolution
        val inputUriString = "content://media/external/video/media/123"
        val inputPath = "/storage/emulated/0/DCIM/Camera/video.mp4"
        
        mockStatic(FileUtil::class.java).use { fileUtilMock ->
            fileUtilMock.`when`<String> { FileUtil.getRealPathFromURI(any(), any()) }.thenReturn(inputPath)
            
            // Mock FFmpeg execution
            mockStatic(FFmpegKit::class.java).use { ffmpegKitMock ->
                `when`(mockFFmpegSession.returnCode).thenReturn(mockReturnCode)
                `when`(mockReturnCode.isValueSuccess).thenReturn(true)
                ffmpegKitMock.`when`<FFmpegSession> { FFmpegKit.execute(anyString()) }.thenReturn(mockFFmpegSession)

                // Create fake output files
                val timestamp = System.currentTimeMillis()
                val outputDir = File(cacheDir, "video_chunks_$timestamp")
                outputDir.mkdirs()
                File(outputDir, "chunk_000.mp4").createNewFile()
                File(outputDir, "chunk_001.mp4").createNewFile()
                File(outputDir, "chunk_002.mp4").createNewFile()

                // We need to mock System.currentTimeMillis() or just match the directory creation pattern
                // Since we can't easily mock System time without more libs, let's rely on the fact that VideoSplitter creates a NEW directory.
                // Wait, VideoSplitter creates the directory internally. We can't pre-create it easily with the exact timestamp unless we mock time.
                // OR we can just let VideoSplitter create it, and we mock the FFmpeg command to NOT actually do anything but we manually create files 
                // AFTER the command (simulated) execution but BEFORE the listFiles() call?
                // No, listFiles happens inside split().
                
                // Better approach: Mock the File constructor? No, too hard.
                // Let's use a fixed timestamp if possible, or just accept that we can't verify the exact directory name easily without PowerMock.
                // BUT, VideoSplitter logic is: 1. Create Dir, 2. Run FFmpeg, 3. List Files.
                // We can mock FFmpegKit.execute to create the files!
                
                ffmpegKitMock.`when`<FFmpegSession> { FFmpegKit.execute(anyString()) }.thenAnswer {
                    // Find the directory created by VideoSplitter. 
                    // It should be the only directory in our test cacheDir starting with "video_chunks_"
                    val createdDir = cacheDir.listFiles()?.find { it.name.startsWith("video_chunks_") }
                    createdDir?.let { dir ->
                        File(dir, "chunk_000.mp4").createNewFile()
                        File(dir, "chunk_001.mp4").createNewFile()
                        File(dir, "chunk_002.mp4").createNewFile()
                    }
                    mockFFmpegSession
                }

                val result = videoSplitter.split(mockUri)

                assertEquals(3, result.size)
                assertEquals("chunk_000.mp4", result[0].name)
                assertEquals("chunk_001.mp4", result[1].name)
                assertEquals("chunk_002.mp4", result[2].name)
                
                // Verify logging
                verify(mockLogger, atLeastOnce()).verbose(contains("Successfully split video"))
            }
        }
    }

    @Test
    fun `test split failure handles cleanup`() {
        val inputPath = "/storage/emulated/0/DCIM/Camera/video.mp4"
        
        mockStatic(FileUtil::class.java).use { fileUtilMock ->
            fileUtilMock.`when`<String> { FileUtil.getRealPathFromURI(any(), any()) }.thenReturn(inputPath)
            
            mockStatic(FFmpegKit::class.java).use { ffmpegKitMock ->
                `when`(mockFFmpegSession.returnCode).thenReturn(mockReturnCode)
                `when`(mockReturnCode.isValueSuccess).thenReturn(false) // Simulate failure
                `when`(mockFFmpegSession.failStackTrace).thenReturn("Some error")
                ffmpegKitMock.`when`<FFmpegSession> { FFmpegKit.execute(anyString()) }.thenReturn(mockFFmpegSession)

                val result = videoSplitter.split(mockUri)

                assertTrue(result.isEmpty())
                
                // Verify cleanup: Directory should be gone (or empty if deleteRecursively works on the dir itself)
                // VideoSplitter deletes the directory.
                val createdDir = cacheDir.listFiles()?.find { it.name.startsWith("video_chunks_") }
                assertTrue(createdDir == null || !createdDir.exists())
                
                // Verify error logging
                verify(mockLogger).error(contains("FFmpeg execution failed"), any())
            }
        }
    }

    @Test
    fun `test split with invalid uri returns empty`() {
        mockStatic(FileUtil::class.java).use { fileUtilMock ->
            fileUtilMock.`when`<String> { FileUtil.getRealPathFromURI(any(), any()) }.thenReturn(null)
            
            val result = videoSplitter.split(mockUri)
            
            assertTrue(result.isEmpty())
            verify(mockLogger).error(contains("Failed to resolve input path"))
        }
    }
