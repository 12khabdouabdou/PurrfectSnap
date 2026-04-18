package me.eternal.purrfectsnap.core.features.impl.tweaks

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.ClassDetector
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import java.lang.reflect.Modifier

/**
 * Feature to enable native video splitting for CAMERA_ROLL source type.
 *
 * Hooks the C41838s50.a() static method which determines whether chunking
 * is enabled based on the source type. By default, CAMERA_ROLL is excluded
 * from native splitting. This hook forces the method to return true for
 * CAMERA_ROLL, enabling native Snapchat splitting.
 *
 * Risk Level: MEDIUM
 */
class CameraRollSourceOverride : Feature("Camera Roll Source Override") {
    override fun init() {
        context.log.info("=== CameraRollSourceOverride initializing ===")
        
        // Check if feature is enabled
        val isEnabled = context.config.global.enableNativeCameraRollSplitting.get()
        context.log.info("CameraRollSourceOverride: Feature enabled = $isEnabled")
        
        if (!isEnabled) {
            context.log.warn("CameraRollSourceOverride: Feature is DISABLED in config, skipping initialization")
            return
        }
        
        context.log.info("CameraRollSourceOverride: Feature is ENABLED, proceeding with hook...")
        
        val classLoader = context.androidContext.classLoader
        
        // Find the class by method signature (not class name)
        // Target: public static final boolean a(C41838s50, EnumC34633n6i, C19463cgc)
        context.log.info("CameraRollSourceOverride: Searching for target class by method signature...")
        val targetClass = ClassDetector.findClassBySignature(
            classLoader = classLoader,
            knownNames = emptyList(),
            methodSignature = { clazz ->
                clazz.methods.any { method ->
                    method.name == "a" &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 3 &&
                    method.returnType == Boolean::class.javaPrimitiveType
                }
            }
        ) ?: run {
            context.log.error("❌ CameraRollSourceOverride: Failed to find C41838s50.a() method by signature")
            context.log.error("CameraRollSourceOverride: This might mean:")
            context.log.error(" - Snapchat version changed (method signature changed)")
            context.log.error(" - Class obfuscation pattern changed")
            context.log.error(" - Mappings are outdated")
            return
        }
        
        context.log.info("✅ CameraRollSourceOverride: Found target class: ${targetClass.name}")
        context.log.info("CameraRollSourceOverride: Class loader = ${targetClass.classLoader}")
        
        // Verify this is the right class by checking parameter types
        val matchingMethods = targetClass.methods.filter { method ->
            method.name == "a" &&
            method.parameterCount == 3 &&
            method.parameterTypes[0].name.contains("C41838s50") &&
            method.parameterTypes[1].isEnum &&
            method.parameterTypes[2].name.contains("C19463cgc")
        }
        
        if (matchingMethods.isEmpty()) {
            context.log.error("❌ CameraRollSourceOverride: Found class has incorrect parameter types")
            context.log.error("CameraRollSourceOverride: Available methods named 'a':")
            targetClass.methods.filter { it.name == "a" }.forEach { method ->
                context.log.error(" - ${method.name}(${method.parameterTypes.joinToString(", ") { it.name }}): ${method.returnType.name}")
            }
            return
        }
        
        context.log.info("✅ CameraRollSourceOverride: Verified method signature matches expected pattern")
        context.log.info("CameraRollSourceOverride: Matching methods: ${matchingMethods.size}")
        
        // Hook the method with BEFORE stage
        context.log.info("CameraRollSourceOverride: Installing hook on ${targetClass.name}.a()...")
        targetClass.hook("a", HookStage.BEFORE) { param ->
            try {
                context.log.verbose("🔍 CameraRollSourceOverride: Hook triggered!")
                context.log.verbose("CameraRollSourceOverride: Thread = ${Thread.currentThread().name}")
                
                // Get the source type enum (parameter 1)
                val sourceTypeEnum = param.argNullable<Any>(1)
                if (sourceTypeEnum == null) {
                    context.log.warn("⚠️ CameraRollSourceOverride: Source type enum is NULL (arg index 1)")
                    return@hook
                }
                
                context.log.verbose("CameraRollSourceOverride: Source type enum class = ${sourceTypeEnum.javaClass.name}")
                context.log.verbose("CameraRollSourceOverride: Source type enum = ${sourceTypeEnum.toString()}")
                
                // Try to get enum ordinal
                val ordinalField = sourceTypeEnum.javaClass.getDeclaredField("a").apply { isAccessible = true }
                val ordinalValue = ordinalField.get(sourceTypeEnum) as? Int
                context.log.verbose("CameraRollSourceOverride: Enum ordinal value = $ordinalValue")
                
                // Method 1: Check by enum name
                val enumName = sourceTypeEnum.toString()
                context.log.verbose("CameraRollSourceOverride: Enum name = '$enumName'")
                
                if (enumName == "CAMERA_ROLL") {
                    context.log.info("✅✅✅ CameraRollSourceOverride: CAMERA_ROLL detected (by name)!")
                    context.log.info("CameraRollSourceOverride: Forcing chunking ENABLED (returning true)")
                    context.log.info("CameraRollSourceOverride: This should enable native splitting for gallery videos!")
                    param.setResult(true)
                    context.log.verbose("CameraRollSourceOverride: Hook completed successfully")
                    return@hook
                }
                
                // Method 2: Check by ordinal value (fallback)
                if (ordinalValue != null && ordinalValue == 11) { // 11 = CAMERA_ROLL
                    context.log.info("✅✅✅ CameraRollSourceOverride: CAMERA_ROLL detected (by ordinal=11)!")
                    context.log.info("CameraRollSourceOverride: Forcing chunking ENABLED (returning true)")
                    param.setResult(true)
                    context.log.verbose("CameraRollSourceOverride: Hook completed successfully (ordinal match)")
                    return@hook
                }
                
                // Not CAMERA_ROLL - log but don't interfere
                context.log.verbose("CameraRollSourceOverride: Not CAMERA_ROLL (ordinal=$ordinalValue, name=$enumName)")
                context.log.verbose("CameraRollSourceOverride: Allowing original method to proceed")
                
            } catch (e: Exception) {
                context.log.error("❌❌❌ CameraRollSourceOverride: ERROR in hook!")
                context.log.error("CameraRollSourceOverride: Exception type = ${e.javaClass.name}")
                context.log.error("CameraRollSourceOverride: Message = ${e.message}")
                context.log.error("CameraRollSourceOverride: Stack trace:")
                e.printStackTrace()?.let { context.log.error(it) }
                // Don't setResult - let original method handle it on error
            }
        }
        
        context.log.info("✅ CameraRollSourceOverride: Hook installed successfully")
        context.log.info("CameraRollSourceOverride: Native splitting should now be enabled for CAMERA_ROLL")
        context.log.info("CameraRollSourceOverride: === Initialization complete ===")
    }
}
