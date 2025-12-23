package me.eternal.purrfectsnap.core.features.impl.experiments

import me.eternal.purrfectsnap.common.bridge.FileHandleScope
import me.eternal.purrfectsnap.core.ModContext
import java.io.File
import java.io.FileOutputStream


private var cacheFontPath: String? = null
private var cacheFontName: String? = null

fun getCustomEmojiFontPath(
    context: ModContext
): String? {
    val customFileName = context.config.experimental.nativeHooks.customEmojiFont.getNullable()?.takeIf { it.isNotBlank() } ?: return null
    val safeFileName = customFileName.substringAfterLast("/")
    return runCatching {
        val handle = context.fileHandlerManager.getFileHandle(
            FileHandleScope.USER_IMPORT.key,
            customFileName
        ) ?: return@runCatching null
        if (!handle.exists()) {
            File(context.androidContext.filesDir, "emoji_fonts")
                .resolve(safeFileName)
                .takeIf { it.exists() }
                ?.delete()
            cacheFontPath = ""
            cacheFontName = safeFileName
            return@runCatching null
        }

        val persistentDir = File(context.androidContext.filesDir, "emoji_fonts").apply {
            mkdirs()
        }
        val persistentFile = File(persistentDir, safeFileName)
        handle.open(android.os.ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
            if (!persistentFile.exists() || pfd.statSize != persistentFile.length()) {
                FileOutputStream(persistentFile).use { output ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
        cacheFontName = safeFileName
        cacheFontPath = persistentFile.absolutePath
        cacheFontPath?.takeIf { it.isNotEmpty() }
    }.onFailure {
        context.log.error("Failed to get custom emoji font", it)
    }.getOrNull()
}


