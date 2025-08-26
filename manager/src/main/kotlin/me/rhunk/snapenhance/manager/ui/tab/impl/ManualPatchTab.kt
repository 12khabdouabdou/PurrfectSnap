package me.rhunk.snapenhance.manager.ui.tab.impl

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import me.rhunk.snapenhance.manager.ui.tab.Tab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.SEDownloadTab
import me.rhunk.snapenhance.manager.ui.tab.impl.download.SnapchatPatchTab

class ManualPatchTab : Tab("manualpatch") {
    override fun init(activity: ComponentActivity) {
        super.init(activity)
        // Register tabs if you have nested navigation (optional, not strictly necessary for Compose only)
        // registerNestedTab(SEDownloadTab::class)
        // registerNestedTab(SnapchatPatchTab::class)
    }

    @Composable
    override fun Content() {
        Column {
            SEDownloadTab().Content()
            SnapchatPatchTab().Content()
        }
    }
}
