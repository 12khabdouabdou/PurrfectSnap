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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rhunk.snapenhance.ui.setup.screens.SetupScreen

class RemoteBypassConsentScreen : SetupScreen() {
    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            allowNext(false)
        }
        DialogText(text = "PurrfectSnap offers a closed-source security bypass for enhanced functionality. By clicking 'Agree', you consent to downloading and using this feature.")
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = {
                context.config.root.experimental.remoteBypassConsent.set(false)
                context.config.writeConfig()
                allowNext(true)
                goNext()
            }) {
                Text("Disagree")
            }
            Button(onClick = {
                context.config.root.experimental.remoteBypassConsent.set(true)
                context.config.root.experimental.useRemoteBypass.set(true)
                context.config.writeConfig()
                allowNext(true)
                goNext()
            }) {
                Text("Agree")
            }
        }
    }
}
