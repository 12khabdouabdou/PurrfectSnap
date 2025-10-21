package me.rhunk.snapenhance.core

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.bridge.FileHandleScope
import me.rhunk.snapenhance.common.bridge.toWrapper
import me.rhunk.snapenhance.common.config.MOD_DETECTION_VERSION_CHECK
import me.rhunk.snapenhance.common.config.VersionRequirement
import me.rhunk.snapenhance.common.ui.Requirements
import me.rhunk.snapenhance.common.ui.createComposeView
import me.rhunk.snapenhance.core.ui.CustomComposable
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.hook.hookConstructor
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.mapper.impl.PlatformClientAttestationMapper
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

class SecurityFeatures(
    private val context: ModContext
) {
    companion object {
        private const val CHALLENGE_ENDPOINT_URL = "https://bypass-endpoint.purrfectsnap-bypass.workers.dev"
        private const val BYPASS_SHA256 = "E7B3A64C786271A23F56C8FE88519258C3326F5AC751D778DC7B1DC2DECD6EDD"
        private const val CERTIFICATE_PIN = "Lz9eFj8/SD9nPy4/PT9LAj9eXD9NPwI/WD8jPyUQPz8NCg=="
    }

    private var oldBypassInitialized = false
    private var newBypassInitialized = false

    fun init() {
        if (context.config.experimental.useRemoteBypass.get()) {
            if (context.config.experimental.remoteBypassConsent.get()) {
                initNewBypass()
            } else {
                context.log.debug("Remote bypass consent not granted, using old bypass")
                initOldBypass()
            }
            return
        }
        initOldBypass()
    }

    private fun initNewBypass() {
        if (newBypassInitialized) return
        newBypassInitialized = true

        val bypassFile = File(context.androidContext.filesDir, "bypass.dex")
        if (bypassFile.exists()) {
            loadBypassModule(bypassFile)
        }
    }

    private fun initOldBypass() {
        if (oldBypassInitialized) return
        oldBypassInitialized = true

        val snapchatVersionCode = context.androidContext.packageManager?.getPackageInfo(context.androidContext.packageName, 0)?.longVersionCode
            ?: throw IllegalStateException("Failed to get version code")

        var shouldDisablePlugin = MOD_DETECTION_VERSION_CHECK.checkVersion(snapchatVersionCode)?.second == VersionRequirement.OLDER_REQUIRED
        var usingCustomSharedLibrary = false

        // load user shared library
        context.config.experimental.nativeHooks.customSharedLibrary.get().takeIf { it.isNotEmpty() }?.let {
            runCatching {
                context.native.loadSharedLibrary(
                    context.fileHandlerManager.getFileHandle(FileHandleScope.USER_IMPORT.key, it).toWrapper().readBytes()
                )
                context.log.verbose("loaded custom shared library")
                shouldDisablePlugin = false
                usingCustomSharedLibrary = true

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

        context.disablePlugin = shouldDisablePlugin
        context.log.verbose("disablePlugin=	extvariable.disablePlugin")

        if (context.disablePlugin) {
             context.features.addActivityCreateListener { activity ->
                if (!activity.javaClass.name.endsWith("LoginSignupActivity")) return@addActivityCreateListener
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
                                            "SnapEnhance can't be used to login or signup because your Snapchat version isn't the recommended one. Please downgrade to Snapchat v${MOD_DETECTION_VERSION_CHECK.maxVersion?.first ?: "0.0.0"} or disable SnapEnhance in LSPosed to continue.\n\nFor more details, join t.me/snapenhance_chat",
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
            return
        }


        context.androidContext.classLoader.apply {
            loadClass("com.snapchat.client.client_attestation.ArgosClient\$CppProxy").apply {
                hookConstructor(HookStage.BEFORE) { it.setResult(null) }
                hook("getArgosTokenAsync", HookStage.BEFORE) { it.setResult(null) }
                hook("getAttestationHeaders", HookStage.BEFORE) { it.setResult(null) }
            }
            loadClass("com.snapchat.client.client_attestation.ArgosClient").hook("createInstance", HookStage.BEFORE) { param ->
                param.setResult(null)
            }
            loadClass("com.snap.security.attestation.impl.SCClientAttestationDurableJob").hookConstructor(HookStage.BEFORE) { param ->
                param.setArg(0, null)
            }
            loadClass("com.snapchat.client.grpc.AuthContext").hookConstructor(HookStage.AFTER) { param ->
                val headers by lazy { (param.thisObject<Any>().getObjectField("mHeaders") as? List<*>)?.filterNotNull() ?: emptyList() }

                if (param.thisObject<Any>().getObjectField("mAuthTokenErrorCode") != null ||
                    headers.isEmpty() ||
                    headers.mapNotNull { it.getObjectField("mKey")?.toString()?.lowercase() }.any { it != "x-snap-access-token" }
                ) {
                    context.log.error("invalid headers ${headers.size}")
                    exitProcess(139)
                }
            }
            loadClass("com.snapchat.client.duplex.DuplexClient\$CppProxy").hook("registerHandler",
                HookStage.BEFORE) { param ->
                val path = param.arg<String>(0)
                if (path == "hermod_dup") {
                    param.setResult(null)
                    return@hook
                }
            }
        }

        context.mappings.useMapper(PlatformClientAttestationMapper::class) {
            apiInvocationHandler.getAsClass()?.hook("invoke", HookStage.BEFORE) { param ->
                val method = param.arg<Method>(1)
                if (method.annotations.any { it.toString().contains("attestation") }) {
                    context.log.verbose("blocked call ${method.declaringClass.name}.${method.name}(...)")
                    if (method.returnType.name.endsWith("Single")) {
                        param.setResult(
                            method.returnType.methods.first {
                                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 1 && it.parameterTypes[0] == Throwable::class.java
                            }.invoke(null, IOException())
                        )
                        return@hook
                    }

                    param.setResult(null)
                }
            } ?: context.log.warn("apiInvocationHandler not found in mappings")
        }
    }

    private fun loadBypassModule(file: File) {
        val activity = context.mainActivity ?: return
        try {
            val dexClassLoader = DexClassLoader(file.absolutePath, null, null, context.androidContext.classLoader)
            val bypassClass = dexClassLoader.loadClass("me.rhunk.snapenhance.core.NewBypass")
            val bypassInstance = bypassClass.getConstructor(ModContext::class.java).newInstance(context)
            val initMethod = bypassClass.getMethod("init")
            initMethod.invoke(bypassInstance)
            context.log.info("Successfully loaded new bypass module.")
        } catch (e: Exception) {
            context.log.error("Failed to load new bypass module", e)
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("Error")
                    .setMessage("Failed to load bypass module: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
