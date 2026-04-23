package me.eternal.purrfectsnap.mapper.impl

import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ext.getAllConstStrings
import me.eternal.purrfectsnap.mapper.ext.getClassName
import me.eternal.purrfectsnap.mapper.ext.getSuperClassName
import me.eternal.purrfectsnap.mapper.ext.isAbstract
import me.eternal.purrfectsnap.mapper.ext.isEnum
import me.eternal.purrfectsnap.mapper.ext.isFinal
import me.eternal.purrfectsnap.mapper.ext.isInterface

class MemoriesTwoChatMediaDrawerMapper : AbstractClassMapper("MemoriesTwoChatMediaDrawer") {
    val memoriesTwoDrawerClass = classReference("memoriesTwoDrawerClass")
    val memoriesTwoActionHandlerClass = classReference("memoriesTwoActionHandlerClass")
    val memoriesTwoSendItemsMethodName = string("memoriesTwoSendItemsMethodName")
    val memoriesTwoPickerResultClass = classReference("memoriesTwoPickerResultClass")
    val memTwoDataEntityTypeClass = classReference("memTwoDataEntityTypeClass")
    val memTwoDataEntityClass = classReference("memTwoDataEntityClass")
    val memoriesTwoPickerMultiCallbacksClass = classReference("memoriesTwoPickerMultiCallbacksClass")
    val memoriesTwoActionHandlerImplClass = classReference("memoriesTwoActionHandlerImplClass")
    val memoriesTwoPickerMultiCallbacksImplClass = classReference("memoriesTwoPickerMultiCallbacksImplClass")

    init {
        mapper {
            val drawerClass = classes.firstOrNull { clazz ->
                val name = clazz.getClassName()
                val superName = clazz.getSuperClassName() ?: return@firstOrNull false
                name.contains("MemoriesTwoChatMediaDrawer") &&
                    !name.contains("ActionHandler") &&
                    !name.contains("EditLauncher") &&
                    superName.contains("ValdiGeneratedRootView")
            } ?: return@mapper

            memoriesTwoDrawerClass.set(drawerClass.getClassName().replace("/", "."))

            val actionHandlerClazz = classes.firstOrNull { clazz ->
                clazz.getClassName().contains("MemoriesTwoChatMediaDrawerActionHandler")
            } ?: return@mapper

            memoriesTwoActionHandlerClass.set(actionHandlerClazz.getClassName().replace("/", "."))

            val sendItemsMethod = actionHandlerClazz.methods.firstOrNull { method ->
                method.name == "sendItems" && method.parameterTypes.size == 1
            } ?: actionHandlerClazz.methods.firstOrNull { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].startsWith("Ljava/util/")
            } ?: return@mapper

            memoriesTwoSendItemsMethodName.set(sendItemsMethod.name)

            val pickerResultClazz = classes.firstOrNull { clazz ->
                clazz.getClassName().contains("MemoriesTwoPickerResult")
            }
            pickerResultClazz?.let { memoriesTwoPickerResultClass.set(it.getClassName().replace("/", ".")) }

            val dataEntityClazz = classes.firstOrNull { clazz ->
                clazz.isInterface() && clazz.getClassName().contains("MemTwoDataEntity") && !clazz.getClassName().contains("Id") && !clazz.getClassName().contains("Subtype") && !clazz.getClassName().contains("Status") && !clazz.getClassName().contains("MutableFields") && !clazz.getClassName().contains("SnapDocActionType") && !clazz.getClassName().contains("MediaContentState")
            }
            dataEntityClazz?.let { memTwoDataEntityClass.set(it.getClassName().replace("/", ".")) }

            val entityTypeClazz = classes.firstOrNull { clazz ->
                clazz.isEnum() && clazz.getClassName().contains("MemTwoDataEntityType")
            } ?: classes.firstOrNull { clazz ->
                clazz.isEnum() && runCatching {
                    clazz.methods.any { method ->
                        method.name == "values" || method.name == "valueOf"
                    } && clazz.fields.count { it.type == clazz.type } >= 3
                }.getOrDefault(false) && runCatching {
                    val staticInit = clazz.methods.firstOrNull { it.name == "<clinit>" }
                    staticInit?.implementation?.getAllConstStrings()
                        ?.any { it == "CAMERA_ROLL" || it == "SNAP" } == true
                }.getOrDefault(false)
            }
            entityTypeClazz?.let { memTwoDataEntityTypeClass.set(it.getClassName().replace("/", ".")) }

        val callbacksClazz = classes.firstOrNull { clazz ->
            clazz.isInterface() && clazz.getClassName().contains("MemoriesTwoPickerMultiCallbacks")
        }
        callbacksClazz?.let { memoriesTwoPickerMultiCallbacksClass.set(it.getClassName().replace("/", ".")) }

        val actionHandlerImplClazz = classes.firstOrNull { clazz ->
            !clazz.isInterface() && !clazz.isAbstract() &&
                clazz.interfaces.any { it.contains("MemoriesTwoChatMediaDrawerActionHandler") }
        }
        actionHandlerImplClazz?.let { memoriesTwoActionHandlerImplClass.set(it.getClassName().replace("/", ".")) }

        val callbacksImplClazz = classes.firstOrNull { clazz ->
            !clazz.isInterface() && !clazz.isAbstract() && clazz.isFinal() &&
                clazz.interfaces.any { it.contains("MemoriesTwoPickerMultiCallbacks") } &&
                clazz.methods.size > 5
        }
        callbacksImplClazz?.let { memoriesTwoPickerMultiCallbacksImplClass.set(it.getClassName().replace("/", ".")) }
        }
    }
}
