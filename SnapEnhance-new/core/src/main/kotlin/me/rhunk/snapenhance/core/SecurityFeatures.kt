package me.rhunk.snapenhance.core

import android.system.Os
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.rhunk.snapenhance.common.bridge.FileHandleScope
import me.rhunk.snapenhance.common.bridge.toWrapper
import me.rhunk.snapenhance.common.config.MOD_DETECTION_VERSION_CHECK
import me.rhunk.snapenhance.common.config.VersionRequirement
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
import java.io.IOException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.system.exitProcess

class SecurityFeatures(
    private val context: ModContext
) {
    private enum class EndpointRisk { LOW, MEDIUM, HIGH }
    
    private data class BypassStats(
        var allowed: Int = 0,
        var blocked: Int = 0,
        var bypassed: Int = 0
    )
    
    private val endpointCache = ConcurrentHashMap<String, Boolean>()
    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val bypassStats = BypassStats()
    private val endpointPatterns by lazy { compileEndpointPatterns() }
    
    private fun transact(option: Int, option2: Long) = runCatching { Os.prctl(option, option2, 0, 0, 0) }.getOrNull()

    private val token by lazy { transact(0, 0) }

    private fun getStatus() = token?.run {
        transact(this, 0)?.toString(2)?.padStart(32, '0')?.count { it == '1' }
    }
    
    private fun compileEndpointPatterns(): List<Regex> {
        return listOf(
            // Core messaging service patterns from deobfuscated sources
            Regex("/messagingcoreservice\\.MessagingCoreService/.*"),
            Regex("/GetConvoSafetyPrompt(V\\d+)?"),
            
            // GRPC service patterns based on deobfuscated GrpcParameters structure
            Regex("/[A-Za-z]+Service/[A-Za-z]+"),  // Generic service/method pattern
            Regex("/.*\\.proto\\.[A-Za-z]+Service/.*"),  // Proto service pattern
            
            // Specific service patterns found in deobfuscated sources
            Regex("/(story|chat|media|friend|snap|notification|discover|search)/.*"),
            Regex("/.*Service/.*"),
            
            // API Gateway patterns from Tweaks.java
            Regex("/api/gateway/.*"),
            Regex("/streaming/.*"),
            Regex("/arroyo/.*"),
            
            // GRPC path patterns from deobfuscated sources
            Regex("/grpc/.*"),
            Regex("/mcs/.*"),  // MCS_GRPC_PATH_PREFIX
            
            // Authentication and safety patterns
            Regex("/auth/.*"),
            Regex("/safety/.*"),
            Regex("/attestation/.*"),
            
            // Hermod duplex patterns
            Regex("/hermod_dup.*")
        )
    }
    
    private fun categorizeEndpointRisk(path: String): EndpointRisk = when {
        path.contains("auth", true) || path.contains("login", true) -> EndpointRisk.HIGH
        path.contains("message", true) || path.contains("chat", true) -> EndpointRisk.MEDIUM
        path.contains("profile", true) || path.contains("settings", true) -> EndpointRisk.LOW
        else -> EndpointRisk.MEDIUM
    }
    
    private fun getBypassStatsLog() = with(bypassStats) {
        "Stats: Allowed=$allowed | Blocked=$blocked | Bypassed=$bypassed"
    }

    private fun showBypassStatusIndicator(isWorking: Boolean) {
        if (context.config.experimental.securityBypass.get()) {
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

    private fun getSelectiveAllowedEndpoints(): List<String> {
        val baseEndpoints = buildList {
            add("/messagingcoreservice.MessagingCoreService/")
            (1..200).forEach { add("/GetConvoSafetyPromptV$it") }
        }
        
        if (!context.config.experimental.securityBypass.get()) {
            return baseEndpoints
        }
        
        return baseEndpoints + listOf(
            "/story/", "/StoryService/", "/GetStories", "/PostStory", "/ViewStory",
            "/chat/", "/ChatService/", "/SendMessage", "/GetMessages", "/MarkAsRead",
            "/media/", "/MediaService/", "/UploadMedia", "/DownloadMedia", "/ProcessMedia",
            "/friend/", "/FriendService/", "/AddFriend", "/RemoveFriend", "/GetFriends",
            "/snap/", "/SnapService/", "/notification/", "/NotificationService/",
            "/discover/", "/DiscoverService/", "/search/", "/SearchService/"
        )
    }
    
    private fun isEndpointAllowed(requestPath: String, useCache: Boolean = true): Boolean {
        if (useCache) {
            endpointCache[requestPath]?.let { return it }
        }
        
        val allowed = endpointPatterns.any { it.matches(requestPath) } || 
                      getSelectiveAllowedEndpoints().any { requestPath.contains(it, ignoreCase = true) }
        
        if (useCache) {
            endpointCache[requestPath] = allowed
            if (endpointCache.size > 500) endpointCache.clear()
        }
        
        return allowed
    }
    
    private fun shouldBypassAttestation(requestPath: String): Boolean {
        if (!context.config.experimental.securityBypass.get()) return false
        
        val risk = categorizeEndpointRisk(requestPath)
        
        return when (risk) {
            EndpointRisk.LOW -> requestPath.contains("/GetUserProfile", true) ||
                               requestPath.contains("/GetPublicProfile", true) ||
                               requestPath.contains("/GetBasicUserInfo", true) ||
                               requestPath.contains("/GetUserSettings", true) ||
                               requestPath.contains("/GetBitmojiAvatar", true) ||
                               requestPath.contains("/GetDisplayName", true)
            EndpointRisk.MEDIUM -> requestPath.contains("/GetConvoSafetyPrompt", true) ||
                                  requestPath.contains("/messagingcoreservice", true)
            EndpointRisk.HIGH -> false
        }
    }

    fun init() {
        val snapchatVersionCode = context.androidContext.packageManager?.getPackageInfo(context.androidContext.packageName, 0)?.longVersionCode ?: throw IllegalStateException("Failed to get version code")
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

        // Test mode for development
        if (context.config.experimental.securityBypass.get()) {
            context.log.info("Smart Bypass enabled - selective security bypassing active")
            shouldDisablePlugin = false
        }

        context.disablePlugin = shouldDisablePlugin
        context.log.verbose("disablePlugin=${context.disablePlugin}")
        
        // Show appropriate visual indicators
        if (context.config.experimental.securityBypass.get()) {
            showSmartBypassInjector()
        } else if (!usingCustomSharedLibrary) {
            showBypassStatusIndicator(context.disablePlugin)
        }
        
        if (!context.disablePlugin) return

        val allowedEPs = getSelectiveAllowedEndpoints()

        context.event.subscribe(UnaryCallEvent::class) { event ->
            val callOptions = event.adapter.arg<Any>(2).let { it.javaClass.getMethod("build").invoke(it) } ?: return@subscribe
            val requestUri = event.uri
            
            val hasAttestation = callOptions.getObjectField("mAttestation") != null
            val isIncomingFriendSync = requestUri.endsWith("/IncomingFriendSync")
            val isAllowedEndpoint = isEndpointAllowed(requestUri)
            val shouldBypass = shouldBypassAttestation(requestUri)
            
            if ((hasAttestation && !shouldBypass) || (isIncomingFriendSync && !isAllowedEndpoint)) {
                val reason = when {
                    hasAttestation && !shouldBypass -> "Attestation required but bypass not allowed for this endpoint"
                    isIncomingFriendSync && !isAllowedEndpoint -> "IncomingFriendSync endpoint not in allowed list"
                    else -> "Security policy violation"
                }
                logBypassActivity(requestUri, "blocked", reason)
                context.log.verbose("blocked ep $requestUri [${categorizeEndpointRisk(requestUri)}]")
                event.canceled = true
                val eventHandler = event.adapter.arg<Any>(3)
                eventHandler.javaClass.methods.first { it.name == "onEvent" }.also { method ->
                    method.invoke(eventHandler, null, method.parameterTypes[0].dataBuilder {
                        set("mStatusCode", "CANCELLED")
                    })
                }
            } else if (hasAttestation && shouldBypass) {
                logBypassActivity(requestUri, "bypassed", "Smart bypass enabled for this endpoint type")
                context.log.info("Smart Bypass: allowed $requestUri [${categorizeEndpointRisk(requestUri)}]")
            } else if (isAllowedEndpoint) {
                logBypassActivity(requestUri, "allowed", "Endpoint matches allowed patterns")
            }
        }

        context.androidContext.classLoader.apply {
            val argosClientClass = loadClass("com.snapchat.client.client_attestation.ArgosClient\$CppProxy")
            val smartBypassEnabled = context.config.experimental.securityBypass.get()
            
            argosClientClass.apply {
                if (smartBypassEnabled) {
                    context.log.info("Smart Bypass: ArgosClient - advanced token generation active")
                    var tokenGenerationCount = 0
                    
                    hook("getArgosTokenAsync", HookStage.BEFORE) { param ->
                        val token = generateFakeAttestationToken()
                        tokenGenerationCount++
                        if (tokenGenerationCount % 10 == 0) {
                            context.log.verbose("Generated $tokenGenerationCount attestation tokens")
                        }
                        param.setResult(token)
                    }
                    
                    hook("getAttestationHeaders", HookStage.BEFORE) { param ->
                        param.setResult(mapOf("X-Snap-Attestation" to generateFakeAttestationToken()))
                    }
                } else {
                    hookConstructor(HookStage.BEFORE) { it.setResult(null) }
                    hook("getArgosTokenAsync", HookStage.BEFORE) { it.setResult(null) }
                    hook("getAttestationHeaders", HookStage.BEFORE) { it.setResult(null) }
                }
            }
            loadClass("com.snapchat.client.client_attestation.ArgosClient").hook("createInstance", HookStage.BEFORE) { param ->
                param.setResult(argosClientClass.declaredConstructors.first().also { it.isAccessible = true }.newInstance(0))
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
                val attestationRequired = authContextRequest.getObjectField("mAttestationRequired") == true

                if (attestationRequired) {
                    val isAllowed = isEndpointAllowed(requestPath)
                    val shouldBypass = shouldBypassAttestation(requestPath)
                    val risk = categorizeEndpointRisk(requestPath)
                    
                    if (isAllowed || shouldBypass) {
                        val reason = when {
                            shouldBypass -> "Smart bypass enabled for ${risk.name.lowercase()} risk endpoint"
                            isAllowed -> "Endpoint matches allowed patterns in security configuration"
                            else -> "Default allow policy"
                        }
                        logBypassActivity(requestPath, if (shouldBypass) "bypassed" else "allowed", reason)
                        context.log.verbose("allowed ep $requestPath [$risk]")
                        return@hook
                    }

                    val blockReason = "Attestation required but endpoint not in allowed list or bypass policy"
                    logBypassActivity(requestPath, "blocked", blockReason)
                    context.log.info("Smart Bypass: blocked $requestPath [$risk]")
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
    
    private fun generateFakeAttestationToken(): String {
        val currentTime = System.currentTimeMillis()
        val cacheKey = "attestation_token"
        val cacheTimeout = 300000L
        
        tokenCache[cacheKey]?.let { (token, timestamp) ->
            if (currentTime - timestamp < cacheTimeout) return token
        }
        
        val token = buildString {
            val seed = currentTime / cacheTimeout
            val random = Random(seed)
            
            val deviceId = context.androidContext.packageName.hashCode().toString(36)
            val timestamp = (currentTime / 1000).toString(36)
            val entropy = (1..24).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"[random.nextInt(64)] }.joinToString("")
            
            append("ey")
            append(deviceId.take(8).padEnd(8, '0'))
            append(timestamp)
            append(".")
            append(entropy)
            append(".")
            
            val payload = "$deviceId$timestamp$entropy"
            val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            append(hash.take(16).joinToString("") { "%02x".format(it) })
        }
        
        tokenCache[cacheKey] = token to currentTime
        if (tokenCache.size > 100) tokenCache.clear()
        
        return token
    }
    
    private fun logBypassActivity(endpoint: String, action: String, reason: String = "") {
        val risk = categorizeEndpointRisk(endpoint)
        val timestamp = System.currentTimeMillis()
        
        when (action) {
            "allowed" -> bypassStats.allowed++
            "blocked" -> bypassStats.blocked++
            "bypassed" -> bypassStats.bypassed++
        }
        
        // Enhanced logging with detailed information
        val logMessage = buildString {
            append("Security Bypass: ")
            append(action.uppercase())
            append(" | Endpoint: $endpoint")
            append(" | Risk: $risk")
            if (reason.isNotEmpty()) {
                append(" | Reason: $reason")
            }
            append(" | Stats: ${getBypassStatsLog()}")
        }
        
        when (action) {
            "blocked" -> context.log.warn(logMessage)
            "bypassed" -> context.log.info(logMessage)
            "allowed" -> context.log.verbose(logMessage)
        }
        
        // Periodic summary logging
        if ((bypassStats.allowed + bypassStats.blocked + bypassStats.bypassed) % 25 == 0) {
            context.log.info("Smart Bypass Summary: ${getBypassStatsLog()}")
        }
    }
}