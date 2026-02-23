package me.eternal.purrfectsnap.core.wrapper.impl

import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper

/**
 * A typed wrapper around Snapchat's obfuscated Friend object.
 *
 * This wrapper is used to extract the userId string from the Friend object,
 * which is then used by the Active Sort feature to check against the cached
 * active status map.
 *
 * per ADR-2, this uses the AbstractWrapper field delegate for safe access.
 */
class FriendWrapper(instance: Any?) : AbstractWrapper(instance) {
    /**
     * The unique identifier for the friend.
     * Retreals the mUserId field from the obfuscated Friend object.
     * Returns null if access fails, as required by the graceful degradation contract (Story 2.3).
     */
    val userId: String? by field("mUserId") { it?.let { SnapUUID(it).toString() } }
}
