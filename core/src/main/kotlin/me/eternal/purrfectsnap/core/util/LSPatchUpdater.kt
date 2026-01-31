package me.eternal.purrfectsnap.core.util

import me.eternal.purrfectsnap.common.Constants
import me.eternal.purrfectsnap.core.ModContext
import java.io.File
import java.util.zip.ZipFile

object LSPatchUpdater {
    private const val TAG = "LSPatchUpdater"

    var HAS_LSPATCH = false
        private set

    private fun getModuleUniqueHash(module: ZipFile): String {
        return module.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.crc }
            .reduce { acc, crc -> acc xor crc }
            .toString(16)
    }

    fun onBridgeConnected(context: ModContext) {
        val obfuscatedModulePath by lazy {
            (runCatching {
                context::class.java.classLoader?.loadClass("org.lsposed.lspatch.share.Constants")
            }.getOrNull())?.declaredFields?.firstOrNull { it.name == "MANAGER_PACKAGE_NAME" }?.also {
                it.isAccessible = true
            }?.get(null) as? String
        }

        val embeddedModule = context.androidContext.cacheDir
            .resolve("lspatch")
            .resolve(Constants.MODULE_PACKAGE_NAME).let { moduleDir ->
                if (!moduleDir.exists()) return@let null
                moduleDir.listFiles()?.firstOrNull { it.extension == "apk" }
            } ?: obfuscatedModulePath?.let { path ->
                context.androidContext.cacheDir.resolve(path).let dir@{ moduleDir ->
                    if (!moduleDir.exists()) return@dir null
                    moduleDir.listFiles()?.firstOrNull { it.extension == "apk" }
                } ?: return
            } ?: return

        HAS_LSPATCH = true
        context.log.verbose("Found embedded PurrfectSnap at ${embeddedModule.absolutePath}", TAG)

        val seAppApk = File(context.bridgeClient.getApplicationApkPath()).also {
            if (!it.canRead()) {
                throw IllegalStateException("Cannot read PurrfectSnap apk")
            }
        }

        runCatching {
            if (getModuleUniqueHash(ZipFile(embeddedModule)) == getModuleUniqueHash(ZipFile(seAppApk))) {
                context.log.verbose("Embedded PurrfectSnap is up to date", TAG)
                return
            }
        }.onFailure {
            throw IllegalStateException("Failed to compare module signature", it)
        }

        context.log.verbose("updating", TAG)
        context.shortToast(context.translation["toast_updating_purrfectsnap"])
        // copy embedded module to cache
        runCatching {
            seAppApk.copyTo(embeddedModule, overwrite = true)
        }.onFailure {
            seAppApk.delete()
            context.log.error("Failed to copy embedded module", it, TAG)
            context.longToast(context.translation["toast_update_purrfectsnap_failed"])
            context.forceCloseApp()
            return
        }

        context.longToast(context.translation["toast_purrfectsnap_updated"])
        context.log.verbose("updated", TAG)
        context.softRestartApp()
    }
}

