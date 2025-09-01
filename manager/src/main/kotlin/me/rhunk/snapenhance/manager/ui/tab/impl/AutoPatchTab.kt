package me.rhunk.snapenhance.manager.ui.tab.impl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlin.coroutines.resume // <<== THIS IMPORT FIXES resume()

private val Context.dataStore by preferencesDataStore(name = "auto_patch_state")

class AutoPatchTab : Tab("auto_patch") {
    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var installDeferred: CompletableDeferred<Int>? = null

    companion object {
        private val KEY_PHASE = stringPreferencesKey("patch_phase")
        private val KEY_STATUS = stringPreferencesKey("patch_status")
    }

    enum class Phase {
        Idle, Patching12, Uploading12, AwaitingLogin, Patching13, Uploading13, Finished, Error
    }

    private val SNAP12_URL = "https://github.com/particle-box/auto-patch-server/releases/download/v1.0.0/snapchat-12.33.1.19.apk"
    private val SNAP13_URL = "https://github.com/particle-box/auto-patch-server/releases/download/v1.0.0/snapchat-13.51.0.56.apk"
    private val PATCH_SERVER_BASE = "https://eternal077-auto-patch-server.hf.space"

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
        var phase by remember { mutableStateOf(Phase.Idle) }
        var status by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(0f) }
        var isDownloading by remember { mutableStateOf(false) }
        var logsExpanded by remember { mutableStateOf(false) }
        val logsScrollState = rememberScrollState()
        var specialNotice by remember { mutableStateOf("") }
        var onLoginContinue by remember { mutableStateOf<(() -> Unit)?>(null) }

        val neonColors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
            Color(0xFFFFF176).copy(alpha = 0.65f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )

        val borderBrush = Brush.sweepGradient(
            neonColors,
            center = androidx.compose.ui.geometry.Offset.Zero
        )

        val longClient = remember {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
        }

        fun persistState(newPhase: Phase? = null, newStatus: String? = null) {
            scope.launch {
                context.dataStore.edit { prefs ->
                    newPhase?.let { prefs[KEY_PHASE] = it.name }
                    newStatus?.let { prefs[KEY_STATUS] = it }
                }
            }
        }

        fun log(msg: String) {
            status += msg + "\n"
            persistState(newStatus = status)
        }

        fun logStep(step: String, msg: String, special: String? = null) {
            log("[$step] $msg")
            if (special != null) specialNotice = special else specialNotice = ""
        }

        fun logError(msg: String) {
            log("❌ $msg")
        }

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

        suspend fun installPackage(file: File, packageName: String): Boolean {
            if (sharedConfig.useRootInstaller) {
                val res = Shell.cmd(
                    "cp \"${file.absolutePath}\" /data/local/tmp/",
                    "pm install -r \"/data/local/tmp/${file.name}\"",
                    "rm \"/data/local/tmp/${file.name}\""
                ).exec()
                if (res.isSuccess) return true
                repeat(10) { if (isPackageInstalled(packageName)) return true; Thread.sleep(1000) }
                return false
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
            val resultCode = deferred.await()
            if (resultCode == Activity.RESULT_OK) return true
            repeat(15) { if (isPackageInstalled(packageName)) return true; Thread.sleep(1000) }
            return false
        }

        data class AssetResult(val snapEnhanceName: String, val snapEnhanceUrl: String, val coreName: String, val coreUrl: String)
        data class AbiChoice(val assetLabel: String, val desiredLibDir: String)

        fun detectAbiChoice(): AbiChoice {
            val abis = (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",").lowercase(Locale.ROOT)
            return when {
                "arm64" in abis || "v8a" in abis || "aarch64" in abis || "armv8" in abis -> AbiChoice("armv8", "arm64-v8a")
                "armeabi-v7a" in abis || "armv7" in abis || "armeabi" in abis || "v7a" in abis -> AbiChoice("armv7", "armeabi-v7a")
                else -> AbiChoice("armv8", "arm64-v8a")
            }
        }

        fun fetchSnapEnhanceAndCoreAssets(assetLabel: String): AssetResult? {
            val request = Request.Builder().url("https://api.github.com/repos/particle-box/SnapEnhance/releases").build()
            longClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val arr = JSONArray(body)
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
                        if (name.endsWith(".apk", true) && !name.equals("core.apk", true)) {
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

        fun downloadWithOkHttp(url: String, toDir: File, onProgress: (Float) -> Unit, fileNameOverride: String? = null): File? {
            isDownloading = true
            var result: File? = null
            try {
                longClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val out = if (fileNameOverride != null) File(toDir, fileNameOverride) else File.createTempFile("artifact", ".apk", toDir)
                    out.deleteOnExit()
                    val size = resp.body?.contentLength() ?: -1L
                    var total = 0L
                    resp.body?.byteStream()?.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                total += read
                                if (size > 0) onProgress(total.toFloat() / size.toFloat()) else onProgress(-1f)
                            }
                            output.flush()
                        }
                    }
                    result = out
                }
            } finally {
                isDownloading = false
            }
            return result
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
                } catch (_: Throwable) {}
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
            return false
        }

        fun clearApkCache() {
            val cacheDirs = listOfNotNull(activity?.externalCacheDir, activity?.cacheDir)
            cacheDirs.forEach { dir ->
                dir?.listFiles()?.forEach { f -> if (f.name.endsWith(".apk")) f.delete() }
            }
        }

        fun startPatchFlow() {
            scope.launch {
                try {
                    clearApkCache()
                    status = ""; progress = 0f; specialNotice = ""; logsExpanded = true
                    persistState(newStatus = status)
                    phase = Phase.Patching12

                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    logStep("1", "Downloading Snapchat 12.33.1.19...")

                    val snapchat12Apk = downloadWithOkHttp(SNAP12_URL, cacheDir, { progress = it }, "snapchat12.apk")
                        ?: throw RuntimeException("Failed to download Snapchat 12.33")

                    logStep("2", "Uploading Snapchat 12.33 to patch server (no modules)...")
                    warmUpServer("$PATCH_SERVER_BASE/health")
                    val reqBody12 = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("apk", "snapchat12.apk", snapchat12Apk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .build()
                    val request12 = Request.Builder().url("$PATCH_SERVER_BASE/patch_nomod").post(reqBody12).build()
                    longClient.newCall(request12).execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(2000) ?: ""
                            throw RuntimeException("Server failed patch (12.33, no modules): code=${response.code}, body=${err}")
                        }
                        val patchedFile12 = File(cacheDir, "PatchedSnapchat12.apk")
                        response.body?.byteStream()?.use { input ->
                            patchedFile12.outputStream().use { output -> input.copyTo(output) }
                        }
                        logStep("3", "Patched Snapchat 12.33 APK received. Installing...")
                        if (!installPackage(patchedFile12, sharedConfig.snapchatPackageName))
                            throw RuntimeException("Patched Snapchat 12.33 install failed (no modules patch)")
                    }

                    phase = Phase.AwaitingLogin
                    status += "\n=== LOGIN INSTRUCTIONS ===\n"
                    status += "1. Open Snapchat and login.\n"
                    status += "2. If you see the 'temporarily disabled' error, force stop Snapchat from App Info and relogin.\n"
                    status += "3. After login, return here to continue patching for modules.\n"
                    status += "\nTap 'Continue' below after you've logged in.\n"
                    persistState(newStatus = status)

                    // -- FIX: suspendCancellableCoroutine needs correct lambda args AND .resume import!
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                        onLoginContinue = { cont.resume(Unit) }
                    }

                    phase = Phase.Patching13

                    val abi = detectAbiChoice()
                    logStep("4", "Downloading core.apk for 13.51...")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")

                    val coreApk = downloadWithOkHttp(assets.coreUrl, cacheDir, { progress = it }, "core.apk")
                        ?: throw RuntimeException("Failed to download core.apk")

                    logStep("5", "Downloading Snapchat 13.51.0.56...")
                    val snapchat13Apk = downloadWithOkHttp(SNAP13_URL, cacheDir, { progress = it }, "snapchat13.apk")
                        ?: throw RuntimeException("Failed to download Snapchat 13.51")

                    logStep("6", "Uploading core.apk & Snapchat 13.51 to server for patching modules...")
                    warmUpServer("$PATCH_SERVER_BASE/health")
                    val reqBody13 = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("core", "core.apk", coreApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .addFormDataPart("apk", "snapchat13.apk", snapchat13Apk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .build()
                    val request13 = Request.Builder().url("$PATCH_SERVER_BASE/patch").post(reqBody13).build()
                    longClient.newCall(request13).execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(2000) ?: ""
                            throw RuntimeException("Server failed patch (13.51): code=${response.code}, body=${err}")
                        }
                        val patchedFile13 = File(cacheDir, "PatchedSnapchat13.apk")
                        response.body?.byteStream()?.use { input ->
                            patchedFile13.outputStream().use { output -> input.copyTo(output) }
                        }
                        logStep("7", "Patched Snapchat 13.51 APK received. Installing...")
                        if (!installPackage(patchedFile13, sharedConfig.snapchatPackageName))
                            throw RuntimeException("Patched Snapchat 13.51 install failed")
                    }

                    logStep("8", "Downloading SnapEnhance...")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")
                    val seApk = downloadWithOkHttp(assets.snapEnhanceUrl, cacheDir, { progress = it }, "snapenhance.apk")
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    logStep("9", "Installing SnapEnhance...")

                    if (!installPackage(seApk, sharedConfig.snapEnhancePackageName))
                        throw RuntimeException("SnapEnhance install failed")

                    phase = Phase.Finished
                    status += "\n\nAll done!\n\nFirst, open SnapEnhance and set it up.\nThen, open Snapchat and enjoy!"
                    persistState(newStatus = status)
                    clearApkCache()
                } catch (t: Throwable) {
                    logError("Error: ${t.message}\n${t.stackTraceToString()}")
                    phase = Phase.Error
                }
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp, bottom = 10.dp, start = 6.dp, end = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Auto Patch", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (phase == Phase.Idle || phase == Phase.Error || phase == Phase.Finished) {
                        Button(onClick = { startPatchFlow() }) { Text("Start Auto Patch") }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (specialNotice.isNotEmpty()) {
                        Text(specialNotice,
                             color = Color.Red,
                             fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.padding(vertical = 4.dp))
                    }
                    if (phase == Phase.AwaitingLogin) {
                        Spacer(Modifier.height(20.dp))
                        Text("Login Steps:", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("1. Open Snapchat and login.")
                        Text("2. If you see the 'temporarily disabled' error, force stop Snapchat from App Info and relogin.")
                        Text("3. After login, return here to continue patching for modules.")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { onLoginContinue?.invoke() }) { Text("Continue") }
                        Spacer(Modifier.height(10.dp))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(logsScrollState)
                            .background(Color(0x11000000))
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, borderBrush, RoundedCornerShape(8.dp))
                    ) {
                        Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (isDownloading || (progress > 0f && progress < 1f)) {
                        LinearProgressIndicator(progress)
                    }
                }
            }
        }
    }
}
