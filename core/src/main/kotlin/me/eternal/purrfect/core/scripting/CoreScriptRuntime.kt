package me.eternal.purrfect.core.scripting

import me.eternal.purrfect.common.scripting.JSModule
import me.eternal.purrfect.bridge.scripting.AutoReloadListener
import me.eternal.purrfect.common.logger.AbstractLogger
import me.eternal.purrfect.common.scripting.ScriptRuntime
import me.eternal.purrfect.common.scripting.bindings.BindingSide
import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.scripting.impl.*

/**
 * Core-side implementation of the [ScriptRuntime].
 * Manages script lifecycle synchronized with the JNI bridge connection state
 * to prevent race conditions during early-init hooks.
 */
class CoreScriptRuntime(
    private val modContext: ModContext,
    logger: AbstractLogger,
): ScriptRuntime(
    config = { modContext.config },
    androidContext = modContext.androidContext,
    logger = logger
) {
    // Indicates if the bridge has been reloaded at least once in this session
    private var isBridgeReloaded = false

    /**
     * Bridge connection status. Use [isBridgeConnected] to guard JNI-dependent operations.
     */
    @Volatile
    private var isBridgeConnected = false

    /**
     * Initializes the scripting environment and establishes bridge-aware lifecycle observers.
     */
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
            runCatching {
                modContext.bridgeClient.getScriptingInterface()?.let { scriptingInterface ->
                    if (!modContext.bridgeClient.isServiceAlive()) return@addOnConnectedCallback
                    logger.info("JNI Bridge established. Initializing scripts...")
                    scripting = scriptingInterface
                    isBridgeConnected = true

                    if (!isBridgeReloaded) {
                        val enabled = runCatching { scriptingInterface.enabledScripts }.getOrElse {
                            logger.error("Failed to fetch enabled scripts from bridge", it)
                            emptyList()
                        }
                        enabled.forEach { path ->
                            runCatching {
                                logger.verbose("Loading script: $path")
                                val content = scriptingInterface.getScriptContent(path)
                                load(path, content)
                            }.onFailure {
                                logger.error("Failed to load script $path", it)
                            }
                        }
                    }

                    runCatching {
                        scriptingInterface.registerAutoReloadListener(object : AutoReloadListener.Stub() {
                            override fun restartApp() {
                                logger.info("Script change detected. Soft-restarting app...")
                                modContext.softRestartApp()
                            }
                        })
                    }.onFailure {
                        logger.error("Failed to register auto-reload listener", it)
                    }

                    eachModule {
                        onBridgeConnected(reloaded = isBridgeReloaded)
                    }

                    if (!isBridgeReloaded) {
                        isBridgeReloaded = true
                    }
                } ?: run {
                    isBridgeConnected = false
                    logger.error("JNI Bridge callback triggered but interface is null.")
                }
            }.onFailure { throwable ->
                isBridgeConnected = false
                logger.error("Failed during script runtime bridge initialization", throwable)
            }
        }
    }

    /**
     * Safely iterates over loaded modules only when the JNI bridge is confirmed connected.
     */
    override fun eachModule(f: JSModule.() -> Unit) {
        if (!isBridgeConnected) return
        super.eachModule(f)
    }
}
