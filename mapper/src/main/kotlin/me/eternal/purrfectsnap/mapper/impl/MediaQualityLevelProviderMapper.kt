package me.eternal.purrfectsnap.mapper.impl

import com.android.tools.smali.dexlib2.AccessFlags
import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ext.getClassName
import me.eternal.purrfectsnap.mapper.ext.hasStaticConstructorString
import me.eternal.purrfectsnap.mapper.ext.isAbstract
import me.eternal.purrfectsnap.mapper.ext.isEnum

class MediaQualityLevelProviderMapper : AbstractClassMapper("MediaQualityLevelProvider") {
    val mediaQualityLevelProvider = classReference("mediaQualityLevelProvider")
    val mediaQualityLevelProviderMethod = string("mediaQualityLevelProviderMethod")

    init {
        var enumQualityLevel : String? = null

        mapper {
            for (enumClass in classes) {
                if (!enumClass.isEnum()) continue

                if (enumClass.hasStaticConstructorString("LEVEL_MAX")) {
                    enumQualityLevel = enumClass.getClassName()
                    break;
                }
            }
        }

        mapper {
            if (enumQualityLevel == null) return@mapper

            for (clazz in classes) {
                if (!clazz.isAbstract()) continue
                if (clazz.fields.none { it.accessFlags and AccessFlags.TRANSIENT.value != 0 }) continue

                clazz.methods.firstOrNull { it.returnType == "L$enumQualityLevel;" }?.let {
                    mediaQualityLevelProvider.set(clazz.getClassName())
                    mediaQualityLevelProviderMethod.set(it.name)
                    return@mapper
                }
            }
        }
    }
}
