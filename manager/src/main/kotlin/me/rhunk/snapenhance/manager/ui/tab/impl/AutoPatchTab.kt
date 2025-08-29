package me.rhunk.snapenhance.manager.ui.tab.impl

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import me.rhunk.snapenhance.manager.ui.tab.Tab
import me.rhunk.snapenhance.manager.data.APKMirror
import me.rhunk.snapenhance.manager.data.DNSBlockedException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class AutoPatchTab : Tab("auto_patch") {
    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var installDeferred: CompletableDeferred<Int>? = null

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

        // Long-timeout OkHttp client for large uploads and cold starts
        val longClient = remember {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
        }

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
                listOf(
                    Regex("arm64[-_]?v8a", RegexOption.IGNORE_CASE),
                    Regex("\\barm64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\barmv8\\b", RegexOption.IGNORE_CASE),
                    Regex("\\baarch64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\bv8a\\b", RegexOption.IGNORE_CASE)
                )
            else
                listOf(
                    Regex("armeabi[-_]?v7a", RegexOption.IGNORE_CASE),
                    Regex("\\barmv7\\b", RegexOption.IGNORE_CASE),
                    Regex("\\barmeabi\\b", RegexOption.IGNORE_CASE),
                    Regex("\\bv7a\\b", RegexOption.IGNORE_CASE)
                )

        fun verifyApkMatchesAbi(apk: File, desiredLibDir: String): Boolean =
            try { ZipFile(apk).use { zf -> zf.entries().asSequence().any { it.name.startsWith("lib/$desiredLibDir/") } } }
            catch (_: Throwable) { false }

        fun fetchSnapEnhanceAndCoreAssets(assetLabel: String): AssetResult? {
            val request = Request.Builder().url("https://api.github.com/repos/particle-box/SnapEnhance/releases").build()
            longClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val arr = JSONArray(body)
                // Find latest prerelease with matching SnapEnhance ABI and core.apk
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (!obj.optBoolean("prerelease", false)) continue
                    val assetsArr = obj.optJSONArray("assets") ?: continue
                    var snapAssetName: String? = null
                    var snapAssetUrl: String? = null
                    var coreAssetName: String? = null
                    var coreAssetUrl: String? = null
                    for (j in 0 until assetsArr.length()) {
                        val a = assetsArr.getJSONObject(j)
                        val name = a.optString("name", "")
                        val url = a.optString("browser_download_url", "")
                        if (name.equals("core.apk", ignoreCase = true)) {
                            coreAssetName = name
                            coreAssetUrl = url
                        }
                        if (name.endsWith(".apk", true) && !name.equals("core.apk", true) &&
                            patternsFor(assetLabel).any { it.containsMatchIn(name) }) {
                            snapAssetName = name
                            snapAssetUrl = url
                        }
                    }
                    if (snapAssetName != null && coreAssetName != null && snapAssetUrl != null && coreAssetUrl != null)
                        return AssetResult(snapAssetName, snapAssetUrl, coreAssetName, coreAssetUrl)
                }
                return null
            }
        }

        fun downloadWithOkHttp(url: String, toDir: File, onProgress: (Float) -> Unit): File? {
            longClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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
        }

        fun downloadWithDoh(url: String, toDir: File, onProgress: (Float) -> Unit): File? {
            val dohClient = APKMirror().okhttpClient.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
            dohClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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
        }

        suspend fun findSnapchatVersionItem(
            apkMirror: APKMirror,
            targetVersion: String,
            maxPages: Int = 100
        ): me.rhunk.snapenhance.manager.data.DownloadItem? {
            for (page in 1..maxPages) {
                val items = try {
                    apkMirror.fetchSnapchatVersions(page)
                } catch (e: DNSBlockedException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (items.isNullOrEmpty()) break
                val match = items.firstOrNull { it.title.contains(targetVersion) }
                if (match != null) return match
            }
            return null
        }

        // Robust package presence check (fallback if result code is unreliable)
        fun isPackageInstalled(pkg: String): Boolean {
            return try {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        // Install with Activity Result API and fallback poll of PackageManager
        suspend fun installPackage(file: File, packageName: String): Boolean {
            if (sharedConfig.useRootInstaller) {
                val res = Shell.cmd(
                    "cp \"${file.absolutePath}\" /data/local/tmp/",
                    "pm install -r \"/data/local/tmp/${file.name}\"",
                    "rm \"/data/local/tmp/${file.name}\""
                ).exec()
                if (res.isSuccess) return true
                // If root install reports failure, still check if the package appeared
                repeat(10) { // ~10s fallback poll
                    if (isPackageInstalled(packageName)) return true
                    Thread.sleep(1000)
                }
                return false
            }

            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                // Ask the package installer to return a result
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

            val deferred = CompletableDeferred<Int>()
            installDeferred = deferred
            // Launch via Activity Result API (reliable callback)
            installLauncher.launch(intent)

            // Await result; RESULT_OK indicates success
            val resultCode = deferred.await()
            if (resultCode == Activity.RESULT_OK) return true

            // Fallback: poll PackageManager in case some OEMs don’t return proper result codes
            repeat(15) { // ~15s poll
                if (isPackageInstalled(packageName)) return true
                Thread.sleep(1000)
            }
            return false
        }

        fun warmUpServer(baseUrl: String, maxWaitMs: Long = 120_000L, intervalMs: Long = 3000L): Boolean {
            val warmClient = longClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < maxWaitMs) {
                try {
                    val req = Request.Builder().url(baseUrl).get().build()
                    warmClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) return true
                    }
                } catch (_: Throwable) {
                    // ignore and retry
                }
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return false
        }

        fun startPatchAndInstall() {
            scope.launch {
                try {
                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")

                    // 1) Detect ABI
                    val abi = detectAbiChoice()
                    log("Detected ABI: ${abi.desiredLibDir}")

                    // 2) Download SnapEnhance + core.apk from latest prerelease
                    log("Looking for SnapEnhance & core.apk release assets...")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")
                    log("Downloading SnapEnhance: ${assets.snapEnhanceName}")
                    progress = 0f
                    val seApk = downloadWithOkHttp(assets.snapEnhanceUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    if (!verifyApkMatchesAbi(seApk, abi.desiredLibDir)) throw RuntimeException("SnapEnhance APK does not match ABI")
                    log("SnapEnhance APK ready.")

                    log("Downloading core.apk (no ABI check needed)...")
                    val coreApk = downloadWithOkHttp(assets.coreUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download core.apk")
                    log("core.apk ready.")

                    // 3) Download Snapchat 12.33.1.19 using APKMirror flow
                    val apkMirror = APKMirror()
                    val snapchatTargetVersion = "12.33.1.19"
                    log("Searching APKMirror for Snapchat $snapchatTargetVersion...")
                    val versionItem = findSnapchatVersionItem(apkMirror, snapchatTargetVersion)
                        ?: throw RuntimeException("Snapchat version $snapchatTargetVersion not found on APKMirror.")
                    log("Found version: ${versionItem.title} (${versionItem.releaseDate})")
                    log("Resolving download link...")
                    val realSnapchatUrl = apkMirror.fetchDownloadLink(versionItem.downloadPage)
                        ?: throw RuntimeException("Could not resolve Snapchat APK download link from APKMirror")
                    log("Downloading Snapchat from resolved URL: $realSnapchatUrl")
                    progress = 0f
                    val snapchatApk = downloadWithDoh(realSnapchatUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download Snapchat")
                    log("Downloaded Snapchat -> ${snapchatApk.absolutePath}")

                    // 4) Warm up free server
                    log("Warming up patch server (free cold start may take ~30–60s)...")
                    val warmed = warmUpServer("https://auto-patch-server.onrender.com/health")
                    if (!warmed) log("Proceeding with upload despite warm-up not confirming; long timeouts will handle cold start.")

                    // 5) Upload only core.apk and Snapchat APK for patching
                    log("Uploading for patch (only core.apk and Snapchat APK)...")
                    progress = -1f
                    val reqBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("core", "core.apk", coreApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .addFormDataPart("apk", "snapchat.apk", snapchatApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .build()
                    val request = Request.Builder()
                        .url("https://auto-patch-server.onrender.com/patch")
                        .post(reqBody)
                        .build()
                    longClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(2000) ?: ""
                            throw RuntimeException("Server failed patch: code=${response.code}, message=${response.message}, body=${err}")
                        }
                        val patchedFile = File(cacheDir, "PatchedSnapchat.apk")
                        response.body?.byteStream()?.use { input ->
                            patchedFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        log("Patched Snapchat APK received. Installing...")

                        // 6) Install SnapEnhance first (robust, waits for result and/or polls PM)
                        if (!installPackage(seApk, sharedConfig.snapEnhancePackageName)) throw RuntimeException("SnapEnhance install failed")
                        log("SnapEnhance installed.")

                        // 7) Install patched Snapchat (same robust flow)
                        if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat install failed")
                        log("Patched Snapchat installed. Done!")
                        isDone = true
                    }
                } catch (t: Throwable) {
                    log("ERROR: ${t.message}")
                    isError = true
                } finally {
                    isRunning = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(8.dp)
                    ) {
                        Text(status, overflow = TextOverflow.Visible)
                    }
                }
            }
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    strokeCap = StrokeCap.Round
                )
            }
            if (isDone || isError) {
                Button(
                    onClick = { navigation.navigateTo(HomeTab::class, noHistory = true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isDone) "Back to Home" else "Close")
                }
            }
            LaunchedEffect(status) { scrollState.scrollTo(scrollState.maxValue) }
        }

        BackHandler(enabled = isRunning) { /* block back while running */ }
    }
}
