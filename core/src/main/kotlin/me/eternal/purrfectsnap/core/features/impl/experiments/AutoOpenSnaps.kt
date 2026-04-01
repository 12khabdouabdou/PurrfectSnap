package me.eternal.purrfectsnap.core.features.impl.experiments

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import me.eternal.purrfectsnap.bridge.AutoOpenInterface
import me.eternal.purrfectsnap.common.BuildConfig
import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.data.MessageUpdate
import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.core.event.events.impl.BuildMessageEvent
import me.eternal.purrfectsnap.core.features.MessagingRuleFeature
import me.eternal.purrfectsnap.core.features.impl.messaging.Messaging
import me.eternal.purrfectsnap.core.features.impl.tweaks.PerformanceMode
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.random.Random

class AutoOpenSnaps: MessagingRuleFeature("Auto Open Snaps", MessagingRuleType.AUTO_OPEN_SNAPS) {
    companion object {
        const val ACTION_PAUSE_RESUME = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_PAUSE_RESUME"
        const val ACTION_CLEAR_QUEUE = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_CLEAR_QUEUE"
        private const val STATUS_NOTIFICATION_ID = 54321
        private const val NOTIFICATION_GROUP_KEY = "purrfectsnap.AUTO_OPEN"
        private const val PREF_TOTAL_OPENED = "auto_open_total_opened"
        private const val PREF_TOTAL_DETECTED = "auto_open_total_detected"
        private const val PREF_SESSION_START = "auto_open_session_start"
        private const val PREF_SAVED_QUEUE = "auto_open_saved_queue"
    }

    private val gson = Gson()
    private val isPaused = AtomicBoolean(false)
    private val totalProcessed = AtomicInteger(0)
    private val totalDetected = AtomicInteger(0)
    private val sessionProcessed = AtomicInteger(0)
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val totalPausedDuration = AtomicLong(0)
    private var lastPausedAt = AtomicLong(0)
    private val averageProcessingTime = AtomicLong(800)
    private val hasBeenActive = AtomicBoolean(false)

    private val snapQueue = MutableSharedFlow<Long>(extraBufferCapacity = 100)
    private val openedSnaps = ConcurrentHashMap.newKeySet<Long>()
    private val queuedSnaps = mutableListOf<SnapQueueItem>()
    private val deadLetterQueue = mutableListOf<SnapQueueItem>()

    private val nameCache = ConcurrentHashMap<String, String>()
    private val conversationTypeCache = ConcurrentHashMap<String, String>()

    private val config by lazy { context.config.messaging.autoOpenSnaps }
    private val notificationManager by lazy { context.androidContext.getSystemService(NotificationManager::class.java) }
    private val prefs by lazy { context.androidContext.getSharedPreferences("me.eternal.purrfectsnap_preferences", Context.MODE_PRIVATE) }

    private var lastConversationId: String? = null
    private var batchSnapCount = 0
    private var currentStatusText = "Monitoring..."
    private var currentSpeedText = "Full Speed"
    private var isCurrentlyWaiting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastQueueActivity = System.currentTimeMillis()

    // Throttling & Performance fields
    private val lastNotificationUpdate = AtomicLong(0)
    private val notificationUpdateDelay = 1000L
    private val pendingNotificationUpdate = AtomicBoolean(false)
    private val processedSinceLastSave = AtomicInteger(0)
    private val snapTimestamps = LinkedList<Long>()
    
    // Safety & Synergy
    private val isSaving = AtomicBoolean(false)
    private val needsSaving = AtomicBoolean(false)
    private var isThermalThrottled = false
    private var lastThermalThrottleAt = 0L

    data class SnapQueueItem(
        val conversationId: String,
        val messageId: Long,
        val senderId: String,
        var senderName: String = "Pending...",
        var conversationType: String = "Processing",
        val contentType: String,
        val timestamp: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    )

    private val autoOpenInterface = object : AutoOpenInterface.Stub() {
        override fun getProcessedCount(): Int = totalProcessed.get()
        override fun getQueueItems(): List<String> = synchronized(queuedSnaps) { queuedSnaps.map { gson.toJson(it) } }
        override fun reset() {
            clearInternalState()
        }
    }

    private fun clearInternalState() {
        resetPersistence()
        totalProcessed.set(0)
        totalDetected.set(0)
        sessionProcessed.set(0)
        totalPausedDuration.set(0)
        lastPausedAt.set(0)
        sessionStartTime.set(System.currentTimeMillis())
        synchronized(queuedSnaps) { queuedSnaps.clear() }
        synchronized(deadLetterQueue) { deadLetterQueue.clear() }
        openedSnaps.clear()

        prefs.edit()
            .putInt(PREF_TOTAL_OPENED, 0)
            .putInt(PREF_TOTAL_DETECTED, 0)
            .putLong(PREF_SESSION_START, System.currentTimeMillis())
            .remove(PREF_SAVED_QUEUE)
            .apply()

        updateStatusNotification()
    }

    fun getInterface(): AutoOpenInterface = autoOpenInterface

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_RESUME -> {
                    val paused = !isPaused.get()
                    isPaused.set(paused)
                    if (paused) {
                        lastPausedAt.set(System.currentTimeMillis())
                    } else {
                        if (lastPausedAt.get() > 0) {
                            totalPausedDuration.addAndGet(System.currentTimeMillis() - lastPausedAt.get())
                        }
                        snapQueue.tryEmit(System.currentTimeMillis())
                    }
                    updateStatusNotification()
                }
                ACTION_CLEAR_QUEUE -> {
                    synchronized(queuedSnaps) { queuedSnaps.clear() }
                    synchronized(deadLetterQueue) { deadLetterQueue.clear() }
                    triggerLazySave()
                    updateStatusNotification()
                }
            }
        }
    }

    override fun init() {
        val messaging = context.feature(Messaging::class)
        restorePersistence()
        hasBeenActive.set(true)

        if (config.allowRunningInBackground.get()) {
            acquireWakeLock()
            findClass("com.snapchat.client.duplex.DuplexClient\$CppProxy").apply {
                hook("appStateChanged", HookStage.BEFORE) { param ->
                    if (config.allowRunningInBackground.get()) {
                        val state = param.arg<Any>(0).toString()
                        if (state == "INACTIVE" || state == "BACKGROUND") {
                            param.setResult(null)
                        }
                    }
                }
                hookConstructor(HookStage.AFTER) { param ->
                    methods.firstOrNull { it.name == "appStateChanged" }?.let { method ->
                        val enumClass = method.parameterTypes[0]
                        val activeState = enumClass.enumConstants?.firstOrNull { 
                            it.toString() == "ACTIVE" || it.toString() == "FOREGROUND" 
                        }
                        if (activeState != null) {
                            method.invoke(param.thisObject<Any>(), activeState)
                        }
                    }
                }
            }
            findClass("com.snapchat.client.network_manager.NetworkManager\$CppProxy").apply {
                hook("onAppForegrounded", HookStage.BEFORE) { param ->
                    if (config.allowRunningInBackground.get()) param.setResult(null)
                }
                hook("onAppBackgrounded", HookStage.BEFORE) { param ->
                    if (config.allowRunningInBackground.get()) param.setResult(null)
                }
            }
        }

        createNotificationChannels()
        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE_RESUME)
            addAction(ACTION_CLEAR_QUEUE)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED && config.thermalProtection.get()) {
                    val temp = intent.getIntExtra("temperature", 0) / 10f
                    if (temp >= 40f && !isThermalThrottled) {
                        isThermalThrottled = true
                        lastThermalThrottleAt = System.currentTimeMillis()
                        context.log.warn("[THERMAL] Device hit ${temp}C. Throttling AutoOpen.")
                    } else if (isThermalThrottled && temp <= 36f && System.currentTimeMillis() - lastThermalThrottleAt > 600000) {
                        isThermalThrottled = false
                        context.log.info("[THERMAL] Device cooled to ${temp}C. Resuming full speed.")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.androidContext.registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            context.androidContext.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.androidContext.registerReceiver(actionReceiver, filter)
            context.androidContext.registerReceiver(batteryReceiver, filter)
        }

        if (synchronized(queuedSnaps) { queuedSnaps.isNotEmpty() }) {
            snapQueue.tryEmit(System.currentTimeMillis())
        }

        context.coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (config.globalState != true) {
                    shutdownFeature()
                    break
                }
                val remainingCount = synchronized(queuedSnaps) { queuedSnaps.size }
                if (remainingCount == 0 && sessionProcessed.get() > 0) {
                    sessionProcessed.set(0)
                    triggerLazySave()
                }
                
                if (remainingCount > 0) {
                    lastQueueActivity = System.currentTimeMillis()
                    acquireWakeLock()
                    if (!isPaused.get()) snapQueue.tryEmit(System.currentTimeMillis())
                } else {
                    // IDLE REVIVAL: Check dead letter queue every 5 mins when idle
                    if (!isPaused.get() && System.currentTimeMillis() - lastQueueActivity > 300000) {
                        val revived = synchronized(deadLetterQueue) {
                            if (deadLetterQueue.isNotEmpty()) deadLetterQueue.removeAt(0) else null
                        }
                        if (revived != null) {
                            synchronized(queuedSnaps) { queuedSnaps.add(revived) }
                            snapQueue.tryEmit(System.currentTimeMillis())
                        }
                    }
                    
                    if (System.currentTimeMillis() - lastQueueActivity > 600000) { // 10 mins true idle
                        releaseWakeLock()
                    }
                }

                updateStatusNotification()
                delay(5000)
            }
        }

        context.coroutineScope.launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            snapQueue.collect { _ ->
                while (isActive && config.globalState == true) {
                    if (isPaused.get()) {
                        delay(1000)
                        continue
                    }
                    
                    val item = synchronized(queuedSnaps) { 
                        if (queuedSnaps.isNotEmpty()) queuedSnaps.removeAt(0) else null 
                    } ?: break

                    var resourceWaiting = true
                    while (resourceWaiting) {
                        if (config.globalState != true || isPaused.get()) break
                        val isWifi = isWifiConnected()
                        val isIdle = isDeviceIdle()
                        val isGaming = isGaming()
                        val inSleepWindow = if (config.onlyWhenIdle.get()) isInsideSleepWindow() else false

                        when {
                            config.onlyOnWifi.get() && !isWifi -> {
                                currentStatusText = context.translation["auto_open_snaps.only_on_wifi.name"] ?: "Waiting for WiFi..."
                                currentSpeedText = "Throttled"
                                isCurrentlyWaiting = true
                                delay(5000)
                            }
                            config.onlyWhenIdle.get() && !isIdle && !inSleepWindow -> {
                                currentStatusText = context.translation["auto_open_snaps.only_when_idle.name"] ?: "Waiting for idle..."
                                currentSpeedText = "Throttled"
                                isCurrentlyWaiting = true
                                delay(5000)
                            }
                            config.pauseDuringGaming.get() && isGaming -> {
                                currentStatusText = context.translation["auto_open_snaps.pause_during_gaming.name"] ?: "Paused (Gaming Mode)"
                                currentSpeedText = "Paused"
                                isCurrentlyWaiting = true
                                delay(60000)
                            }
                            else -> {
                                resourceWaiting = false
                                currentSpeedText = if (inSleepWindow || isThermalThrottled) "Throttled" else "Full Speed"
                            }
                        }
                        if (resourceWaiting) updateStatusNotification()
                    }

                    if (isPaused.get() || config.globalState != true) {
                        synchronized(queuedSnaps) { queuedSnaps.add(0, item) }
                        continue
                    }
                    isCurrentlyWaiting = false

                    val inSleepWindow = if (config.onlyWhenIdle.get()) isInsideSleepWindow() else false
                    if (inSleepWindow || isThermalThrottled) {
                        currentStatusText = if (isThermalThrottled) context.translation["auto_open_snaps.thermal_status_title"] ?: "Thermal Cooling" else context.translation["auto_open_snaps.speed_throttled"] ?: "Throttled"
                        delay(Random.nextLong(3000, 5000))
                    } else if (lastConversationId != null && lastConversationId != item.conversationId) {
                        currentStatusText = "Switching chats..."
                        delay(Random.nextLong(1500, 2500))
                        batchSnapCount = 0
                        triggerLazySave()
                    } else if (lastConversationId == item.conversationId) {
                        when {
                            isThermalThrottled -> delay(Random.nextLong(100, 150))
                            config.safeProcessing.get() -> delay(Random.nextLong(50, 150))
                            else -> delay(Random.nextLong(10, 40))
                        }
                    }

                    lastConversationId = item.conversationId
                    currentStatusText = context.translation["auto_open_snaps.status_active"] ?: "Opening snap..."
                    updateStatusNotification()

                    var success = false
                    val startTime = System.currentTimeMillis()
                    var currentRetryDelay = config.retryDelay.get().toLong()

                    for (i in 0 until config.retryAttempts.get()) {
                        if (isPaused.get() || config.globalState != true) break
                        while ((!config.allowRunningInBackground.get() && context.isMainActivityPaused) || (messaging.conversationManager == null && !config.allowRunningInBackground.get())) {
                            if (config.globalState != true || isPaused.get()) break
                            currentStatusText = "Waiting for UI..."
                            isCurrentlyWaiting = true
                            updateStatusNotification()
                            delay(2000)
                        }
                        if (isPaused.get() || config.globalState != true) break
                        isCurrentlyWaiting = false

                        success = performOpen(messaging, item)
                        if (success) {
                            totalProcessed.incrementAndGet()
                            sessionProcessed.incrementAndGet()
                            synchronized(snapTimestamps) { 
                                snapTimestamps.addLast(System.currentTimeMillis())
                                if (snapTimestamps.size > 100) snapTimestamps.removeFirst()
                            }
                            val duration = System.currentTimeMillis() - startTime
                            averageProcessingTime.set((averageProcessingTime.get() * 0.7 + duration * 0.3).toLong())
                            
                            val remaining = synchronized(queuedSnaps) { queuedSnaps.size }
                            val threshold = if (remaining > 100) 100 else 25
                            if (processedSinceLastSave.incrementAndGet() >= threshold) {
                                triggerLazySave()
                                processedSinceLastSave.set(0)
                            }
                            break
                        }
                        if (i < config.retryAttempts.get() - 1) {
                            currentStatusText = context.translation["auto_open_snaps.status_retrying"] ?: "Retrying..."
                            updateStatusNotification()
                            delay(currentRetryDelay); currentRetryDelay *= 2
                        }
                    }

                    if (!success && !isPaused.get()) {
                        currentStatusText = context.translation["auto_open_snaps.status_failed"]?.replace("{sender}", item.senderName) ?: "Failed to open"
                        updateStatusNotification()
                        
                        // MOVE TO DEAD LETTER QUEUE (Revival Engine)
                        synchronized(deadLetterQueue) {
                            if (deadLetterQueue.size < 100) deadLetterQueue.add(item)
                            else { deadLetterQueue.removeAt(0); deadLetterQueue.add(item) }
                        }
                        delay(2000)
                    }

                    if (synchronized(queuedSnaps) { queuedSnaps.isEmpty() }) {
                        delay(500)
                        if (synchronized(queuedSnaps) { queuedSnaps.isEmpty() }) {
                            currentStatusText = context.translation["auto_open_snaps.status_monitoring"] ?: "Monitoring..."
                            isCurrentlyWaiting = false
                            triggerLazySave()
                            updateStatusNotification()
                        }
                    }
                }
            }
        }

        context.event.subscribe(BuildMessageEvent::class, priority = 103) { event ->
            if (config.globalState != true) return@subscribe
            if (event.message.senderId?.toString() == context.database.myUserId) return@subscribe
            val conversationId = event.message.messageDescriptor?.conversationId?.toString() ?: return@subscribe
            val clientMessageId = event.message.messageDescriptor?.messageId ?: return@subscribe
            val contentType = event.message.messageContent?.contentType
            if (contentType != ContentType.SNAP && contentType != ContentType.EXTERNAL_MEDIA) return@subscribe
            if (event.message.messageMetadata?.openedBy?.any { it.toString() == context.database.myUserId } == true) return@subscribe

            acquireWakeLock()
            
            context.coroutineScope.launch(Dispatchers.Default) {
                if (!canUseRule(conversationId)) return@launch
                if (!openedSnaps.add(clientMessageId)) return@launch
                if (openedSnaps.size > 15000) openedSnaps.clear()
                val senderId = event.message.senderId?.toString() ?: "unknown"
                val item = SnapQueueItem(conversationId, clientMessageId, senderId, getSenderDisplayName(senderId), getConversationType(conversationId, senderId), getSnapContentType(contentType))
                synchronized(queuedSnaps) {
                    if (queuedSnaps.size >= config.queueSize.get()) queuedSnaps.removeFirstOrNull()
                    queuedSnaps.add(item); totalDetected.incrementAndGet()
                }
                if (!isPaused.get()) {
                    snapQueue.tryEmit(System.currentTimeMillis())
                }
            }
        }
    }

    private suspend fun performOpen(messaging: Messaging, item: SnapQueueItem): Boolean = withContext(Dispatchers.IO) {
        val manager = messaging.conversationManager ?: return@withContext false
        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                runCatching {
                    manager.updateMessage(item.conversationId, item.messageId, MessageUpdate.READ) { result ->
                        cont.resume(result == null || result == "DUPLICATEREQUEST")
                    }
                }.onFailure { cont.resume(false) }
            }
        } ?: false
    }

    private fun formatDuration(millis: Long): String {
        val s = (millis / 1000) % 60; val m = (millis / 60000) % 60; val h = millis / 3600000
        return when { h > 0 -> "${h}h ${m}m ${s}s"; m > 0 -> "${m}m ${s}s"; else -> "${s}s" }
    }

    private fun getSnapsPerSecond(): Double {
        val now = System.currentTimeMillis()
        val window = 5000L
        synchronized(snapTimestamps) {
            snapTimestamps.removeIf { now - it > window }
            return (snapTimestamps.size.toDouble() / (window / 1000.0))
        }
    }

    private fun updateStatusNotification() {
        val currentTime = System.currentTimeMillis()
        val lastUpdate = lastNotificationUpdate.get()

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
        val processed = sessionProcessed.get()
        val total = totalProcessed.get()
        val remaining = synchronized(queuedSnaps) { queuedSnaps.size }
        if (total <= 0 && remaining <= 0 && processed <= 0) return

        val isWorking = remaining > 0
        val isCompact = config.compactNotification.get() == true
        val sessionTotal = processed + remaining
        val progressPercent = if (sessionTotal > 0) (processed * 100) / sessionTotal else 0
        val speed = getSnapsPerSecond()

        val builder = Notification.Builder(context.androidContext, "auto_open_snaps")
            .setSmallIcon(if (isPaused.get()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setOngoing(isWorking).setAutoCancel(!isWorking).setOnlyAlertOnce(true)
            .setGroup(NOTIFICATION_GROUP_KEY).setGroupSummary(false)

        val eta = if (isWorking && !isCurrentlyWaiting && !isPaused.get()) formatDuration(remaining * averageProcessingTime.get()) else null

        // Refined Collapsed Logic
        builder.setContentTitle("Auto-Open: $currentStatusText")
        
        if (isWorking) {
            builder.setContentText("Opened: $processed │ ETA: ${eta ?: "..."}")
            builder.setSubText("$progressPercent% • $remaining Queued")
        } else {
            // Idle stats for collapsed view
            builder.setContentText("$processed Opened Today │ $total Lifetime")
            builder.setSubText("Monitoring Snaps...")
        }
        
        builder.setProgress(if (isWorking) sessionTotal else 0, if (isWorking) processed else 0, !isWorking) 

        builder.addAction(Notification.Action.Builder(null, if (isPaused.get()) "Resume" else "Pause", createPendingIntent(ACTION_PAUSE_RESUME)).build())
        builder.addAction(Notification.Action.Builder(null, "Clear Queue", createPendingIntent(ACTION_CLEAR_QUEUE)).build())

        if (!isCompact) {
            val recentSnaps = synchronized(queuedSnaps) { queuedSnaps.takeLast(5) }
            val bigTextStyle = Notification.BigTextStyle()
            
            val detailText = buildString {
                append("QUEUE STATISTICS\n")
                append("├─ Opened: $processed snaps\n")
                append("├─ Remaining: $remaining snaps\n")
                if (eta != null) append("├─ Estimated time: $eta\n")
                append("├─ Lifetime Opened: $total snaps\n")
                append("└─ Speed: $currentSpeedText (${String.format("%.1f", speed)}/s)\n\n")

                append("QUEUE PREVIEW\n")
                if (isWorking) {
                    recentSnaps.reversed().forEach { item ->
                        append("• ${item.senderName} │ ${item.conversationType} (${item.contentType})\n")
                    }
                } else {
                    append(context.translation["auto_open_snaps.notification_no_snaps_queue"] ?: "Monitoring snaps in background...")
                }
            }
            bigTextStyle.bigText(detailText)
            bigTextStyle.setSummaryText(null) 
            builder.setStyle(bigTextStyle)
        }
        
        notificationManager.notify(STATUS_NOTIFICATION_ID, builder.build())
    }

    private fun shutdownFeature() {
        notificationManager.cancel(STATUS_NOTIFICATION_ID)
        val finalCount = totalProcessed.get()
        if (hasBeenActive.get()) {
            val elapsedMillis = System.currentTimeMillis() - sessionStartTime.get() - totalPausedDuration.get()
            val durationMins = maxOf(0, elapsedMillis / 60000)
            val summary = Notification.Builder(context.androidContext, "auto_open_snaps")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Auto-Open: Deactivated")
                .setContentText("Opened: $finalCount snaps | Session: ${durationMins}m")
                .setGroup(NOTIFICATION_GROUP_KEY)
                .setAutoCancel(true).build()
            notificationManager.notify(Random.nextInt(), summary)
            hasBeenActive.set(false)
        }
        triggerLazySave()
        releaseWakeLock()
    }

    private fun triggerLazySave() {
        needsSaving.set(true)
        if (isSaving.compareAndSet(false, true)) {
            context.coroutineScope.launch(Dispatchers.IO) {
                while (needsSaving.get()) {
                    needsSaving.set(false)
                    saveToDiskInternal()
                    delay(1000)
                }
                isSaving.set(false)
            }
        }
    }

    private fun saveToDiskInternal() {
        prefs.edit {
            putInt(PREF_TOTAL_OPENED, totalProcessed.get())
            putInt(PREF_TOTAL_DETECTED, totalDetected.get())
            putLong(PREF_SESSION_START, sessionStartTime.get())
            synchronized(queuedSnaps) {
                putString(PREF_SAVED_QUEUE, gson.toJson(queuedSnaps))
            }
        }
    }

    private fun restorePersistence() {
        totalProcessed.set(prefs.getInt(PREF_TOTAL_OPENED, 0))
        totalDetected.set(prefs.getInt(PREF_TOTAL_DETECTED, 0))
        sessionStartTime.set(prefs.getLong(PREF_SESSION_START, System.currentTimeMillis()))
        val savedQueueJson = prefs.getString(PREF_SAVED_QUEUE, null)
        if (!savedQueueJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<SnapQueueItem>>() {}.type
                val restored: List<SnapQueueItem> = gson.fromJson(savedQueueJson, type)
                val now = System.currentTimeMillis()
                synchronized(queuedSnaps) { 
                    queuedSnaps.clear()
                    queuedSnaps.addAll(restored.filter { (now - it.timestamp) < 3600000 }) 
                }
            } catch (e: Exception) { 
                prefs.edit().remove(PREF_SAVED_QUEUE).apply()
            }
        }
    }

    private fun resetPersistence() {
        prefs.edit().remove(PREF_TOTAL_OPENED).remove(PREF_TOTAL_DETECTED).remove(PREF_SESSION_START).remove(PREF_SAVED_QUEUE).apply()
        synchronized(queuedSnaps) { queuedSnaps.clear() }
    }

    private fun isInsideSleepWindow(): Boolean {
        try {
            val sleepWindow = config.sleepWindow.get()
            if (!sleepWindow.contains("-") || !sleepWindow.contains(":")) return false
            val window = sleepWindow.split("-")
            if (window.size != 2) return false
            val startStr = window[0].split(":")
            val endStr = window[1].split(":")
            if (startStr.size != 2 || endStr.size != 2) return false
            val now = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, startStr[0].toInt()); set(Calendar.MINUTE, startStr[1].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val end = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, endStr[0].toInt()); set(Calendar.MINUTE, endStr[1].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            return if (end.before(start)) now.after(start) || now.before(end) else now.after(start) && now.before(end)
        } catch (e: Exception) { return false }
    }

    private fun isGaming(): Boolean {
        val am = context.androidContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.processName?.let {
            !it.contains("snapchat") && !it.contains("purrfectsnap")
        } ?: false
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.androidContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        } else {
            @Suppress("DEPRECATION") cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private fun isDeviceIdle(): Boolean = (context.androidContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = context.androidContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PurrfectSnap:AutoOpen")
            wakeLock?.acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply { setPackage(context.androidContext.packageName) }
        return PendingIntent.getBroadcast(context.androidContext, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel("auto_open_snaps", "Auto Open Snaps", NotificationManager.IMPORTANCE_LOW).apply {
            enableVibration(false); setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun getSenderDisplayName(senderId: String): String = nameCache.getOrPut(senderId) {
        context.database.getFriendInfo(senderId)?.let { it.displayName ?: it.mutableUsername } ?: "Unknown"
    }

    private fun getConversationType(conversationId: String, senderId: String): String = conversationTypeCache.getOrPut("$conversationId:$senderId") {
        if (context.database.getDMOtherParticipant(conversationId) != null) "Friend DM"
        else context.database.getFeedEntryByConversationId(conversationId)?.feedDisplayName ?: "Group Chat"
    }

    private fun getSnapContentType(type: ContentType?): String = when (type) {
        ContentType.SNAP -> "Photo/Video"
        ContentType.EXTERNAL_MEDIA -> "Media"
        else -> "Snap"
    }
}
