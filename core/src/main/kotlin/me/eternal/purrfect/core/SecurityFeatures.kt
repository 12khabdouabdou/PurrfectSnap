package me.eternal.purrfect.core

import android.system.Os
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.eternal.purrfect.common.bridge.FileHandleScope
import me.eternal.purrfect.common.bridge.toWrapper
import me.eternal.purrfect.common.config.MOD_DETECTION_VERSION_CHECK
import me.eternal.purrfect.common.config.VersionRequirement
import me.eternal.purrfect.common.ui.createComposeView
import me.eternal.purrfect.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfect.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfect.core.features.impl.experiments.BypassTrace
import me.eternal.purrfect.core.ui.CustomComposable
import me.eternal.purrfect.core.ui.PurrfectOverlayPalette
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import me.eternal.purrfect.core.util.dataBuilder
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.mapper.impl.CallbackMapper
import me.eternal.purrfect.mapper.impl.PlatformClientAttestationMapper
import java.io.IOException
import java.lang.reflect.Method

class SecurityFeatures(
    private val context: ModContext
) {
    private fun transact(option: Int, option2: Long) = runCatching { Os.prctl(option, option2, 0, 0, 0) }.getOrNull()

    /** Loads a target-app class from the APK classloader. Never use the bare `loadClass` receiver. */
    private fun loadSnapClass(name: String): Class<*> =
        context.androidContext.classLoader.loadClass(name)

    private val token by lazy {
        transact(0, 0)
    }

    private fun getStatus() = token?.run {
        transact(this, 0)?.toString(2)?.padStart(32, '0')?.count { it == '1' }
    }

    private fun isLoginSignupActivity() = context.mainActivity?.javaClass?.name?.endsWith("LoginSignupActivity") == true

    @Composable
    private fun LoginSignupHelpButton(onClick: () -> Unit) {
        val skin = LocalPurrfectSkin.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF6F28A8).copy(alpha = 0.92f),
                                Color(0xFF0059B7).copy(alpha = 0.62f)
                            )
                        ),
                        RoundedCornerShape(999.dp)
                    )
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        RoundedCornerShape(999.dp)
                    )
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.16f))
                        .border(BorderStroke(1.dp, PurrfectOverlayPalette.textPrimary.copy(alpha = 0.28f)), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.HelpOutline,
                        contentDescription = "Login Help",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Can't Login?",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }

    @Composable
    private fun LoginSignupHelpDialog(onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            PurrfectOverlayTheme(context) {
                val shape = RoundedCornerShape(20.dp)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = shape,
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.55f),
                                PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.35f)
                            )
                        )
                    ),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier
                            .background(PurrfectOverlayPalette.cardOverlay, shape)
                            .padding(20.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val scrollState = rememberScrollState()

                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = PurrfectOverlayPalette.textPrimary,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(28.dp)
                            )
                            Text(
                                text = context.translation["setup.mappings.notice_title"],
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = PurrfectOverlayPalette.textPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 10.dp)
                                        .verticalScroll(scrollState),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = context.translation["setup.mappings.notice_intro"],
                                        color = PurrfectOverlayPalette.textSecondary,
                                        textAlign = TextAlign.Start
                                    )
                                    Text(
                                        text = "For non-rooted users:",
                                        color = PurrfectOverlayPalette.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = context.translation["setup.mappings.notice_step_1"],
                                        color = PurrfectOverlayPalette.textSecondary,
                                        textAlign = TextAlign.Start
                                    )
                                    Text(
                                        text = context.translation["setup.mappings.notice_step_2"],
                                        color = PurrfectOverlayPalette.textSecondary,
                                        textAlign = TextAlign.Start
                                    )
                                    Text(
                                        text = context.translation["setup.mappings.notice_step_3"],
                                        color = PurrfectOverlayPalette.textSecondary,
                                        textAlign = TextAlign.Start
                                    )
                                    Text(
                                        text = context.translation["setup.mappings.notice_rooted_title"],
                                        color = PurrfectOverlayPalette.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = context.translation["setup.mappings.notice_rooted_body"],
                                        color = PurrfectOverlayPalette.textSecondary,
                                        textAlign = TextAlign.Start
                                    )
                                }

                                val maxScroll = scrollState.maxValue
                                val thumbRatio = if (maxScroll > 0) {
                                    ((scrollState.value.toFloat() / maxScroll.toFloat()) * 0.7f).coerceIn(0f, 0.7f)
                                } else 0f
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(if (maxScroll > 0) 0.28f else 1f)
                                            .offset(y = (320.dp * thumbRatio))
                                            .background(
                                                PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.75f),
                                                RoundedCornerShape(999.dp)
                                            )
                                    )
                                }
                            }
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.92f),
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("OK")
                            }
                        }
                    }
                }
            }
        }
    }

    fun init() {
        val snapchatVersionCode = context.androidContext.packageManager?.getPackageInfo(context.androidContext.packageName, 0)?.longVersionCode ?: throw IllegalStateException("Failed to get version code")
        var shouldDisablePlugin = MOD_DETECTION_VERSION_CHECK.checkVersion(snapchatVersionCode)?.second == VersionRequirement.OLDER_REQUIRED

        // load user shared library
        context.config.experimental.nativeHooks.customSharedLibrary.get().takeIf { it.isNotEmpty() }?.let {
            runCatching {
                context.native.loadSharedLibrary(
                    context.fileHandlerManager.getFileHandle(FileHandleScope.USER_IMPORT.key, it).toWrapper().readBytes()
                )
                context.log.verbose("loaded custom shared library")
                shouldDisablePlugin = false

                lateinit var composable: CustomComposable
                composable = {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopCenter),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF85A947))
                    }

                    LaunchedEffect(Unit) {
                        delay(2500)
                        context.inAppOverlay.removeCustomComposable(composable)
                    }
                }

                context.inAppOverlay.addCustomComposable(composable)
            }.onFailure {
                context.log.error("Failed to load custom shared library", it)
            }
        }

        val isTestModeEnabled = context.bridgeClient.getDebugProp("test_mode", "false") == "true"
        if (isTestModeEnabled) {
            shouldDisablePlugin = false
        }

        context.disablePlugin = shouldDisablePlugin

        if (context.disablePlugin && !isTestModeEnabled) {
            // Defer showing toast until an activity is available
            context.features.addActivityCreateListener { activity ->
                context.inAppOverlay.showStatusToast(
                    Icons.Outlined.Cancel,
                    "Failed to enable security features. Some features may not work properly.",
                    durationMs = 3000
                )
            }
        }

        lateinit var loginHelpComposable: CustomComposable
        loginHelpComposable = {
            var showDialog by remember { mutableStateOf(false) }
            var isLoginScreen by remember { mutableStateOf(false) }
            val disableHelpButton = context.bridgeClient.getDebugProp("disable_cant_login_button", "false") == "true"

            LaunchedEffect(Unit) {
                while (true) {
                    val currentlyInLogin = isLoginSignupActivity()
                    isLoginScreen = currentlyInLogin
                    if (!currentlyInLogin) showDialog = false
                    delay(150)
                }
            }

            if (isLoginScreen && !disableHelpButton) {
                LoginSignupHelpButton(
                    onClick = { showDialog = true }
                )
            }

            if (isLoginScreen && !disableHelpButton && showDialog) {
                LoginSignupHelpDialog(
                    onDismiss = { showDialog = false }
                )
            }
        }
        context.inAppOverlay.addCustomComposable(loginHelpComposable)

        if (!context.disablePlugin) return

        val allowedEPs = listOf(
            "/messagingcoreservice.MessagingCoreService/",
            "/GetSnapchatterPublicInfo",
            "/UserRecentlyActive",
            "/socialsms.SocialSms/UpdateLink", // Direct link sharing
        )

        context.event.subscribe(UnaryCallEvent::class) { event ->
            val callOptions = event.adapter.arg<Any>(2).let { it.javaClass.getMethod("build").invoke(it) } ?: return@subscribe
            if (callOptions.getObjectField("mAttestation") != null || event.uri.endsWith("/IncomingFriendSync")) {
                BypassTrace.noteSeamFired("unary_call_attestation_cancelled")
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
            val argosClientClass = loadSnapClass("com.snapchat.client.client_attestation.ArgosClient\$CppProxy")
            argosClientClass.apply {
                hookConstructor(HookStage.BEFORE) {
                    BypassTrace.noteSeamFired("argos_ctor_intercepted")
                    it.setResult(null)
                }
                hook("getArgosTokenAsync", HookStage.BEFORE) {
                    BypassTrace.noteSeamFired("argos_get_token_nulled")
                    it.setResult(null)
                }
                hook("getAttestationHeaders", HookStage.BEFORE) {
                    BypassTrace.noteSeamFired("argos_attestation_headers_nulled")
                    it.setResult(null)
                }
            }
            loadSnapClass("com.snapchat.client.client_attestation.ArgosClient").hook("createInstance", HookStage.BEFORE) { param ->
                BypassTrace.noteSeamFired("argos_create_instance_spoofed")
                param.setResult(argosClientClass.declaredConstructors.first().also { it.isAccessible = true }.newInstance(0))
            }
            loadSnapClass("com.snap.security.attestation.impl.SCClientAttestationDurableJob").hookConstructor(HookStage.BEFORE) { param ->
                BypassTrace.noteSeamFired("sc_client_attestation_job_suppressed")
                param.setArg(0, null)
            }
            loadSnapClass("com.snapchat.client.grpc.AuthContext").hookConstructor(HookStage.AFTER) { param ->
                val headers by lazy { (param.thisObject<Any>().getObjectField("mHeaders") as? List<*>)?.filterNotNull() ?: emptyList() }

                if (param.thisObject<Any>().getObjectField("mAuthTokenErrorCode") != null ||
                    headers.isEmpty() ||
                    headers.mapNotNull { it.getObjectField("mKey")?.toString()?.lowercase() }.any { it != "x-snap-access-token" }
                ) {
                    // Intentionally do nothing to avoid terminating the host process.
                }
            }
            loadSnapClass("com.snapchat.client.duplex.DuplexClient\$CppProxy").hook("registerHandler",
                HookStage.BEFORE) { param ->
                val path = param.arg<String>(0)
                if (path == "hermod_dup") {
                    BypassTrace.noteSeamFired("duplex_hermod_dup_blocked")
                    param.setResult(null)
                    return@hook
                }
            }
        }

        context.mappings.useMapper(CallbackMapper::class) {
            callbacks.getClass("AuthContextDelegate")?.hook("getAuthContext", HookStage.BEFORE) { param ->
                val authContextRequest = param.arg<Any>(0)
                val requestPath = authContextRequest.getObjectField("mRequestPath").toString()

                if (authContextRequest.getObjectField("mAttestationRequired") == true) {
                    if (allowedEPs.any { requestPath.contains(it) }) {
                        return@hook
                    }

                    BypassTrace.noteSeamFired("auth_context_attestation_nulled")
                    param.setResult(null)
                }
            } ?: error("AuthContextDelegate not found in mappings")
        }

        context.mappings.useMapper(PlatformClientAttestationMapper::class) {
            apiInvocationHandler.getAsClass()?.hook("invoke", HookStage.BEFORE) { param ->
                val method = param.arg<Method>(1)
                if (method.annotations.any { it.toString().contains("attestation") }) {
                    if (method.returnType.name.endsWith("Single")) {
                        BypassTrace.noteSeamFired("platform_attestation_single_err")
                        param.setResult(
                            method.returnType.methods.first {
                                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 1 && it.parameterTypes[0] == Throwable::class.java
                            }.invoke(null, IOException())
                        )
                        return@hook
                    }

                    BypassTrace.noteSeamFired("platform_attestation_nulled")
                    param.setResult(null)
                }
            } ?: context.log.warn("apiInvocationHandler not found in mappings")
        }

        // Tier-2a + Tier-2c: Play Integrity + Graphene HTTP-layer mute (canonical NetworkApi seam).
        //
        // Purrfectsnap hooks these the SAME way `EndpointsBlocker` does: a
        // `NetworkApiRequestEvent` subscriber that cancels the matching HTTP URL.
        // The naive "hook the Java interface method" approach misses here — both
        // `StandardIntegrityManager$StandardIntegrityTokenProvider` (Play) and
        // `GrapheneHttpInterface` (graphene) are plain Retrofit-style Java
        // INTERFACES (verified in the decompile — `@LMce` HTTP path annotations,
        // `Single<Ekg<...>>` returns, no `$CppProxy` generated for either; the
        // impls are DI-bound at runtime and unhookable by class name). LSPosed
        // can't instrument abstract methods.
        //
        // Both interfaces route their requests through `com.snapchat.client.
        // network_api.NetworkApi.submit` — purrfect's universal HTTP seam —
        // which fires `NetworkApiRequestEvent` with `event.url`. So we cancel
        // the URL match there: identical to `EndpointsBlocker`'s pattern. The
        // NATIVE risk-blocklist (DET keywords 9-14/52-57 + 74/75) is the belt
        // to this Java braces (and covers any path that bypasses NetworkApi).
        //
        //  - Play Integrity (`url` contains "PlayIntegrity") — always-on, mirrors
        //    the native posture (Play Core's own SDK traffic is unhookable, but
        //    Snapchat's direct /PlayIntegrity HTTP requests go through NetworkApi).
        //  - Graphene metrics (`url` ends with /v1/metrics — the localhost drain)
        //    — gated by `experimental.security.muteGrapheneTelemetry` (default true).
        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            if (event.url.contains("PlayIntegrity", ignoreCase = true)) {
                BypassTrace.noteSeamFired("play_integrity_http_muted")
                event.canceled = true
                context.log.verbose("Play Integrity HTTP muted (NetworkApi) url=${event.url}")
                return@subscribe
            }
        }
        if (context.config.experimental.security.muteGrapheneTelemetry.get()) {
            context.event.subscribe(NetworkApiRequestEvent::class) { event ->
                if (event.url.contains("/v1/metrics") || event.url.endsWith("v1/metrics")) {
                    BypassTrace.noteSeamFired("graphene_metrics_muted")
                    event.canceled = true
                    context.log.verbose("Graphene emitMetricFrame muted (NetworkApi) url=${event.url}")
                }
            }
            context.log.info("Graphene emitMetricFrame mute active (NetworkApi seam, gated by muteGrapheneTelemetry)")
        }


        context.features.addActivityCreateListener { activity ->
            if (!activity.javaClass.name.endsWith("LoginSignupActivity")) return@addActivityCreateListener
            if (context.bridgeClient.getDebugProp("disable_cant_login_button", "false") == "true") {
                return@addActivityCreateListener
            }

            activity.findViewById<ViewGroup>(android.R.id.content).apply {
                visibility = ViewGroup.INVISIBLE

                post {
                    addView(createComposeView(activity) {
                        Surface(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(Icons.Rounded.NotInterested, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(110.dp))
                                    Spacer(Modifier.height(50.dp))
                                    Text(
                                        "Purrfect can't be used to login or signup because your Snapchat version isn't the recommended one. Please downgrade to Snapchat v${MOD_DETECTION_VERSION_CHECK.maxVersion?.first ?: "0.0.0"} or disable Purrfect in LSPosed to continue.\n\nFor more details, join t.me/purrfect_tg",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }

                        LaunchedEffect(Unit) {
                            visibility = ViewGroup.VISIBLE
                        }
                    })
                }
            }
        }
    }
}
