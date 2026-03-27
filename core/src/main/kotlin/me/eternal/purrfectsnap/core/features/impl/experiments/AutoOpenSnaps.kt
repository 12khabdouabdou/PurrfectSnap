package me.eternal.purrfectsnap.core.features.impl.experiments

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.data.MessageUpdate
import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.core.event.events.impl.BuildMessageEvent
import me.eternal.purrfectsnap.core.features.MessagingRuleFeature
import me.eternal.purrfectsnap.core.features.impl.messaging.Messaging
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

import me.eternal.purrfectsnap.bridge.AutoOpenInterface
import com.google.gson.Gson

class AutoOpenSnaps: MessagingRuleFeature("Auto Open Snaps", MessagingRuleType.AUTO_OPEN_SNAPS) {
    companion object {
        const val ACTION_PAUSE_RESUME = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_PAUSE_RESUME"
        const val ACTION_CLEAR_QUEUE = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_CLEAR_QUEUE"
    }

    private val gson = Gson()

    data class SnapQueueItem(
        val conversationId: String,
        val messageId: Long,
        val senderInfo: String,
        val conversationType: String,
        val contentType: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val autoOpenInterface = object : AutoOpenInterface.Stub() {
        override fun getProcessedCount(): Int = totalProcessed
        override fun getQueueItems(): List<String> {
            return synchronized(queuedSnaps) {
                queuedSnaps.map { gson.toJson(it) }
            }
        }
        override fun reset() {
            synchronized(queuedSnaps) {
                queuedSnaps.clear()
            }
            totalProcessed = 0
            updateStatusNotification()
        }
    }

    fun getInterface(): AutoOpenInterface = autoOpenInterface

    private val snapQueue = MutableSharedFlow<SnapQueueItem>()
    private val openedSnaps = ArrayDeque<Long>()
    private val isPaused = AtomicBoolean(false)
    val queuedSnaps = mutableListOf<SnapQueueItem>()
    var totalProcessed = 0
        private set
    
    var sessionStartTime = System.currentTimeMillis()
        private set
    private var lastResetTime = System.currentTimeMillis()
    
    private var currentBatchSize = 0
    private var currentBatchProcessed = 0
    private var batchSnapCount = 0 // For jitter batch cooldown

    private val config by lazy { context.config.messaging.autoOpenSnaps }

    private val notificationManager by lazy {
        context.androidContext.getSystemService(NotificationManager::class.java)
    }
    private val statusNotificationId by lazy { Random.nextInt() }

    private val baseChannelId = "auto_open_snaps"
    private val priorityChannelId = "auto_open_snaps_priority"

    private var lastNotificationUpdate = AtomicLong(0)
    private val notificationUpdateDelay = 1000L 
    private var pendingNotificationUpdate = AtomicBoolean(false)

    private var lastTempNotificationTime = AtomicLong(0)
    private val tempNotificationDelay = 2000L 
    private val activeNotificationIds = mutableSetOf<Int>()

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_RESUME -> {
                    val wasPaused = isPaused.get()
                    isPaused.set(!wasPaused)
                    
                    val feedbackTitle = if (wasPaused) {
                        this@AutoOpenSnaps.context.translation["auto_open_snaps.resumed_feedback"]
                    } else {
                        this@AutoOpenSnaps.context.translation["auto_open_snaps.paused_feedback"]
                    }
                    
                    val feedbackContent = if (wasPaused) {
                        this@AutoOpenSnaps.context.translation["auto_open_snaps.resumed_message"]
                    } else {
                        this@AutoOpenSnaps.context.translation["auto_open_snaps.paused_message"].replace("{count}", synchronized(queuedSnaps) { queuedSnaps.size }.toString())
                    }
                    
                    showTemporaryNotification(feedbackTitle, feedbackContent)
                    updateStatusNotification()
                    
            
                    if (wasPaused && synchronized(queuedSnaps) { queuedSnaps.size } > 0) {
                        this@AutoOpenSnaps.context.log.debug("[AUTO-OPEN] Resumed with ${synchronized(queuedSnaps) { queuedSnaps.size }} snaps in queue")
                    }
                }
                ACTION_CLEAR_QUEUE -> {
                    val queueSize = synchronized(queuedSnaps) { queuedSnaps.size }
                    val processedCount = totalProcessed
                    
                    clearQueue(resetTotalCount = true, showNotification = false)
                    
                    val feedbackTitle = this@AutoOpenSnaps.context.translation["auto_open_snaps.queue_cleared_reset"]
                    val feedbackContent = if (queueSize > 0) {
                        this@AutoOpenSnaps.context.translation["auto_open_snaps.queue_cleared_feedback"]
                            .replace("{count}", queueSize.toString())
                            .replace("{processed}", processedCount.toString())
                    } else {
                        this@AutoOpenSnaps.context.translation["auto_open_snaps.queue_cleared_feedback_simple"]
                            .replace("{processed}", processedCount.toString())
                    }
                    
                    showTemporaryNotification(feedbackTitle, feedbackContent)
                }
            }
        }
    }

    private fun createNotificationChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(baseChannelId, 
                context.translation["auto_open_snaps.title"], 
                // Visible Presence Fix: Upgrade importance to DEFAULT so it stays in status bar.
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.translation["auto_open_snaps.channel_description"]
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
            }
        )

        notificationManager.createNotificationChannel(
            NotificationChannel(priorityChannelId,
                context.translation["auto_open_snaps.priority_title"],
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.translation["auto_open_snaps.priority_channel_description"]
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.androidContext.packageName)
        }
        return PendingIntent.getBroadcast(
            context.androidContext,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun verifyQueueSync(): Boolean {
        return synchronized(queuedSnaps) {
            val currentTime = System.currentTimeMillis()
            val timeoutMs = 5 * 60 * 1000L
            
            val removed = mutableListOf<Long>()
            queuedSnaps.removeAll { item ->
                val stuck = (currentTime - item.timestamp) > timeoutMs
                if (stuck) removed.add(item.messageId)
                stuck
            }
            
            if (removed.isNotEmpty()) {
                context.log.warn("[AUTO-OPEN] Cleaned up ${removed.size} stuck items")
            }
            
            val uniqueItems = queuedSnaps.distinctBy { it.messageId }.toMutableList()
            if (uniqueItems.size != queuedSnaps.size) {
                context.log.warn("[AUTO-OPEN] Found ${queuedSnaps.size - uniqueItems.size} duplicate items")
                queuedSnaps.clear()
                queuedSnaps.addAll(uniqueItems)
            }
            
            return@synchronized true
        }
    }

    private fun updateStatusNotification() {
        val currentTime = System.currentTimeMillis()
        val lastUpdate = lastNotificationUpdate.get()
        
        // Throttle notification updates to prevent spam
        if ((currentTime - lastUpdate) < notificationUpdateDelay) {
            if (pendingNotificationUpdate.compareAndSet(false, true)) {
                context.coroutineScope.launch {
                    delay(notificationUpdateDelay - (currentTime - lastUpdate))
                    pendingNotificationUpdate.set(false)
                    updateStatusNotificationInternal()
                }
            }
            return
        }
        
        lastNotificationUpdate.set(currentTime)
        updateStatusNotificationInternal()
    }

    private fun updateStatusNotificationInternal() {
        verifyQueueSync()
        
        val queueCount = synchronized(queuedSnaps) { queuedSnaps.size }
        val processed = totalProcessed
        
        // Self-Cleaning Logic: If work is done, wait 10s then auto-clear.
        if (queueCount <= 0) {
            if (processed > 0) {
                context.coroutineScope.launch {
                    delay(10000)
                    if (synchronized(queuedSnaps) { queuedSnaps.size } <= 0) {
                        notificationManager.cancel(statusNotificationId)
                    }
                }
            } else {
                notificationManager.cancel(statusNotificationId)
            }
            return
        }

        val channelId = if (queueCount > config.queueSize.get() * 0.8) {
            priorityChannelId
        } else {
            baseChannelId
        }

        val notificationBuilder = Notification.Builder(context.androidContext, channelId)
            .setSmallIcon(if (isPaused.get()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setOngoing(queueCount > 0)
            .setAutoCancel(false)

        val statusText = if (isPaused.get()) {
            context.translation["auto_open_snaps.status_paused"]
        } else if (queueCount > 0) {
            "Processing: $queueCount queued"
        } else {
            context.translation["auto_open_snaps.status_monitoring"]
        }

        notificationBuilder
            .setContentTitle(context.translation["auto_open_snaps.title"])
            .setContentText(statusText)

        if (queueCount > 0) {
            val progressMax = maxOf(currentBatchSize, queueCount + currentBatchProcessed)
            val progressCurrent = currentBatchProcessed
            
            notificationBuilder.setProgress(progressMax, progressCurrent, false)
            
            val progressPercent = if (progressMax > 0) ((progressCurrent * 100) / progressMax) else 0
            notificationBuilder.setSubText("Progress: $progressPercent% • Queue: $queueCount")
        } else {
            notificationBuilder.setSubText("")
        }

        val pauseResumeText = if (isPaused.get()) {
            context.translation["auto_open_snaps.action_resume"]
        } else {
            context.translation["auto_open_snaps.action_pause"]
        }
        notificationBuilder.addAction(
            Notification.Action.Builder(
                null,
                pauseResumeText,
                createPendingIntent(ACTION_PAUSE_RESUME)
            ).build()
        )

        if (queueCount > 0 || processed > 0) {
            val clearText = if (queueCount > 0) {
                context.translation["auto_open_snaps.action_clear"]
            } else {
                context.translation["auto_open_snaps.action_reset"]
            }
            notificationBuilder.addAction(
                Notification.Action.Builder(
                    null,
                    clearText,
                    createPendingIntent(ACTION_CLEAR_QUEUE)
                ).build()
            )
        }

        if (config.compactNotification.get()) {
            notificationManager.notify(statusNotificationId, notificationBuilder.build())
            return
        }

        val recentSnaps = synchronized(queuedSnaps) { queuedSnaps.takeLast(5) }
        val bigTextStyle = Notification.BigTextStyle()
        
        val detailText = buildString {
            append("${context.translation["auto_open_snaps.notification_status"]}: ")
            if (isPaused.get()) {
                append("${context.translation["auto_open_snaps.status_paused"]}\n\n")
            } else if (queueCount > 0) {
                append("${context.translation["auto_open_snaps.status_active"]}\n\n")
            } else {
                append("${context.translation["auto_open_snaps.status_monitoring"]}\n\n")
            }
            
            append("${context.translation["auto_open_snaps.notification_statistics"]}:\n")
            append("├─ ${context.translation["auto_open_snaps.notification_queue_size"]}: $queueCount snaps\n")
            append("└─ ${context.translation["auto_open_snaps.notification_total_opened"]}: $processed snaps\n\n")
            
            if (queueCount > 0) {
                append("${context.translation["auto_open_snaps.notification_queue_preview"]}:\n")
                recentSnaps.forEachIndexed { index, snap ->
                    val statusIcon = when {
                        index == recentSnaps.size - 1 -> ">"
                        else -> "-"
                    }
                    
                    append("$statusIcon ${snap.senderInfo}\n")
                    append("  ${snap.contentType} • ${snap.conversationType}\n")
                    if (index < recentSnaps.size - 1) append("\n")
                }
                append("\n${context.translation["auto_open_snaps.notification_processing_continue"]}")
            } else {
                append(context.translation["auto_open_snaps.notification_no_snaps_queue"])
            }
        }
        
        bigTextStyle.bigText(detailText)
        bigTextStyle.setBigContentTitle(context.translation["auto_open_snaps.title"])
        
        if (queueCount > 0) {
            bigTextStyle.setSummaryText("Queue: $queueCount | ${if (isPaused.get()) context.translation["auto_open_snaps.status_paused"] else context.translation["auto_open_snaps.status_active"]}")
        } else {
            bigTextStyle.setSummaryText("")
        }
        
        notificationBuilder.setStyle(bigTextStyle)
        
        notificationManager.notify(statusNotificationId, notificationBuilder.build())
    }

    private fun clearQueue(resetTotalCount: Boolean = false, showNotification: Boolean = true) {
        val clearedCount = synchronized(queuedSnaps) {
            val count = queuedSnaps.size
            queuedSnaps.clear()
            count
        }
        
        synchronized(openedSnaps) {
            openedSnaps.clear()
        }
        
        if (resetTotalCount) {
            totalProcessed = 0
            lastResetTime = System.currentTimeMillis()
        }
        
        currentBatchSize = 0
        currentBatchProcessed = 0
        
        verifyQueueSync()
        
        notificationManager.cancel(statusNotificationId)
        
        if (showNotification) {
            val message = if (resetTotalCount) {
                context.translation["auto_open_snaps.queue_cleared"]
            } else {
                context.translation["auto_open_snaps.notification_queue_cleared_opened"].replace("{opened}", totalProcessed.toString())
            }
            showTemporaryNotification(context.translation["auto_open_snaps.queue_cleared_title"], message)
        }
    }



    private fun showTemporaryNotification(title: String, content: String) {
        val currentTime = System.currentTimeMillis()
        val lastTempTime = lastTempNotificationTime.get()

        if ((currentTime - lastTempTime) < tempNotificationDelay) {
            return
        }
        
        lastTempNotificationTime.set(currentTime)
        
        // Cancel any existing temporary notifications
        synchronized(activeNotificationIds) {
            activeNotificationIds.forEach { id ->
                notificationManager.cancel(id)
            }
            activeNotificationIds.clear()
        }
        
        val tempNotificationId = Random.nextInt()
        
        val icon = when {
            title.contains("cleared", ignoreCase = true) -> android.R.drawable.ic_menu_delete
            title.contains("reset", ignoreCase = true) -> android.R.drawable.ic_menu_revert
            title.contains("paused", ignoreCase = true) -> android.R.drawable.ic_media_pause
            title.contains("resumed", ignoreCase = true) -> android.R.drawable.ic_media_play
            else -> android.R.drawable.ic_dialog_info
        }
        
        val notificationBuilder = Notification.Builder(context.androidContext, baseChannelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .setCategory(Notification.CATEGORY_STATUS)

        synchronized(activeNotificationIds) {
            activeNotificationIds.add(tempNotificationId)
        }
        
        notificationManager.notify(tempNotificationId, notificationBuilder.build())

        context.coroutineScope.launch {
            delay(4000)
            synchronized(activeNotificationIds) {
                activeNotificationIds.remove(tempNotificationId)
            }
        }
    }

    override fun init() {
        if (getRuleState() == null) return
        if (config.globalState != true) return
        val messaging = context.feature(Messaging::class)
        
        sessionStartTime = System.currentTimeMillis()

        if (config.allowRunningInBackground.get()) {
            findClass("com.snapchat.client.duplex.DuplexClient\$CppProxy").apply {
                hook("appStateChanged", HookStage.BEFORE) { param ->
                    if (param.arg<Any>(0).toString() == "INACTIVE") param.setResult(null)
                }
                hookConstructor(HookStage.AFTER) { param ->
                    methods.first { it.name == "appStateChanged" }.let { method ->
                        method.invoke(param.thisObject(), method.parameterTypes[0].enumConstants!!.first { it.toString() == "ACTIVE" })
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_PAUSE_RESUME)
            addAction(ACTION_CLEAR_QUEUE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.androidContext.registerReceiver(
                actionReceiver, 
                intentFilter, 
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.androidContext.registerReceiver(actionReceiver, intentFilter)
        }

        createNotificationChannels()

        context.coroutineScope.launch(Dispatchers.Default) {
            while (true) {
                delay(30000)
                verifyQueueSync()
            }
        }

        context.coroutineScope.launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            snapQueue.collect { snapItem ->
                verifyQueueSync()
                
                var wasAddedToPausedQueue = false
                while (isPaused.get()) {
                    if (!wasAddedToPausedQueue) {
                        synchronized(queuedSnaps) {
                            if (!queuedSnaps.any { it.messageId == snapItem.messageId }) {
                                queuedSnaps.add(snapItem)
                                wasAddedToPausedQueue = true
                                updateStatusNotification()
                            }
                        }
                    }
                    delay(2000)
                }

                synchronized(queuedSnaps) {
                    queuedSnaps.removeAll { it.messageId == snapItem.messageId }
                }

                // RESOURCE AWARENESS
                val connectivityManager = context.androidContext.getSystemService(ConnectivityManager::class.java)
                val isWifi = connectivityManager?.activeNetwork?.let { 
                    connectivityManager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) 
                } == true
                
                val powerManager = context.androidContext.getSystemService(PowerManager::class.java)
                val isIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) powerManager?.isDeviceIdleMode == true else false
                
                val activityManager = context.androidContext.getSystemService(ActivityManager::class.java)
                val isGaming = activityManager?.runningAppProcesses?.firstOrNull { 
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND 
                }?.processName?.let { name ->
                    !name.contains("snapchat") && !name.contains("purrfectsnap")
                } ?: false

                // Immediate logging for visibility in [RESOURCE] filter
                context.log.info("[RESOURCE] AutoOpen: Processing snap from ${snapItem.senderInfo}. Current state: WiFi=$isWifi, Idle=$isIdle, Gaming=$isGaming")

                while (
                    (config.onlyOnWifi.get() && !isWifi) ||
                    (config.onlyWhenIdle.get() && !isIdle) ||
                    (config.pauseDuringGaming.get() && isGaming)
                ) {
                    val waitTime = if (isGaming) 60000L else 5000L
                    context.log.warn("[RESOURCE] AutoOpen: Throttling queue due to resource constraints. Waiting ${waitTime}ms")
                    delay(waitTime)
                    if (!kotlin.coroutines.coroutineContext.isActive) return@collect
                }

                // Stealth Pacing: Apply variable delays to remain undetected
                if (config.safeProcessing.get()) {
                    batchSnapCount++
                    val isRollingCooldown = batchSnapCount % 10 == 0
                    val minDelayMs = if (isRollingCooldown) 5000L else config.minDelay.get().toLong()
                    val maxDelayMs = if (isRollingCooldown) 10000L else config.maxDelayMs.get().toLong()
                    
                    val jitter = if (maxDelayMs > minDelayMs) {
                        java.util.concurrent.ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs)
                    } else minDelayMs
                    
                    context.log.verbose("[AUTO-OPEN] Stealth Pacing active. Waiting ${jitter}ms")
                    delay(jitter)
                } else {
                    context.log.verbose("[AUTO-OPEN] Stealth Pacing disabled. Executing at maximum speed.")
                }

                var result: String? = null
                var lastError = ""

                for (i in 0 until config.retryAttempts.get()) {
                    while ((!config.allowRunningInBackground.get() && context.isMainActivityPaused) || messaging.conversationManager == null) {
                        delay(1000)
                    }

                    result = suspendCoroutine { continuation ->
                        runCatching {
                            messaging.conversationManager?.updateMessage(snapItem.conversationId, snapItem.messageId, MessageUpdate.READ) { result ->
                                continuation.resume(result)
                            }
                        }.getOrNull() ?: continuation.resume("ConversationManager is null")
                    }

                    if (result == null || result == "DUPLICATEREQUEST") {
                        break
                    }
                    
                    lastError = result
                    if (i < config.retryAttempts.get() - 1) {
                        delay(config.retryDelay.get().toLong())
                    }
                }

                if (result == null || result == "DUPLICATEREQUEST") {
                    totalProcessed++
                    currentBatchProcessed++
                    context.log.verbose("[AUTO-OPEN] Successfully opened ${snapItem.contentType} from ${snapItem.senderInfo}")
                } else {
                    context.log.error("[AUTO-OPEN] Failed to open ${snapItem.contentType} from ${snapItem.senderInfo}: $lastError")
                }

                if (synchronized(queuedSnaps) { queuedSnaps.size } <= 0) {
                    currentBatchSize = 0
                    currentBatchProcessed = 0
                }
                
                updateStatusNotification()
            }
        }

        context.event.subscribe(BuildMessageEvent::class, priority = 103) { event ->
            if (event.message.senderId?.toString() == context.database.myUserId) return@subscribe
            val conversationId = event.message.messageDescriptor?.conversationId?.toString() ?: return@subscribe
            val clientMessageId = event.message.messageDescriptor?.messageId ?: return@subscribe

            val contentType = event.message.messageContent?.contentType
            
            if (contentType != ContentType.SNAP && contentType != ContentType.EXTERNAL_MEDIA) {
                return@subscribe
            }
            
            if (event.message.messageMetadata?.openedBy?.any { it.toString() == context.database.myUserId } == true) {
                return@subscribe
            }

                context.coroutineScope.launch(Dispatchers.Default) {
                if (!canUseRule(conversationId)) return@launch
                synchronized(openedSnaps) {
                    if (openedSnaps.contains(clientMessageId)) {
                        return@launch
                    }
                    if (openedSnaps.size >= 500) openedSnaps.removeFirst()
                    openedSnaps.addLast(clientMessageId)
                }

                val senderId = event.message.senderId?.toString() ?: context.translation["auto_open_snaps.unknown_sender"]
                val senderInfo = getSenderDisplayName(senderId)
                val conversationType = getConversationType(conversationId, senderId)
                val contentType = getSnapContentType(event.message.messageContent?.contentType)

                val snapItem = SnapQueueItem(
                    conversationId = conversationId,
                    messageId = clientMessageId,
                    senderInfo = senderInfo,
                    conversationType = conversationType,
                    contentType = contentType
                )

                synchronized(queuedSnaps) {
                    val existingItem = queuedSnaps.find { it.messageId == snapItem.messageId }
                    if (existingItem != null) {
                        return@launch
                    }
                    
                    if (queuedSnaps.size >= config.queueSize.get()) {
                        queuedSnaps.removeFirstOrNull()
                    }
                    
                    queuedSnaps.add(snapItem)
                    currentBatchSize = maxOf(currentBatchSize, queuedSnaps.size)
                }
                
                updateStatusNotification()
                
                snapQueue.emit(snapItem)
            }
        }
    }

    override fun onBridgeAction(action: String, extras: Map<String, Any>?, callback: (Any?) -> Unit) {
        if (action == "get_auto_open_status") {
            val status = mutableMapOf<String, Any>()
            status["processed"] = totalProcessed
            status["queue"] = synchronized(queuedSnaps) {
                queuedSnaps.map { item ->
                    mapOf(
                        "senderInfo" to item.senderInfo,
                        "contentType" to item.contentType,
                        "conversationType" to item.conversationType
                    )
                }
            }
            callback(status)
        }
    }

    private fun getSenderDisplayName(senderId: String): String {
        return try {
            val friendInfo = context.database.getFriendInfo(senderId)
            friendInfo?.displayName ?: friendInfo?.mutableUsername ?: context.translation["auto_open_snaps.unknown_user"]
        } catch (e: Exception) {
            context.translation["auto_open_snaps.unknown_user"]
        }
    }

    private fun getSnapContentType(contentType: ContentType?): String {
        return when (contentType) {
            ContentType.SNAP -> context.translation["auto_open_snaps.content_type_photo_video_snap"]
            ContentType.EXTERNAL_MEDIA -> context.translation["auto_open_snaps.content_type_external_media"]
            else -> context.translation["auto_open_snaps.content_type_snap"]
        }
    }

    private fun getConversationType(conversationId: String, senderId: String): String {
        return try {
            val dmParticipant = context.database.getDMOtherParticipant(conversationId)
            
            if (dmParticipant != null) {
                val friendInfo = context.database.getFriendInfo(senderId)
                return when {
                    friendInfo != null -> context.translation["auto_open_snaps.conversation_type_friend_dm"]
                    else -> context.translation["auto_open_snaps.conversation_type_dm"]
                }
            } else {
                val feedEntry = context.database.getFeedEntryByConversationId(conversationId)
                val groupName = feedEntry?.feedDisplayName?.takeIf { it.isNotBlank() }
                
                return if (groupName != null) {
                    context.translation["auto_open_snaps.conversation_type_group_with_name"].replace("{name}", groupName)
                } else {
                    context.translation["auto_open_snaps.conversation_type_group_chat"]
                }
            }
        } catch (e: Exception) {
            context.translation["auto_open_snaps.conversation_type_chat"]
        }
    }
}
