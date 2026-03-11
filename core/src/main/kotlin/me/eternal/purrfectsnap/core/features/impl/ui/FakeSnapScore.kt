package me.eternal.purrfectsnap.core.features.impl.ui

import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class FakeSnapScore : Feature("Fake Snap Score") {
    override fun init() {
        if (context.config.userInterface.spoofSnapScore.globalState != true) return

        val customScoreRaw = context.config.userInterface.spoofSnapScore.customSnapScore.getNullable()?.trim() ?: return
        val customScore = try {
            val digitsOnly = customScoreRaw.replace(Regex("[^0-9]"), "")
            if (digitsOnly.isNotEmpty()) {
                val clampedVal = digitsOnly.toLong().coerceAtMost(9999999L)
                val formatted = StringBuilder()
                val reversed = clampedVal.toString().reversed()
                for (i in reversed.indices) {
                    formatted.append(reversed[i])
                    if ((i + 1) % 3 == 0 && i != reversed.lastIndex) {
                        formatted.append(",")
                    }
                }
                formatted.reverse().toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } ?: return

        onNextActivityCreate {
            TextView::class.java.hook("setText", HookStage.BEFORE) { param ->
                val text = param.argNullable<CharSequence>(0)?.toString() ?: return@hook
                
                if (text.matches(Regex("^[0-9\\s,.]+$"))) {
                    val digits = text.replace(Regex("[^0-9]"), "")
                    
                    if (digits.length >= 4 || text.contains(",")) {
                        val textView = param.thisObject() as TextView
                        var parent = textView.parent
                        var isProfile = false
                        
                        while (parent != null) {
                            val className = parent.javaClass.simpleName.lowercase()
                            if (className.contains("profile") || className.contains("identity")) {
                                isProfile = true
                                break
                            }
                            parent = parent.parent
                        }

                        if (isProfile) {
                            param.setArg(0, customScore)
                            
                            textView.post {
                                textView.layoutParams?.let { lp ->
                                    if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                                        textView.layoutParams = lp
                                    }
                                }
                                
                                val paint = textView.paint
                                val textWidth = paint.measureText(customScore).toInt() + textView.paddingLeft + textView.paddingRight
                                if (textView.minWidth < textWidth) {
                                    textView.minWidth = textWidth
                                }

                                var currentParent = textView.parent
                                while (currentParent is ViewGroup) {
                                    if (currentParent is ConstraintLayout) {
                                        currentParent.layoutParams?.let { plp ->
                                            if (plp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                                                plp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                                                currentParent.layoutParams = plp
                                            }
                                        }
                                    }
                                    currentParent = currentParent.parent
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
