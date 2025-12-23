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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class AutoOpenSnaps: MessagingRuleFeature("Auto Open Snaps", MessagingRuleType.AUTO_OPEN_SNAPS) {
    companion object {
        const val ACTION_PAUSE_RESUME = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_PAUSE_RESUME"
        const val ACTION_CLEAR_QUEUE = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_CLEAR_QUEUE"
    }

    data class SnapQueueItem(
        val conversationId: String,
        val messageId: Long,
        val senderInfo: String,
        val conversationType: String,
        val contentType: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val snapQueue = MutableSharedFlow<SnapQueueItem>()
    private var snapQueueSize = AtomicInteger(0)
    private val openedSnaps = mutableListOf<Long>()
    private val isPaused = AtomicBoolean(false)
    private val queuedSnaps = mutableListOf<SnapQueueItem>()
    private var totalProcessed = AtomicInteger(0)
    
    private var sessionStartTime = System.currentTimeMillis()
    private var lastResetTime = System.currentTimeMillis()
    
    private var currentBatchSize = AtomicInteger(0)
    private var currentBatchProcessed = AtomicInteger(0)

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
                        this@AutoOpenSnaps.context.translation["auto_open_snaps.paused_message"].replace("{count}", snapQueueSize.get().toString())
                    }
                    
                    showTemporaryNotification(feedbackTitle, feedbackContent)
                    updateStatusNotification()
                    
            
                    if (wasPaused && snapQueueSize.get() > 0) {
                        this@AutoOpenSnaps.context.log.debug("Resumed with ${snapQueueSize.get()} snaps in queue")
                    }
                }
                ACTION_CLEAR_QUEUE -> {
                    val queueSize = snapQueueSize.get()
                    val processedCount = totalProcessed.get()
                    
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
                NotificationManager.IMPORTANCE_LOW).apply {
                description = context.translation["auto_open_snaps.channel_description"]
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        
        notificationManager.createNotificationChannel(
            NotificationChannel(priorityChannelId,
                context.translation["auto_open_snaps.priority_title"],
                NotificationManager.IMPORTANCE_DEFAULT).apply {
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
            val actualSize = queuedSnaps.size
            val atomicSize = snapQueueSize.get()
            
            val currentTime = System.currentTimeMillis()
            val timeoutMs = 5 * 60 * 1000L
            val originalSize = queuedSnaps.size
            
            val removed = mutableListOf<Long>()
            queuedSnaps.removeAll { item ->
                val stuck = (currentTime - item.timestamp) > timeoutMs
                if (stuck) removed.add(item.messageId)
                stuck
            }
            
            if (removed.isNotEmpty()) {
                context.log.warn("Cleaned up ${removed.size} stuck items")
            }
            
            if (actualSize != atomicSize) {
                context.log.warn("Queue size mismatch! Actual: $actualSize, Atomic: $atomicSize")
                snapQueueSize.set(queuedSnaps.size)
                
                val uniqueItems = queuedSnaps.distinctBy { it.messageId }.toMutableList()
                if (uniqueItems.size != queuedSnaps.size) {
                    context.log.warn("Found ${queuedSnaps.size - uniqueItems.size} duplicate items")
                    queuedSnaps.clear()
                    queuedSnaps.addAll(uniqueItems)
                    snapQueueSize.set(queuedSnaps.size)
                }
                return@synchronized false
            } else {
                snapQueueSize.set(queuedSnaps.size)
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
        
        val queueCount = snapQueueSize.get()
        val processed = totalProcessed.get()
        
        if (queueCount <= 0 && processed <= 0) {
            notificationManager.cancel(statusNotificationId)
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
            val batchSize = currentBatchSize.get()
            val batchProcessed = currentBatchProcessed.get()
            val progressMax = maxOf(batchSize, queueCount + batchProcessed)
            val progressCurrent = batchProcessed
            
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

        val recentSnaps = queuedSnaps.takeLast(5)
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
            snapQueueSize.set(0)
            count
        }
        
        synchronized(openedSnaps) {
            openedSnaps.clear()
        }
        
        if (resetTotalCount) {
            totalProcessed.set(0)
            lastResetTime = System.currentTimeMillis()
        }
        
        currentBatchSize.set(0)
        currentBatchProcessed.set(0)
        
        verifyQueueSync()
        
        notificationManager.cancel(statusNotificationId)
        
        if (showNotification) {
            val message = if (resetTotalCount) {
                context.translation["auto_open_snaps.queue_cleared"]
            } else {
                context.translation["auto_open_snaps.notification_queue_cleared_opened"].replace("{opened}", totalProcessed.get().toString())
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
                                snapQueueSize.set(queuedSnaps.size)
                                wasAddedToPausedQueue = true
                                updateStatusNotification()
                            }
                        }
                    }
                    delay(2000)
                }

                val queueSizeAfterRemoval = synchronized(queuedSnaps) {
                    queuedSnaps.removeAll { it.messageId == snapItem.messageId }
                    snapQueueSize.set(queuedSnaps.size)
                    queuedSnaps.size
                }

                val minDelayMs = config.minDelay.get().toLong()
                val maxDelayMs = config.maxDelayMs.get().toLong()
                val delayMs = if (maxDelayMs > minDelayMs) {
                    Random.nextLong(minDelayMs, maxDelayMs)
                } else {
                    minDelayMs
                }
                delay(delayMs)
                var result: String? = null
                var lastError = ""

                for (i in 0 until config.retryAttempts.get()) {
                    while ((!config.allowRunningInBackground.get() && context.isMainActivityPaused) || messaging.conversationManager == null) {
                        delay(2000)
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
                    totalProcessed.incrementAndGet()
                    currentBatchProcessed.incrementAndGet()
                } else {
                    context.log.error("Failed to open ${snapItem.contentType} from ${snapItem.senderInfo}: $lastError")
                }

                val finalQueueSize = snapQueueSize.get()
                
                if (finalQueueSize <= 0) {
                    currentBatchSize.set(0)
                    currentBatchProcessed.set(0)
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
                    openedSnaps.add(clientMessageId)
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

                val actualQueueSize = synchronized(queuedSnaps) {
                    val existingItem = queuedSnaps.find { it.messageId == snapItem.messageId }
                    if (existingItem != null) {
                        return@launch
                    }
                    
                    if (queuedSnaps.size >= config.queueSize.get()) {
                        queuedSnaps.removeFirstOrNull()
                    }
                    
                    queuedSnaps.add(snapItem)
                    snapQueueSize.set(queuedSnaps.size)
                    
                    val newSize = queuedSnaps.size
                    currentBatchSize.set(maxOf(currentBatchSize.get(), newSize))
                    
                    queuedSnaps.size
                }
                
                updateStatusNotification()
                
                snapQueue.emit(snapItem)
            }
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
