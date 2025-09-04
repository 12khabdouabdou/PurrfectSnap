package me.rhunk.snapenhance.core.features.impl.ui

import android.content.res.TypedArray
import android.util.TypedValue
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField

class CustomTheming : Feature("Custom Theming") {
    private val amoledBlack = 0xFF000000.toInt()
    private val onlyPatchId = 0x7f0404b8 // The attrId you want to patch for AMOLED

    private val colorTypes = setOf(
        TypedValue.TYPE_INT_COLOR_ARGB8,
        TypedValue.TYPE_INT_COLOR_RGB8,
        TypedValue.TYPE_INT_COLOR_ARGB4,
        TypedValue.TYPE_INT_COLOR_RGB4
    )

    override fun init() {
        if (!context.config.userInterface.forceAmoledTheme.get()) return

        onNextActivityCreate {
            context.androidContext.theme.javaClass
                .getMethod("obtainStyledAttributes", IntArray::class.java)
                .hook(HookStage.AFTER) { param ->
                    val array = param.arg<IntArray>(0)
                    val attrId = array[0]
                    val result = param.getResult() as TypedArray
                    val typedArrayData = result.getObjectField("mData") as IntArray
                    val type = result.getType(0)
                    if (type in colorTypes && attrId == onlyPatchId) {
                        typedArrayData[1] = amoledBlack
                        context.log.error(
                            "[AMOLED PATCH] Patched ONLY attrId 0x${attrId.toString(16)} to AMOLED black"
                        )
                    }
                }
        }
    }
}
