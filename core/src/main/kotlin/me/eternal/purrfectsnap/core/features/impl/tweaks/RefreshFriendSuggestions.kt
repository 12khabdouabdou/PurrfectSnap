package me.eternal.purrfectsnap.core.features.impl.tweaks

import me.eternal.purrfectsnap.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook

class RefreshFriendSuggestions : Feature("Refresh Friend Suggestions") {
    override fun init() {
        listOf("Y26", "y26").forEach { className ->
            listOf("m34803a", "mo81d").forEach { methodName ->
                runCatching {
                    findClass(className).hook(methodName, HookStage.AFTER) { param ->
                        (param.getResult() as? MutableMap<String, String>)?.apply {
                            put("_t", System.currentTimeMillis().toString())
                            put("limit", "100")
                        }
                    }
                }.onFailure {}
            }
        }

        listOf("Hl3", "HL3", "nh3").forEach { className ->
            listOf("m13680e2", "m58660d3").forEach { methodName ->
                runCatching {
                    findClass(className).hook(methodName, HookStage.BEFORE) { param ->
                        if (param.arg<Int>(1) == 10) {
                            param.setArg(1, 100)
                        }
                    }
                }.onFailure {}
            }
        }

        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            if (event.url.contains("suggest_friend")) {
                val separator = if (event.url.contains("?")) "&" else "?"
                event.url += "${separator}_t=${System.currentTimeMillis()}&limit=100"
            }
        }
    }
}

