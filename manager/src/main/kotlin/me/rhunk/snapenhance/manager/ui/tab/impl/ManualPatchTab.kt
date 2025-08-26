package me.rhunk.snapenhance.manager.ui.tab.impl

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import me.rhunk.snapenhance.manager.ui.tab.Tab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.SEDownloadTab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.SnapchatPatchTab

class ManualPatchTab : Tab("manualpatch") {
    override fun init(activity: ComponentActivity) {
        super.init(activity)
        registerNestedTab(SEDownloadTab::class)
        registerNestedTab(SnapchatPatchTab::class)
    }

    @Composable
    override fun Content() {
        // Show both SnapEnhance and Snapchat patch UI in one screen
        Column {
            navigation.getNestedTab(SEDownloadTab::class)?.Content()
            navigation.getNestedTab(SnapchatPatchTab::class)?.Content()
        }
    }
}
