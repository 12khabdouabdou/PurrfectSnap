package me.rhunk.snapenhance.manager.ui.tab.impl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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

private val Context.dataStore by preferencesDataStore(name = "auto_patch_state")

class AutoPatchTab : Tab("auto_patch") {
    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var installDeferred: CompletableDeferred<Int>? = null

    companion object {
        private val KEY_PHASE = stringPreferencesKey("patch_phase")
        private val KEY_STATUS = stringPreferencesKey("patch_status")
    }
    enum class Phase {
        Idle, Patching12, Uploading12, AwaitingLogin, TestModeDialog, Disclaimer, Patching13, Uploading13, Finished, Error
    }

    // Use your actual release asset URLs here!
    private val SNAP12_URL = "https://github.com/particle-box/auto-patch-server/releases/download/v1.0.0/snapchat-12.33.1.19.apk"
    private val SNAP13_URL = "https://github.com/particle-box/auto-patch-server/releases/download/v1.0.0/snapchat-13.51.0.56.apk"

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
        var showTestModeDialog by remember { mutableStateOf(false) }
        var showTestModeNoMsg by remember { mutableStateOf(false) }
        var logsExpanded by remember { mutableStateOf(false) }
        val logsScrollState = rememberScrollState()
        var currentLogLine by remember { mutableIntStateOf(-1) }
        var stepShortMsg by remember { mutableStateOf("") }
        var specialNotice by remember { mutableStateOf("") }

        // Glowy animated border for logs
        val neonColors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
            Color(0xFFFFF176).copy(alpha = 0.65f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        val animTransition = rememberInfiniteTransition(label = "glow")
        val borderBrush = Brush.sweepGradient(
            neonColors,
            center = Offset.Zero
        )

        val shortSteps = listOf(
            "Downloading SnapEnhance...",
            "Downloading core.apk...",
            "Downloading Snapchat 12.33...",
            "Uploading files to patch server (may take 2–3 minutes)...",
            "Installing SnapEnhance...",
            "Installing patched Snapchat 12.33...",
            "Login/SnapEnhance setup...",
            "Downloading core.apk for 13.51...",
            "Downloading Snapchat 13.51...",
            "Uploading files (13.51) to patch server (may take 2–3 minutes)...",
            "Installing patched Snapchat 13.51..."
        )
        LaunchedEffect(Unit) {
            val prefs = context.dataStore.data.first()
            phase = runCatching { Phase.valueOf(prefs[KEY_PHASE] ?: Phase.Idle.name) }.getOrElse { Phase.Idle }
            status = prefs[KEY_STATUS] ?: ""
            delay(100)
            logsScrollState.scrollTo(logsScrollState.maxValue)
        }
        fun persistState(newPhase: Phase? = null, newStatus: String? = null) {
            scope.launch {
                context.dataStore.edit { prefs ->
                    newPhase?.let { prefs[KEY_PHASE] = it.name }
                    newStatus?.let { prefs[KEY_STATUS] = it }
                }
            }
        }
        val longClient = remember {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
        }
        fun log(msg: String, asStep: Boolean = false, asError: Boolean = false, showShort: String? = null, special: String? = null) {
            val linesBefore = status.lineSequence().count()
            status += msg + "\n"
            if (asStep || asError) currentLogLine = linesBefore
            if (showShort != null) stepShortMsg = showShort
            if (special != null) specialNotice = special else specialNotice = ""
            persistState(newStatus = status)
        }
        fun logStep(idx: Int, msg: String, special: String? = null) = log(msg, asStep = true, showShort = shortSteps.getOrNull(idx), special = special)
        fun logError(msg: String) = log("❌ $msg", asError = true, showShort = "Error: see logs")

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
        fun verifyApkMatchesAbi(apk: File, desiredLibDir: String): Boolean =
            try { ZipFile(apk).use { zf -> zf.entries().asSequence().any { it.name.startsWith("lib/$desiredLibDir/") } } }
            catch (_: Throwable) { false }
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
        fun setPhase(newPhase: Phase) {
            phase = newPhase
            persistState(newPhase)
        }
        fun clearApkCache() {
            val cacheDirs = listOfNotNull(activity?.externalCacheDir, activity?.cacheDir)
            cacheDirs.forEach { dir ->
                dir?.listFiles()?.forEach { f -> if (f.name.endsWith(".apk")) f.delete() }
            }
        }

        fun startPatchV12() {
            scope.launch {
                setPhase(Phase.Patching12)
                status = ""; progress = 0f; specialNotice = ""
                persistState(newStatus = status)
                try {
                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    val abi = detectAbiChoice()
                    logStep(0, "Detected ABI: ${abi.desiredLibDir}")
                    logStep(0, "Looking for SnapEnhance & core.apk release assets...")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")
                    progress = 0f
                    logStep(0, "Downloading SnapEnhance from: ${assets.snapEnhanceName}")
                    val seApk = downloadWithOkHttp(assets.snapEnhanceUrl, cacheDir, { progress = it }, "snapenhance.apk")
                        ?: throw RuntimeException("Failed to download SnapEnhance")
                    logStep(1, "SnapEnhance APK ready.")
                    val coreApk = downloadWithOkHttp(assets.coreUrl, cacheDir, { progress = it }, "core.apk")
                        ?: throw RuntimeException("Failed to download core.apk")
                    logStep(2, "core.apk ready.")
                    logStep(2, "Downloading Snapchat 12.33.1.19 directly from server...")
                    val snapchatApk = downloadWithOkHttp(SNAP12_URL, cacheDir, { progress = it }, "snapchat12.apk")
                        ?: throw RuntimeException("Failed to download Snapchat 12.33")
                    logStep(3, "Downloaded Snapchat 12.33: ${snapchatApk.absolutePath}")
                    setPhase(Phase.Uploading12)
                    logStep(
                        3, "Uploading for patch (core.apk & Snapchat 12.33)... This will take 2–3 minutes depending on your network and server load.",
                        "This will take 2–3 minutes depending on your network & server load!"
                    )
                    warmUpServer("https://eternal077-auto-patch-server.hf.space/health")
                    val reqBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("core", "core.apk", coreApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .addFormDataPart("apk", "snapchat.apk", snapchatApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .build()
                    val request = Request.Builder().url("https://eternal077-auto-patch-server.hf.space/patch").post(reqBody).build()
                    longClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(2000) ?: ""
                            throw RuntimeException("Server failed patch: code=${response.code}, body=${err}")
                        }
                        val patchedFile = File(cacheDir, "PatchedSnapchat12.apk")
                        response.body?.byteStream()?.use { input -> patchedFile.outputStream().use { output -> input.copyTo(output) } }
                        logStep(4, "Patched Snapchat APK received. Installing...")
                        if (!installPackage(seApk, sharedConfig.snapEnhancePackageName)) throw RuntimeException("SnapEnhance install failed")
                        logStep(5, "SnapEnhance installed.")
                        if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat 12.33 install failed")
                        logStep(6, "Patched Snapchat 12.33 installed.")
                        setPhase(Phase.AwaitingLogin)
                    }
                } catch (t: Throwable) {
                    logError("Error: ${t.message}\n${t.stackTraceToString()}")
                    setPhase(Phase.Error)
                }
            }
        }
        fun startPatchV13() {
            scope.launch {
                setPhase(Phase.Patching13)
                status = ""; progress = 0f; specialNotice = ""
                persistState(newStatus = status)
                try {
                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    val abi = detectAbiChoice()
                    logStep(7, "Downloading core.apk (latest) for 13.51...")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")
                    val coreApk = downloadWithOkHttp(assets.coreUrl, cacheDir, { progress = it }, "core.apk")
                        ?: throw RuntimeException("Failed to download core.apk")
                    logStep(8, "core.apk ready.")
                    logStep(8, "Downloading Snapchat 13.51.0.56 directly from server...")
                    val snapchatApk = downloadWithOkHttp(SNAP13_URL, cacheDir, { progress = it }, "snapchat13.apk")
                        ?: throw RuntimeException("Failed to download Snapchat 13.51")
                    logStep(9, "Downloaded Snapchat 13.51: ${snapchatApk.absolutePath}")
                    setPhase(Phase.Uploading13)
                    logStep(
                        9, "Uploading core.apk and Snapchat 13.51 APK to patch server... This will take 2–3 minutes depending on your network and server load.",
                        "This will take 2–3 minutes depending on your network & server load!"
                    )
                    warmUpServer("https://eternal077-auto-patch-server.hf.space/health")
                    val reqBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("core", "core.apk", coreApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .addFormDataPart("apk", "snapchat.apk", snapchatApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull()))
                        .build()
                    val request = Request.Builder().url("https://eternal077-auto-patch-server.hf.space/patch").post(reqBody).build()
                    longClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(2000) ?: ""
                            throw RuntimeException("Server failed patch: code=${response.code}, body=${err}")
                        }
                        val patchedFile = File(cacheDir, "PatchedSnapchat13.apk")
                        response.body?.byteStream()?.use { input -> patchedFile.outputStream().use { output -> input.copyTo(output) } }
                        logStep(10, "Patched Snapchat 13.51 APK received. Installing...")
                        if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat 13.51 install failed")
                        logStep(10, "Patched Snapchat 13.51 installed. All steps complete!")
                        clearApkCache()
                        setPhase(Phase.Finished)
                    }
                } catch (t: Throwable) {
                    logError("Error: ${t.message}\n${t.stackTraceToString()}")
                    setPhase(Phase.Error)
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
                    when (phase) {
                        Phase.Idle -> {
                            Card(
                                Modifier.padding(12.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(
                                    Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "Welcome to Auto Patch",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Time required: Approximately 3–5 minutes (super fast path).\n\nThis is an automated process which spares you the hassle of manually needing to download and patching apks. You will be properly instructed when an action is required.",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(Modifier.height(32.dp))
                                    Button(onClick = { startPatchV12() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                                        Text("Start Auto Patch")
                                    }
                                }
                            }
                        }
                        Phase.Patching12, Phase.Patching13, Phase.Uploading12, Phase.Uploading13 -> {
                            Column(
                                Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                AnimatedContent(targetState = stepShortMsg, label = "") { msg ->
                                    Text(
                                        msg,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(0.dp, 14.dp, 0.dp, 18.dp)
                                    )
                                }
                                if (isDownloading) {
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier
                                            .fillMaxWidth(0.65f)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(16.dp)),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(40.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surface
                                    )
                                }
                                if (specialNotice.isNotBlank()) {
                                    Spacer(Modifier.height(18.dp))
                                    Text(
                                        specialNotice,
                                        color = Color(0xFFFFEE58),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { logsExpanded = !logsExpanded },
                                    modifier = Modifier.padding(top = 16.dp),
                                    shape = RoundedCornerShape(21.dp)
                                ) {
                                    if (logsExpanded) {
                                        Icon(Icons.Filled.ExpandLess, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Hide Logs")
                                    } else {
                                        Icon(Icons.Filled.ExpandMore, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Show Logs")
                                    }
                                }
                                AnimatedVisibility(
                                    visible = logsExpanded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 20.dp)
                                    ) {
                                        Surface(
                                            Modifier
                                                .fillMaxWidth()
                                                .border(
                                                    width = 2.8.dp,
                                                    brush = borderBrush,
                                                    shape = RoundedCornerShape(24.dp)
                                                )
                                                .clip(RoundedCornerShape(24.dp)),
                                            color = MaterialTheme.colorScheme.surface,
                                            tonalElevation = 4.dp
                                        ) {
                                            LaunchedEffect(status, logsExpanded) {
                                                delay(150)
                                                logsScrollState.scrollTo(logsScrollState.maxValue)
                                            }
                                            val logLines = status.lines()
                                            Column(
                                                Modifier
                                                    .padding(12.dp)
                                                    .verticalScroll(logsScrollState)
                                            ) {
                                                logLines.forEachIndexed { idx, line ->
                                                    val isCurrent = idx == currentLogLine
                                                    AnimatedContent(targetState = isCurrent, label = "", transitionSpec = {
                                                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                                    }) { highlight ->
                                                        if (highlight)
                                                            Text(
                                                                text = line,
                                                                color = Color(0xFFFFF176),
                                                                fontWeight = FontWeight.Bold,
                                                                style = MaterialTheme.typography.bodyMedium
                                                            )
                                                        else
                                                            Text(
                                                                text = line,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                fontWeight = FontWeight.Normal,
                                                                style = MaterialTheme.typography.bodySmall
                                                            )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Phase.AwaitingLogin -> {
                            Spacer(Modifier.height(16.dp))
                            Text("Next Steps (Please follow carefully)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Card(
                                shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.padding(18.dp)) {
                                    Text("1. Open SnapEnhance and complete initial setup (ignore mapping errors if any).", fontWeight = FontWeight.Medium)
                                    Text("2. In SnapEnhance settings, ENABLE Test mode.", fontWeight = FontWeight.Medium)
                                    Text("3. Open Snapchat and log in. If you see 'temporarily disabled', go to Snapchat App Info, Force Stop, and retry login.", fontWeight = FontWeight.Medium)
                                    Text("4. After login, in SnapEnhance, turn OFF Test mode.", fontWeight = FontWeight.Medium)
                                    Text("5. Come back here and press 'Login Done'.")
                                }
                            }
                            Spacer(Modifier.height(26.dp))
                            Button(
                                onClick = { showTestModeDialog = true },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Login Done")
                            }
                        }
                        Phase.Disclaimer -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.3f to MaterialTheme.colorScheme.secondaryContainer,
                                            1f to MaterialTheme.colorScheme.background
                                        ),
                                        RoundedCornerShape(24.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Column(
                                    Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "⚠️ Important: Test Mode Warning",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    Text(
                                    """
Before you proceed, you *MUST* turn OFF "Test mode" in SnapEnhance settings!

**Why?**
- "Test mode" is only required for logging into 12.33.
- ON NEWER VERSIONS, "test mode" allows detection and will cause your account to be locked!
- You MUST turn test mode OFF before continuing.

If you haven't already:
  • Open SnapEnhance settings
  • Ensure "Test Mode" is toggled OFF
  • Proceed ONLY if you're 100% sure

If you don't do this, your account may be locked!
                                        """.trimIndent(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(34.dp))
                                    Button(
                                        onClick = { startPatchV13() },
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    ) {
                                        Text("Yes, I understand. Test mode is OFF. Let's proceed!")
                                    }
                                }
                            }
                        }
                        Phase.Finished -> {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(62.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Done! Snapchat is patched!", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Open SnapEnhance and let it regenerate mappings, then open Snapchat and enjoy!",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    setPhase(Phase.Idle)
                                    status = ""
                                    persistState(newStatus = "")
                                },
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text("Back to Home")
                            }
                        }
                        Phase.Error -> {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                            Text("An error occurred", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text("Please review the log below for details.", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    setPhase(Phase.Idle)
                                    status = ""
                                    persistState(newStatus = "")
                                },
                                Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Retry", color = MaterialTheme.colorScheme.onError)
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { logsExpanded = !logsExpanded },
                                shape = RoundedCornerShape(21.dp),
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                if (logsExpanded) {
                                    Icon(Icons.Filled.ExpandLess, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Hide Logs")
                                } else {
                                    Icon(Icons.Filled.ExpandMore, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Show Logs")
                                }
                            }
                            AnimatedVisibility(
                                visible = logsExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 20.dp)
                                ) {
                                    Surface(
                                        Modifier
                                            .fillMaxWidth()
                                            .border(
                                                width = 2.8.dp,
                                                brush = borderBrush,
                                                shape = RoundedCornerShape(24.dp)
                                            )
                                            .clip(RoundedCornerShape(24.dp)),
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 4.dp
                                    ) {
                                        LaunchedEffect(status, logsExpanded) {
                                            delay(150)
                                            logsScrollState.scrollTo(logsScrollState.maxValue)
                                        }
                                        val logLines = status.lines()
                                        Column(
                                            Modifier
                                                .padding(12.dp)
                                                .verticalScroll(logsScrollState)
                                        ) {
                                            logLines.forEachIndexed { idx, line ->
                                                val isCurrent = idx == currentLogLine
                                                AnimatedContent(targetState = isCurrent, label = "") { highlight ->
                                                    if (highlight)
                                                        Text(
                                                            text = line,
                                                            color = Color(0xFFFFF176),
                                                            fontWeight = FontWeight.Bold,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                    else
                                                        Text(
                                                            text = line,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            fontWeight = FontWeight.Normal,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                    if (showTestModeDialog) {
                        AlertDialog(
                            onDismissRequest = { showTestModeDialog = false },
                            title = { Text("Have you turned OFF Test mode in SnapEnhance settings?") },
                            text = { Text("Test mode must be OFF before continuing — leaving it ON will highly likely result in a Snapchat ban.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showTestModeDialog = false
                                    setPhase(Phase.Disclaimer)
                                }) { Text("Yes, test mode is OFF") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showTestModeDialog = false
                                    showTestModeNoMsg = true
                                }) { Text("No") }
                            }
                        )
                    }
                    if (showTestModeNoMsg) {
                        AlertDialog(
                            onDismissRequest = { showTestModeNoMsg = false },
                            title = { Text("Turn off Test mode!") },
                            text = { Text("Please, open SnapEnhance settings and switch OFF Test mode before continuing. This step is critical!") },
                            confirmButton = {
                                TextButton(onClick = { showTestModeNoMsg = false }) { Text("OK") }
                            },
                            dismissButton = {}
                        )
                    }
                }
            }
            BackHandler(enabled = (
                phase == Phase.Patching12 ||
                phase == Phase.Uploading12 ||
                phase == Phase.Patching13 ||
                phase == Phase.Uploading13
            )) { }
        }
    }
}
