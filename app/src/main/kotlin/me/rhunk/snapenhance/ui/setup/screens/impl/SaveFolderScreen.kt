package me.rhunk.snapenhance.ui.setup.screens.impl

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rhunk.snapenhance.ui.setup.screens.SetupScreen
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import me.rhunk.snapenhance.ui.util.chooseFolder
import androidx.compose.foundation.interaction.MutableInteractionSource
import me.rhunk.snapenhance.ui.util.scaleOnPress

class SaveFolderScreen : SetupScreen() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper

    override fun init() {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            allowNext(false)
        }
        DialogText(text = context.translation["setup.dialogs.save_folder"])
        Spacer(modifier = Modifier.height(16.dp))
        val src = remember { MutableInteractionSource() }
        Button(onClick = {
            activityLauncherHelper.chooseFolder {
                if (it.isBlank()) {
                    allowNext(false)
                    return@chooseFolder
                }
                context.config.root.downloader.saveFolder.set(it)
                context.config.writeConfig()
                allowNext(true)
            }
        }, interactionSource = src, modifier = Modifier.scaleOnPress(src)) {
            Text(text = context.translation["setup.dialogs.select_save_folder_button"])
        }
    }
}
