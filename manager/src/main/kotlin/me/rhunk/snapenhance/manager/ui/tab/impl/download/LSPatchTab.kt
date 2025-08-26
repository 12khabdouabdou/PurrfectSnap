package me.rhunk.snapenhance.manager.ui.tab.impl.download

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.*
import me.rhunk.snapenhance.manager.data.APKMirror
import me.rhunk.snapenhance.manager.data.DownloadItem
import me.rhunk.snapenhance.manager.patch.LSPatch
import me.rhunk.snapenhance.manager.ui.components.DowngradeNoticeDialog
import me.rhunk.snapenhance.manager.ui.tab.Tab
import okio.use
import java.io.File
import kotlin.properties.Delegates

class LSPatchTab : Tab("lspatch") {
    private val apkMirror = APKMirror()
    private fun patch(
        log: (Any?) -> Unit,
        onProgress: (Float) -> Unit,
        downloadItem: DownloadItem? = null,
        snapEnhanceModule: File? = null,
        localItemFile: File? = null,
        patchedApk: MutableState<File?>
    ) {
        var apkFile: File? = localItemFile
        // Download if item provided
        downloadItem?.let {
            log("Fetching download link for ${it.title}...")
            val downloadLink = try {
                apkMirror.fetchDownloadLink(it.downloadPage)
            } catch (e: Exception) {
                log("Failed to fetch download link: ${e.message}")
                return
            }
            if (downloadLink.isNullOrEmpty()) {
                log("== Failed to fetch download link ==")
                return
            }
            log("Downloading apk...")
            val downloadResponse = apkMirror.okhttpClient.newCall(
                okhttp3.Request.Builder()
                    .url(downloadLink)
                    .build()
            ).execute()
            if (!downloadResponse.isSuccessful) {
                log("== Failed to download apk ==")
                log("Response code: ${downloadResponse.code}")
                return
            }
            apkFile = sharedConfig.apkCache.resolve("${it.hash}.apk")
            runCatching {
                apkFile!!.outputStream().use { outputStream ->
                    downloadResponse.body?.byteStream()?.use { inputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read: Int
                        var totalRead = 0L
                        val totalSize = downloadResponse.body?.contentLength() ?: -1L
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                            totalRead += read
                            if (totalSize > 0)
                                onProgress(totalRead.toFloat() / totalSize.toFloat())
                        }
                    }
                }
            }.onFailure { throwable ->
                log("== Failed to download apk ==")
                log(throwable)
                return
            }
            // Defensive null/exists check
            if (apkFile == null || !apkFile.exists()) {
                log("Downloaded file is missing/null! Aborting patch step.")
                patchedApk.value = null
                return
            }
            // Robust: use externalCacheDir or internal cacheDir, always use copyTo for cross-filesystem compatibility.
            val targetDir = activity.externalCacheDir ?: activity.cacheDir
            if (targetDir == null) {
                log("Both externalCacheDir and cacheDir are null, cannot save base.apk!")
                patchedApk.value = null
                return
            }
            val baseApkFile = File(targetDir, "base.apk")
            try {
                apkFile!!.copyTo(baseApkFile, overwrite = true)
                log("APK copied to ${baseApkFile.absolutePath}")
            } catch (e: Exception) {
                log("Failed to copy APK to ${baseApkFile.absolutePath}: ${e.message}")
                patchedApk.value = null
                return
            }
            apkFile = baseApkFile
        }
        log("== Downloaded apk ==")
        // Only patch if apkFile is not null and exists!
        if (apkFile == null || !apkFile.exists()) {
            log("Downloaded APK file is null or missing. Aborting patch.")
            patchedApk.value = null
            return
        }
        snapEnhanceModule?.let { module ->
            val lsPatch = LSPatch(
                activity,
                mapOf(sharedConfig.snapEnhancePackageName to module),
                printLog = { log("[LSPatch] $it") },
                obfuscate = sharedConfig.obfuscateLSPatch
            )
            log("== Patching apk ==")
            val outputFiles = lsPatch.patchSplits(listOf(apkFile!!))
            patchedApk.value = outputFiles["base.apk"] ?: run {
                log("== Failed to patch apk ==")
                return
            }
            return
        }
        patchedApk.value = apkFile
    }

    @Suppress("DEPRECATION")
    override fun build(navGraphBuilder: NavGraphBuilder) {
        var currentJob: Job? = null
        val coroutineScope = CoroutineScope(Dispatchers.IO)
        val patchedApk = mutableStateOf<File?>(null)
        val status = mutableStateOf("")
        var progress by mutableFloatStateOf(-1f)
        var isRunning by Delegates.observable(false) { _, _, newValue ->
            if (!newValue) {
                currentJob?.cancel()
                currentJob = null
                progress = -1f
            }
        }
        navGraphBuilder.composable(route) {
            var showDowngradeNoticeDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (isRunning) return@LaunchedEffect
                status.value = ""
                currentJob = coroutineScope.launch(Dispatchers.IO) {
                    isRunning = true
                    runCatching {
                        patch(
                            localItemFile = getArguments()?.getString("localItemFile")?.let { File(it) },
                            log = {
                                coroutineScope.launch {
                                    status.value += when (it) {
                                        is Throwable -> it.message + "\n" + it.stackTraceToString()
                                        else -> it.toString()
                                    } + "\n"
                                }
                            },
                            downloadItem = getArguments()?.getParcelable("downloadItem"),
                            snapEnhanceModule = getArguments()?.getString("modulePath")?.let { File(it) },
                            patchedApk = patchedApk,
                            onProgress = { progress = it }
                        )
                    }.onFailure {
                        coroutineScope.launch {
                            status.value += it.message + "\n" + it.stackTraceToString()
                        }
                    }
                    isRunning = false
                }
            }
            DisposableEffect(Unit) {
                onDispose {
                    // Correction—NO always false condition!
                    patchedApk.value = null
                }
            }
            val scrollState = rememberScrollState()
            fun triggerInstallation(shouldUninstall: Boolean) {
                navigation.navigateTo(InstallPackageTab::class, args = Bundle().apply {
                    putString("downloadPath", patchedApk.value?.absolutePath)
                    putString("appPackage", sharedConfig.snapchatPackageName)
                    putBoolean("uninstall", shouldUninstall)
                })
            }
            BackHandler(isRunning) {}
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
                    ) {
                        Text(
                            text = status.value,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                if (progress != -1f) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.height(10.dp), strokeCap = StrokeCap.Round)
                }
                if (patchedApk.value != null) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { triggerInstallation(true) }) {
                        Text(text = "Uninstall & Install")
                    }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { showDowngradeNoticeDialog = true }) {
                        Text(text = "Update")
                    }
                }
                LaunchedEffect(status) { scrollState.scrollTo(scrollState.maxValue) }
            }
            if (showDowngradeNoticeDialog) {
                Dialog(onDismissRequest = { showDowngradeNoticeDialog = false }) {
                    DowngradeNoticeDialog(onDismiss = { showDowngradeNoticeDialog = false }, onSuccess = {
                        triggerInstallation(false)
                    })
                }
            }
        }
    }
}
