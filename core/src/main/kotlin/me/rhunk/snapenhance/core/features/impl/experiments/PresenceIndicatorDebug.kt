package me.rhunk.snapenhance.core.features.impl.tweaks

import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.Hooker

class PresenceIndicatorDebug : Feature("Presence Indicator Debug") {
    
    override fun init() {
        if (!context.config.experimental.presenceDebug.get()) {
            context.log.verbose("Presence debug disabled")
            return
        }
        
        context.log.verbose("=== PRESENCE INDICATOR DEBUG ENABLED ===")
        
        // Hook classes related to presence/online status
        hookPresenceClasses()
        
        // Hook friend list rendering
        hookFriendListClasses()
        
        // Hook UI components (Views)
        hookViewClasses()
    }
    
    private fun hookPresenceClasses() {
        onNextActivityCreate(defer = true) {
            defer {
                context.log.verbose("Searching for Presence classes...")
                
                val presenceClasses = listOf(
                    "com.snapchat.client.messaging.Presence",
                    "com.snapchat.client.presence.PresenceInfo",
                    "com.snapchat.client.presence.PresenceManager",
                    "com.snapchat.android.presence.PresenceSession",
                )
                
                for (className in presenceClasses) {
                    runCatching {
                        val clazz = context.androidContext.classLoader.loadClass(className)
                        context.log.verbose("✓ Found: $className")
                        
                        // Hook all methods
                        clazz.declaredMethods.forEach { method ->
                            Hooker.hook(clazz, method, HookStage.BEFORE) { param ->
                                context.log.verbose("[PRESENCE] ${clazz.simpleName}.${method.name}()")
                                
                                // Log arguments
                                method.parameterTypes.forEachIndexed { i, paramType ->
                                    val arg = param.argNullable<Any>(i)
                                    if (arg != null) {
                                        context.log.verbose("  [${paramType.simpleName}] $arg")
                                    }
                                }
                            }
                            
                            Hooker.hook(clazz, method, HookStage.AFTER) { param ->
                                val result = param.result
                                if (result != null) {
                                    context.log.verbose("  → Returns: ${result.javaClass.simpleName} = $result")
                                    
                                    // If result is a presence object, inspect it
                                    if (result.javaClass.simpleName.contains("Presence", ignoreCase = true)) {
                                        inspectPresenceObject(result)
                                    }
                                }
                            }
                        }
                        
                        context.log.verbose("  Hooked ${clazz.declaredMethods.size} methods")
                        
                    }.onFailure {
                        context.log.verbose("✗ Not found: $className")
                    }
                }
            }
        }
    }
    
    private fun hookFriendListClasses() {
        onNextActivityCreate(defer = true) {
            defer {
                context.log.verbose("Searching for Friend List classes...")
                
                val friendClasses = listOf(
                    "com.snapchat.client.grpc.FriendService",
                    "com.snapchat.android.friends.FriendManager",
                    "com.snapchat.android.friends.Friend",
                    "com.snapchat.android.framework.model.User",
                )
                
                for (className in friendClasses) {
                    runCatching {
                        val clazz = context.androidContext.classLoader.loadClass(className)
                        context.log.verbose("✓ Found: $className")
                        
                        // Look for methods related to presence/online status
                        clazz.declaredMethods.forEach { method ->
                            val methodName = method.name.lowercase()
                            
                            if (methodName.contains("presence") ||
                                methodName.contains("online") ||
                                methodName.contains("active") ||
                                methodName.contains("status") ||
                                methodName.contains("indicator")) {
                                
                                context.log.verbose("  ⭐ Interesting method: ${method.name}")
                                
                                Hooker.hook(clazz, method, HookStage.BEFORE) { param ->
                                    context.log.verbose("[FRIEND] ${clazz.simpleName}.${method.name}()")
                                    
                                    method.parameterTypes.forEachIndexed { i, paramType ->
                                        val arg = param.argNullable<Any>(i)
                                        if (arg != null) {
                                            context.log.verbose("  [${paramType.simpleName}] $arg")
                                        }
                                    }
                                }
                                
                                Hooker.hook(clazz, method, HookStage.AFTER) { param ->
                                    val result = param.result
                                    if (result != null) {
                                        context.log.verbose("  → ${result.javaClass.simpleName} = $result")
                                    }
                                }
                            }
                        }
                        
                    }.onFailure {
                        context.log.verbose("✗ Not found: $className")
                    }
                }
            }
        }
    }
    
    private fun hookViewClasses() {
        onNextActivityCreate(defer = true) {
            defer {
                context.log.verbose("Hooking View classes for green dot indicators...")
                
                // Hook View.setVisibility to see when indicators are shown/hidden
                try {
                    val viewClass = findClass("android.view.View")
                    val setVisibilityMethod = viewClass.getMethod("setVisibility", Int::class.javaPrimitiveType)
                    
                    Hooker.hook(viewClass, setVisibilityMethod, HookStage.BEFORE) { param ->
                        val view = param.thisObject() as android.view.View
                        val visibility = param.arg<Int>(0)
                        
                        // Check if this view is a presence indicator
                        val viewId = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                        
                        if (viewId?.contains("presence", ignoreCase = true) == true ||
                            viewId?.contains("indicator", ignoreCase = true) == true ||
                            viewId?.contains("dot", ignoreCase = true) == true ||
                            viewId?.contains("badge", ignoreCase = true) == true ||
                            viewId?.contains("online", ignoreCase = true) == true) {
                            
                            val visibilityStr = when (visibility) {
                                android.view.View.VISIBLE -> "VISIBLE"
                                android.view.View.INVISIBLE -> "INVISIBLE"
                                android.view.View.GONE -> "GONE"
                                else -> "UNKNOWN($visibility)"
                            }
                            
                            context.log.verbose("!".repeat(60))
                            context.log.verbose("[VIEW] Presence Indicator visibility changed!")
                            context.log.verbose("  View ID: $viewId")
                            context.log.verbose("  Visibility: $visibilityStr")
                            context.log.verbose("  View Type: ${view.javaClass.simpleName}")
                            context.log.verbose("!".repeat(60))
                        }
                    }
                    
                    context.log.verbose("✓ Hooked View.setVisibility")
                    
                } catch (e: Exception) {
                    context.log.error("Failed to hook View.setVisibility", e)
                }
            }
        }
    }
    
    private fun inspectPresenceObject(obj: Any) {
        context.log.verbose("    [Presence Object Details]")
        
        runCatching {
            val fields = obj.javaClass.declaredFields
            
            for (field in fields) {
                field.isAccessible = true
                val value = field.get(obj)
                
                val fieldName = field.name
                if (fieldName.contains("online", ignoreCase = true) ||
                    fieldName.contains("active", ignoreCase = true) ||
                    fieldName.contains("present", ignoreCase = true) ||
                    fieldName.contains("status", ignoreCase = true) ||
                    fieldName.contains("available", ignoreCase = true)) {
                    
                    context.log.verbose("      ⭐ ${field.name} = $value")
                } else {
                    context.log.verbose("      ${field.name} = $value")
                }
            }
        }.onFailure {
            context.log.error("Failed to inspect presence object", it)
        }
    }
}
