package me.rhunk.snapenhance.manager.ui.tab.impl

import android.content.Context
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import me.rhunk.snapenhance.manager.patch.LSPatch
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Locale
import me.rhunk.snapenhance.manager.data.APKMirror
import me.rhunk.snapenhance.manager.data.DNSBlockedException

// Compose layout imports:
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth

class AutoPatchTab : Tab("autopatch", icon = Icons.Default.Build) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var status by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(-1f) }
        var showConfirm by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf(false) }

        fun appendStatus(msg: String) {
            status += msg + "\n"
        }

        // --- ABI detection & SE debug APK selection logic (NO zip entry parse) ---
        data class AbiChoice(val assetLabel: String, val display: String)
        fun detectAbiChoice(): AbiChoice {
            val abis = (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",").lowercase(Locale.ROOT)
            return when {
                "arm64" in abis || "v8a" in abis || "aarch64" in abis || "armv8" in abis ->
                    AbiChoice("armv8", "arm64-v8a")
                "armeabi-v7a" in abis || "armv7" in abis || "armeabi" in abis || "v7a" in abis ->
                    AbiChoice("armv7", "armeabi-v7a")
                else ->
                    AbiChoice("armv8", "arm64-v8a")
            }
        }

        fun patternsFor(assetLabel: String): List<Regex> {
            return if (assetLabel == "armv8") {
                listOf(
                    Regex("arm64[-_]?v8a", RegexOption.IGNORE_CASE),
                    Regex("\\barm64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\barmv8\\b", RegexOption.IGNORE_CASE),
                    Regex("\\baarch64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\bv8a\\b", RegexOption.IGNORE_CASE)
                )
            } else {
                listOf(
                    Regex("armeabi[-_]?v7a", RegexOption.IGNORE_CASE),
                    Regex("\\barmv7\\b", RegexOption.IGNORE_CASE),
                    Regex("\\barmeabi\\b", RegexOption.IGNORE_CASE),
                    Regex("\\bv7a\\b", RegexOption.IGNORE_CASE)
                )
            }
        }

        fun chooseAssetForArch(
            assets: Map<String, Pair<Long, String>>,
            assetLabel: String
        ): Pair<String, String>? {
            val pats = patternsFor(assetLabel)
            val apkAssets = assets.entries.filter { it.key.endsWith(".apk", true) }
            val preferred = apkAssets
                .filter { entry -> pats.any { it.containsMatchIn(entry.key) } }
                .maxByOrNull { it.value.first }
            if (preferred != null) return preferred.key to preferred.value.second
            val any = apkAssets.maxByOrNull { it.value.first } ?: return null
            return any.key to any.value.second
        }

        fun fetchLatestSEDebugAssetForArch(assetLabel: String): Pair<String, String>? {
            val req = Request.Builder()
                .url("https://api.github.com/repos/particle-box/SnapEnhance/releases")
                .build()
            val resp = OkHttpClient().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val isPre = obj.optBoolean("prerelease", false)
                val tag = obj.optString("tag_name", "")
                if (!isPre || !tag.startsWith("debug-")) continue
                val assetsArr = obj.optJSONArray("assets") ?: continue
                val assets = mutableMapOf<String, Pair<Long, String>>() // name -> (size, url)
                for (j in 0 until assetsArr.length()) {
                    val a = assetsArr.getJSONObject(j)
                    val name = a.optString("name", "")
                    val size = a.optLong("size", 0L)
                    val url = a.optString("browser_download_url", "")
                    if (name.endsWith(".apk", true) && name.isNotBlank() && url.isNotBlank()) {
                        assets[name] = size to url
                    }
                }
                chooseAssetForArch(assets, assetLabel)?.let { return it }
            }
            return null
        }

        // --- Sanity check on plain APK file ---
        fun File.isApkFile(): Boolean {
            if (!exists() || length() < 4) return false
            return inputStream().use {
                val hdr = ByteArray(4)
                if (it.read(hdr) == 4)
                    hdr[0] == 0x50.toByte() && hdr[1] == 0x4B.toByte() // "PK"
                else false
            }
        }

        fun downloadFile(
            ctx: Context,
            url: String,
            useDns: Boolean = false,
            onProgress: (Float) -> Unit = {}
        ): File? {
            return try {
                val client = if (!useDns) OkHttpClient() else APKMirror().okhttpClient
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("network error code ${resp.code}")
                    val length = resp.body?.contentLength() ?: -1L
                    val tmpFile = File.createTempFile("download", ".apk", ctx.externalCacheDir).apply { deleteOnExit() }
                    resp.body?.byteStream()?.use { input ->
                        tmpFile.outputStream().use { output ->
                            val buf = ByteArray(4096)
                            var read: Int
                            var written = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                written += read
                                if (length > 0) onProgress(written.toFloat() / length)
                            }
                            output.flush()
                        }
                    }
                    tmpFile
                }
            } catch (e: DNSBlockedException) {
                throw e
            } catch (e: Exception) {
                appendStatus("Download error: ${e.message}")
                null
            }
        }

        fun patchApk(
            ctx: Context,
            snapchatApk: File,
            snapenhanceApk: File,
            onLog: (Any?) -> Unit
        ): File? {
            // Just check basic existence & is APK (not zip content)
            if (!snapenhanceApk.isApkFile())
                throw Exception("SnapEnhance APK is missing or corrupt!")
            if (!snapchatApk.isApkFile())
                throw Exception("Snapchat APK is missing or corrupt!")
            return try {
                val lspatch = LSPatch(ctx, mapOf("me.rhunk.snapenhance" to snapenhanceApk), false, onLog)
                val outputMap = lspatch.patchSplits(listOf(snapchatApk))
                outputMap["base.apk"]
            } catch (e: Exception) {
                onLog("Patch failed: ${e.message}")
                null
            }
        }
        
        fun runPipeline() {
            running = true
            done = false
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    appendStatus("Detecting device architecture...")
                    val abi = detectAbiChoice()
                    appendStatus("Detected architecture: ${abi.display} (label: ${abi.assetLabel})")
                    appendStatus("Looking up latest SnapEnhance debug asset for this architecture...")
                    val debugAsset = fetchLatestSEDebugAssetForArch(abi.assetLabel)
                        ?: throw Exception("No matching SnapEnhance debug APK found.")
                    appendStatus("Downloading SnapEnhance APK: ${debugAsset.first} ...")
                    progress = 0.05f
                    val snapenhanceApk = downloadFile(context, debugAsset.second, useDns = false) {
                        progress = 0.05f + it * 0.4f
                    } ?: throw Exception("Failed to download SnapEnhance APK.")
                    appendStatus("Downloaded SnapEnhance APK: ${snapenhanceApk.name}")
                    val snapchatUrl =
                        "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=4764674&key=bd0c88c47174308d9c6862f815bc96246d5077a8&forcebaseapk=true"
                    appendStatus("Downloading Snapchat APK (DNS-over-HTTPS)...")
                    progress = 0.55f
                    val snapchatApk = try {
                        downloadFile(context, snapchatUrl, useDns = true) {
                            progress = 0.55f + it * 0.35f
                        }
                    } catch (e: DNSBlockedException) {
                        appendStatus("DNS-Blocked! Try a different network.")
                        running = false
                        return@launch
                    } ?: throw Exception("Failed to download Snapchat APK.")
                    appendStatus("Downloaded Snapchat APK: ${snapchatApk.name}")
                    appendStatus("Patching Snapchat APK with SnapEnhance module...")
                    progress = 0.92f
                    val patchedFile = patchApk(context, snapchatApk, snapenhanceApk) { msg ->
                        coroutineScope.launch(Dispatchers.Main) { appendStatus(msg.toString()) }
                    } ?: throw Exception("Failed to patch APK.")
                    appendStatus("Patched APK ready: ${patchedFile.absolutePath}")
                    appendStatus("Launching installer...")
                    progress = 1f
                    (context as? ComponentActivity)?.runOnUiThread {
                        navigation.navigateTo(
                            me.rhunk.snapenhance.manager.ui.tab.impl.download.InstallPackageTab::class,
                            Bundle().apply {
                                putString("downloadPath", patchedFile.absolutePath)
                                putString("appPackage", "com.snapchat.android")
                                putBoolean("uninstall", false)
                            }
                        )
                    }
                    appendStatus("Done!")
                    done = true
                } catch (e: Exception) {
                    appendStatus("ERROR: ${e.message}")
                } finally {
                    running = false
                }
            }
        }

        Column(Modifier.padding(24.dp)) {
            Text(
                "Auto Patch Wizard", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Text("This will download SnapEnhance and Snapchat APKs, patch and auto-install!", fontSize = 16.sp)
            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                enabled = !running && !done,
                onClick = { showConfirm = true }
            ) { Text("Start Auto Patch") }
            if (showConfirm) {
                AlertDialog(
                    onDismissRequest = { showConfirm = false },
                    title = { Text("Auto Patch") },
                    text = { Text("Are you sure to continue?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showConfirm = false
                                status = ""
                                runPipeline()
                            }
                        ) { Text("Yes") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showConfirm = false }
                        ) { Text("No") }
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(7.dp),
                progress = if (progress < 0f) 0f else progress,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("Status:\n${status.trim()}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
