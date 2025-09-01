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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
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
        Idle, Downloading12, Installing12, AwaitingLogin, Downloading13, Installing13, DownloadingSnapEnhance, InstallingSnapEnhance, Finished, Error
    }

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
        var showLoginConfirmDialog by remember { mutableStateOf(false) }
        var showLoginFailedDialog by remember { mutableStateOf(false) }
        var logsExpanded by remember { mutableStateOf(false) }
        val logsScrollState = rememberScrollState()
        var currentLogLine by remember { mutableIntStateOf(-1) }
        var stepShortMsg by remember { mutableStateOf("") }
        var specialNotice by remember { mutableStateOf("") }

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
            "Downloading patched Snapchat 12.33...",
            "Installing patched Snapchat 12.33...",
            "Login/Setup...",
            "Downloading patched Snapchat 13.51...",
            "Installing patched Snapchat 13.51...",
            "Downloading SnapEnhance...",
            "Installing SnapEnhance..."
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
        
        val client = remember {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
        }
        
        fun log(msg: String, asStep: Boolean = false, asError: Boolean = false, showShort: String? = null, special: String? = null) {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val linesBefore = status.lineSequence().count()
            status += "[$timestamp] $msg\n"
            if (asStep || asError) currentLogLine = linesBefore
            if (showShort != null) stepShortMsg = showShort
            if (special != null) specialNotice = special else specialNotice = ""
            persistState(newStatus = status)
        }
        
        fun logStep(idx: Int, msg: String, special: String? = null) = log("✅ $msg", asStep = true, showShort = shortSteps.getOrNull(idx), special = special)
        fun logInfo(msg: String) = log("ℹ️ $msg")
        fun logError(msg: String) = log("❌ $msg", asError = true, showShort = "Error: see logs")
        fun logDebug(msg: String) = log("🔍 $msg")

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
            logDebug("Starting installation of ${file.name} (${file.length() / 1024 / 1024} MB)")
            if (sharedConfig.useRootInstaller) {
                logDebug("Using root installer method")
                val res = Shell.cmd(
                    "cp \"${file.absolutePath}\" /data/local/tmp/",
                    "pm install -r \"/data/local/tmp/${file.name}\"",
                    "rm \"/data/local/tmp/${file.name}\""
                ).exec()
                if (res.isSuccess) {
                    logDebug("Root installation successful")
                    return true
                }
                logDebug("Root installation failed, checking if package installed anyway...")
                repeat(10) { 
                    if (isPackageInstalled(packageName)) {
                        logDebug("Package found installed after ${it + 1} attempts")
                        return true
                    }
                    Thread.sleep(1000)
                }
                return false
            }
            logDebug("Using standard installer method")
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
            logDebug("Installation intent returned with code: $resultCode")
            if (resultCode == Activity.RESULT_OK) {
                logDebug("Installation successful")
                return true
            }
            logDebug("Installation may have failed, checking if package installed anyway...")
            repeat(15) { 
                if (isPackageInstalled(packageName)) {
                    logDebug("Package found installed after ${it + 1} attempts")
                    return true
                }
                Thread.sleep(1000)
            }
            logError("Installation failed after all attempts")
            return false
        }

        data class AssetResult(val snapEnhanceName: String, val snapEnhanceUrl: String, val coreName: String, val coreUrl: String)
        data class AbiChoice(val assetLabel: String, val desiredLibDir: String)
        data class ReleaseAssets(val snapchat12Url: String, val snapchat13Url: String, val releaseName: String)
        
        fun detectAbiChoice(): AbiChoice {
            val abis = (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",").lowercase(Locale.ROOT)
            logDebug("Device ABIs: $abis")
            return when {
                "arm64" in abis || "v8a" in abis || "aarch64" in abis || "armv8" in abis -> AbiChoice("armv8", "arm64-v8a")
                "armeabi-v7a" in abis || "armv7" in abis || "armeabi" in abis || "v7a" in abis -> AbiChoice("armv7", "armeabi-v7a")
                else -> AbiChoice("armv8", "arm64-v8a")
            }
        }
        
        fun fetchLatestPatchedApks(): ReleaseAssets? {
            logInfo("Fetching latest patched APKs from GitHub releases...")
            val request = Request.Builder().url("https://api.github.com/repos/particle-box/auto-patch-server/releases").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logError("Failed to fetch releases: HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val releases = JSONArray(body)
                
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    val releaseName = release.optString("name", "Unknown")
                    val tagName = release.optString("tag_name", "")
                    logDebug("Checking release: $releaseName (tag: $tagName)")
                    
                    val assets = release.optJSONArray("assets") ?: continue
                    var snapchat12Url: String? = null
                    var snapchat13Url: String? = null
                    
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val name = asset.optString("name", "")
                        val url = asset.optString("browser_download_url", "")
                        
                        when {
                            name == "PatchedSnapchat-12.33-noembed.apk" -> {
                                snapchat12Url = url
                                logDebug("Found Snapchat 12.33 APK: $name")
                            }
                            name == "PatchedSnapchat-13.51-embedded.apk" -> {
                                snapchat13Url = url
                                logDebug("Found Snapchat 13.51 APK: $name")
                            }
                        }
                    }
                    
                    if (snapchat12Url != null && snapchat13Url != null) {
                        logInfo("Found both patched APKs in release: $releaseName")
                        return ReleaseAssets(snapchat12Url, snapchat13Url, releaseName)
                    }
                }
                logError("No release found with both patched APKs")
                return null
            }
        }
        
        fun fetchSnapEnhanceAndCoreAssets(assetLabel: String): AssetResult? {
            logInfo("Fetching SnapEnhance and core.apk assets for ABI: $assetLabel")
            val request = Request.Builder().url("https://api.github.com/repos/particle-box/SnapEnhance/releases").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logError("Failed to fetch SnapEnhance releases: HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val releaseName = obj.optString("name", "Unknown")
                    if (!obj.optBoolean("prerelease", false)) {
                        logDebug("Skipping non-prerelease: $releaseName")
                        continue
                    }
                    logDebug("Checking prerelease: $releaseName")
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
                            logDebug("Found core.apk")
                        }
                        if (name.endsWith(".apk", true) && !name.equals("core.apk", true)) {
                            snapAssetName = name
                            snapAssetUrl = url
                            logDebug("Found SnapEnhance APK: $name")
                        }
                    }
                    if (snapAssetName != null && coreAssetName != null && snapAssetUrl != null && coreAssetUrl != null) {
                        logInfo("Found SnapEnhance assets in release: $releaseName")
                        return AssetResult(snapAssetName, snapAssetUrl, coreAssetName, coreAssetUrl)
                    }
                }
                logError("No SnapEnhance/core.apk pair found")
                return null
            }
        }
        
        fun downloadWithProgress(url: String, toDir: File, onProgress: (Float) -> Unit, fileNameOverride: String? = null): File? {
            isDownloading = true
            var result: File? = null
            val fileName = fileNameOverride ?: url.substringAfterLast("/")
            logInfo("Starting download: $fileName")
            logDebug("Download URL: $url")
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        logError("Download failed: HTTP ${resp.code}")
                        return null
                    }
                    val out = if (fileNameOverride != null) File(toDir, fileNameOverride) else File.createTempFile("download", ".apk", toDir)
                    out.deleteOnExit()
                    val size = resp.body?.contentLength() ?: -1L
                    logDebug("File size: ${size / 1024 / 1024} MB")
                    var total = 0L
                    var lastLoggedPercent = 0
                    resp.body?.byteStream()?.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                total += read
                                if (size > 0) {
                                    val percent = ((total * 100) / size).toInt()
                                    onProgress(total.toFloat() / size.toFloat())
                                    if (percent >= lastLoggedPercent + 10) {
                                        logDebug("Download progress: $percent%")
                                        lastLoggedPercent = percent
                                    }
                                } else {
                                    onProgress(-1f)
                                }
                            }
                            output.flush()
                        }
                    }
                    logInfo("Download complete: ${out.name} (${total / 1024 / 1024} MB)")
                    result = out
                }
            } catch (e: Exception) {
                logError("Download exception: ${e.message}")
            } finally {
                isDownloading = false
            }
            return result
        }
        
        fun setPhase(newPhase: Phase) {
            logDebug("Phase transition: $phase -> $newPhase")
            phase = newPhase
            persistState(newPhase)
        }
        
        fun clearApkCache() {
            logDebug("Clearing APK cache...")
            val cacheDirs = listOfNotNull(activity?.externalCacheDir, activity?.cacheDir)
            var deletedCount = 0
            cacheDirs.forEach { dir ->
                dir?.listFiles()?.forEach { f -> 
                    if (f.name.endsWith(".apk")) {
                        f.delete()
                        deletedCount++
                    }
                }
            }
            logDebug("Deleted $deletedCount APK files from cache")
        }

        fun startPatchFlow() {
            scope.launch {
                try {
                    clearApkCache()
                    status = ""; progress = 0f; specialNotice = ""
                    persistState(newStatus = status)
                    setPhase(Phase.Downloading12)
                    
                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    logInfo("Cache directory: ${cacheDir.absolutePath}")
                    
                    // Fetch latest patched APKs
                    val patchedApks = fetchLatestPatchedApks()
                        ?: throw RuntimeException("Failed to fetch latest patched APKs from GitHub")
                    
                    logStep(0, "Downloading pre-patched Snapchat 12.33 (no embed)...")
                    logInfo("Release: ${patchedApks.releaseName}")
                    val snapchat12File = downloadWithProgress(
                        patchedApks.snapchat12Url,
                        cacheDir,
                        { progress = it },
                        "PatchedSnapchat-12.33.apk"
                    ) ?: throw RuntimeException("Failed to download patched Snapchat 12.33")
                    
                    setPhase(Phase.Installing12)
                    logStep(1, "Installing patched Snapchat 12.33...")
                    if (!installPackage(snapchat12File, sharedConfig.snapchatPackageName)) {
                        throw RuntimeException("Failed to install patched Snapchat 12.33")
                    }
                    logInfo("Successfully installed Snapchat 12.33")
                    
                    setPhase(Phase.AwaitingLogin)
                    logStep(2, "Please login to Snapchat now", "Please complete the login process before continuing")
                    
                    // Wait for user to confirm login
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                        showLoginConfirmDialog = true
                        scope.launch {
                            while (showLoginConfirmDialog) {
                                delay(100)
                            }
                            cont.resume(Unit)
                        }
                    }
                    
                    setPhase(Phase.Downloading13)
                    logStep(3, "Downloading pre-patched Snapchat 13.51 (with embed)...")
                    val snapchat13File = downloadWithProgress(
                        patchedApks.snapchat13Url,
                        cacheDir,
                        { progress = it },
                        "PatchedSnapchat-13.51.apk"
                    ) ?: throw RuntimeException("Failed to download patched Snapchat 13.51")
                    
                    setPhase(Phase.Installing13)
                    logStep(4, "Installing patched Snapchat 13.51...")
                    if (!installPackage(snapchat13File, sharedConfig.snapchatPackageName)) {
                        throw RuntimeException("Failed to install patched Snapchat 13.51")
                    }
                    logInfo("Successfully installed Snapchat 13.51")
                    
                    // Fetch and install SnapEnhance
                    val abi = detectAbiChoice()
                    logInfo("Device ABI choice: ${abi.desiredLibDir}")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found for ABI: ${abi.assetLabel}")
                    
                    setPhase(Phase.DownloadingSnapEnhance)
                    logStep(5, "Downloading SnapEnhance...")
                    logInfo("SnapEnhance asset: ${assets.snapEnhanceName}")
                    val seApk = downloadWithProgress(
                        assets.snapEnhanceUrl,
                        cacheDir,
                        { progress = it },
                        "snapenhance.apk"
                    ) ?: throw RuntimeException("Failed to download SnapEnhance")
                    
                    setPhase(Phase.InstallingSnapEnhance)
                    logStep(6, "Installing SnapEnhance...")
                    if (!installPackage(seApk, sharedConfig.snapEnhancePackageName)) {
                        throw RuntimeException("Failed to install SnapEnhance")
                    }
                    logInfo("Successfully installed SnapEnhance")
                    
                    clearApkCache()
                    setPhase(Phase.Finished)
                    logInfo("🎉 Auto patch process completed successfully!")
                    
                } catch (t: Throwable) {
                    logError("Fatal error: ${t.message}")
                    logDebug("Stack trace: ${t.stackTraceToString()}")
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
                                        "Time required: Approximately 3–5 minutes.\n\nThis is an automated process which spares you the hassle of manually needing to download and patching apks. You will be properly instructed when an action is required.",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(Modifier.height(32.dp))
                                    Button(onClick = { startPatchFlow() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                                        Text("Start Auto Patch")
                                    }
                                }
                            }
                        }
                        Phase.Downloading12, Phase.Installing12, Phase.Downloading13, Phase.Installing13, Phase.DownloadingSnapEnhance, Phase.InstallingSnapEnhance -> {
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
                                        modifier = Modifier.padding(0.dp, 14.dp, 0.dp, 18.dp),
                                        textAlign = TextAlign.Center
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
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center
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
                            Text(
                                "Next Steps (Please follow carefully)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Card(
                                shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.padding(18.dp)) {
                                    Text("1. Open Snapchat and log in.", fontWeight = FontWeight.Medium)
                                    Text("2. If you see 'temporarily disabled', go to Snapchat App Info, Force Stop, and retry login.", fontWeight = FontWeight.Medium)
                                    Text("3. After successful login, come back here and press 'Login Done'.", fontWeight = FontWeight.Medium)
                                }
                            }
                            if (specialNotice.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    specialNotice,
                                    color = Color(0xFFFFEE58),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(Modifier.height(26.dp))
                            Button(
                                onClick = { logsExpanded = !logsExpanded },
                                shape = RoundedCornerShape(21.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
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
                                        .padding(vertical = 12.dp)
                                ) {
                                    Surface(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
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
                                            logLines.forEach { line ->
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
                        Phase.Finished -> {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(62.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Done! Snapchat is patched!",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Open SnapEnhance and let it regenerate mappings, then open Snapchat and enjoy!",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 10.dp),
                                textAlign = TextAlign.Center
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
                            Text(
                                "An error occurred",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Please review the log below for details.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
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
                    }
                    if (showLoginConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { },
                            title = {
                                Text(
                                    "Login Successful?",
                                    textAlign = TextAlign.Center
                                )
                            },
                            text = {
                                Text(
                                    "Have you successfully logged into Snapchat?",
                                    textAlign = TextAlign.Center
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showLoginConfirmDialog = false
                                    logInfo("User confirmed successful login")
                                }) { Text("Yes, I'm logged in") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showLoginConfirmDialog = false
                                    showLoginFailedDialog = true
                                    logInfo("User indicated login not complete")
                                }) { Text("No") }
                            }
                        )
                    }
                    if (showLoginFailedDialog) {
                        AlertDialog(
                            onDismissRequest = { showLoginFailedDialog = false },
                            title = {
                                Text(
                                    "Please complete login",
                                    textAlign = TextAlign.Center
                                )
                            },
                            text = {
                                Text(
                                    "Please complete the login process in Snapchat before continuing. If you see 'temporarily disabled', force stop Snapchat from App Info and try again.",
                                    textAlign = TextAlign.Center
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showLoginFailedDialog = false
                                    showLoginConfirmDialog = true
                                }) { Text("OK, I'll try again") }
                            }
                        )
                    }
                }
            }
            BackHandler(enabled = (
                phase == Phase.Downloading12 ||
                phase == Phase.Installing12 ||
                phase == Phase.Downloading13 ||
                phase == Phase.Installing13 ||
                phase == Phase.DownloadingSnapEnhance ||
                phase == Phase.InstallingSnapEnhance
            )) { }
        }
    }
}
