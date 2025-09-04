package me.rhunk.snapenhance.core.features.impl.ui

import android.content.res.TypedArray
import android.util.TypedValue
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getIdentifier
import me.rhunk.snapenhance.core.util.ktx.getObjectField

class CustomTheming: Feature("Custom Theming") {
    private fun getAttribute(name: String): Int {
        return context.resources.getIdentifier(name, "attr")
    }

    private fun parseAttributeList(vararg attributes: Pair<String, Number>): Map<Int, Int> {
        return attributes.toMap().mapKeys {
            getAttribute(it.key)
        }.filterKeys { it != 0 }.mapValues {
            it.value.toInt()
        }
    }

    override fun init() {
        if (!context.config.userInterface.forceAmoledTheme.get()) return

        val currentTheme = parseAttributeList(
            "sigColorTextPrimary" to 0xFFFFFFFF,
            "sigColorChatChat" to 0xFFFFFFFF,
            "sigColorChatPendingSending" to 0xFFFFFFFF,
            "sigColorChatSnapWithSound" to 0xFFFFFFFF,
            "sigColorChatSnapWithoutSound" to 0xFFFFFFFF,
            "sigColorBackgroundMain" to 0xFF000000,
            "sigColorBackgroundSurface" to 0xFF000000,
            "listDivider" to 0xFF000000,
            "actionSheetBackgroundDrawable" to 0xFF000000,
            "actionSheetRoundedBackgroundDrawable" to 0xFF000000,
            "sigExceptionColorCameraGridLines" to 0xFF000000,
        )

        onNextActivityCreate {
            if (currentTheme.isEmpty()) return@onNextActivityCreate

            context.androidContext.theme.javaClass.getMethod("obtainStyledAttributes", IntArray::class.java).hook(
                HookStage.AFTER) { param ->
                val array = param.arg<IntArray>(0)
                val customColor = (currentTheme[array[0]] as? Number)?.toInt() ?: return@hook

                val result = param.getResult() as TypedArray
                val typedArrayData = result.getObjectField("mData") as IntArray

                when (val attributeType = result.getType(0)) {
                    TypedValue.TYPE_INT_COLOR_ARGB8, TypedValue.TYPE_INT_COLOR_RGB8, TypedValue.TYPE_INT_COLOR_ARGB4, TypedValue.TYPE_INT_COLOR_RGB4 -> {
                        typedArrayData[1] = customColor // index + STYLE_DATA
                    }
                    TypedValue.TYPE_STRING -> {
                        val stringValue = result.getString(0)
                        if (stringValue?.endsWith(".xml") == true) {
                            typedArrayData[0] = TypedValue.TYPE_INT_COLOR_ARGB4 // STYLE_TYPE
                            typedArrayData[1] = customColor // STYLE_DATA
                            typedArrayData[5] = 0; // STYLE_DENSITY
                        }
                    }
                    else -> context.log.warn("unknown attribute type: ${attributeType.toString(16)}")
                }
            }
        }
    }
}
