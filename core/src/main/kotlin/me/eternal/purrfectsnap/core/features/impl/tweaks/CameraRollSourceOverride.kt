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
        // Check if feature is enabled
        if (!context.config.global.enableNativeCameraRollSplitting.get()) {
            return
        }

        val classLoader = context.androidContext.classLoader

        // Find the class by method signature (not class name)
        // Target: public static final boolean a(C41838s50, EnumC34633n6i, C19463cgc)
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
            context.log.error("Failed to find C41838s50.a() method by signature")
            return
        }

        context.log.verbose("Found target class: ${targetClass.name}")

        // Verify this is the right class by checking parameter types
        val hasCorrectParams = targetClass.methods.any { method ->
            method.name == "a" &&
            method.parameterCount == 3 &&
            method.parameterTypes[0].name.contains("C41838s50") &&
            method.parameterTypes[1].isEnum &&
            method.parameterTypes[2].name.contains("C19463cgc")
        }

        if (!hasCorrectParams) {
            context.log.error("Found class has incorrect parameter types")
            return
        }

        // Hook the method with BEFORE stage
        targetClass.hook("a", HookStage.BEFORE) { param ->
            try {
                // Parameters:
                // arg(0): C41838s50 instance (not used for static method)
                // arg(1): EnumC34633n6i enum value (source type) - CHECK THIS
                // arg(2): C19463cgc instance (not used)

                val sourceTypeEnum = param.argNullable<Any>(1) ?: return@hook

                // Method 1: Check by enum name (safer, more readable)
                val enumName = sourceTypeEnum.toString()
                if (enumName == "CAMERA_ROLL") {
                    context.log.debug("CAMERA_ROLL detected, enabling native splitting")
                    param.setResult(true)
                    return@hook
                }

                // Method 2: Check by ordinal value (fallback)
                // The enum field 'a' stores the ordinal value
                val ordinalField = sourceTypeEnum::class.java
                    .getDeclaredField("a")
                    .apply { isAccessible = true }
                val ordinalValue = ordinalField.get(sourceTypeEnum) as? Int

                if (ordinalValue == 11) { // 11 = CAMERA_ROLL
                    context.log.debug("CAMERA_ROLL detected (ordinal=11), enabling native splitting")
                    param.setResult(true)
                    return@hook
                }

            } catch (e: Exception) {
                context.log.error("Error in CAMERA_ROLL source type hook", e)
                // Don't setResult - let original method handle it on error
            }
        }

        context.log.info("CameraRollSourceOverride initialized - native splitting enabled for CAMERA_ROLL")
    }
}
