package me.eternal.purrfectsnap.core

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import me.eternal.purrfectsnap.common.BuildConfig
import me.eternal.purrfectsnap.common.Constants
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class XposedLoader : IXposedHookLoadPackage {
    override fun handleLoadPackage(p0: XC_LoadPackage.LoadPackageParam) {
        if (p0.packageName != Constants.SNAPCHAT_PACKAGE_NAME) return
        // prevent loading in sub-processes
        if (p0.processName.contains(":")) return
        XposedBridge.log("Loading PurrfectSnap v${BuildConfig.VERSION_NAME}#${BuildConfig.GIT_HASH} (package: ${BuildConfig.APPLICATION_ID})")
        Application::class.java.hook("attach", HookStage.BEFORE) { param ->
            PurrfectSnap().init(param.arg(0))
        }
    }
}