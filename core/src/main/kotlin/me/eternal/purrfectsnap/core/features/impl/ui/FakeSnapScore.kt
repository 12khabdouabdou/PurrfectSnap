package me.eternal.purrfectsnap.core.features.impl.ui

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class FakeSnapScore : Feature("Fake Snap Score") {
    override fun init() {
        if (context.config.userInterface.spoofSnapScore.globalState != true) return

        val customScoreRaw = context.config.userInterface.spoofSnapScore.customSnapScore.getNullable()?.trim()?.takeIf { it.isNotBlank() }
            ?: return

        val customScore = customScoreRaw.replace(Regex("[^0-9]"), "")
        if (customScore.isEmpty()) return

        onNextActivityCreate {
            android.widget.TextView::class.java.hook("setText", HookStage.BEFORE) { param ->
                val text = param.argNullable<CharSequence>(0)?.toString() ?: return@hook
                
                if (text.matches(Regex("^[0-9\\s,.]+$"))) {
                    val digits = text.replace(Regex("[^0-9]"), "")
                    
                    if (digits.length >= 4 || text.contains(",")) {
                        val textView = param.thisObject() as android.widget.TextView
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
                            textView.ellipsize = null
                            textView.setSingleLine(false)
                        }
                    }
                }
            }
        }
    }
}
