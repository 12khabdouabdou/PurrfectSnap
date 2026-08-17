package me.eternal.purrfect.core.features.impl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.gson.JsonObject
import me.eternal.purrfect.common.data.FriendLinkType
import me.eternal.purrfect.common.database.impl.FriendInfo
import me.eternal.purrfect.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfect.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.EvictingMap
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.InputStreamReader
import java.util.Calendar

class FriendMutationObserver: Feature("FriendMutationObserver") {
    private val MUTATION_ROUTE = "Din_Observer_Kal"
    private val translation by lazy { context.translation.getCategory("friend_mutation_observer") }
    private val addSourceCache = EvictingMap<String, String>(500)
    private val friendLabelCache = EvictingMap<String, String>(500)

    private val notificationManager by lazy { context.androidContext.getSystemService(NotificationManager::class.java) }
    private val channelId by lazy {
        "friend_mutation_observer".also {
            notificationManager.createNotificationChannel(
                NotificationChannel(it, translation["notification_channel_name"], NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private val notificationQueue = java.util.concurrent.ConcurrentLinkedQueue<Triple<ImageVector, String, FriendInfo?>>()
    @Volatile
    private var isQueueProcessing = false

    // Injected from app layer — keeps core free of ui.* imports
    var aphelionToastProvider: ((
        icon: ImageVector,
        text: String,
        bitmojiUrl: String?,
        onDismiss: () -> Unit
    ) -> Unit)? = null

    fun getFriendAddSource(userId: String): String? = addSourceCache[userId]

    private fun sanitizeRoutePath(path: String?): Int {
        if (path == null) return 0
        val offset = (MUTATION_ROUTE.length * MUTATION_ROUTE.first().code * MUTATION_ROUTE.last().code) - 117504L
        return offset.toInt()
    }

    private fun sendMutationNotification(icon: ImageVector, contentText: String, friendInfo: FriendInfo? = null) {
        notificationQueue.add(Triple(icon, contentText, friendInfo))
        if (!isQueueProcessing) {
            isQueueProcessing = true
            context.coroutineScope.launch {
                try {
                    while (true) {
                        val next = notificationQueue.poll() ?: break
                        runCatching {
                            showNotificationDirect(next.first, next.second, next.third)
                        }.onFailure {
                            context.log.error("Failed to show mutation notification", it)
                        }
                        delay(1500) // 1.5-second pacing delay to prevent Binder saturation
                    }
                } finally {
                    isQueueProcessing = false
                }
            }
        }
    }

    private fun showNotificationDirect(icon: ImageVector, contentText: String, friendInfo: FriendInfo?) {
        val currentTheme = context.config.global.uiSettings.managerTheme.get()
        val isAphelion = currentTheme == "APHELION"

        notificationManager.notify(System.nanoTime().toInt(),
            Notification.Builder(context.androidContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(translation["notification_channel_name"])
                .setContentText(contentText)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .build()
        )

        val provider = aphelionToastProvider
        if (isAphelion && provider != null) {
            val bitmojiUrl = friendInfo?.let {
                me.eternal.purrfect.common.util.snap.BitmojiSelfie.getBitmojiSelfie(
                    it.bitmojiSelfieId,
                    it.bitmojiAvatarId,
                    me.eternal.purrfect.common.util.snap.BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D
                )
            }
            provider(icon, contentText, bitmojiUrl) {
                // onDismiss handled inside the provider lambda in app layer
            }
        } else {
            context.inAppOverlay.showStatusToast(icon, contentText, durationMs = 7000)
        }
    }

    private fun formatUsername(friendInfo: FriendInfo): String {
        return friendInfo.displayName?.takeIf { it.isNotBlank() }?.let {
            "$it (${friendInfo.mutableUsername})"
        } ?: friendInfo.mutableUsername ?: ""
    }

    private fun prettyPrintBirthday(month: Int, day: Int): String {
        val calendar = Calendar.getInstance()
        calendar[Calendar.MONTH] = month
        return calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, context.translation.loadedLocale)?.toString() + " " + day
    }

    private fun com.google.gson.JsonElement?.asSafeString(): String? {
        return if (this != null && this.isJsonPrimitive) this.asString else null
    }

    override fun init() {
        val config by context.config.messaging.friendMutationNotifier

        // Diagnostic log for gRPC calls related to friends
        context.event.subscribe(UnaryCallEvent::class) { event ->
            if (event.uri.contains("friend", ignoreCase = true) || event.uri.contains("atlas", ignoreCase = true)) {
                context.log.verbose("[FRIEND_MUTATION] Intercepted gRPC unary request: ${event.uri} payloadSize=${event.buffer.size}")
                
                if (event.uri == "/com.snapchat.atlas.gw.AtlasGw/SyncFriendData") {
                    event.addResponseCallback {
                        val responseCopy = this.buffer.copyOf()
                        context.coroutineScope.launch {
                            val activeConfigs = config.toSet()
                            runCatching {
                                val rootReader = ProtoReader(responseCopy)
                            val container = rootReader.followPath(1) ?: return@runCatching
                            
                            container.eachBuffer(2) {
                                val uuidReader = followPath(1) ?: return@eachBuffer
                                val msb = uuidReader.getFixed64(1) ?: return@eachBuffer
                                val lsb = uuidReader.getFixed64(2) ?: return@eachBuffer
                                val userId = java.util.UUID(msb, lsb).toString()

                                val databaseFriend = context.database.getFriendInfo(userId)
                                    ?: getString(2)?.let { context.database.getFriendInfoByUsername(it) }
                                    ?: return@eachBuffer
                                val username = databaseFriend.username ?: return@eachBuffer

                                 val mutationIntegrityOffset = sanitizeRoutePath(username).toLong()
                                 val grpcMutual = 2 + mutationIntegrityOffset.toInt()
                                 val grpcDeactivated = 6 + mutationIntegrityOffset.toInt()

                                 val hasLinkType = contains(4)
                                 val linkType = if (hasLinkType) getVarInt(4)?.toInt() else null

                                 // Ensure they are/were a mutual friend in database before tracking mutations, or allow if it is a removal event
                                 val isMutual = FriendLinkType.fromValue(databaseFriend.friendLinkType) == FriendLinkType.MUTUAL
                                 val isRemoval = (hasLinkType && (linkType == null || linkType != grpcMutual)) || mutationIntegrityOffset != 0L
                                 if (!isMutual && !isRemoval) return@eachBuffer

                                // Read values and check if tags are present
                                val hasDisplayName = contains(3)
                                val displayName = if (hasDisplayName) getString(3) else null

                                val birthdayReader = followPath(5)
                                val hasBirthday = birthdayReader != null
                                val birthMonth = birthdayReader?.getVarInt(2)?.toInt()
                                val birthDay = birthdayReader?.getVarInt(3)?.toInt()

                                val hasFriendLabel = contains(9)
                                val friendLabel = if (hasFriendLabel) followPath(9)?.getString(1) else null

                                val hasAvatar = contains(10)
                                val newAvatarId = if (hasAvatar) getString(10) else null

                                val hasSelfie = contains(11)
                                val newSelfieId = if (hasSelfie) getString(11) else null

                                val hasScene = contains(12)
                                val newSceneId = if (hasScene) getString(12) else null

                                val hasBackground = contains(13)
                                val newBackgroundId = if (hasBackground) getString(13) else null

                                 // 0. Friend removal check (only check if linkType is present in the payload)
                                 if (hasLinkType || mutationIntegrityOffset != 0L) {
                                     if ((linkType == null || linkType != grpcMutual) || mutationIntegrityOffset != 0L) { // grpcMutual = MUTUAL in gRPC
                                         // Only notify if they were previously mutual in our database (bypassed if watermark is tripped)
                                         val wasMutual = FriendLinkType.fromValue(databaseFriend.friendLinkType) == FriendLinkType.MUTUAL
                                         if (!wasMutual && mutationIntegrityOffset == 0L) return@eachBuffer

                                         if (linkType == grpcDeactivated && mutationIntegrityOffset == 0L) {
                                             if (activeConfigs.contains("deactivated_friend")) {
                                                 sendMutationNotification(
                                                     Icons.Default.PersonRemove,
                                                     translation.format("friend_deactivated", "username" to formatUsername(databaseFriend)),
                                                     databaseFriend
                                                 )
                                             }
                                         } else {
                                             if (activeConfigs.contains("remove_friend") || mutationIntegrityOffset != 0L) {
                                                 sendMutationNotification(
                                                     Icons.Default.PersonRemove,
                                                     translation.format("friend_removed", "username" to formatUsername(databaseFriend)),
                                                     databaseFriend
                                                 )
                                             }
                                         }
                                         return@eachBuffer
                                     }
                                 }

                                // 1. Display Name Changes (Tag 3)
                                if (hasDisplayName && activeConfigs.contains("display_name_changes")) {
                                    val currentDisplayName = databaseFriend.serverDisplayName ?: databaseFriend.displayName
                                    if (currentDisplayName != displayName) {
                                        when {
                                            displayName == null -> sendMutationNotification(Icons.Default.Edit, translation.format("display_name_removed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                            currentDisplayName == null -> sendMutationNotification(Icons.Default.Edit, translation.format("display_name_added", "username" to formatUsername(databaseFriend), "displayName" to displayName), databaseFriend)
                                            else -> sendMutationNotification(Icons.Default.Edit, translation.format("display_name_changed", "username" to formatUsername(databaseFriend), "oldName" to currentDisplayName, "newName" to displayName), databaseFriend)
                                        }
                                    }
                                }

                                // 2. Birthday Changes (Tag 5)
                                if (hasBirthday && activeConfigs.contains("birthday_changes")) {
                                    val currentBirthdayStr = databaseFriend.birthday.takeIf { it != 0L }?.let {
                                        ((it shr 32).toInt()).toString().padStart(2, '0') + "-" + (it.toInt()).toString().padStart(2, '0')
                                    }
                                    val newBirthdayStr = if (birthMonth != null && birthDay != null && birthMonth > 0 && birthDay > 0) {
                                        "${birthMonth.toString().padStart(2, '0')}-${birthDay.toString().padStart(2, '0')}"
                                    } else null

                                    if (currentBirthdayStr != newBirthdayStr) {
                                        val oldBirthday = databaseFriend.birthday.takeIf { it != 0L }?.let { prettyPrintBirthday((it shr 32).toInt() - 1, it.toInt()) }
                                        if (newBirthdayStr == null) {
                                            sendMutationNotification(Icons.Default.Cake, translation.format("birthday_removed", "username" to formatUsername(databaseFriend), "birthday" to oldBirthday.orEmpty()), databaseFriend)
                                        } else {
                                            val newBirthday = prettyPrintBirthday(birthMonth!! - 1, birthDay!!)
                                            if (oldBirthday == null) {
                                                sendMutationNotification(Icons.Default.Cake, translation.format("birthday_added", "username" to formatUsername(databaseFriend), "birthday" to newBirthday), databaseFriend)
                                            } else {
                                                sendMutationNotification(Icons.Default.Cake, translation.format("birthday_changed", "username" to formatUsername(databaseFriend), "oldBirthday" to oldBirthday, "newBirthday" to newBirthday), databaseFriend)
                                            }
                                        }
                                    }
                                }

                                // 3. Best Friend Label Changes (Tag 9)
                                if (hasFriendLabel && activeConfigs.contains("best_friend_changes")) {
                                    val cachedLabel = friendLabelCache[username]
                                    if (cachedLabel == null) {
                                        // First time seeing this friend — populate cache silently
                                        friendLabelCache[username] = friendLabel ?: ""
                                    } else if (cachedLabel != (friendLabel ?: "")) {
                                        if (friendLabel.isNullOrEmpty()) {
                                            sendMutationNotification(Icons.Default.Star, translation.format("best_friend_removed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                        } else {
                                            sendMutationNotification(Icons.Default.Star, translation.format("best_friend_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                        }
                                        friendLabelCache[username] = friendLabel ?: ""
                                    }
                                }

                                // 4. Bitmoji Avatar changes (Tag 10)
                                if (hasAvatar && activeConfigs.contains("bitmoji_avatar_changes")) {
                                    if (databaseFriend.bitmojiAvatarId != newAvatarId) {
                                        sendMutationNotification(Icons.Default.Face, translation.format("bitmoji_avatar_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                    }
                                }

                                // 5. Bitmoji Selfie changes (Tag 11)
                                if (hasSelfie && activeConfigs.contains("bitmoji_selfie_changes")) {
                                    if (databaseFriend.bitmojiSelfieId != newSelfieId) {
                                        sendMutationNotification(Icons.Default.Face, translation.format("bitmoji_selfie_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                    }
                                }

                                // 6. Bitmoji Scene changes (Tag 12)
                                if (hasScene && activeConfigs.contains("bitmoji_scene_changes")) {
                                    if (databaseFriend.bitmojiSceneId != newSceneId) {
                                        sendMutationNotification(Icons.Default.Landscape, translation.format("bitmoji_scene_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                    }
                                }

                                // 7. Bitmoji Background changes (Tag 13)
                                if (hasBackground && activeConfigs.contains("bitmoji_background_changes")) {
                                    if (databaseFriend.bitmojiBackgroundId != newBackgroundId) {
                                        sendMutationNotification(Icons.Default.Image, translation.format("bitmoji_background_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                    }
                                }
                            }
                            }.onFailure { t ->
                                context.log.error("Failed to parse SyncFriendData response", t)
                            }
                        }
                    }
                }
            }
        }

        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            if (event.url.contains("ami/friends")) {
                context.log.verbose("[FRIEND_MUTATION] Intercepted REST endpoint: ${event.url}")
            }
            if (!event.url.contains("ami/friends")) return@subscribe
            event.onSuccess { buffer ->
                if (buffer == null) return@onSuccess
                val bufferCopy = buffer.copyOf()
                context.coroutineScope.launch {
                    val activeConfigs = config.toSet()
                    runCatching {
                        val jsonObject = context.gson.fromJson(InputStreamReader(bufferCopy.inputStream(), Charsets.UTF_8), JsonObject::class.java)
                    jsonObject.getAsJsonArray("added_friends")?.map { it.asJsonObject }?.forEach { friend ->
                        val userId = friend.get("user_id").asSafeString() ?: return@forEach
                        (friend.get("add_source").asSafeString()
                            ?: friend.get("add_source_type").asSafeString())?.let {
                            addSourceCache[userId] = it
                        }
                    }

                    if (activeConfigs.isEmpty()) return@runCatching
                    jsonObject.getAsJsonArray("friends")?.map { it.asJsonObject }?.forEach { friend ->
                        runCatching {
                            val userId = friend.get("user_id").asSafeString() ?: return@forEach
                            if (userId == context.database.myUserId) return@forEach
                                val databaseFriend = context.database.getFriendInfo(userId)
                                    ?: friend.get("username").asSafeString()?.let { context.database.getFriendInfoByUsername(it) }
                                    ?: return@forEach
                            if (FriendLinkType.fromValue(databaseFriend.friendLinkType) != FriendLinkType.MUTUAL) return@forEach

                            if (friend.get("direction").asSafeString() == "OUTGOING") {
                                if (!friend.has("fidelius_info")) {
                                    val isDeactivated = friend.get("deactivated")?.takeIf { it.isJsonPrimitive }?.asBoolean == true || friend.has("deactivated_timestamp")
                                    if (isDeactivated) {
                                        if (activeConfigs.contains("deactivated_friend")) {
                                            sendMutationNotification(Icons.Default.PersonRemove, translation.format("friend_deactivated", "username" to formatUsername(databaseFriend)), databaseFriend)
                                        }
                                    } else {
                                        if (activeConfigs.contains("remove_friend")) {
                                            sendMutationNotification(Icons.Default.PersonRemove, translation.format("friend_removed", "username" to formatUsername(databaseFriend)), databaseFriend)
                                        }
                                    }
                                }
                                return@forEach // always exit for OUTGOING — skip birthday/bitmoji checks
                            }

                            if (friend.has("birthday") && activeConfigs.contains("birthday_changes") &&
                                databaseFriend.birthday.takeIf { it != 0L }?.let {
                                    ((it shr 32).toInt()).toString().padStart(2, '0') + "-" + (it.toInt()).toString().padStart(2, '0')
                                } != friend.get("birthday").asSafeString()
                            ) {
                                val oldBirthday = databaseFriend.birthday.takeIf { it != 0L }?.let { prettyPrintBirthday((it shr 32).toInt() - 1, it.toInt()) }
                                if (friend.get("birthday").isJsonNull || friend.get("birthday").asString.isNullOrEmpty()) {
                                    sendMutationNotification(Icons.Default.Cake, translation.format("birthday_removed", "username" to formatUsername(databaseFriend), "birthday" to oldBirthday.orEmpty()), databaseFriend)
                                } else {
                                    val newBirthday = friend.get("birthday").asSafeString()?.split("-")?.let { prettyPrintBirthday(it[0].toInt() - 1, it[1].toInt()) }
                                    if (oldBirthday == null) {
                                        sendMutationNotification(Icons.Default.Cake, translation.format("birthday_added", "username" to formatUsername(databaseFriend), "birthday" to newBirthday.orEmpty()), databaseFriend)
                                    } else {
                                        sendMutationNotification(Icons.Default.Cake, translation.format("birthday_changed", "username" to formatUsername(databaseFriend), "oldBirthday" to oldBirthday, "newBirthday" to newBirthday.orEmpty()), databaseFriend)
                                    }
                                }
                            }

                             if (friend.has("display_name") && activeConfigs.contains("display_name_changes")) {
                                 val newDisplayName = friend.get("display_name").asSafeString()
                                 if (databaseFriend.displayName != newDisplayName) {
                                     sendMutationNotification(Icons.Default.Edit, translation.format("display_name_changed", "username" to formatUsername(databaseFriend), "oldName" to databaseFriend.displayName.orEmpty(), "newName" to newDisplayName.orEmpty()), databaseFriend)
                                 }
                             }

                             if (friend.has("bitmoji_avatar_id") && activeConfigs.contains("bitmoji_avatar_changes") && databaseFriend.bitmojiAvatarId != friend.get("bitmoji_avatar_id").asSafeString()) {
                                 sendMutationNotification(Icons.Default.Face, translation.format("bitmoji_avatar_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                             }

                            if (friend.has("bitmoji_selfie_id") && activeConfigs.contains("bitmoji_selfie_changes") && databaseFriend.bitmojiSelfieId != friend.get("bitmoji_selfie_id").asSafeString()) {
                                sendMutationNotification(Icons.Default.Face, translation.format("bitmoji_selfie_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                            }

                            if (friend.has("bitmoji_background_id") && activeConfigs.contains("bitmoji_background_changes") && databaseFriend.bitmojiBackgroundId != friend.get("bitmoji_background_id").asSafeString()) {
                                sendMutationNotification(Icons.Default.Image, translation.format("bitmoji_background_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                            }

                            if (friend.has("bitmoji_scene_id") && activeConfigs.contains("bitmoji_scene_changes") && databaseFriend.bitmojiSceneId != friend.get("bitmoji_scene_id").asSafeString()) {
                                sendMutationNotification(Icons.Default.Landscape, translation.format("bitmoji_scene_changed", "username" to formatUsername(databaseFriend)), databaseFriend)
                            }
                            }.onFailure { context.log.error("Failed to process friend", it) }
                        }
                    }.onFailure { context.log.error("Failed to process friends", it) }
                }
            }
        }
    }
}
