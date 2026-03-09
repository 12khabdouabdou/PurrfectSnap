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
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != Constants.SNAPCHAT_PACKAGE_NAME) return
        // prevent loading in sub-processes
        if (param.processName.contains(":")) return
        XposedBridge.log(
            "Loading PurrfectSnap v${BuildConfig.VERSION_NAME}#${BuildConfig.GIT_HASH} (package: ${BuildConfig.APPLICATION_ID})"
        )
        Application::class.java.hook("attach", HookStage.BEFORE) { hookParam ->
            PurrfectSnap().init(hookParam.arg(0))
        }
    }
}
