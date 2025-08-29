package me.rhunk.snapenhance.manager.ui.tab.impl

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.data.APKMirror
import me.rhunk.snapenhance.manager.data.DNSBlockedException
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.*
import org.json.JSONArray
import java.io.File
import java.util.*
import java.util.zip.ZipFile

class AutoPatchTab : Tab("auto_patch") {
    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var installDeferred: CompletableDeferred<Int>? = null
    private val hasRoot get() = sharedConfig.useRootInstaller

    override fun init(activity: ComponentActivity) {
        super.init(activity)
        installLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            installDeferred?.complete(it.resultCode)
            installDeferred = null
        }
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val activity = context as? Activity
        val scope = remember { CoroutineScope(Dispatchers.IO) }
        var status by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(-1f) }
        var isRunning by remember { mutableStateOf(false) }
        var isDone by remember { mutableStateOf(false) }
        var isError by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        var showDialog by remember { mutableStateOf(false) }

        fun log(any: Any?) {
            status += when (any) {
                is Throwable -> any.message + "\n" + any.stackTraceToString()
                else -> any.toString()
            } + "\n"
        }

        data class AssetResult(
            val snapEnhanceName: String, val snapEnhanceUrl: String,
            val coreName: String, val coreUrl: String
        )
        data class AbiChoice(val assetLabel: String, val desiredLibDir: String)
        fun detectAbiChoice(): AbiChoice {
            val abis = (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",").lowercase(Locale.ROOT)
            return when {
                "arm64" in abis || "v8a" in abis || "aarch64" in abis || "armv8" in abis -> AbiChoice("armv8", "arm64-v8a")
                "armeabi-v7a" in abis || "armv7" in abis || "armeabi" in abis || "v7a" in abis -> AbiChoice("armv7", "armeabi-v7a")
                else -> AbiChoice("armv8", "arm64-v8a")
            }
        }
        fun patternsFor(assetLabel: String): List<Regex> =
            if (assetLabel == "armv8")
                listOf(Regex("arm64[-_]?v8a", RegexOption.IGNORE_CASE), Regex("\\barm64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\barmv8\\b", RegexOption.IGNORE_CASE), Regex("\\baarch64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\bv8a\\b", RegexOption.IGNORE_CASE))
            else listOf(Regex("armeabi[-_]?v7a", RegexOption.IGNORE_CASE), Regex("\\barmv7\\b", RegexOption.IGNORE_CASE),
                Regex("\\barmeabi\\b", RegexOption.IGNORE_CASE), Regex("\\bv7a\\b", RegexOption.IGNORE_CASE))
        fun AssetResult.isSnapEnhanceMatchesAbi(abi: AbiChoice): Boolean {
            return patternsFor(abi.assetLabel).any { it.containsMatchIn(snapEnhanceName) }
        }
        fun verifyApkMatchesAbi(apk: File, desiredLibDir: String): Boolean =
            try { ZipFile(apk).use { zf -> zf.entries().asSequence().any { it.name.startsWith("lib/$desiredLibDir/") } } }
            catch (_: Throwable) { false }

        fun fetchSnapEnhanceAndCoreAssets(assetLabel: String): AssetResult? {
            val request = Request.Builder().url("https://api.github.com/repos/particle-box/SnapEnhance/releases").build()
            val resp = OkHttpClient().newCall(request).execute()
            if (!resp.isSuccessful) return null
            val arr = JSONArray(resp.body?.string() ?: return null)
            // Find latest prerelease with matching SnapEnhance ABI and core.apk
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (!obj.optBoolean("prerelease", false)) continue // Must be prerelease
                val assetsArr = obj.optJSONArray("assets") ?: continue
                var snapAssetName: String? = null
                var snapAssetUrl: String? = null
                var coreAssetUrl: String? = null
                var coreAssetName: String? = null
                for (j in 0 until assetsArr.length()) {
                    val a = assetsArr.getJSONObject(j)
                    val name = a.optString("name", "")
                    val url = a.optString("browser_download_url", "")
                    if (name.equals("core.apk", ignoreCase = true)) {
                        coreAssetName = name
                        coreAssetUrl = url
                    }
                    // Find matching SnapEnhance APK for assetLabel
                    if (name.endsWith(".apk", true) && !name.equals("core.apk", true) &&
                        patternsFor(assetLabel).any { it.containsMatchIn(name) }) {
                        // Prefer the largest matching APK
                        if (snapAssetName == null || a.optLong("size", 0L) > 0L) {
                            snapAssetName = name
                            snapAssetUrl = url
                        }
                    }
                }
                if (snapAssetName != null && coreAssetName != null && snapAssetUrl != null && coreAssetUrl != null)
                    return AssetResult(snapAssetName, snapAssetUrl, coreAssetName, coreAssetUrl)
            }
            return null
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

        suspend fun installPackage(file: File, packageName: String): Boolean {
            if (hasRoot) {
                val res = Shell.cmd(
                    "cp \"${file.absolutePath}\" /data/local/tmp/",
                    "pm install -r \"/data/local/tmp/${file.name}\"",
                    "rm \"/data/local/tmp/${file.name}\""
                ).exec()
                return res.isSuccess
            }
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            val deferred = CompletableDeferred<Int>()
            installDeferred = deferred
            activity?.startActivityForResult(intent, 1)
            val code = deferred.await()
            return code == Activity.RESULT_OK
        }

        fun startPatchAndInstall() {
            scope.launch {
                try {
                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    // 1. Get ABI
                    val abi = detectAbiChoice()
                    log("Detected ABI: ${abi.desiredLibDir}")

                    // 2. Download SnapEnhance + core.apk from the same prerelease asset
                    log("Looking for SnapEnhance & core.apk release assets...")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")
                    log("Downloading SnapEnhance: ${assets.snapEnhanceName}")
                    progress = 0f
                    val seApk = downloadWithOkHttp(assets.snapEnhanceUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    if (!verifyApkMatchesAbi(seApk, abi.desiredLibDir)) throw RuntimeException("SnapEnhance APK does not match ABI")
                    log("SnapEnhance APK ready.")
                    log("Downloading core.apk...")
                    val coreApk = downloadWithOkHttp(assets.coreUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download core.apk")
                    log("core.apk ready.")

                    // 3. Download Snapchat 12.33.1.19 APK (same as your previous logic)
                    log("Fetching Snapchat 12.33.1.19...")
                    val apkMirror = APKMirror()
                    val versionItem = with(apkMirror) { fetchSnapchatVersions(1).find { it.title.contains("12.33.1.19") } }
                        ?: throw RuntimeException("Snapchat v12.33.1.19 not found!")
                    val downloadUrl = with(apkMirror) { fetchDownloadLink(versionItem.downloadPage) }
                        ?: throw RuntimeException("Could not resolve Snapchat direct download link")
                    log("Downloading Snapchat APK...")
                    val snapchatApk = downloadWithOkHttp(downloadUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download Snapchat APK")
                    log("All APKs downloaded. Uploading for patch...")

                    // 4. Upload to server
                    progress = -1f
                    val client = OkHttpClient()
                    val reqBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("core", "core.apk", coreApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .addFormDataPart("apk", "snapchat.apk", snapchatApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .build()
                    val request = Request.Builder().url("https://auto-patch-server.onrender.com/patch").post(reqBody).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw RuntimeException("Server failed patch: ${response.message}")
                    val patchedFile = File(cacheDir, "PatchedSnapchat.apk")
                    response.body?.byteStream()?.use { input -> patchedFile.outputStream().use { output -> input.copyTo(output) } }
                    log("Patched Snapchat APK received. Installing...")

                    // 5. Install SnapEnhance (update allowed)
                    if (!installPackage(seApk, sharedConfig.snapEnhancePackageName)) throw RuntimeException("SnapEnhance install failed")
                    log("SnapEnhance installed.")

                    // 6. Install patched Snapchat APK
                    if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat install failed")
                    log("Patched Snapchat installed. Done!")

                    isDone = true
                } catch (t: Throwable) {
                    log("ERROR: ${t.message}")
                    isError = true
                } finally {
                    isRunning = false
                }
            }
        }

        if (!isRunning && !isDone && !isError) {
            Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Start Auto Patch")
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Continue with Auto Patch?") },
                text = { Text("Are you sure you want to patch Snapchat?") },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        isRunning = true
                        status = ""
                        isDone = false
                        isError = false
                        startPatchAndInstall()
                    }) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) { Text("No") }
                }
            )
        }
        if (status.isNotBlank()) {
            Card(modifier = Modifier.weight(1f).padding(8.dp)) {
                Column(modifier = Modifier.verticalScroll(scrollState).padding(8.dp)) {
                    Text(status, overflow = TextOverflow.Visible)
                }
            }
        }
        if (progress >= 0f) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round
            )
        }
        if (isDone || isError) {
            Button(onClick = { navigation.navigateTo(HomeTab::class, noHistory = true) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isDone) "Back to Home" else "Close")
            }
        }
        LaunchedEffect(status) { scrollState.scrollTo(scrollState.maxValue) }
    }
}
