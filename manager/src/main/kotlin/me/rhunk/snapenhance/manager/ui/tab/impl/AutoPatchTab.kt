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
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
        Idle,
        Patching12, SearchingV12, Uploading12, AwaitingLogin, TestModeDialog, Disclaimer,
        Patching13, SearchingV13, Uploading13, Finished, Error
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
        val scrollState = rememberScrollState()
        var phase by remember { mutableStateOf(Phase.Idle) }
        var status by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(-1f) }
        var showTestModeDialog by remember { mutableStateOf(false) }
        var showTestModeNoMsg by remember { mutableStateOf(false) }

        // Restore persisted state on fresh launch
        LaunchedEffect(Unit) {
            val prefs = context.dataStore.data.first()
            phase = runCatching { Phase.valueOf(prefs[KEY_PHASE] ?: Phase.Idle.name) }.getOrElse { Phase.Idle }
            status = prefs[KEY_STATUS] ?: ""
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

        fun log(any: Any?) {
            val msg = when (any) {
                is Throwable -> any.message + "\n" + any.stackTraceToString()
                else -> any.toString()
            }
            status += msg + "\n"
            persistState(newStatus = status)
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
        fun patternsFor(assetLabel: String): List<Regex> = if (assetLabel == "armv8")
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
            apkMirror: APKMirror, targetVersion: String, maxPages: Int
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

        fun startPatchV12() {
            scope.launch {
                setPhase(Phase.Patching12)
                status = ""; progress = -1f
                persistState(newStatus = status)
                try {
                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    val abi = detectAbiChoice()
                    log("Detected ABI: ${abi.desiredLibDir}")
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

                    setPhase(Phase.SearchingV12)
                    log("Searching APKMirror for Snapchat 12.33.1.19 (This is an old APK, so resolving the download link may take 3~4 minutes)...")
                    val apkMirror = APKMirror()
                    val snapchatTargetVersion = "12.33.1.19"
                    val versionItem = findSnapchatVersionItem(apkMirror, snapchatTargetVersion, 100)
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

                    setPhase(Phase.Uploading12)
                    log("Uploading for patch (only core.apk and Snapchat APK)... This will take 3–4 minutes depending on your network and server load.")
                    warmUpServer("https://auto-patch-server.onrender.com/health")
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
                        val patchedFile = File(cacheDir, "PatchedSnapchat12.apk")
                        response.body?.byteStream()?.use { input -> patchedFile.outputStream().use { output -> input.copyTo(output) } }
                        log("Patched Snapchat 12.33 APK received. Installing...")
                        if (!installPackage(seApk, sharedConfig.snapEnhancePackageName)) throw RuntimeException("SnapEnhance install failed")
                        log("SnapEnhance installed.")
                        if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat (12.33) install failed")
                        log("Patched Snapchat 12.33 installed.")
                        log(
                            """
Open SnapEnhance and complete its setup. If any mapping error appears, just ignore it and continue!
After that, go to SnapEnhance settings and ENABLE Test Mode.
Now open Snapchat and login. If you see "temporarily disabled", go to Snapchat's App Info, Force Stop it, and try again.
After a successful login, go to SnapEnhance and TURN OFF Test Mode. Return here to continue.
                            """.trimIndent()
                        )
                        setPhase(Phase.AwaitingLogin)
                    }
                } catch (t: Throwable) {
                    log("ERROR: ${t.message}")
                    setPhase(Phase.Error)
                }
            }
        }

        fun startPatchV13() {
            scope.launch {
                setPhase(Phase.Patching13)
                status = ""; progress = -1f
                persistState(newStatus = status)
                try {
                    val cacheDir = activity?.externalCacheDir ?: activity?.cacheDir ?: File(context.cacheDir, "web-cache")
                    val abi = detectAbiChoice()
                    log("Downloading core.apk (latest) for 13.51...")
                    val assets = fetchSnapEnhanceAndCoreAssets(abi.assetLabel)
                        ?: throw RuntimeException("No SnapEnhance/core.apk pair found in prereleases for ABI: ${abi.assetLabel}")
                    val coreApk = downloadWithOkHttp(assets.coreUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download core.apk")
                    log("core.apk ready.")

                    setPhase(Phase.SearchingV13)
                    log("Searching APKMirror for Snapchat 13.51.0.56...")
                    val apkMirror = APKMirror()
                    val snapchatTargetVersion = "13.51.0.56"
                    val versionItem = findSnapchatVersionItem(apkMirror, snapchatTargetVersion, 100)
                        ?: throw RuntimeException("Snapchat version $snapchatTargetVersion not found on APKMirror.")
                    log("Found version: ${versionItem.title} (${versionItem.releaseDate})")
                    log("Resolving download link...")
                    val realSnapchatUrl = apkMirror.fetchDownloadLink(versionItem.downloadPage)
                        ?: throw RuntimeException("Could not resolve Snapchat APK download link from APKMirror")
                    log("Downloading Snapchat from resolved URL: $realSnapchatUrl")
                    progress = 0f
                    val snapchatApk = downloadWithDoh(realSnapchatUrl, cacheDir) { progress = it }
                        ?: throw RuntimeException("Failed to download Snapchat (13.51)")
                    log("Downloaded Snapchat 13.51 -> ${snapchatApk.absolutePath}")

                    setPhase(Phase.Uploading13)
                    log("Uploading core.apk and Snapchat 13.51 APK to patch server... This takes 3–4 minutes depending on your network and server load.")
                    warmUpServer("https://auto-patch-server.onrender.com/health")
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
                        val patchedFile = File(cacheDir, "PatchedSnapchat13.apk")
                        response.body?.byteStream()?.use { input -> patchedFile.outputStream().use { output -> input.copyTo(output) } }
                        log("Patched Snapchat 13.51 APK received. Installing...")
                        if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat 13.51 install failed")
                        log("Patched Snapchat 13.51 installed. All steps complete!\nOpen SnapEnhance and let it regenerate mappings, then open Snapchat and enjoy!")
                        setPhase(Phase.Finished)
                    }
                } catch (t: Throwable) {
                    log("ERROR: ${t.message}")
                    setPhase(Phase.Error)
                }
            }
        }

        // --- Modern, visually appealing UI ---
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 0.dp, bottom = 10.dp, start = 6.dp, end = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Modern header title
                Text(
                    text = "auto_patch",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 16.dp)
                )
                when (phase) {
                    Phase.Idle -> {
                        Card(
                            Modifier
                                .shadow(8.dp, RoundedCornerShape(18.dp))
                                .padding(12.dp)
                        ) {
                            Column(
                                Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Welcome to Auto Patch", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Time required: Approximately 10 minutes.\n\n" +
                                            "Please follow all steps exactly to avoid issues or bans.\n\n" +
                                            "You'll patch Snapchat 12.33, log in, then patch 13.51. All setup steps will be explained.",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.height(32.dp))
                                Button(onClick = { startPatchV12() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                                    Text("Start Auto Patch")
                                }
                            }
                        }
                    }
                    Phase.Patching12, Phase.Patching13, Phase.SearchingV12, Phase.SearchingV13, Phase.Uploading12, Phase.Uploading13 -> {
                        val processMsg =
                            when (phase) {
                                Phase.SearchingV12 -> "Searching for Snapchat 12.33 (This may take 3-4 minutes to get the old download link)…"
                                Phase.Uploading12, Phase.Uploading13 -> "Uploading APK(s) for patching. Please have patience: this step often takes 3–4 minutes based on your internet speed and server load."
                                Phase.SearchingV13 -> "Searching for Snapchat 13.51.0.56—please wait…"
                                Phase.Patching12, Phase.Patching13 -> "Patience, magic is happening…"
                                else -> ""
                            }
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = processMsg,
                                modifier = Modifier.padding(18.dp, 4.dp, 18.dp, 16.dp),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // Modern loader (one only)
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surface
                            )
                            Spacer(Modifier.height(10.dp))
                            if (phase == Phase.Uploading12 || phase == Phase.Uploading13) {
                                Text(
                                    "This upload/patching operation usually takes 3–4 minutes. Please make sure you remain on this screen and keep the app open.\n",
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp, end = 2.dp)
                                )
                            }
                        }
                    }
                    Phase.AwaitingLogin -> {
                        Icon(Icons.Filled.DoneAll, contentDescription = "", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Next Steps (Please follow carefully)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
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
                                Modifier.verticalScroll(scrollState).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "⚠️ Important: Test Mode Warning",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.error,
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
                                    color = MaterialTheme.colorScheme.onSurface,
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
                            painter = rememberVectorPainter(Icons.Filled.DoneAll),
                            tint = MaterialTheme.colorScheme.primary,
                            contentDescription = null,
                            modifier = Modifier.size(62.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Done! Snapchat is patched!", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "To finish: Open SnapEnhance first and let it regenerate mappings. When done, open Snapchat and enjoy!",
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
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
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
                    }
                    else -> {}
                }

                // --- Animated/log-flash border effect ---
                if (status.isNotBlank()) {
                    val infiniteTransition = rememberInfiniteTransition(label = "flashAnim")
                    val flashColor by infiniteTransition.animateColor(
                        initialValue = MaterialTheme.colorScheme.primaryContainer,
                        targetValue = MaterialTheme.colorScheme.primary,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1100, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "logBorderAnim"
                    )
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(24.dp))
                            .border(2.dp, flashColor, RoundedCornerShape(24.dp)),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            Modifier.padding(18.dp).verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                status.trim(),
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }
                }
            }
            // Dialogs, always at end/root of UI tree
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
        BackHandler(enabled = (
            phase == Phase.Patching12 ||
            phase == Phase.SearchingV12 ||
            phase == Phase.Uploading12 ||
            phase == Phase.Patching13 ||
            phase == Phase.SearchingV13 ||
            phase == Phase.Uploading13
        )) { }
    }
}
