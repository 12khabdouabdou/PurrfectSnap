package me.rhunk.snapenhance.core

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.system.Os
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.unit.sp
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.bridge.FileHandleScope
import me.rhunk.snapenhance.common.bridge.toWrapper
import me.rhunk.snapenhance.common.config.MOD_DETECTION_VERSION_CHECK
import me.rhunk.snapenhance.common.config.VersionRequirement
import me.rhunk.snapenhance.common.ui.Requirements
import me.rhunk.snapenhance.common.ui.createComposeView
import me.rhunk.snapenhance.core.event.events.impl.UnaryCallEvent
import me.rhunk.snapenhance.core.ui.CustomComposable
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.hook.hookConstructor
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.mapper.impl.CallbackMapper
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
        private const val BYPASS_SHA256 = "C8EB29DA68264660CDE873D16FD7B70195D217A75645C2DE571AFD3C1273DADA"
        private const val CERTIFICATE_PIN = "Lz9eFj8/SD9nPy4/PT9LAj9eXD9NPwI/WD8jPyUQPz8NCg=="
    }

    private var oldBypassInitialized = false
    private var newBypassInitialized = false

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
                    .offset(y = (-8).dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.8f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
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
                    text = if (isWorking) "Bypass Active" else "Bypass Inactive",
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

    private fun showSmartBypassInjector() {
        lateinit var composable: CustomComposable
        composable = {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        color = Color(0xFF4CAF50).copy(alpha = 0.9f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Smart Bypass Active",
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

    fun init() {
        context.log.info("SecurityFeatures init started")

        val snapchatVersionCode = context.androidContext.packageManager?.getPackageInfo(context.androidContext.packageName, 0)?.longVersionCode
            ?: throw IllegalStateException("Failed to get version code")

        var shouldDisablePlugin = MOD_DETECTION_VERSION_CHECK.checkVersion(snapchatVersionCode)?.second == VersionRequirement.OLDER_REQUIRED
        var usingCustomSharedLibrary = false

        context.config.experimental.nativeHooks.customSharedLibrary.get().takeIf { it.isNotEmpty() }?.let {
            runCatching {
                context.native.loadSharedLibrary(
                    context.fileHandlerManager.getFileHandle(FileHandleScope.USER_IMPORT.key, it).toWrapper().readBytes()
                )
                shouldDisablePlugin = false
                usingCustomSharedLibrary = true
            }.onFailure {
                context.log.error("Failed to load custom shared library", it)
            }
        }

        if (context.config.experimental.useRemoteBypass.get()) {
            shouldDisablePlugin = false
            if (initNewBypass()) {
                showSmartBypassInjector()
            } else {
                context.log.error("Failed to initialize new bypass, safety measures enabled")
                shouldDisablePlugin = true
            }
        }

        context.disablePlugin = shouldDisablePlugin
        if (!usingCustomSharedLibrary && !context.config.experimental.useRemoteBypass.get()) {
            showBypassStatusIndicator(!context.disablePlugin)
        }

        if (context.disablePlugin) {
            initOldBypass()
        }
    }

    private fun initNewBypass(): Boolean {
        if (newBypassInitialized) return true

        val encryptedData = context.bridgeClient.getBypassData()
        if (encryptedData.isEmpty()) {
            context.log.error("getBypassData() returned empty array")
            return false
        }

        val decryptedFile = File(context.androidContext.cacheDir, "bypass.dex")
        return try {
            decryptBypass(encryptedData, decryptedFile)
            loadBypassModule(decryptedFile)
            newBypassInitialized = true
            true
        } catch (e: Exception) {
            context.log.error("Failed to initialize new bypass", e)
            false
        }
        finally {
            if (decryptedFile.exists()) {
                decryptedFile.delete()
            }
        }
    }

    private fun decryptBypass(encryptedData: ByteArray, decryptedFile: File) {
        val password = context.native.getSecretKey().toCharArray()

        encryptedData.inputStream().use { fis ->
            val saltHeader = ByteArray(8)
            fis.read(saltHeader)
            val salt = ByteArray(8)
            fis.read(salt)

            val keySpec = PBEKeySpec(password, salt, 100000, 256 + 128)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = factory.generateSecret(keySpec)

            val keyBytes = key.encoded.copyOfRange(0, 32)
            val ivBytes = key.encoded.copyOfRange(32, 48)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivParameterSpec = IvParameterSpec(ivBytes)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)

            decryptedFile.outputStream().use { fos ->
                val cipherInputStream = CipherInputStream(fis, cipher)
                cipherInputStream.copyTo(fos)
            }
        }
    }

    private fun initOldBypass() {
        if (oldBypassInitialized) return
        oldBypassInitialized = true

        val allowedEPs = listOf(
            "/messagingcoreservice.MessagingCoreService/",
            "/GetConvoSafetyPrompt",
            "/GetSnapchatterPublicInfo",
            "/UserRecentlyActive",
            "/socialsms.SocialSms/UpdateLink", // Direct link sharing
        )

        context.event.subscribe(UnaryCallEvent::class) { event ->
            val callOptions = event.adapter.arg<Any>(2).let { it.javaClass.getMethod("build").invoke(it) } ?: return@subscribe
            if (callOptions.getObjectField("mAttestation") != null || event.uri.endsWith("/IncomingFriendSync")) {
                context.log.verbose("blocked ep ${event.adapter.arg<Any>(0)}", "UnaryCallEvent")
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

        context.mappings.useMapper(CallbackMapper::class) {
            callbacks.getClass("AuthContextDelegate")?.hook("getAuthContext", HookStage.BEFORE) { param ->
                val authContextRequest = param.arg<Any>(0)
                val requestPath = authContextRequest.getObjectField("mRequestPath").toString()

                if (authContextRequest.getObjectField("mAttestationRequired") == true) {
                    if (allowedEPs.any { requestPath.contains(it) }) {
                        context.log.verbose("ep $requestPath", "AuthContextDelegate")
                        return@hook
                    }

                    context.log.verbose("blocked ep $requestPath", "AuthContextDelegate")
                    param.setResult(null)
                }
            } ?: error("AuthContextDelegate not found in mappings")
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
    }

    private fun loadBypassModule(file: File) {
        val activity = context.mainActivity ?: return
        try {
            val dexClassLoader = DexClassLoader(file.absolutePath, null, null, context.androidContext.classLoader)
            val bypassClass = dexClassLoader.loadClass("me.rhunk.snapenhance.core.NewBypass")
            val bypassInstance = bypassClass.getConstructor(ModContext::class.java).newInstance(context)
            val initMethod = bypassClass.getMethod("init")
            initMethod.invoke(bypassInstance)
        } catch (e: Exception) {
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("Error")
                    .setMessage("Failed to load bypass module: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
            throw e
        }
    }
}
