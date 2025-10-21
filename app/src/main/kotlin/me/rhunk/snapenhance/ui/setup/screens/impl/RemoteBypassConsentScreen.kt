package me.rhunk.snapenhance.ui.setup.screens.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rhunk.snapenhance.download.BypassDownloader
import me.rhunk.snapenhance.ui.setup.screens.SetupScreen
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding

class RemoteBypassConsentScreen : SetupScreen() {
    @Composable
    override fun Content() {
        val downloadState by BypassDownloader.downloadState.collectAsState()
        val downloadProgress by BypassDownloader.downloadProgress.collectAsState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            allowNext(false)
        }

        when (downloadState) {
            BypassDownloader.DownloadState.IDLE -> {
                DialogText(text = "PurrfectSnap offers a closed-source security bypass for enhanced functionality. By clicking 'Agree', you consent to downloading and using this feature.")
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = {
                        context.config.root.experimental.useRemoteBypass.set(false)
                        context.config.writeConfig()
                        allowNext(true)
                        goNext()
                    }) {
                        Text("Disagree")
                    }
                    Button(onClick = {
                        context.config.root.experimental.useRemoteBypass.set(true)
                        context.config.writeConfig()
                        BypassDownloader.download(context.androidContext, scope)
                    }) {
                        Text("Agree")
                    }
                }
            }
            BypassDownloader.DownloadState.DOWNLOADING -> {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                    DialogText(text = "Downloading bypass...")
                    LinearProgressIndicator(progress = { downloadProgress })
                }
            }
            BypassDownloader.DownloadState.DECRYPTING -> {
                DialogText(text = "Decrypting bypass...")
            }
            BypassDownloader.DownloadState.COMPLETED -> {
                DialogText(text = "Download complete!")
                LaunchedEffect(Unit) {
                    allowNext(true)
                    goNext()
                }
            }
            BypassDownloader.DownloadState.FAILED -> {
                val errorMessage by BypassDownloader.errorMessage.collectAsState()
                DialogText(text = "Download failed: ${errorMessage ?: "Unknown error"}")
            }
        }
    }
}
