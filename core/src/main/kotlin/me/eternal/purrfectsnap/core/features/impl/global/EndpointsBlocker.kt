package me.eternal.purrfectsnap.core.features.impl.global

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfectsnap.core.ui.CustomComposable
import me.eternal.purrfectsnap.core.util.dataBuilder
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.mapper.impl.CallbackMapper
import me.eternal.purrfectsnap.mapper.impl.PlatformClientAttestationMapper
import java.io.IOException
import java.lang.reflect.Method

class EndpointsBlocker : Feature("EndpointsBlocker") {
    @Volatile
    private var isInLoginSignup = false

    private fun showBypassStatusIndicator(isWorking: Boolean) {
        if (context.bridgeClient.getDebugProp("disable_bypass_indicator", "false") == "true") {
            return
        }

        lateinit var composable: CustomComposable
        composable = {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        color = Color.Black.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isWorking) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (isWorking) Color.Green else Color.Red,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PurrAura",
                        color = Color.White,
                        fontSize = 14.sp
                    )
            }

            LaunchedEffect(Unit) {
                delay(3000)
                context.inAppOverlay.removeCustomComposable(composable)
            }
        }

        context.inAppOverlay.addCustomComposable(composable)
    }

    override fun init() {
        val bypassToggleEnabled = context.bridgeClient.getDebugProp("test_mode", "false") == "true"
        
        if (context.disablePlugin && !bypassToggleEnabled) {
            context.log.verbose("EndpointsBlocker: Plugin disabled and test_mode not enabled, skipping initialization")
            return
        }

        onNextActivityCreate { activity ->
            if (activity.javaClass.name.endsWith("LoginSignupActivity")) {
                isInLoginSignup = true
                runCatching { context.native.setInLoginSignup(true) }

                onNextActivityCreate {
                    isInLoginSignup = false
                    runCatching { context.native.setInLoginSignup(false) }
                }
            }
        }
        runCatching { context.native.setInLoginSignup(false) }

        if (bypassToggleEnabled) {
            context.native.setTestMode(true)
            val healthy = context.native.isEndpointBlockerHealthy(testMode = true)
            if (!healthy) {
                context.log.warn("EndpointsBlocker: native self-test failed, indicator set to inactive")
                context.log.warn("EndpointsBlocker: Possible causes - config not loaded or self-test logic failed")
            }
            showBypassStatusIndicator(healthy)
        } else {
            context.native.setTestMode(false)
        }

        context.event.subscribe(UnaryCallEvent::class) { event ->
            val bypassToggleEnabled = context.bridgeClient.getDebugProp("test_mode", "false") == "true"
            if (!bypassToggleEnabled && context.disablePlugin) {
                return@subscribe
            }
            if (isInLoginSignup) return@subscribe
            
            val callOptions = event.adapter.arg<Any>(2).let { it.javaClass.getMethod("build").invoke(it) } ?: return@subscribe
            val hasAttestation = callOptions.getObjectField("mAttestation") != null
            val arg0 = event.adapter.arg<Any>(0).toString()

            val decision = context.native.evaluateEndpoint(event.uri, arg0, hasAttestation)

            if (decision.blocked) {
                event.canceled = true
                val eventHandler = event.adapter.arg<Any>(3)
                eventHandler.javaClass.methods.first { it.name == "onEvent" }.also { method ->
                    method.invoke(eventHandler, null, method.parameterTypes[0].dataBuilder {
                        set("mStatusCode", "CANCELLED")
                    })
                }
            }
        }

        context.androidContext.classLoader.apply {
            loadClass("com.snapchat.client.duplex.DuplexClient\$CppProxy").hook("registerHandler",
                HookStage.BEFORE) { param ->
                val bypassToggleEnabled = context.bridgeClient.getDebugProp("test_mode", "false") == "true"
                if (!bypassToggleEnabled && context.disablePlugin) {
                    return@hook
                }
                if (isInLoginSignup) return@hook
                
                val path = param.arg<String>(0)
                if (context.native.shouldBlockDuplexClient(path)) {
                    param.setResult(null)
                    return@hook
                }
            }
        }

        context.mappings.useMapper(CallbackMapper::class) {
            callbacks.getClass("AuthContextDelegate")?.hook("getAuthContext", HookStage.BEFORE) { param ->
                val bypassToggleEnabled = context.bridgeClient.getDebugProp("test_mode", "false") == "true"
                if (!bypassToggleEnabled && context.disablePlugin) {
                    return@hook
                }
                if (isInLoginSignup) return@hook
                
                val authContextRequest = param.arg<Any>(0)
                val requestPath = authContextRequest.getObjectField("mRequestPath").toString()
                val attestationRequired = authContextRequest.getObjectField("mAttestationRequired") == true
                val decision = context.native.evaluateAuthContext(requestPath, attestationRequired)

                if (decision.blocked) {
                    param.setResult(null)
                }
            } ?: error("AuthContextDelegate not found in mappings")
        }

        context.mappings.useMapper(PlatformClientAttestationMapper::class) {
            apiInvocationHandler.getAsClass()?.hook("invoke", HookStage.BEFORE) { param ->
                val bypassToggleEnabled = context.bridgeClient.getDebugProp("test_mode", "false") == "true"
                if (!bypassToggleEnabled && context.disablePlugin) {
                    return@hook
                }
                if (isInLoginSignup) return@hook
                
                val method = param.arg<Method>(1)
                val methodId = "${method.declaringClass.name}.${method.name}"
                val annotationBlob = method.annotations.joinToString("\n") { it.toString() }
                val decision = context.native.evaluateApiInvocation(methodId, annotationBlob)

                if (decision.blocked) {
                    if (method.returnType.name.endsWith("Single")) {
                        val errorSingle = method.returnType.methods.first {
                            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 1 && it.parameterTypes[0] == Throwable::class.java
                        }.invoke(null, IOException())
                        param.setResult(errorSingle)
                        return@hook
                    }
                    param.setResult(null)
                }
            } ?: context.log.warn("apiInvocationHandler not found in mappings")
        }
    }
}
