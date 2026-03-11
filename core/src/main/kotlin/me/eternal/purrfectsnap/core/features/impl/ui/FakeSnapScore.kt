package me.eternal.purrfectsnap.core.features.impl.ui

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class FakeSnapScore : Feature("Fake Snap Score") {
    override fun init() {
        if (context.config.userInterface.spoofSnapScore.globalState != true) return

        val customScoreRaw = context.config.userInterface.spoofSnapScore.customSnapScore.getNullable()?.trim()?.takeIf { it.isNotBlank() }
            ?: return

        val customScore = try {
            val digitsOnly = customScoreRaw.replace(Regex("[^0-9]"), "")
            if (digitsOnly.isNotEmpty()) {
                val clampedVal = digitsOnly.toLong().coerceAtMost(9999999L)
                java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(clampedVal)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } ?: return

        onNextActivityCreate {
            android.widget.TextView::class.java.hook("setText", HookStage.BEFORE) { param ->
                val text = param.argNullable<CharSequence>(0)?.toString() ?: return@hook
                
                if (text.matches(Regex("^[0-9\\s,.]+$"))) {
                    val digits = text.replace(Regex("[^0-9]"), "")
                    
                    if (digits.length >= 4 || text.contains(",")) {
                        var parent = (param.thisObject() as android.widget.TextView).parent
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
                        }
                    }
                }
            }
        }
    }
}
