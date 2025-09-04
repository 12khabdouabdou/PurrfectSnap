package me.rhunk.snapenhance.core.features.impl.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.TypedArray
import android.util.TypedValue
import android.widget.Toast
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField

class CustomTheming : Feature("Custom Theming") {

    private val amoledBlack = 0xFF000000.toInt()
    private val colorTypes = setOf(
        TypedValue.TYPE_INT_COLOR_ARGB8,
        TypedValue.TYPE_INT_COLOR_RGB8,
        TypedValue.TYPE_INT_COLOR_ARGB4,
        TypedValue.TYPE_INT_COLOR_RGB4
    )

    // All unique attrIds found in your logs for extensive debugging/cycling
    private val candidateAttrIds = arrayOf(
        0x7f04054d, 0x1010433, 0x7f04013b, 0x7f0405b2, 0x1010036, 0x101009b, 0x7f040148,
        0x7f040517, 0x7f04051a, 0x7f04051b, 0x7f040584, 0x7f040519, 0x7f0405b3, 0x7f0405b5,
        0x7f040233, 0x7f040234, 0x7f040236, 0x7f04054c, 0x7f0404b8, 0x7f04054b, 0x7f0405a4,
        0x7f0405a1, 0x7f040124, 0x7f040557, 0x7f04056e, 0x7f040110, 0x7f0405a5, 0x7f040134,
        0x7f04011c, 0x7f040311, 0x7f04030d, 0x7f040400, 0x7f040401, 0x7f0406fd, 0x7f0403e1,
        0x7f0403e2, 0x7f0404ce, 0x7f04055d
        // If you find more, just append the hex IDs here.
    )

    private var currentIndex = 0

    override fun init() {
        if (!context.config.userInterface.forceAmoledTheme.get()) return

        // Broadcast receiver to live-cycle through attrIds using ADB
        val filter = IntentFilter("me.rhunk.snapenhance.CYCLE_AMOLED_ATTR")
        context.androidContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                currentIndex = (currentIndex + 1) % candidateAttrIds.size
                val patchingNow = candidateAttrIds[currentIndex]
                Toast.makeText(context.androidContext, "Now patching attrId: 0x${patchingNow.toString(16)}", Toast.LENGTH_SHORT).show()
                context.log.info("AMOLED DEBUG: Now patching attrId: 0x${patchingNow.toString(16)} [${currentIndex + 1}/${candidateAttrIds.size}]")
            }
        }, filter)

        onNextActivityCreate {
            context.androidContext.theme.javaClass.getMethod("obtainStyledAttributes", IntArray::class.java).hook(
                HookStage.AFTER
            ) { param ->
                val array = param.arg<IntArray>(0)
                val attrId = array[0]
                val result = param.getResult() as TypedArray
                val typedArrayData = result.getObjectField("mData") as IntArray
                val type = result.getType(0)
                val patchingNow = candidateAttrIds[currentIndex]
                if (type in colorTypes && attrId == patchingNow) {
                    typedArrayData[1] = amoledBlack
                    Toast.makeText(context.androidContext,
                        "Patched attrId: 0x${attrId.toString(16)} to BLACK (index ${currentIndex + 1}/${candidateAttrIds.size})",
                        Toast.LENGTH_SHORT
                    ).show()
                    context.log.info("AMOLED DEBUG: Patched ONLY attrId 0x${attrId.toString(16)} at index $currentIndex to AMOLED black")
                }
            }
        }
    }
}
