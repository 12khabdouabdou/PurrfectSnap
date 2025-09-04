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
        val amoledThemeConfig = context.config.userInterface.forceAmoledTheme
        if (amoledThemeConfig.globalState != true) return

        onNextActivityCreate {
            context.androidContext.theme.javaClass.getMethod("obtainStyledAttributes", IntArray::class.java).hook(
                HookStage.AFTER
            ) { param ->
                val array = param.arg<IntArray>(0)
                val result = param.getResult() as TypedArray
                val typedArrayData = result.getObjectField("mData") as IntArray

                val attrId = array[0]
                val attrName = try {
                    context.androidContext.resources.getResourceEntryName(attrId)
                } catch (e: Exception) {
                    return@hook
                }

                if (amoledThemeConfig.properties.find { it.key.name == attrName }?.value?.get() != true) return@hook

                val type = result.getType(0)
                if (type in colorTypes) {
                    val original = typedArrayData[1]
                    typedArrayData[1] = amoledBlack // Patch to true black
                    context.log.info(
                        "CustomTheming: Patched attrId 0x${attrId.toString(16)} ($attrName) from original 0x${original.toString(16)} to AMOLED black (0x${amoledBlack.toString(16)})"
                    )
                } else if (type == TypedValue.TYPE_STRING) {
                    val stringValue = result.getString(0)
                    if (stringValue?.endsWith(".xml") == true) {
                        typedArrayData[0] = TypedValue.TYPE_INT_COLOR_ARGB4 // STYLE_TYPE
                        typedArrayData[1] = amoledBlack // STYLE_DATA
                        typedArrayData[5] = 0 // STYLE_DENSITY
                        context.log.info(
                            "CustomTheming: Patched XML drawable attrId 0x${attrId.toString(16)} ($attrName) to AMOLED black (0x${amoledBlack.toString(16)})"
                        )
                    }
                }
            }
        }
    }
}
