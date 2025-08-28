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
import me.rhunk.snapenhance.manager.patch.LSPatch
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class AutoPatchTab : Tab("auto_patch") {

    // Activity result launchers (non-root flow)
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
            val line = when (any) {
                is Throwable -> any.message + "\n" + any.stackTraceToString()
                else -> any.toString()
            }
            status += line + "\n"
        }

        fun detectArch(): String {
            val abis = Build.SUPPORTED_ABIS?.joinToString(",")?.lowercase(Locale.ROOT) ?: ""
            return when {
                "arm64" in abis || "v8a" in abis -> "arm64-v8a"
                "armeabi-v7a" in abis || "armeabi" in abis -> "armeabi-v7a"
                else -> "arm64-v8a" // sensible default for modern devices
            }
        }

        fun chooseAssetForArch(assets: Map<String, Pair<Long, String>>, arch: String): Pair<String, String>? {
            // Prefer exact arch match in filename, else fallback to first .apk
            val preferred = assets.entries
                .filter { it.key.endsWith(".apk", true) }
                .filter { it.key.contains(arch, true) }
                .maxByOrNull { it.value.first } // biggest usually correct
            if (preferred != null) return preferred.key to preferred.value.second
            val anyApk = assets.entries.firstOrNull { it.key.endsWith(".apk", true) } ?: return null
            return anyApk.key to anyApk.value.second
        }

        fun fetchLatestSEDebugAssetForArch(arch: String): Pair<String, String>? {
            // Refer to SEDownloadTab debug releases approach but filter to latest debug prerelease from particle-box/SnapEnhance
            // Returns (fileName, downloadUrl)
            val req = Request.Builder()
                .url("https://api.github.com/repos/particle-box/SnapEnhance/releases")
                .build()
            val resp = OkHttpClient().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val arr = JSONArray(body)
            // Pick most recent prerelease that has assets
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val isPre = obj.optBoolean("prerelease", false)
                val tag = obj.optString("tag_name", "")
                if (!isPre || !tag.startsWith("debug-")) continue
                val assetsArr = obj.optJSONArray("assets") ?: continue
                val assets = mutableMapOf<String, Pair<Long, String>>() // fileName -> (size, url)
                for (j in 0 until assetsArr.length()) {
                    val a = assetsArr.getJSONObject(j)
                    val name = a.optString("name", "")
                    val size = a.optLong("size", 0L)
                    val url = a.optString("browser_download_url", "")
                    if (name.isNotBlank() && url.isNotBlank()) {
                        assets[name] = size to url
                    }
                }
                val chosen = chooseAssetForArch(assets, arch)
                if (chosen != null) return chosen
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

        fun downloadWithDoh(url: String, toDir: File, onProgress: (Float) -> Unit): File? {
            // Use APKMirror.okhttpClient which is built on top of DoH per APKMirror.kt
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
                if (!uninstallPackage(packageName)) {
                    log("Uninstall failed for $packageName")
                    return false
                }
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

        fun patchWithLSPatch(
            baseApk: File,
            seModule: File,
            onLog: (Any?) -> Unit
        ): File? {
            val patcher = LSPatch(
                activity,
                modules = mapOf(sharedConfig.snapEnhancePackageName to seModule),
                obfuscate = sharedConfig.obfuscateLSPatch,
                printLog = { onLog("[LSPatch] $it") }
            )
            val out = patcher.patchSplits(listOf(baseApk))
            return out["base.apk"]
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

                    // 1) Detect ABI
                    val arch = detectArch()
                    log("Detected ABI: $arch")

                    // 2) Download latest SE debug for device ABI (no DNS override)
                    log("Fetching latest SnapEnhance debug asset...")
                    val (seName, seUrl) = fetchLatestSEDebugAssetForArch(arch)
                        ?: throw RuntimeException("No matching SnapEnhance debug APK found")
                    log("Downloading SnapEnhance: $seName")
                    progress = 0f
                    val seApk = downloadWithOkHttp(seUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    log("Downloaded SE -> ${seApk.absolutePath}")

                    // 3) Install SnapEnhance (update if present)
                    log("Installing SnapEnhance...")
                    progress = -1f
                    if (!installPackage(seApk, sharedConfig.snapEnhancePackageName, uninstallBefore = false)) {
                        throw RuntimeException("SnapEnhance install failed")
                    }
                    log("SnapEnhance installed")

                    // 4) Download Snapchat with DoH client against the provided APK URL
                    val snapchatUrl = "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=4764674&key=bd0c88c47174308d9c6862f815bc96246d5077a8&forcebaseapk=true"
                    log("Downloading Snapchat (DoH enabled)...")
                    progress = 0f
                    val snapchatBase = downloadWithDoh(snapchatUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download Snapchat")
                    log("Downloaded Snapchat -> ${snapchatBase.absolutePath}")

                    // 5) Install Snapchat base (update if present)
                    log("Installing Snapchat base...")
                    progress = -1f
                    if (!installPackage(snapchatBase, sharedConfig.snapchatPackageName, uninstallBefore = false)) {
                        throw RuntimeException("Snapchat base install failed")
                    }
                    log("Snapchat base installed")

                    // 6) Patch Snapchat with LSPatch using the downloaded SE module
                    log("Patching Snapchat with LSPatch...")
                    progress = -1f
                    val patched = patchWithLSPatch(snapchatBase, seApk) { msg -> log(msg) }
                        ?: throw RuntimeException("Patching failed")
                    log("Patched APK -> ${patched.absolutePath}")

                    // 7) Uninstall & install patched Snapchat (signature change)
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

        BackHandler(isRunning) { /* Do nothing, block back press if running */ }

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
            if (isDone) {
                Button(onClick = { navigation.navigateTo(HomeTab::class, noHistory = true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to Home")
                }
            } else if (isError) {
                Button(onClick = { navigation.navigateTo(HomeTab::class, noHistory = true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
            LaunchedEffect(status) { scrollState.scrollTo(scrollState.maxValue) }
        }
    }
}
