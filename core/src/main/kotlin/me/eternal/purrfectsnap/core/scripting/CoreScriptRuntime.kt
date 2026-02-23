package me.eternal.purrfectsnap.core.scripting

import me.eternal.purrfectsnap.bridge.scripting.AutoReloadListener
import me.eternal.purrfectsnap.common.logger.AbstractLogger
import me.eternal.purrfectsnap.common.scripting.ScriptRuntime
import me.eternal.purrfectsnap.common.scripting.bindings.BindingSide
import me.eternal.purrfectsnap.core.ModContext
import me.eternal.purrfectsnap.core.scripting.impl.*

class CoreScriptRuntime(
    private val modContext: ModContext,
    logger: AbstractLogger,
): ScriptRuntime(
    config = { modContext.config },
    androidContext = modContext.androidContext,
    logger = logger
) {
    // we assume that the bridge is reloaded the next time we connect to it
    private var isBridgeReloaded = false

    fun init() {
        buildModuleObject = { module ->
            putConst("currentSide", this, BindingSide.CORE.key)
            module.registerBindings(
                CoreScriptConfig(),
                CoreIPC(),
                CoreScriptHooker(),
                CoreMessaging(modContext),
                CoreEvents(modContext),
            )
        }

        modContext.bridgeClient.addOnConnectedCallback(initNow = true) {
            modContext.bridgeClient.getScriptingInterface()?.let { scriptingInterface ->
                scripting = scriptingInterface

                if (!isBridgeReloaded) {
                    scriptingInterface.enabledScripts.forEach { path ->
                        runCatching {
                            load(path, scriptingInterface.getScriptContent(path))
                        }.onFailure {
                            logger.error("Failed to load script $path", it)
                        }
                    }
                }

                scriptingInterface.registerAutoReloadListener(object : AutoReloadListener.Stub() {
                    override fun restartApp() {
                        modContext.softRestartApp()
                    }
                })

                eachModule {
                    onBridgeConnected(reloaded = isBridgeReloaded)
                }

                if (!isBridgeReloaded) {
                    isBridgeReloaded = true
                }
            }
        }
    }
}
