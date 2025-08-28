package me.rhunk.snapenhance.manager.ui.tab.impl

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.manager.patch.LSPatch
import me.rhunk.snapenhance.manager.ui.tab.Tab
import me.rhunk.snapenhance.manager.data.APKMirror
import me.rhunk.snapenhance.manager.data.DNSBlockedException
import java.io.File

class AutoPatchTab : Tab("autopatch", icon = Icons.Default.Build) {
    @Composable
    override fun Content() {
        val coroutineScope = rememberCoroutineScope()
        var status by remember { mutableStateOf<String?>(null) }
        var showDialog by remember { mutableStateOf(false) }
        var inProgress by remember { mutableStateOf(false) }

        // For navigation to installer after patch
        var patchedApkPath by remember { mutableStateOf<String?>(null) }

        Column(Modifier.padding(32.dp)) {
            Text("Auto Patch: Snapchat v12.33.1.19", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showDialog = true },
                enabled = !inProgress
            ) { Text("Start Auto Patch") }

            status?.let {
                Spacer(Modifier.height(16.dp))
                Text(it)
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Auto Patch") },
                text = { Text("Are you sure to continue?") },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            status = "Running DNS logic (DoH)..."
                            inProgress = true
                            try {
                                // -- STEP 1: Download APK with DNS/DoH logic from APKMirror.kt --
                                val url = "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=4764674&key=bd0c88c47174308d9c6862f815bc96246d5077a8&forcebaseapk=true"
                                val apkMirror = APKMirror()
                                val targetFile = File(sharedConfig.apkCache, "snapchat_12.33.1.19_autopatch.apk")
                                apkMirror.okhttpClient.newCall(
                                    okhttp3.Request.Builder()
                                        .url(url)
                                        .build()
                                ).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        status = "Failed to download APK. (${response.code})"
                                        inProgress = false
                                        return@launch
                                    }
                                    val sink = targetFile.outputStream()
                                    response.body?.byteStream()?.copyTo(sink)
                                    sink.close()
                                }
                                status = "Downloaded APK."

                                // -- STEP 2: Patch it blankly (local mode, NO modules) --
                                status = "Patching APK..."
                                val outputFile = File(sharedConfig.apkCache, "snapchat_12.33.1.19_autopatch_PATCHED.apk")
                                val lsPatch = LSPatch(
                                    activity,
                                    modules = mapOf(),
                                    obfuscate = false,
                                    printLog = { log -> status = log?.toString() ?: "" }
                                )
                                val result = lsPatch.patchSplits(listOf(targetFile))
                                val patched = result["base.apk"]
                                if (patched == null || !patched.exists()) {
                                    status = "Failed to patch APK!"
                                    inProgress = false
                                    return@launch
                                }
                                outputFile.writeBytes(patched.readBytes())
                                status = "Patch complete! Ready to install..."
                                patchedApkPath = outputFile.absolutePath
                            } catch (dns: DNSBlockedException) {
                                status = "DNS Blocked! Please check your connection or VPN."
                                inProgress = false
                                return@launch
                            }
                            catch (e: Exception) {
                                status = "Error: ${e.localizedMessage}"
                                inProgress = false
                                return@launch
                            }
                            inProgress = false
                        }
                    }) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) { Text("No") }
                }
            )
        }

        // Launch installer tab if ready
        if (patchedApkPath != null) {
            // Directly navigate to your existing InstallPackageTab
            LaunchedEffect(patchedApkPath) {
                navigation.navigateTo(
                    me.rhunk.snapenhance.manager.ui.tab.impl.download.InstallPackageTab::class,
                    args = Bundle().apply {
                        putString("downloadPath", patchedApkPath)
                        putString("appPackage", sharedConfig.snapchatPackageName)
                        putBoolean("uninstall", true)
                    }
                )
                // Reset state (optionally)
                patchedApkPath = null
            }
        }
    }
}
