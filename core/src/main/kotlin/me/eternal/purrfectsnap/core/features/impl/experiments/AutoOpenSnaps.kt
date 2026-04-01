package me.eternal.purrfectsnap.core.features.impl.experiments

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.util.Calendar
import kotlin.random.Random
import me.eternal.purrfectsnap.bridge.AutoOpenInterface
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.coroutines.resume

class AutoOpenSnaps: MessagingRuleFeature("Auto Open Snaps", MessagingRuleType.AUTO_OPEN_SNAPS) {
    companion object {
        const val ACTION_PAUSE_RESUME = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_PAUSE_RESUME"
        const val ACTION_CLEAR_QUEUE = "me.eternal.purrfectsnap.AUTO_OPEN_SNAPS_CLEAR_QUEUE"
        private const val STATUS_NOTIFICATION_ID = 54321
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

    data class SnapQueueItem(
        val conversationId: String,
        val messageId: Long,
        val senderId: String,
        var senderName: String = "Pending...",
        var conversationType: String = "Processing",
        val contentType: String,
        val timestamp: Long = System.currentTimeMillis()
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
                        val pauseStarted = lastPausedAt.get()
                        if (pauseStarted > 0) {
                            totalPausedDuration.addAndGet(System.currentTimeMillis() - pauseStarted)
                            lastPausedAt.set(0)
                        }
                        snapQueue.tryEmit(System.currentTimeMillis())
                    }
                    updateStatusNotification()
                }
                ACTION_CLEAR_QUEUE -> {
                    clearInternalState()
                    this@AutoOpenSnaps.context.log.info("[AutoOpen] All statistics and queue reset.")
                }
            }
        }
    }

    override fun init() {
        if (config.globalState != true) return
        context.log.info("[AutoOpen] Initializing Ultra Premium engine...")

        val messaging = context.feature(Messaging::class)
        restorePersistence()
        hasBeenActive.set(true)

        if (config.allowRunningInBackground.get()) {
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
                    methods.first { it.name == "appStateChanged" }.let { method ->
                        method.invoke(param.thisObject(), method.parameterTypes[0].enumConstants!!.first { it.toString() == "ACTIVE" })
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.androidContext.registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.androidContext.registerReceiver(actionReceiver, filter)
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
                }
                updateStatusNotification()
                if (remainingCount > 0) snapQueue.tryEmit(System.currentTimeMillis())
                delay(5000)
            }
        }

        context.coroutineScope.launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            snapQueue.collect { _ ->
                while (isActive && config.globalState == true) {
                    val item = synchronized(queuedSnaps) { queuedSnaps.firstOrNull() } ?: break

                    while (isPaused.get() || config.globalState != true) {
                        if (config.globalState != true) return@collect
                        currentStatusText = context.translation["auto_open_snaps.status_paused"] ?: "Paused"
                        isCurrentlyWaiting = true
                        updateStatusNotification()
                        delay(2000)
                    }

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
                                currentSpeedText = context.translation["auto_open_snaps.paused_status"] ?: "Paused (No WiFi)"
                                isCurrentlyWaiting = true
                                delay(5000)
                            }
                            config.onlyWhenIdle.get() && !isIdle && !inSleepWindow -> {
                                currentStatusText = context.translation["auto_open_snaps.only_when_idle.name"] ?: "Waiting for idle..."
                                currentSpeedText = context.translation["auto_open_snaps.paused_status"] ?: "Paused (Device Active)"
                                isCurrentlyWaiting = true
                                delay(5000)
                            }
                            config.pauseDuringGaming.get() && isGaming -> {
                                currentStatusText = context.translation["auto_open_snaps.pause_during_gaming.name"] ?: "Paused (Gaming Mode)"
                                currentSpeedText = context.translation["auto_open_snaps.paused_status"] ?: "Paused (Gaming)"
                                isCurrentlyWaiting = true
                                delay(60000)
                            }
                            else -> {
                                resourceWaiting = false
                                currentSpeedText = if (inSleepWindow) context.translation["auto_open_snaps.speed_throttled"] ?: "Throttled" else context.translation["auto_open_snaps.processing_speed_full"] ?: "Full Speed"
                            }
                        }
                        if (resourceWaiting) updateStatusNotification()
                    }

                    if (isPaused.get() || config.globalState != true) continue
                    isCurrentlyWaiting = false

                    val inSleepWindow = if (config.onlyWhenIdle.get()) isInsideSleepWindow() else false
                    if (inSleepWindow) {
                        currentStatusText = context.translation["auto_open_snaps.speed_throttled"] ?: "Throttled"
                        delay(Random.nextLong(3000, 5000))
                    } else if (lastConversationId != null && lastConversationId != item.conversationId) {
                        currentStatusText = "..."
                        delay(Random.nextLong(1500, 2500))
                        batchSnapCount = 0
                    }

                    lastConversationId = item.conversationId
                    currentStatusText = if (inSleepWindow) context.translation["auto_open_snaps.status_active"] ?: "Active" else context.translation["auto_open_snaps.status_active"] ?: "Opening snap..."
                    updateStatusNotification()

                    if (config.safeProcessing.get() && !inSleepWindow) {
                        batchSnapCount++
                        if (batchSnapCount % 10 == 0) {
                            currentStatusText = "..."
                            updateStatusNotification()
                            delay(Random.nextLong(3000, 5000))
                        }
                    }

                    var success = false
                    val startTime = System.currentTimeMillis()
                    var currentRetryDelay = config.retryDelay.get().toLong()

                    for (i in 0 until config.retryAttempts.get()) {
                        if (isPaused.get() || config.globalState != true) break
                        while ((!config.allowRunningInBackground.get() && context.isMainActivityPaused) || (messaging.conversationManager == null && !config.allowRunningInBackground.get())) {
                            if (config.globalState != true || isPaused.get()) break
                            currentStatusText = "..."
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
                            val duration = System.currentTimeMillis() - startTime
                            averageProcessingTime.set((averageProcessingTime.get() * 0.7 + duration * 0.3).toLong())
                            saveStatsToDisk()
                            break
                        }
                        if (i < config.retryAttempts.get() - 1) {
                            currentStatusText = context.translation["auto_open_snaps.status_retrying"] ?: "Retrying..."
                            updateStatusNotification()
                            delay(currentRetryDelay); currentRetryDelay *= 2
                        }
                    }

                    if (success) {
                        synchronized(queuedSnaps) { queuedSnaps.removeAll { it.messageId == item.messageId }; saveQueueToDisk() }
                    } else if (!isPaused.get()) {
                        currentStatusText = context.translation["auto_open_snaps.status_failed"]?.replace("{sender}", item.senderName) ?: "Failed to open"
                        updateStatusNotification()
                        delay(5000)
                        synchronized(queuedSnaps) { queuedSnaps.removeAll { it.messageId == item.messageId }; saveQueueToDisk() }
                    }

                    if (synchronized(queuedSnaps) { queuedSnaps.isEmpty() }) {
                        currentStatusText = context.translation["auto_open_snaps.status_monitoring"] ?: "Monitoring..."
                        isCurrentlyWaiting = false
                        updateStatusNotification()
                        releaseWakeLock()
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

            context.coroutineScope.launch(Dispatchers.Default) {
                if (!canUseRule(conversationId)) return@launch
                if (!openedSnaps.add(clientMessageId)) return@launch
                if (openedSnaps.size > 15000) openedSnaps.clear()
                val senderId = event.message.senderId?.toString() ?: "unknown"
                val item = SnapQueueItem(conversationId, clientMessageId, senderId, getSenderDisplayName(senderId), getConversationType(conversationId, senderId), getSnapContentType(contentType))
                synchronized(queuedSnaps) {
                    if (queuedSnaps.size >= config.queueSize.get()) queuedSnaps.removeFirstOrNull()
                    queuedSnaps.add(item); totalDetected.incrementAndGet(); saveQueueToDisk()
                }
                acquireWakeLock()
                snapQueue.tryEmit(System.currentTimeMillis())
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

    private fun updateStatusNotification() {
        val processed = sessionProcessed.get()
        val total = totalProcessed.get()
        val remaining = synchronized(queuedSnaps) { queuedSnaps.size }
        if (total <= 0 && remaining <= 0 && processed <= 0) return

        val isWorking = remaining > 0
        val showProgressBar = config.showProgressBar.get() == true
        val sessionTotal = processed + remaining
        val progressPercent = if (sessionTotal > 0) (processed * 100) / sessionTotal else 0

        val builder = Notification.Builder(context.androidContext, "auto_open_snaps")
            .setSmallIcon(if (isPaused.get()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setOngoing(isWorking).setAutoCancel(!isWorking).setOnlyAlertOnce(true)

        // Disable native progress bar to avoid "Double Bar" issue with our Premium Unicode bar
        builder.setProgress(0, 0, false)

        val eta = if (isWorking && !isCurrentlyWaiting && !isPaused.get()) formatDuration(remaining * averageProcessingTime.get()) else null

        if (config.compactNotification.get() == true) {
            builder.setContentTitle("Auto-Open: $currentStatusText")
            builder.setContentText("Remaining: $remaining | Opened: $processed" + (eta?.let { " | ETA: $it" } ?: ""))
        } else {
            builder.setContentTitle("Auto-Open: $currentStatusText")
            builder.setContentText("Remaining: $remaining | Opened: $processed")
        }

        builder.addAction(Notification.Action.Builder(null, if (isPaused.get()) "Resume" else "Pause", createPendingIntent(ACTION_PAUSE_RESUME)).build())
        builder.addAction(Notification.Action.Builder(null, "Clear Queue", createPendingIntent(ACTION_CLEAR_QUEUE)).build())

        if (config.compactNotification.get() != true) {
            val recentSnaps = synchronized(queuedSnaps) { queuedSnaps.takeLast(5) }
            val bigTextStyle = Notification.BigTextStyle()
            val detailText = buildString {
                if (showProgressBar) append("${context.translation["auto_open_snaps.notification_statistics"] ?: "STATISTICS"} - ${drawProgressBar(progressPercent)}\n")
                else append("${context.translation["auto_open_snaps.notification_statistics"] ?: "STATISTICS"}\n")
                
                append("\u251c\u2500 ${context.translation["auto_open_snaps.processed_count"] ?: "Opened"}: $processed snaps\n")
                append("\u251c\u2500 ${context.translation["auto_open_snaps.queue_size"] ?: "Remaining"}: $remaining snaps\n")
                
                if (eta != null) {
                    append("\u251c\u2500 ${context.translation["auto_open_snaps.estimated_time"] ?: "Estimated time"}: $eta\n")
                }
                
                if (config.showLifetimeStats.get()) {
                    append("\u251c\u2500 ${context.translation["auto_open_snaps.notification_total_opened"] ?: "Lifetime Opened"}: $total snaps\n")
                }
                
                if (config.showQueuePreview.get()) {
                    append("\u2514\u2500 ${context.translation["auto_open_snaps.processing_speed"] ?: "Speed"}: $currentSpeedText\n\n${context.translation["auto_open_snaps.notification_queue_preview"] ?: "QUEUE PREVIEW"}\n")
                    if (isWorking) {
                        recentSnaps.reversed().forEach { item ->
                            append("\u2022 ${item.senderName}")
                            if (item.conversationType != "Friend DM" && item.conversationType != "Processing") append(" \u2502 ${item.conversationType}")
                            append(" (${item.contentType})\n")
                        }
                    } else append(context.translation["auto_open_snaps.notification_no_snaps_queue"] ?: "Monitoring snaps in background...")
                } else append("\u2514\u2500 ${context.translation["auto_open_snaps.processing_speed"] ?: "Speed"}: $currentSpeedText")
            }
            bigTextStyle.bigText(detailText); builder.setStyle(bigTextStyle)
        }
        notificationManager.notify(STATUS_NOTIFICATION_ID, builder.build())
    }

    private fun drawProgressBar(percent: Int): String {
        val totalBlocks = 12; val filledBlocks = (percent * totalBlocks) / 100
        return buildString {
            append("[")
            repeat(totalBlocks) { i ->
                when { i < filledBlocks -> append("\u2501"); i == filledBlocks -> append("\u2B26"); else -> append("\u2500") }
            }
            append("] $percent%")
        }
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
                .setAutoCancel(true).build()
            notificationManager.notify(Random.nextInt(), summary)
            hasBeenActive.set(false)
        }
        resetPersistence(); releaseWakeLock()
    }

    private fun saveStatsToDisk() {
        prefs.edit().putInt(PREF_TOTAL_OPENED, totalProcessed.get()).putInt(PREF_TOTAL_DETECTED, totalDetected.get()).putLong(PREF_SESSION_START, sessionStartTime.get()).apply()
    }

    private fun saveQueueToDisk() {
        synchronized(queuedSnaps) { prefs.edit().putString(PREF_SAVED_QUEUE, gson.toJson(queuedSnaps)).apply() }
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
                synchronized(queuedSnaps) { queuedSnaps.clear(); queuedSnaps.addAll(restored) }
            } catch (e: Exception) { resetPersistence() }
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

            val now = Calendar.getInstance().apply {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, startStr[0].toInt())
                set(Calendar.MINUTE, startStr[1].toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, endStr[0].toInt())
                set(Calendar.MINUTE, endStr[1].toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
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
