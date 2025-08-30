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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

// DataStore imports
import androidx.datastore.preferences.core.Preferences
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
        Idle, Patching12, AwaitingLogin, TestModeDialog, Disclaimer, Patching13, Finished, Error
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
        fun patternsFor(assetLabel: String): List<Regex> =
            if (assetLabel == "armv8")
                listOf(
                    Regex("arm64[-_]?v8a", RegexOption.IGNORE_CASE),
                    Regex("\\barm64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\barmv8\\b", RegexOption.IGNORE_CASE),
                    Regex("\\baarch64\\b", RegexOption.IGNORE_CASE),
                    Regex("\\bv8a\\b", RegexOption.IGNORE_CASE)
                )
            else listOf(
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

                    val apkMirror = APKMirror()
                    val snapchatTargetVersion = "12.33.1.19"
                    log("Searching APKMirror for Snapchat $snapchatTargetVersion...")
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

                    log("Warming up patch server (free cold start may take ~30–60s)...")
                    warmUpServer("https://auto-patch-server.onrender.com/health")
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
                        val patchedFile = File(cacheDir, "PatchedSnapchat12.apk")
                        response.body?.byteStream()?.use { input -> patchedFile.outputStream().use { output -> input.copyTo(output) } }
                        log("Patched Snapchat APK received. Installing...")
                        if (!installPackage(seApk, sharedConfig.snapEnhancePackageName)) throw RuntimeException("SnapEnhance install failed")
                        log("SnapEnhance installed.")
                        if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat (12.33) install failed")
                        log("Patched Snapchat 12.33 installed.")
                        log("Please login to Snapchat 12.33, then return here and press Login Done below.")
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

                    val apkMirror = APKMirror()
                    val snapchatTargetVersion = "13.51.0.56"
                    log("Searching APKMirror for Snapchat $snapchatTargetVersion...")
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

                    log("Warming up patch server...")
                    warmUpServer("https://auto-patch-server.onrender.com/health")
                    log("Uploading for patch (core.apk and Snapchat 13.51 APK)...")
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
                        val patchedFile = File(cacheDir, "PatchedSnapchat13.apk")
                        response.body?.byteStream()?.use { input -> patchedFile.outputStream().use { output -> input.copyTo(output) } }
                        log("Patched Snapchat 13.51 APK received. Installing...")
                        if (!installPackage(patchedFile, sharedConfig.snapchatPackageName)) throw RuntimeException("Patched Snapchat 13.51 install failed")
                        log("Patched Snapchat 13.51 installed. All steps complete!")
                        setPhase(Phase.Finished)
                    }
                } catch (t: Throwable) {
                    log("ERROR: ${t.message}")
                    setPhase(Phase.Error)
                }
            }
        }


        // ---- MAIN UI PHASE FLOW ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (phase) {
                Phase.Idle -> {
                    Button(onClick = { startPatchV12() }, Modifier.fillMaxWidth()) {
                        Text("Start Auto Patch")
                    }
                }
                Phase.Patching12, Phase.Patching13 -> {
                    Text("Patching and installing, please wait…", Modifier.padding(8.dp))
                }
                Phase.AwaitingLogin -> {
                    Text("Login required", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Please open Snapchat, log in and verify your account.\nOnce done, return to this app and press Login Done below to continue.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { showTestModeDialog = true },
                        Modifier.fillMaxWidth()
                    ) {
                        Text("Login Done")
                    }
                }
                Phase.TestModeDialog -> {
                    // See dialog overlay below!
                }
                Phase.Disclaimer -> {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(12.dp)
                    ) {
                        Column(
                            Modifier
                                .verticalScroll(scrollState)
                                .padding(20.dp)
                        ) {
                            Text("Important: Test Mode Warning", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                """
Before you proceed, you *MUST* turn OFF "Test mode" under SnapEnhance settings!

**Why?**

- "Test mode" is only needed for the login step on Snapchat 12.33.
- With Test mode ON, all patches/modules are in a highly visible state for debugging.
- **On latest Snapchat versions, if Test mode is ON, your account WILL be flagged and likely banned.**
- Test mode disables several anti-detection protections.

**What does this mean for you?**
- You were asked to enable Test mode to get login working on 12.33 -- that version is safe because there are no new detections.
- On newer Snapchat, TEST MODE _MUST_ BE OFF before patching and logging in to avoid bans!
- If you are unsure: Open SnapEnhance, go to Settings, and make 100% certain Test mode is disabled.
- We cannot help you recover the account if you ignore this!

**Summary: Only use Test mode for the first login, and ALWAYS disable it before continuing to patch Snapchat 13.51 or using the SnapEnhance module on new versions.**

Scroll down and press confirm only if you have already disabled Test mode in SnapEnhance.
                                """.trimIndent(),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(40.dp))
                            Button(
                                onClick = { startPatchV13() },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Yes, I understand. I have turned off test mode, let's proceed")
                            }
                        }
                    }
                }
                Phase.Finished -> {
                    Text("✨ All done! You may now use the patched Snapchat.", color = MaterialTheme.colorScheme.primary)
                    Button(
                        onClick = {
                            setPhase(Phase.Idle)
                            status = ""
                            persistState(newStatus = "")
                        },
                        Modifier.fillMaxWidth()
                    ) {
                        Text("Back to Home")
                    }
                }
                Phase.Error -> {
                    Text("❌ An error occurred. Check the log for details.", color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = {
                            setPhase(Phase.Idle)
                            status = ""
                            persistState(newStatus = "")
                        },
                        Modifier.fillMaxWidth()
                    ) {
                        Text("Retry")
                    }
                }
            }
            if (status.isNotBlank()) {
                Card(Modifier.weight(1f).padding(8.dp)) {
                    Column(Modifier.verticalScroll(scrollState).padding(8.dp)) {
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
            // Dialog for test mode confirmation AFTER LOGIN
            if (showTestModeDialog) {
                AlertDialog(
                    onDismissRequest = { showTestModeDialog = false },
                    title = { Text("Have you turned OFF Test mode in SnapEnhance settings?") },
                    text = { Text("Test mode must be OFF before continuing or you may get banned!") },
                    confirmButton = {
                        TextButton(onClick = {
                            showTestModeDialog = false
                            setPhase(Phase.Disclaimer)
                        }) { Text("Yes") }
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
                    title = { Text("Turn off Test mode first!") },
                    text = { Text("Please open SnapEnhance settings and ensure Test mode is turned OFF before continuing.") },
                    confirmButton = {
                        TextButton(onClick = { showTestModeNoMsg = false }) { Text("OK") }
                    },
                    dismissButton = {}
                )
            }
            LaunchedEffect(status) { scrollState.scrollTo(scrollState.maxValue) }
        }
        BackHandler(enabled = (phase == Phase.Patching12 || phase == Phase.Patching13)) { }
    }
}
