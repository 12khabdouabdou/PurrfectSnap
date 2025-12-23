package me.eternal.purrfectsnap.core.features.impl.ui

import android.content.res.TypedArray
import android.util.TypedValue
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.getObjectField

class CustomTheming : Feature("Custom Theming") {
    private val amoledBlack = 0xFF000000.toInt()

    private val legacyKnownAttrIds = setOf(
        0x7f0404b8 // older Snapchat builds
    )

    private val patchedAttrIds = hashSetOf<Int>()

    private val colorTypes = setOf(
        TypedValue.TYPE_INT_COLOR_ARGB8,
        TypedValue.TYPE_INT_COLOR_RGB8,
        TypedValue.TYPE_INT_COLOR_ARGB4,
        TypedValue.TYPE_INT_COLOR_RGB4
    )

    private fun isNearBlackOpaque(color: Int): Boolean {
        val alpha = (color ushr 24) and 0xFF
        if (alpha != 0xFF) return false
        val red = (color ushr 16) and 0xFF
        val green = (color ushr 8) and 0xFF
        val blue = color and 0xFF
        val avg = (red + green + blue) / 3
        return avg <= 0x20
    }

    private fun shouldPatch(attrId: Int, attrName: String?, originalColor: Int): Boolean {
        if (originalColor == amoledBlack) return false
        if (attrId in legacyKnownAttrIds) return true
        if (!isNearBlackOpaque(originalColor)) return false

        val name = (attrName ?: return true).lowercase()
        return listOf(
            "background",
            "surface",
            "container",
            "scrim",
            "overlay",
            "sheet",
            "panel"
        ).any { it in name }
    }

    override fun init() {
        if (!context.config.userInterface.forceAmoledTheme.get()) return

        onNextActivityCreate {
            context.androidContext.theme.javaClass
                .getMethod("obtainStyledAttributes", IntArray::class.java)
                .hook(HookStage.AFTER) { param ->
                    val array = param.arg<IntArray>(0)
                    val attrId = array[0]
                    val result = param.getResult() as TypedArray
                    val type = result.getType(0)
                    if (type !in colorTypes) return@hook

                    val originalColor = runCatching { result.getColor(0, Int.MIN_VALUE) }.getOrNull()
                        ?.takeIf { it != Int.MIN_VALUE }
                        ?: return@hook

                    val attrName = runCatching { context.androidContext.resources.getResourceEntryName(attrId) }.getOrNull()
                    val shouldPatch = attrId in patchedAttrIds || shouldPatch(attrId, attrName, originalColor)
                    if (!shouldPatch) return@hook

                    val typedArrayData = runCatching { result.getObjectField("mData") as IntArray }.getOrNull() ?: return@hook
                    if (typedArrayData.size < 2) return@hook

                    typedArrayData[1] = amoledBlack
                    if (patchedAttrIds.add(attrId)) {
                        context.log.verbose(
                            "[AMOLED PATCH] Patched attrId 0x${attrId.toString(16)} (${attrName ?: "unknown"}) from 0x${originalColor.toUInt().toString(16)} to AMOLED black"
                        )
                    }
                }
        }
    }
}
