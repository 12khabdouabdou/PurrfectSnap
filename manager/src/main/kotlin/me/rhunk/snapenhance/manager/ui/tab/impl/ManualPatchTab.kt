package me.rhunk.snapenhance.manager.ui.tab.impl

import android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.Constants as CommonConstants
import me.rhunk.snapenhance.manager.patch.config.Constants as PatchConstants
import me.rhunk.snapenhance.manager.ui.tab.Tab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.SEDownloadTab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.SnapchatPatchTab

// Provide icon = Icons.Default.Build (or any other valid icon)
class ManualPatchTab : Tab("manualpatch", true, icon = Icons.Default.Build) {
    override fun init(activity: ComponentActivity) {
        super.init(activity)
        // No registerNestedTab! These are not nested tabs anymore
    }
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var snapchatAppInfo by remember { mutableStateOf(null as PackageInfo?) }
        var snapEnhanceInfo by remember { mutableStateOf(null as PackageInfo?) }
        Column {
            // SnapEnhance Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .clickable {
                        navigation.navigateTo(SEDownloadTab::class, noHistory = true)
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "SnapEnhance", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        snapEnhanceInfo?.let {
                            Text(text = "${it.versionName} (${it.longVersionCode}) - ${if ((it.applicationInfo.flags and FLAG_DEBUGGABLE) != 0) "Debug" else "Release"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(it.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        snapEnhanceInfo?.let {
                            Text(text = "Installed", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        } ?: run {
                            Text(text = "Not installed", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.padding(10.dp))
                    }
                }
            }
            // Snapchat Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .clickable {
                        navigation.navigateTo(SnapchatPatchTab::class, noHistory = true)
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Snapchat", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        snapchatAppInfo?.let {
                            Text(text = "${it.versionName} (${it.longVersionCode})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        snapchatAppInfo?.let { appInfo ->
                            val isLSPatched = appInfo.applicationInfo.appComponentFactory == PatchConstants.PROXY_APP_COMPONENT_FACTORY
                            if (isLSPatched) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                                Text(text = "Patched", fontSize = 16.sp)
                            } else {
                                Icon(imageVector = Icons.Default.Close, contentDescription = null)
                                Text(text = "Not patched", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        } ?: run {
                            Text(text = "Not installed", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        SideEffect {
            coroutineScope.launch(Dispatchers.IO) {
                snapchatAppInfo = runCatching {
                    context.packageManager.getPackageInfo(CommonConstants.SNAPCHAT_PACKAGE_NAME, 0)
                }.getOrNull()
                snapEnhanceInfo = runCatching {
                    context.packageManager.getPackageInfo(CommonConstants.SE_PACKAGE_NAME, 0)
                }.getOrNull()
            }
        }
    }
}
