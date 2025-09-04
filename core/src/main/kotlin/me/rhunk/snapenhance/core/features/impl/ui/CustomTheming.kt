package me.rhunk.snapenhance.core.features.impl.ui

import android.content.res.TypedArray
import android.util.TypedValue
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField

class CustomTheming : Feature("Custom Theming") {

    // Brute-force search: returns the first attrId in range matching the value/type
    private fun findAttrIdByThemeValue(
        expectedValue: Int,
        valueType: Int = TypedValue.TYPE_INT_COLOR_ARGB8,
        logLabel: String,
        searchRange: IntRange = 0x7f040000..0x7f04ffff // Common attr range, adjust as needed
    ): Int? {
        val typedValue = TypedValue()
        for (attrId in searchRange) {
            try {
                val found = context.androidContext.theme.resolveAttribute(attrId, typedValue, true)
                if (found && typedValue.type == valueType && typedValue.data == expectedValue) {
                    context.log.info("CustomTheming: FOUND $logLabel at attrId=0x${attrId.toString(16)} (value=0x${expectedValue.toString(16)})")
                    return attrId
                }
            } catch (e: Exception) {
                // Some values may throw, safe to ignore
            }
        }
        context.log.warn("CustomTheming: $logLabel with value 0x${expectedValue.toString(16)} NOT found in attrId range searched.")
        return null
    }

    override fun init() {
        if (!context.config.userInterface.forceAmoledTheme.get()) return

        // List of desired AMOLED theme attributes: (label, value, TypedValue.TYPE_...)
        val wantedAttrs = listOf(
            Triple("sigColorTextPrimary", 0xFFFFFFFF.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("sigColorChatChat", 0xFFFFFFFF.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("sigColorChatPendingSending", 0xFFFFFFFF.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("sigColorChatSnapWithSound", 0xFFFFFFFF.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("sigColorChatSnapWithoutSound", 0xFFFFFFFF.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("sigColorBackgroundMain", 0xFF000000.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("sigColorBackgroundSurface", 0xFF000000.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("listDivider", 0xFF000000.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("actionSheetBackgroundDrawable", 0xFF000000.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("actionSheetRoundedBackgroundDrawable", 0xFF000000.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8),
            Triple("sigExceptionColorCameraGridLines", 0xFF000000.toInt(), TypedValue.TYPE_INT_COLOR_ARGB8)
        )

        // Brute force search for attribute IDs at launch
        val currentTheme = wantedAttrs.mapNotNull { (label, value, type) ->
            val id = findAttrIdByThemeValue(
                expectedValue = value,
                valueType = type,
                logLabel = label,
                searchRange = 0x7f040000..0x7f04ffff // Make this tighter if you know your app's attr range
            )
            if (id != null) id to value else null
        }.toMap()

        onNextActivityCreate {
            if (currentTheme.isEmpty()) {
                context.log.warn("CustomTheming: No AMOLED-related attrId could be found; theming will not be applied!")
                return@onNextActivityCreate
            }

            context.androidContext.theme.javaClass.getMethod("obtainStyledAttributes", IntArray::class.java).hook(
                HookStage.AFTER
            ) { param ->
                val array = param.arg<IntArray>(0)
                val customColor = (currentTheme[array[0]] as? Number)?.toInt() ?: return@hook
                val result = param.getResult() as TypedArray
                val typedArrayData = result.getObjectField("mData") as IntArray
                when (val attributeType = result.getType(0)) {
                    TypedValue.TYPE_INT_COLOR_ARGB8,
                    TypedValue.TYPE_INT_COLOR_RGB8,
                    TypedValue.TYPE_INT_COLOR_ARGB4,
                    TypedValue.TYPE_INT_COLOR_RGB4 -> {
                        typedArrayData[1] = customColor // index + STYLE_DATA
                        context.log.info("CustomTheming: Patched attribute 0x${array[0].toString(16)} (type=color) with 0x${customColor.toString(16)}")
                    }
                    TypedValue.TYPE_STRING -> {
                        val stringValue = result.getString(0)
                        if (stringValue?.endsWith(".xml") == true) {
                            typedArrayData[0] = TypedValue.TYPE_INT_COLOR_ARGB4 // STYLE_TYPE
                            typedArrayData[1] = customColor // STYLE_DATA
                            typedArrayData[5] = 0 // STYLE_DENSITY
                            context.log.info("CustomTheming: Patched attribute 0x${array[0].toString(16)} (type=xml drawable) with 0x${customColor.toString(16)}")
                        }
                    }
                    else -> context.log.warn("CustomTheming: Unknown attribute type: ${attributeType.toString(16)} for attr 0x${array[0].toString(16)}")
                }
            }
        }
    }
}
