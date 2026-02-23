package me.eternal.purrfectsnap.core.features.impl.messaging

import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.mapper.impl.ActiveStatusMapper
import me.eternal.purrfectsnap.core.wrapper.impl.FriendWrapper

/**
 * Active Sort Feature.
 *
 * This feature re-orders the friend list in SendTo and Bulk Messaging screens
 * to prioritize friends who were "Recently Active" (within the last 48 hours).
 */
class ActiveSort : Feature("ActiveSort") {

    enum class HookStatus {
        WORKING,
        MAPPER_FAILED,
        HOOK_FAILED,
        RUNTIME_ERROR
    }

    @Volatile
    var hookStatus: HookStatus = HookStatus.MAPPER_FAILED
        set(value) {
            field = value
            runCatching { context.bridgeClient.setActiveSortHookStatus(value.name) }
        }

    @Volatile
    var activeStatusMap: Map<String, Boolean>? = null

    var activeList: List<*> = emptyList<Any>()
    var inactiveList: List<*> = emptyList<Any>()

    override fun init() {
        // ── PHASE 1: Data Store Capture ───────────────────────────────
        context.mappings.useMapper(ActiveStatusMapper::class) {
            val dataStoreClazz = dataStoreClass.getAsClass()
            val cacheUpdateMethodName = cacheUpdateMethod.get()

            if (dataStoreClazz == null || cacheUpdateMethodName == null) {
                context.log.warn("ActiveSort: mapper failed — target discovery incomplete")
                hookStatus = HookStatus.MAPPER_FAILED
                return@useMapper
            }

            // Layer 2: Hook attachment
            runCatching {
                dataStoreClazz.hook(cacheUpdateMethodName, HookStage.AFTER) { param ->
                    // Capture the Status Map (usually the only Map argument or the result)
                    @Suppress("UNCHECKED_CAST")
                    activeStatusMap = (param.getResult() as? Map<String, Boolean>)
                        ?: (param.args().firstOrNull { it is Map<*, *> } as? Map<String, Boolean>)
                    
                    if (activeStatusMap != null) {
                        hookStatus = HookStatus.WORKING
                    }
                }
                context.log.verbose("ActiveSort: Phase 1 hooked — Data Store capture active")
            }.onFailure {
                hookStatus = HookStatus.HOOK_FAILED
                context.log.error("ActiveSort: Phase 1 hook attachment failed", it)
            }
        }

        // ── PHASE 2: List Interception ────────────────────────────────
        context.mappings.useMapper(ActiveStatusMapper::class) {
            // SendTo Interception
            runCatching {
                val providerClazz = sendToProviderClass.getAsClass()
                val methodName = sendToProviderMethod.get()

                if (providerClazz != null && methodName != null) {
                    providerClazz.hook(methodName, HookStage.AFTER) { param ->
                        if (!context.config.messaging.activeSort.enabled.get()) return@hook
                        if (activeStatusMap == null) return@hook
                        
                        val rawList = param.getResult() as? List<*> ?: return@hook
                        param.setResult(partitionByActive(rawList))
                    }
                }
            }.onFailure {
                context.log.warn("ActiveSort: Phase 2 SendTo hook failed", it)
            }

            // Bulk Messaging Interception
            runCatching {
                val providerClazz = bulkMessagingProviderClass.getAsClass()
                val methodName = bulkMessagingProviderMethod.get()

                if (providerClazz != null && methodName != null) {
                    providerClazz.hook(methodName, HookStage.AFTER) { param ->
                        if (!context.config.messaging.activeSort.enabled.get()) return@hook
                        if (activeStatusMap == null) return@hook
                        
                        val rawList = param.getResult() as? List<*> ?: return@hook
                        param.setResult(partitionByActive(rawList))
                    }
                }
            }.onFailure {
                context.log.warn("ActiveSort: Phase 2 Bulk Messaging hook failed — SendTo still active")
            }
        }
        
        context.log.verbose("ActiveSort: Phase 2 hooked — SendTo + BulkMessaging interceptors active")
    }

    /**
     * Stable partition sort: active friends first, sub-order preserved.
     */
    private fun partitionByActive(friends: List<*>): List<*> {
        return runCatching {
            val (active, inactive) = friends.partition { friend ->
                friend != null && activeStatusMap?.get(FriendWrapper(friend).userId) == true
            }
            activeList = active
            inactiveList = inactive
            
            // Successfully sorted; update status if not in error state
            if (hookStatus == HookStatus.WORKING || hookStatus == HookStatus.MAPPER_FAILED) {
                hookStatus = HookStatus.WORKING
            }
            
            context.log.verbose("ActiveSort: sorted ${active.size}/${friends.size} friends")
            active + inactive
        }.getOrElse { throwable ->
            hookStatus = HookStatus.RUNTIME_ERROR
            context.log.error("ActiveSort: sort algorithm failed — passing through original list", throwable)
            friends // Layer 3 pass-through: return original list untouched
        }
    }
}
