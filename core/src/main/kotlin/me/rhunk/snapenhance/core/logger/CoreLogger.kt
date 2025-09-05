package me.rhunk.snapenhance.core.logger

import android.annotation.SuppressLint
import android.util.Log
import de.robv.android.xposed.XposedBridge
import me.rhunk.snapenhance.common.logger.AbstractLogger
import me.rhunk.snapenhance.common.logger.LogChannel
import me.rhunk.snapenhance.common.logger.LogLevel
import me.rhunk.snapenhance.core.bridge.BridgeClient
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook

@SuppressLint("PrivateApi")
class CoreLogger(
    private val bridgeClient: BridgeClient
) : AbstractLogger(LogChannel.CORE) {

    companion object {
        private const val TAG = "SnapEnhanceCore"

        fun xposedLog(message: Any?, tag: String = TAG) {
            val text = message?.toString() ?: "null"
            Log.println(Log.INFO, tag, text)
            XposedBridge.log("$tag: $text")
        }

        fun xposedLog(message: Any?, throwable: Throwable, tag: String = TAG) {
            val text = message?.toString() ?: "null"
            Log.println(Log.INFO, tag, text)
            XposedBridge.log("$tag: $text")
            XposedBridge.log(throwable)
        }
    }

    private var invokeOriginalPrintLog: (Int, String, String) -> Unit

    init {
        val printLnMethod = Log::class.java.getDeclaredMethod(
            "println",
            Int::class.java,
            String::class.java,
            String::class.java
        )

        // Use explicit type arguments to avoid reified intersection inference warnings
        printLnMethod.hook(HookStage.BEFORE) { param ->
            val priority = param.args()[0] as Int
            val tag = param.args()[1] as String
            val message = param.args()[2] as String
            internalLog(tag, LogLevel.fromPriority(priority) ?: LogLevel.INFO, message)
        }

        invokeOriginalPrintLog = { priority, tag, message ->
            XposedBridge.invokeOriginalMethod(
                printLnMethod,
                null,
                arrayOf(priority, tag, message)
            )
        }
    }

    // Normalize to String early to avoid ambiguous T inference at call sites
    private fun internalLog(tag: String, logLevel: LogLevel, message: Any?) {
        val text = message?.toString() ?: "null"
        runCatching {
            bridgeClient.broadcastLog(tag, logLevel.shortName, text)
        }.onFailure {
            invokeOriginalPrintLog(logLevel.priority, tag, text)
        }
    }

    override fun debug(message: Any?, tag: String) = internalLog(tag, LogLevel.DEBUG, message)
    override fun error(message: Any?, tag: String) = internalLog(tag, LogLevel.ERROR, message)
    override fun error(message: Any?, throwable: Throwable, tag: String) {
        internalLog(tag, LogLevel.ERROR, message)
        internalLog(tag, LogLevel.ERROR, throwable.stackTraceToString())
    }
    override fun info(message: Any?, tag: String) = internalLog(tag, LogLevel.INFO, message)
    override fun verbose(message: Any?, tag: String) = internalLog(tag, LogLevel.VERBOSE, message)
    override fun warn(message: Any?, tag: String) = internalLog(tag, LogLevel.WARN, message)
    override fun assert(message: Any?, tag: String) = internalLog(tag, LogLevel.ASSERT, message)
}
