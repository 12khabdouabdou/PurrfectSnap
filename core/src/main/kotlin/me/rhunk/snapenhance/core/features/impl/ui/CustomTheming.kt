package me.rhunk.snapenhance.core.features.impl.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.TypedArray
import android.util.TypedValue
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
    private val candidateAttrIds = arrayOf(
        0x7f04011c, 0x7f040311, 0x7f04030d, 0x7f040400, 0x7f040401,
        0x7f0406fd, 0x7f0403e1, 0x7f0403e2, 0x7f0404ce, 0x7f04055d
    )

    private val prefsKey = "snapenhance_amoled_attr"
    private val prefsIndex = "current_index"
    private lateinit var prefs: SharedPreferences
    private var currentIndex = 0
        set(value) {
            field = value
            prefs.edit().putInt(prefsIndex, value).apply()
        }

    override fun init() {
        if (!context.config.userInterface.forceAmoledTheme.get()) return

        prefs = context.androidContext.getSharedPreferences(prefsKey, Context.MODE_PRIVATE)
        currentIndex = prefs.getInt(prefsIndex, 0)

        // Cycle command
        val cycleFilter = IntentFilter("me.rhunk.snapenhance.CYCLE_AMOLED_ATTR")
        context.androidContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    currentIndex = (currentIndex + 1) % candidateAttrIds.size
                    val patchingNow = candidateAttrIds[currentIndex]
                    context.log.error(
                        "[AMOLED DEBUG CYCLE] Now patching attrId: 0x${patchingNow.toString(16)} (index ${currentIndex + 1}/${candidateAttrIds.size})"
                    )
                }
            },
            cycleFilter,
            Context.RECEIVER_EXPORTED
        )

        // Reset command
        val resetFilter = IntentFilter("me.rhunk.snapenhance.RESET_AMOLED_ATTR")
        context.androidContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    currentIndex = 0
                    val patchingNow = candidateAttrIds[currentIndex]
                    context.log.error(
                        "[AMOLED DEBUG RESET] Reset attrId cycling to FIRST id: 0x${patchingNow.toString(16)} (index 1/${candidateAttrIds.size})"
                    )
                }
            },
            resetFilter,
            Context.RECEIVER_EXPORTED
        )

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
                    context.log.error(
                        "[AMOLED DEBUG PATCH] Patched ONLY attrId 0x${attrId.toString(16)} at index $currentIndex to AMOLED black"
                    )
                }
            }
        }
    }
}
