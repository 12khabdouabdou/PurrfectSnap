package me.eternal.purrfectsnap.mapper.impl

import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ext.findConstString
import me.eternal.purrfectsnap.mapper.ext.getClassName
import me.eternal.purrfectsnap.mapper.ext.hasStaticConstructorString
import me.eternal.purrfectsnap.mapper.ext.isEnum
import java.lang.reflect.Modifier

class ChunkingDecisionMapper : AbstractClassMapper("ChunkingDecision") {
    val sourceTypeEnumClass = classReference("sourceTypeEnumClass")
    val chunkingDecisionClass = classReference("chunkingDecisionClass")
    val chunkingDecisionMethod = string("chunkingDecisionMethod")
    val uploadPathSelectorClass = classReference("uploadPathSelectorClass")

    init {
        var sourceTypeEnumType: String? = null

        mapper {
            for (clazz in classes) {
                if (!clazz.isEnum()) continue
                if (clazz.hasStaticConstructorString("CAMERA_ROLL") &&
                    clazz.hasStaticConstructorString("GALLERY") &&
                    clazz.hasStaticConstructorString("CAMERA")) {
                    sourceTypeEnumClass.set(clazz.getClassName())
                    sourceTypeEnumType = clazz.type
                    break
                }
            }
        }

        mapper {
            if (sourceTypeEnumType == null) return@mapper

            for (clazz in classes) {
                clazz.methods.firstOrNull { method ->
                    Modifier.isStatic(method.accessFlags) &&
                    method.parameterTypes.size == 3 &&
                    method.returnType == "Z" &&
                    method.parameterTypes[1] == sourceTypeEnumType
                }?.let {
                    chunkingDecisionClass.set(clazz.getClassName())
                    chunkingDecisionMethod.set(it.name)
                    return@mapper
                }
            }
        }

        mapper {
            if (sourceTypeEnumType == null) return@mapper

            for (clazz in classes) {
                val hasChunkUploadRef = clazz.methods.any { method ->
                    method.implementation?.findConstString("ChunkUploadMediaTransformer") == true
                }
                if (!hasChunkUploadRef) continue

                val hasEnumRef = clazz.methods.any { method ->
                    method.parameterTypes.contains(sourceTypeEnumType)
                }
                if (!hasEnumRef) continue

                uploadPathSelectorClass.set(clazz.getClassName())
                return@mapper
            }
        }
    }
}
