package me.eternal.purrfectsnap.common.bridge

/**
 * Represents the operational status of the Active Sort feature hooks.
 * 
 * WORKING: Hooks are attached and data is being captured/processed.
 * MAPPER_FAILED: Key classes/fields could not be resolved by ActiveStatusMapper.
 * HOOK_FAILED: XposedHelpers failed to hook the resolved methods.
 * RUNTIME_ERROR: Hooks are active but encountered errors during execution.
 */
enum class ActiveSortHookStatus {
    WORKING,
    MAPPER_FAILED,
    HOOK_FAILED,
    RUNTIME_ERROR
}
