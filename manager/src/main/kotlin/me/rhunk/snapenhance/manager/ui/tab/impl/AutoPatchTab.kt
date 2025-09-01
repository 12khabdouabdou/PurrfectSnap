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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import kotlin.coroutines.resume

private val Context.dataStore by preferencesDataStore(name = "auto_patch_state")

class AutoPatchTab : Tab("auto_patch") {
    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var installDeferred: CompletableDeferred<Int>? = null

    companion object {
        private val KEY_PHASE = stringPreferencesKey("patch_phase")
        private val KEY_STATUS = stringPreferencesKey("patch_status")
    }

    enum class Phase {
        Idle, Patching12, AwaitingLogin, Patching13, Finished, Error
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
        val logsScrollState = rememberScrollState()
        var specialNotice by remember { mutableStateOf("") }
        var onLoginContinue by remember { mutableStateOf<(() -> Unit)?>(null) }

        // BEAUTIFUL AESTHETIC UI: Neon gradients, glowing border, animated accent!
        val infiniteTransition = rememberInfiniteTransition(label = "neon")
        val sweep = infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(
                animation = tween(3500, easing = LinearEasing)
            ), label = "spin"
        )
        val borderBrush = Brush.sweepGradient(
            colors = listOf(
                Color(0xFF69F0AE),
                Color(0xFF00B8D4),
                Color(0xFFD500F9),
                Color(0xFFE040FB),
                Color(0xFF69F0AE)
            ),
            center = Offset.Zero
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

        fun logStep(msg: String, special: String? = null) {
            log(msg)
            if (special != null) specialNotice = special else specialNotice = ""
        }
        fun logError(msg: String) = log("❌ $msg")

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
                    status = ""; progress = 0f; specialNotice = ""
                    persistState(newStatus = status)
                    phase = Phase.Patching12

                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    logStep("🌙 Downloading Snapchat 12.33.1.19...")

                    val snapchat12Apk = downloadWithOkHttp(SNAP12_URL, cacheDir, { progress = it }, "snapchat12.apk")
                        ?: throw RuntimeException("Failed to download Snapchat 12.33")

                    logStep("📤 Uploading Snapchat 12.33 to patch server (no modules)...")
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
                        logStep("✅ Patched Snapchat 12.33 APK received. Installing...")
                        if (!installPackage(patchedFile12, sharedConfig.snapchatPackageName))
                            throw RuntimeException("Patched Snapchat 12.33 install failed (no modules patch)")
                    }

                    phase = Phase.AwaitingLogin
                    status += "\n🌈 Login Instructions 🌈\n"
                    status += "1. Open Snapchat and login.\n"
                    status += "2. If you see 'temporarily disabled', force stop from App Info and relogin.\n"
                    status += "3. Return here and tap Continue.\n"
                    persistState(newStatus = status)

                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                        onLoginContinue = { cont.resume(Unit) }
                    }

                    phase = Phase.Patching13

                    val abi = detectAbiChoice()
                    logStep("⬇️ Downloading core.apk for 13.51...")
                    val assets13 = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")

                    val coreApk = downloadWithOkHttp(assets13.coreUrl, cacheDir, { progress = it }, "core.apk")
                        ?: throw RuntimeException("Failed to download core.apk")

                    logStep("⬇️ Downloading Snapchat 13.51.0.56...")
                    val snapchat13Apk = downloadWithOkHttp(SNAP13_URL, cacheDir, { progress = it }, "snapchat13.apk")
                        ?: throw RuntimeException("Failed to download Snapchat 13.51")

                    logStep("📤 Uploading core.apk & Snapchat 13.51 to server for patching modules...")
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
                        logStep("✅ Patched Snapchat 13.51 APK received. Installing...")
                        if (!installPackage(patchedFile13, sharedConfig.snapchatPackageName))
                            throw RuntimeException("Patched Snapchat 13.51 install failed")
                    }

                    logStep("⬇️ Downloading SnapEnhance...")
                    val seApk = downloadWithOkHttp(assets13.snapEnhanceUrl, cacheDir, { progress = it }, "snapenhance.apk")
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    logStep("✅ Installing SnapEnhance...")

                    if (!installPackage(seApk, sharedConfig.snapEnhancePackageName))
                        throw RuntimeException("SnapEnhance install failed")

                    phase = Phase.Finished
                    status += "\n\n✨ All done!\n\nFirst, open SnapEnhance and set it up.\nThen, open Snapchat and enjoy!"
                    persistState(newStatus = status)
                    clearApkCache()

                } catch (t: Throwable) {
                    logError("Error: ${t.message}\n${t.stackTraceToString()}")
                    phase = Phase.Error
                }
            }
        }

        // === BEAUTIFUL RESTORED UI ===
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Card(
                modifier = Modifier
                    .padding(18.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .border(
                        width = 3.dp,
                        brush = borderBrush,
                        shape = RoundedCornerShape(22.dp)
                    )
                    .clip(RoundedCornerShape(22.dp)),
                elevation = CardDefaults.cardElevation(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101022))
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.AutoFixHigh, "Patch", modifier = Modifier.size(40.dp), tint = Color(0xFF76FFBC))
                    Spacer(Modifier.height(8.dp))
                    Text("Auto Patch", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00E676))
                    Spacer(Modifier.height(20.dp))
                    if (phase == Phase.Idle || phase == Phase.Error || phase == Phase.Finished) {
                        Button(
                            onClick = { startPatchFlow() },
                            modifier = Modifier.fillMaxWidth(0.85f).height(52.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5))
                        ) {
                            Icon(Icons.Filled.FlashOn, "Go")
                            Spacer(Modifier.width(12.dp))
                            Text("Start Auto Patch", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (specialNotice.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            specialNotice,
                            color = Color(0xFFF44336),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(6.dp),
                            fontSize = 16.sp
                        )
                    }
                    if (phase == Phase.AwaitingLogin) {
                        Spacer(Modifier.height(16.dp))
                        Icon(Icons.Filled.Info, "", modifier = Modifier.size(32.dp), tint = Color(0xFFB39DDB))
                        Text(
                            "Login Steps:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFFCE93D8),
                        )
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.padding(10.dp)) {
                            Text("1. Open Snapchat and login.")
                            Text("2. If you see 'temporarily disabled', force stop from App Info and relogin.")
                            Text("3. Return here and tap Continue.")
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { onLoginContinue?.invoke() },
                            modifier = Modifier.fillMaxWidth(0.65f).height(46.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                        ) {
                            Text("Continue", fontSize = 17.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .verticalScroll(logsScrollState)
                            .background(Color(0x20202840), RoundedCornerShape(14.dp))
                            .border(1.4.dp, borderBrush, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            status,
                            fontSize = 13.5.sp,
                            color = Color(0xFFEEEEFF)
                        )
                    }
                    if (isDownloading || (progress > 0f && progress < 1f)) {
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            color = Color(0xFF3DF0C4),
                            trackColor = Color(0x203DF0C4)
                        )
                    }
                }
            }
        }
    }
}
