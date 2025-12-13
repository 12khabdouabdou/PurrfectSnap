package me.rhunk.snapenhance.data

import kotlinx.serialization.Serializable

@Serializable
data class Recipient(
    val userId: String,
    val name: String,
    val username: String? = null
)

@Serializable
data class Shortcut(
    val id: Long = 0,
    val name: String,
    val recipients: List<Recipient>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long? = null,
    val usageCount: Int = 0
)
