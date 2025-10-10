package me.rhunk.snapenhance.core.features.impl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import com.google.gson.JsonObject
import me.rhunk.snapenhance.common.data.FriendLinkType
import me.rhunk.snapenhance.common.database.impl.FriendInfo
import me.rhunk.snapenhance.common.util.lazyBridge
import me.rhunk.snapenhance.core.event.events.impl.NetworkApiRequestEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.EvictingMap
import java.io.InputStreamReader
import java.util.Calendar

class FriendMutationObserver: Feature("FriendMutationObserver") {
    private val translation by lazy { context.translation.getCategory("friend_mutation_observer") }
    private val logger by lazyBridge { context.bridgeClient.getFriendMutationLogger() }
    private val addSourceCache = EvictingMap<String, String>(500)

    private val notificationManager by lazy { context.androidContext.getSystemService(NotificationManager::class.java) }
    private val channelId by lazy {
        "friend_mutation_observer".also {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    it,
                    translation["notification_channel_name"],
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    fun getFriendAddSource(userId: String): String? {
        return addSourceCache[userId]
    }

    private fun sendWarnNotification(
        contentText: String
    ) {
        notificationManager.notify(System.nanoTime().toInt(),
            Notification.Builder(context.androidContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(translation["notification_channel_name"])
                .setContentText(contentText)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .build()
        )

        context.inAppOverlay.showStatusToast(
            Icons.Default.WarningAmber,
            contentText,
            durationMs = 7000
        )
    }

    private fun formatUsername(friendInfo: FriendInfo): String {
        return friendInfo.displayName?.takeIf { it.isNotBlank() }?.let {
            "$it (${friendInfo.mutableUsername})"
        } ?: friendInfo.mutableUsername ?: ""
    }

    private fun prettyPrintBirthday(month: Int, day: Int): String {
        val calendar = Calendar.getInstance()
        calendar[Calendar.MONTH] = month
        return calendar.getDisplayName(
            Calendar.MONTH,
            Calendar.LONG,
            context.translation.loadedLocale
        )?.toString() + " " + day
    }

    override fun init() {
        val config by context.config.messaging.friendMutationNotifier

        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            if (!event.url.contains("ami/friends")) return@subscribe
            event.onSuccess { buffer ->
                runCatching {
                    val jsonObject = context.gson.fromJson(InputStreamReader(buffer?.inputStream() ?: return@onSuccess, Charsets.UTF_8), JsonObject::class.java)

                    jsonObject.getAsJsonArray("added_friends").map { it.asJsonObject }.forEach { friend ->
                        val userId = friend.get("user_id").asString
                        (friend.get("add_source")?.asString?.takeIf {
                            it.isNotBlank()
                        } ?: friend.get("add_source_type")?.asString?.takeIf {
                            it.isNotBlank()
                        })?.let {
                            addSourceCache[userId] = it
                        }
                    }

                    if (config.isEmpty()) return@runCatching

                    jsonObject.getAsJsonArray("friends").map { it.asJsonObject }.forEach { friend ->
                        runCatching {
                            val userId = friend.get("user_id")?.asString
                            if (userId == context.database.myUserId) return@forEach
                            val databaseFriend = context.database.getFriendInfo(userId ?: return@forEach) ?: return@forEach
                            if (FriendLinkType.fromValue(databaseFriend.friendLinkType) != FriendLinkType.MUTUAL) return@forEach

                            if (config.contains("remove_friend") && friend.get("direction")?.asString == "OUTGOING" && !friend.has("fidelius_info")) {
                                val friendName = formatUsername(databaseFriend)
                                logger.logFriendMutation("friend_removed", friendName, "Friend removed you")
                                sendWarnNotification(translation.format("friend_removed", "username" to friendName))
                                return@forEach
                            }

                            if (config.contains("birthday_changes") &&
                                databaseFriend.birthday.takeIf { it != 0L }?.let {
                                    ((it shr 32).toInt()).toString().padStart(2, '0') + "-" + (it.toInt()).toString().padStart(2, '0')
                                } != friend.get("birthday")?.asString
                            ) {
                                val oldBirthday = databaseFriend.birthday.takeIf { it != 0L }?.let {
                                    prettyPrintBirthday((it shr 32).toInt() - 1, it.toInt())
                                }

                                val friendName = formatUsername(databaseFriend)
                                if (!friend.has("birthday")) {
                                    val details = JsonObject().apply {
                                        addProperty("old", oldBirthday.orEmpty())
                                    }
                                    logger.logFriendMutation("birthday_removed", friendName, context.gson.toJson(details))
                                    sendWarnNotification(translation.format("birthday_removed", "username" to friendName, "birthday" to oldBirthday.orEmpty()))
                                } else {
                                    val newBirthday = friend.get("birthday")?.asString?.split("-")?.let {
                                        prettyPrintBirthday(it[0].toInt() - 1, it[1].toInt())
                                    }
                                    if (oldBirthday == null) {
                                        val details = JsonObject().apply {
                                            addProperty("new", newBirthday.orEmpty())
                                        }
                                        logger.logFriendMutation("birthday_added", friendName, context.gson.toJson(details))
                                        sendWarnNotification(translation.format("birthday_added", "username" to friendName, "birthday" to newBirthday.orEmpty()))
                                    } else {
                                        val details = JsonObject().apply {
                                            addProperty("old", oldBirthday)
                                            addProperty("new", newBirthday.orEmpty())
                                        }
                                        logger.logFriendMutation("birthday_changed", friendName, context.gson.toJson(details))
                                        sendWarnNotification(translation.format("birthday_changed", "username" to friendName, "oldBirthday" to oldBirthday, "newBirthday" to newBirthday.orEmpty()))
                                    }
                                }
                            }

                            if (config.contains("bitmoji_avatar_changes") && databaseFriend.bitmojiAvatarId != friend.get("bitmoji_avatar_id")?.asString) {
                                val friendName = formatUsername(databaseFriend)
                                logger.logFriendMutation("bitmoji_avatar_changed", friendName, "Bitmoji avatar changed")
                                sendWarnNotification(translation.format("bitmoji_avatar_changed", "username" to friendName))
                            }

                            if (config.contains("bitmoji_selfie_changes") && databaseFriend.bitmojiSelfieId != friend.get("bitmoji_selfie_id")?.asString) {
                                val friendName = formatUsername(databaseFriend)
                                logger.logFriendMutation("bitmoji_selfie_changed", friendName, "Bitmoji selfie changed")
                                sendWarnNotification(translation.format("bitmoji_selfie_changed", "username" to friendName))
                            }

                            if (config.contains("bitmoji_background_changes") && databaseFriend.bitmojiBackgroundId != friend.get("bitmoji_background_id")?.asString) {
                                val friendName = formatUsername(databaseFriend)
                                logger.logFriendMutation("bitmoji_background_changed", friendName, "Bitmoji background changed")
                                sendWarnNotification(translation.format("bitmoji_background_changed", "username" to friendName))
                            }

                            if (config.contains("bitmoji_scene_changes") && databaseFriend.bitmojiSceneId != friend.get("bitmoji_scene_id")?.asString) {
                                val friendName = formatUsername(databaseFriend)
                                logger.logFriendMutation("bitmoji_scene_changed", friendName, "Bitmoji scene changed")
                                sendWarnNotification(translation.format("bitmoji_scene_changed", "username" to friendName))
                            }
                        }.onFailure {
                            context.log.error("Failed to process friend", it)
                        }
                    }
                }.onFailure {
                    context.log.error("Failed to process friends", it)
                }
            }
        }
    }
}