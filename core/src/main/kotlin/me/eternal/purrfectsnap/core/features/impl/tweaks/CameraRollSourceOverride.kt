package me.eternal.purrfectsnap.core.features.impl.tweaks

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.mapper.impl.ChunkingDecisionMapper
import java.lang.reflect.Method

class CameraRollSourceOverride : Feature("Camera Roll Source Override") {
    override fun init() {
        context.log.info("CameraRollSourceOverride: === Initializing ===")

        if (!context.config.global.enableNativeCameraRollSplitting.get()) {
            context.log.warn("CameraRollSourceOverride: Feature DISABLED in config, skipping")
            return
        }

        context.log.info("CameraRollSourceOverride: Feature ENABLED, loading mappings...")

        context.mappings.useMapper(ChunkingDecisionMapper::class) {
            val enumClass = sourceTypeEnumClass.getAsClass()
            val decisionClass = chunkingDecisionClass.getAsClass()
            val decisionMethodName = chunkingDecisionMethod.getAsString()
            val selectorClass = uploadPathSelectorClass.getAsClass()

            context.log.info("CameraRollSourceOverride: Mapping results:")
            context.log.info(" - sourceTypeEnumClass = ${sourceTypeEnumClass.getAsString()}")
            context.log.info(" - chunkingDecisionClass = ${chunkingDecisionClass.getAsString()}")
            context.log.info(" - chunkingDecisionMethod = $decisionMethodName")
            context.log.info(" - uploadPathSelectorClass = ${uploadPathSelectorClass.getAsString()}")

            if (enumClass == null) {
                context.log.error("CameraRollSourceOverride: sourceTypeEnumClass mapping FAILED - cannot proceed")
                return@useMapper
            }

            val cameraRollEnum = enumClass.enumConstants?.firstOrNull { it.toString() == "CAMERA_ROLL" }
            val galleryEnum = enumClass.enumConstants?.firstOrNull { it.toString() == "GALLERY" }
            val galleryStoryEnum = enumClass.enumConstants?.firstOrNull { it.toString() == "GALLERY_STORY" }
            val cameraEnum = enumClass.enumConstants?.firstOrNull { it.toString() == "CAMERA" }

            context.log.info("CameraRollSourceOverride: Enum values resolved:")
            context.log.info(" - CAMERA_ROLL = $cameraRollEnum (ordinal=${(cameraRollEnum as? Enum<*>)?.ordinal})")
            context.log.info(" - GALLERY = $galleryEnum (ordinal=${(galleryEnum as? Enum<*>)?.ordinal})")
            context.log.info(" - GALLERY_STORY = $galleryStoryEnum (ordinal=${(galleryStoryEnum as? Enum<*>)?.ordinal})")
            context.log.info(" - CAMERA = $cameraEnum (ordinal=${(cameraEnum as? Enum<*>)?.ordinal})")
            context.log.info("CameraRollSourceOverride: All enum constants = ${enumClass.enumConstants?.joinToString()}")

            if (cameraRollEnum == null || galleryEnum == null) {
                context.log.error("CameraRollSourceOverride: Failed to resolve CAMERA_ROLL or GALLERY enum values")
                context.log.error("CameraRollSourceOverride: Available constants = ${enumClass.enumConstants?.joinToString()}")
                return@useMapper
            }

            if (decisionClass != null && decisionMethodName != null) {
                installHook1(decisionClass, decisionMethodName, enumClass, cameraRollEnum)
            } else {
                context.log.warn("CameraRollSourceOverride: chunkingDecision mapping FAILED - skipping Hook 1")
            }

            if (selectorClass != null) {
                installHook2(selectorClass, enumClass, cameraRollEnum, galleryEnum)
            } else {
                context.log.warn("CameraRollSourceOverride: uploadPathSelector mapping FAILED - skipping Hook 2")
            }
        }

        context.log.info("CameraRollSourceOverride: === Initialization complete ===")
    }

    private fun installHook1(
        decisionClass: Class<*>,
        decisionMethodName: String,
        enumClass: Class<*>,
        cameraRollEnum: Any
    ) {
        context.log.info("CameraRollSourceOverride: [Hook 1] Installing on ${decisionClass.name}.$decisionMethodName()")

        decisionClass.hook(decisionMethodName, HookStage.BEFORE) { param ->
            try {
                val sourceType = param.argNullable<Any>(1)
                if (sourceType == null) {
                    context.log.verbose("CameraRollSourceOverride: [Hook 1] source type arg is null")
                    return@hook
                }

                val enumName = sourceType.toString()
                val enumOrdinal = (sourceType as? Enum<*>)?.ordinal

                context.log.verbose("CameraRollSourceOverride: [Hook 1] triggered: sourceType=$enumName ordinal=$enumOrdinal")

                if (sourceType == cameraRollEnum) {
                    context.log.info("CameraRollSourceOverride: [Hook 1] CAMERA_ROLL detected -> forcing chunking ENABLED (setResult true)")
                    param.setResult(true)
                }
            } catch (e: Exception) {
                context.log.error("CameraRollSourceOverride: [Hook 1] ERROR: ${e.javaClass.simpleName}: ${e.message}")
                val method = param.method() as Method
                context.log.error("CameraRollSourceOverride: [Hook 1] method signature: ${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }}): ${method.returnType.simpleName}")
            }
        }

        context.log.info("CameraRollSourceOverride: [Hook 1] Installed successfully")
    }

    private fun installHook2(
        selectorClass: Class<*>,
        enumClass: Class<*>,
        cameraRollEnum: Any,
        galleryEnum: Any
    ) {
        context.log.info("CameraRollSourceOverride: [Hook 2] Scanning ${selectorClass.name} for methods with source type enum params...")

        val methodsWithEnum = selectorClass.declaredMethods.filter { method ->
            method.parameterTypes.contains(enumClass)
        }

        context.log.info("CameraRollSourceOverride: [Hook 2] Found ${methodsWithEnum.size} methods with source type enum param:")
        methodsWithEnum.forEach { method ->
            context.log.info("CameraRollSourceOverride: [Hook 2]   - ${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }}): ${method.returnType.simpleName}")
        }

        if (methodsWithEnum.isEmpty()) {
            context.log.warn("CameraRollSourceOverride: [Hook 2] No methods with source type enum found - trying all public methods")
            return
        }

        methodsWithEnum.forEach { method ->
            context.log.info("CameraRollSourceOverride: [Hook 2] Hooking ${method.name}...")

            try {
                selectorClass.hook(method.name, HookStage.BEFORE) { param ->
                    try {
                        for (i in 0 until method.parameterCount) {
                            val arg = param.argNullable<Any>(i) ?: continue
                            if (arg == cameraRollEnum) {
                                context.log.info("CameraRollSourceOverride: [Hook 2] ${method.name}: arg[$i] was CAMERA_ROLL -> replacing with GALLERY")
                                param.setArg(i, galleryEnum)
                                context.log.verbose("CameraRollSourceOverride: [Hook 2] ${method.name}: arg[$i] is now ${param.arg<Any>(i)}")
                            }
                        }
                    } catch (e: Exception) {
                        context.log.error("CameraRollSourceOverride: [Hook 2] ERROR in ${method.name}: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                context.log.error("CameraRollSourceOverride: [Hook 2] Failed to hook ${method.name}: ${e.message}")
            }
        }

        context.log.info("CameraRollSourceOverride: [Hook 2] Installed successfully on ${methodsWithEnum.size} methods")
    }
}
