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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.data.APKMirror
import me.rhunk.snapenhance.manager.data.DNSBlockedException
import me.rhunk.snapenhance.manager.patch.LSPatch
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

class AutoPatchTab : Tab("auto_patch") {
    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private lateinit var uninstallLauncher: ActivityResultLauncher<Intent>
    private var installDeferred: CompletableDeferred<Int>? = null
    private var uninstallDeferred: CompletableDeferred<Int>? = null
    private val hasRoot get() = sharedConfig.useRootInstaller

    override fun init(activity: ComponentActivity) {
        super.init(activity)
        installLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            installDeferred?.complete(it.resultCode)
            installDeferred = null
        }
        uninstallLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            uninstallDeferred?.complete(it.resultCode)
            uninstallDeferred = null
        }
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = remember { CoroutineScope(Dispatchers.IO) }
        var status by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(-1f) }
        var isRunning by remember { mutableStateOf(false) }
        var isDone by remember { mutableStateOf(false) }
        var isError by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()

        fun log(any: Any?) {
            status += when (any) {
                is Throwable -> any.message + "\n" + any.stackTraceToString()
                else -> any.toString()
            } + "\n"
        }

        data class AbiChoice(val assetLabel: String, val desiredLibDir: String)
        fun detectAbiChoice(): AbiChoice {
            val abis = (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",").lowercase(Locale.ROOT)
            return when {
                "arm64" in abis || "v8a" in abis || "aarch64" in abis || "armv8" in abis -> AbiChoice(assetLabel = "armv8", desiredLibDir = "arm64-v8a")
                "armeabi-v7a" in abis || "armv7" in abis || "armeabi" in abis || "v7a" in abis -> AbiChoice(assetLabel = "armv7", desiredLibDir = "armeabi-v7a")
                else -> AbiChoice(assetLabel = "armv8", desiredLibDir = "arm64-v8a")
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
        fun chooseAssetForArch(assets: Map<String, Pair<Long, String>>, assetLabel: String): Pair<String, String>? {
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
        fun downloadWithDoh(url: String, toDir: File, onProgress: (Float) -> Unit): File? {
            val dohClient = APKMirror().okhttpClient
            val resp = dohClient.newCall(Request.Builder().url(url).build()).execute()
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
        suspend fun uninstallPackage(packageName: String): Boolean {
            if (hasRoot) {
                val res = Shell.cmd("pm uninstall $packageName").exec()
                return res.isSuccess
            }
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = "package:$packageName".toUri()
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            val deferred = CompletableDeferred<Int>()
            uninstallDeferred = deferred
            uninstallLauncher.launch(intent)
            val code = deferred.await()
            return code == Activity.RESULT_OK
        }
        suspend fun installPackage(file: File, packageName: String, uninstallBefore: Boolean): Boolean {
            if (uninstallBefore) {
                if (!uninstallPackage(packageName)) return false
            }
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
            installLauncher.launch(intent)
            val code = deferred.await()
            return code == Activity.RESULT_OK
        }
        fun patchWithLSPatch(baseApk: File, seModule: File, onLog: (Any?) -> Unit): File? {
            val patcher = LSPatch(
                activity,
                modules = mapOf(sharedConfig.snapEnhancePackageName to seModule),
                obfuscate = sharedConfig.obfuscateLSPatch,
                printLog = { onLog("[LSPatch] $it") }
            )
            val out = patcher.patchSplits(listOf(baseApk))
            return out["base.apk"]
        }

        suspend fun findSnapchatVersionItem(
    apkMirror: APKMirror,
    targetVersion: String,
    maxPages: Int = 100 // Increased for deep paging
): me.rhunk.snapenhance.manager.data.DownloadItem? {
    for (page in 1..maxPages) {
        val items = try {
            apkMirror.fetchSnapchatVersions(page)
        } catch (e: DNSBlockedException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (items.isNullOrEmpty()) break // Stop if no more results
        val match = items.firstOrNull { it.title.contains(targetVersion) }
        if (match != null) return match
    }
    return null
}

        LaunchedEffect(Unit) {
            if (isRunning) return@LaunchedEffect
            isRunning = true
            isDone = false
            isError = false
            status = ""
            progress = -1f
            scope.launch {
                try {
                    val cacheDir = activity.externalCacheDir ?: activity.cacheDir
                    // 1) Detect ABI and choose label
                    val abi = detectAbiChoice()
                    log("Detected ABI: ${abi.desiredLibDir} (asset label: ${abi.assetLabel})")
                    // 2) Fetch and download latest SnapEnhance debug for ABI
                    log("Fetching latest SnapEnhance debug asset for ${abi.assetLabel}...")
                    val firstPick = fetchLatestSEDebugAssetForArch(abi.assetLabel)
                        ?: throw RuntimeException("No matching SnapEnhance debug APK found")
                    log("Downloading SnapEnhance: ${firstPick.first}")
                    progress = 0f
                    var seApk = downloadWithOkHttp(firstPick.second, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    // Verify ABI inside the APK; if mismatch, try the opposite arch once
                    if (!verifyApkMatchesAbi(seApk, abi.desiredLibDir)) {
                        log("Downloaded SE APK does not contain lib/${abi.desiredLibDir}, retrying with opposite arch...")
                        val opposite = if (abi.assetLabel == "armv8") "armv7" else "armv8"
                        val secondPick = fetchLatestSEDebugAssetForArch(opposite)
                            ?: throw RuntimeException("Fallback SnapEnhance APK not found")
                        log("Downloading SnapEnhance (fallback): ${secondPick.first}")
                        progress = 0f
                        val fallback = downloadWithOkHttp(secondPick.second, cacheDir) { progress = it }
                            ?: throw RuntimeException("Failed to download fallback SnapEnhance")
                        seApk = fallback
                        if (!verifyApkMatchesAbi(seApk, if (opposite == "armv8") "arm64-v8a" else "armeabi-v7a")) {
                            throw RuntimeException("Downloaded SnapEnhance APK does not match any expected ABI")
                        }
                    }
                    log("SnapEnhance ready at ${seApk.absolutePath}")
                    // 3) Install SnapEnhance (update allowed)
                    log("Installing SnapEnhance...")
                    progress = -1f
                    if (!installPackage(seApk, sharedConfig.snapEnhancePackageName, uninstallBefore = false)) {
                        throw RuntimeException("SnapEnhance install failed")
                    }
                    log("SnapEnhance installed")
                    // 4) Download Snapchat base (v12.33.1.19) via APKMirror pages
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
                    val snapchatBase = downloadWithDoh(realSnapchatUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download Snapchat")
                    log("Downloaded Snapchat -> ${snapchatBase.absolutePath}")
                    // 5) Install Snapchat base (update allowed)
                    log("Installing Snapchat base...")
                    progress = -1f
                    if (!installPackage(snapchatBase, sharedConfig.snapchatPackageName, uninstallBefore = false)) {
                        throw RuntimeException("Snapchat base install failed")
                    }
                    log("Snapchat base installed")
                    // 6) Patch Snapchat with LSPatch using SnapEnhance module
                    log("Patching Snapchat with LSPatch...")
                    val patched = patchWithLSPatch(snapchatBase, seApk) { msg -> log(msg) }
                        ?: throw RuntimeException("Patching failed")
                    log("Patched APK -> ${patched.absolutePath}")
                    // 7) Uninstall original Snapchat and install patched
                    log("Uninstalling original Snapchat...")
                    if (!uninstallPackage(sharedConfig.snapchatPackageName)) {
                        throw RuntimeException("Failed to uninstall Snapchat before installing patched build")
                    }
                    log("Installing patched Snapchat...")
                    if (!installPackage(patched, sharedConfig.snapchatPackageName, uninstallBefore = false)) {
                        throw RuntimeException("Patched Snapchat install failed")
                    }
                    log("All done!")
                    isDone = true
                } catch (t: Throwable) {
                    log("ERROR: ${t.message}")
                    isError = true
                } finally {
                    isRunning = false
                }
            }
        }
        BackHandler(enabled = isRunning) { /* block back while running */ }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .padding(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(12.dp)
                ) {
                    Text(
                        text = status,
                        overflow = TextOverflow.Visible,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
    }
}
