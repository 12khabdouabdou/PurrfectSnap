package me.eternal.purrfectsnap.core.util.ktx

import android.os.Build
import android.os.ParcelFileDescriptor
import me.eternal.purrfectsnap.bridge.storage.FileHandleManager
import me.eternal.purrfectsnap.common.bridge.FileHandleScope
import me.eternal.purrfectsnap.common.util.ktx.longHashCode
import me.eternal.purrfectsnap.core.ModContext
import java.io.FileOutputStream
import kotlin.math.absoluteValue

fun FileHandleManager.getFileHandleLocalPath(
    context: ModContext,
    scope: FileHandleScope,
    name: String,
    fileUniqueIdentifier: String,
): String? {
    return getFileHandle(scope.key, name)?.open(ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
        val cacheFile = context.androidContext.cacheDir.also {
            it.mkdirs()
        }.resolve((fileUniqueIdentifier + Build.FINGERPRINT).longHashCode().absoluteValue.toString(16))
        if (!cacheFile.exists() || pfd.statSize != cacheFile.length()) {
            FileOutputStream(cacheFile).use { output ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    input.copyTo(output)
                }
            }
        }
        cacheFile.absolutePath
    }
}