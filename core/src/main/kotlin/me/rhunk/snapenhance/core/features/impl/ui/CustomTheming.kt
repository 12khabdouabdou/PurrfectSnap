package me.rhunk.snapenhance.core.features.impl.ui

import android.content.res.TypedArray
import android.util.TypedValue
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField

class CustomTheming : Feature("Custom Theming") {

    // If you later want to limit to AMOLEDED attributes, record their IDs here
    private val amoledBlack = 0xFF000000.toInt()
    private val colorTypes = setOf(
        TypedValue.TYPE_INT_COLOR_ARGB8,
        TypedValue.TYPE_INT_COLOR_RGB8,
        TypedValue.TYPE_INT_COLOR_ARGB4,
        TypedValue.TYPE_INT_COLOR_RGB4
    )

    override fun init() {
        if (!context.config.userInterface.forceAmoledTheme.get()) return

        onNextActivityCreate {
            context.androidContext.theme.javaClass.getMethod("obtainStyledAttributes", IntArray::class.java).hook(
                HookStage.AFTER
            ) { param ->
                val array = param.arg<IntArray>(0)
                val result = param.getResult() as TypedArray
                val typedArrayData = result.getObjectField("mData") as IntArray

                // Always patch first attribute if color type
                val type = result.getType(0)
                if (type in colorTypes) {
                    val attrId = array[0]
                    val original = typedArrayData[1]
                    typedArrayData[1] = amoledBlack // Patch to true black
                    context.log.info(
                        "CustomTheming: Patched attrId 0x${attrId.toString(16)} from original 0x${original.toString(16)} to AMOLED black (0x${amoledBlack.toString(16)})"
                    )
                } else if (type == TypedValue.TYPE_STRING) {
                    val stringValue = result.getString(0)
                    if (stringValue?.endsWith(".xml") == true) {
                        typedArrayData[0] = TypedValue.TYPE_INT_COLOR_ARGB4 // STYLE_TYPE
                        typedArrayData[1] = amoledBlack // STYLE_DATA
                        typedArrayData[5] = 0 // STYLE_DENSITY
                        val attrId = array[0]
                        context.log.info(
                            "CustomTheming: Patched XML drawable attrId 0x${attrId.toString(16)} to AMOLED black (0x${amoledBlack.toString(16)})"
                        )
                    }
                } else {
                    val attrId = array[0]
                    context.log.warn(
                        "CustomTheming: Unknown attribute type: ${type.toString(16)} for attrId 0x${attrId.toString(16)}"
                    )
                }
            }
        }
    }
}
