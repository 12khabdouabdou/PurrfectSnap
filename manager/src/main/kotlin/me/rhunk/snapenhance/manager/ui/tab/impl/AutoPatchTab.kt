package me.rhunk.snapenhance.manager.ui.tab.impl

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.patch.LSPatch
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import me.rhunk.snapenhance.manager.data.APKMirror
import me.rhunk.snapenhance.manager.data.DNSBlockedException
// REQUIRED COMPOSE LAYOUT IMPORTS:
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

class AutoPatchTab : Tab("autopatch", icon = Icons.Default.Build) {

    data class AbiChoice(val assetLabel: String, val desiredLibDir: String)

    fun detectAbiChoice(): AbiChoice {
        val abis = (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",").lowercase(Locale.ROOT)
        return when {
            "arm64" in abis || "v8a" in abis || "aarch64" in abis || "armv8" in abis ->
                AbiChoice(assetLabel = "armv8", desiredLibDir = "arm64-v8a")
            "armeabi-v7a" in abis || "armv7" in abis || "armeabi" in abis || "v7a" in abis ->
                AbiChoice(assetLabel = "armv7", desiredLibDir = "armeabi-v7a")
            else ->
                AbiChoice(assetLabel = "armv8", desiredLibDir = "arm64-v8a")
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
        // fallback to any debug apk if patterns not found
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

    fun verifyApkMatchesAbi(apk: File, desiredLibDir: String): Boolean {
        runCatching {
            ZipFile(apk).use { zf ->
                return zf.entries().asSequence().any { it.name.startsWith("lib/$desiredLibDir/") }
            }
        }
        return false
    }

    fun downloadWithOkHttp(url: String, toDir: File, onProgress: (Float) -> Unit): File? {
        val resp = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) return null
        val out = File.createTempFile("artifact", ".apk", toDir).apply { deleteOnExit() }
        resp.body?.byteStream()?.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(8 * 1024)
                var read: Int
                var total = 0L
                val size = resp.body?.contentLength() ?: -1L
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    total += read
                    if (size > 0) onProgress(total.toFloat() / size.toFloat()) else onProgress(-1f)
                }
                output.flush()
            }
        }
        return out
    }

    fun downloadFile(
        ctx: android.content.Context,
        url: String,
        useDns: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): File? {
        return try {
            val client = if (!useDns) OkHttpClient() else APKMirror().okhttpClient
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("network error code ${resp.code}")
                val length = resp.body?.contentLength() ?: -1L
                val tmpFile =
                    File.createTempFile("download", ".apk", ctx.externalCacheDir).apply { deleteOnExit() }
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
            null
        }
    }

    fun patchApk(
        ctx: android.content.Context,
        snapchatApk: File,
        snapenhanceApk: File,
        onLog: (Any) -> Unit
    ): File? {
        return try {
            val lspatch = LSPatch(ctx, mapOf("me.rhunk.snapenhance" to snapenhanceApk), false, onLog)
            val outputMap = lspatch.patchSplits(listOf(snapchatApk))
            outputMap["base.apk"]
        } catch (e: Exception) {
            onLog("Patch failed: ${e.message}")
            null
        }
    }

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

        fun runPipeline() {
            running = true
            done = false
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    appendStatus("Detecting device architecture...")
                    val abi = detectAbiChoice()
                    appendStatus("Detected architecture: ${abi.desiredLibDir} (asset label: ${abi.assetLabel})")

                    // Download latest SnapEnhance APK (debug release) with ABI and fallback logic
                    appendStatus("Fetching latest SnapEnhance debug APK for ${abi.assetLabel}...")
                    val cacheDir = context.externalCacheDir ?: context.cacheDir
                    val firstPick = fetchLatestSEDebugAssetForArch(abi.assetLabel)
                        ?: throw RuntimeException("No matching SnapEnhance debug APK found")
                    appendStatus("Downloading SnapEnhance: ${firstPick.first}")
                    progress = 0.1f
                    var seApk = downloadWithOkHttp(firstPick.second, cacheDir) { progress = it * 0.3f }
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    // Verify ABI inside the APK; if mismatch, try the opposite arch once
                    if (!verifyApkMatchesAbi(seApk, abi.desiredLibDir)) {
                        appendStatus("Downloaded SE APK does not contain lib/${abi.desiredLibDir}, retrying with opposite arch...")
                        val opposite = if (abi.assetLabel == "armv8") "armv7" else "armv8"
                        val secondPick = fetchLatestSEDebugAssetForArch(opposite)
                            ?: throw RuntimeException("Fallback SnapEnhance APK not found")
                        appendStatus("Downloading SnapEnhance (fallback): ${secondPick.first}")
                        progress = 0.15f
                        val fallback = downloadWithOkHttp(secondPick.second, cacheDir) { progress = 0.3f + it * 0.2f }
                            ?: throw RuntimeException("Failed to download fallback SnapEnhance")
                        seApk = fallback
                        if (!verifyApkMatchesAbi(seApk, if (opposite == "armv8") "arm64-v8a" else "armeabi-v7a")) {
                            throw RuntimeException("Downloaded SnapEnhance APK does not match any expected ABI")
                        }
                    }
                    appendStatus("Downloaded SnapEnhance APK: ${seApk.name}")

                    // Download Snapchat APK via DNS
                    val snapchatUrl =
                        "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=4764674&key=bd0c88c47174308d9c6862f815bc96246d5077a8&forcebaseapk=true"
                    appendStatus("Downloading Snapchat APK (DNS-over-HTTPS)...")
                    progress = 0.55f
                    val snapchatApk = try {
                        downloadFile(context, snapchatUrl, useDns = true) {
                            progress = 0.55f + it * 0.35f // 0.55 - 0.90
                        }
                    } catch (e: DNSBlockedException) {
                        appendStatus("DNS-Blocked! Try a different network.")
                        running = false
                        return@launch
                    } ?: throw RuntimeException("Failed to download Snapchat APK.")
                    appendStatus("Downloaded Snapchat APK: ${snapchatApk.name}")

                    // Patch APK
                    appendStatus("Patching Snapchat APK with SnapEnhance module...")
                    progress = 0.92f
                    val patchedFile = patchApk(context, snapchatApk, seApk) { msg ->
                        coroutineScope.launch(Dispatchers.Main) { appendStatus(msg.toString()) }
                    } ?: throw RuntimeException("Failed to patch APK.")
                    appendStatus("Patched APK ready: ${patchedFile.absolutePath}")

                    // Install
                    appendStatus("Launching installer...")
                    progress = 1f
                    (context as? ComponentActivity)?.runOnUiThread {
                        navigation.navigateTo(
                            me.rhunk.snapenhance.manager.ui.tab.impl.download.InstallPackageTab::class,
                            android.os.Bundle().apply {
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
