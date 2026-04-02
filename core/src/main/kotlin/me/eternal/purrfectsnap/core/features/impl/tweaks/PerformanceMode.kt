package me.eternal.purrfectsnap.core.features.impl.tweaks

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.transition.Transition
import android.os.HandlerThread
import android.os.Process
import android.util.Base64
import android.util.Range
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowManager
import android.view.animation.Animation
import android.widget.OverScroller
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import java.io.File
import java.lang.Thread
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadPoolExecutor
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import okhttp3.Dispatcher

class PerformanceMode : Feature("Performance Mode") {
    private data class SnapshotCell(
        val type: Int,
        val stringValue: String? = null,
        val longValue: Long? = null,
        val doubleValue: Double? = null,
        val blobValue: String? = null,
    )

    private data class CursorSnapshot(
        val columns: List<String>,
        val rows: List<List<SnapshotCell>>,
    )

    override fun init() {
        val profile = context.config.global.performanceMode.profile.getNullable() ?: return
        val isMaxProfile = profile == "max"
        val threadPriority = if (isMaxProfile) {
            Process.THREAD_PRIORITY_DISPLAY
        } else {
            Process.THREAD_PRIORITY_MORE_FAVORABLE
        }
        val minimumFrameRate = if (isMaxProfile) 60 else 45
        val durationScale = if (isMaxProfile) 0.35f else 0.55f
        val recyclerViewCacheSize = if (isMaxProfile) 64 else 32
        val maxRequests = if (isMaxProfile) 192 else 96
        val maxRequestsPerHost = if (isMaxProfile) 32 else 16
        val minimumCoreThreads = if (isMaxProfile) 16 else 8
        val prefetchItemCount = if (isMaxProfile) 24 else 12
        val maxAnimationDurationMs = if (isMaxProfile) 90L else 140L
        val maxScrollDurationMs = if (isMaxProfile) 120 else 180
        val preferredRefreshRate = if (isMaxProfile) 120f else 90f

        context.log.info(
            "Performance mode enabled: profile=$profile, threadPriority=$threadPriority, minFps=$minimumFrameRate, durationScale=$durationScale, rvCache=$recyclerViewCacheSize, maxRequests=$maxRequests/$maxRequestsPerHost, minCoreThreads=$minimumCoreThreads, prefetch=$prefetchItemCount, maxAnimMs=$maxAnimationDurationMs, maxScrollMs=$maxScrollDurationMs, preferredRefreshRate=$preferredRefreshRate",
            "PerformanceMode"
        )

        runCatching {
            ValueAnimator.setFrameDelay(0L)
            context.log.info("Applied ValueAnimator frame delay override: 0ms", "PerformanceMode")
        }

        fun firstHitLogger(name: String): (String) -> Unit {
            val didLog = AtomicBoolean(false)
            return { details ->
                if (didLog.compareAndSet(false, true)) {
                    context.log.info("First hit: $name | $details", "PerformanceMode")
                }
            }
        }

        val handlerThreadConstructorLog = firstHitLogger("HandlerThread.constructor")
        val handlerThreadStartLog = firstHitLogger("HandlerThread.start")
        val threadStartLog = firstHitLogger("Thread.start")
        val executorLog = firstHitLogger("ThreadPoolExecutor.constructor")
        val dispatcherLog = firstHitLogger("OkHttp.Dispatcher.constructor")
        val animatorLog = firstHitLogger("ValueAnimator.getDurationScale")
        val animatorDurationLog = firstHitLogger("ValueAnimator.setDuration")
        val viewAnimatorDurationLog = firstHitLogger("ViewPropertyAnimator.setDuration")
        val transitionDurationLog = firstHitLogger("Transition.setDuration")
        val animationDurationLog = firstHitLogger("Animation.setDuration")
        val recyclerCtorLog = firstHitLogger("RecyclerView.constructor")
        val recyclerAdapterLog = firstHitLogger("RecyclerView.setAdapter")
        val recyclerLayoutManagerLog = firstHitLogger("RecyclerView.setLayoutManager")
        val sqliteOpenLog = firstHitLogger("SQLiteDatabase.openDatabase")
        val sqliteCreateLog = firstHitLogger("SQLiteDatabase.openOrCreateDatabase")
        val mediaRecorderLog = firstHitLogger("MediaRecorder.setVideoFrameRate")
        val captureRequestLog = firstHitLogger("CaptureRequest.Builder.set")
        val sustainedModeLog = firstHitLogger("Window.setSustainedPerformanceMode")
        val refreshRateLog = firstHitLogger("Activity.preferredRefreshRate")
        val overScrollerLog = firstHitLogger("OverScroller.startScroll")
        val chatFeedCacheServeLog = firstHitLogger("ChatFeed.cacheServe")
        val chatFeedCacheRefreshLog = firstHitLogger("ChatFeed.cacheRefresh")
        val mapDialogLog = firstHitLogger("Dialog.show")
        val mapViewLog = firstHitLogger("MapView.constructor")
        val mapboxNetworkBlockLog = firstHitLogger("SnapMap.telemetryBlock")
        val mapCameraAnimLog = firstHitLogger("SnapMap.mapAnimatorDuration")
        val mapThreadLog = firstHitLogger("SnapMap.mapThread")
        val mapRendererFpsLog = firstHitLogger("SnapMap.mapRendererFps")

        fun isPerformanceSensitiveThread(name: String?): Boolean {
            val normalizedName = name?.lowercase() ?: return false
            return listOf("camera", "preview", "codec", "render", "gl", "transcod", "lens", "feed", "story", "opera", "messag", "network", "db", "disk", "map", "mapbox", "snapmap", "viewport").any {
                normalizedName.contains(it)
            }
        }

        val performanceCacheDir = File(context.androidContext.filesDir, "performance_mode_cache").apply { mkdirs() }
        val chatFeedSnapshotFile = File(performanceCacheDir, "chat_feed_snapshot.json")

        fun isChatFeedQuery(sql: String): Boolean {
            val normalized = sql.uppercase()
            if (!normalized.startsWith("SELECT")) return false
            val hitsFriendsFeedView = sql.contains("FriendsFeedView")
            val hitsFeedEntry = sql.contains("feed_entry") && (sql.contains("last_updated_timestamp") || sql.contains("displayInteractionType") || sql.contains("streak_count"))
            return (hitsFriendsFeedView || hitsFeedEntry) &&
                !normalized.contains("COUNT(") &&
                !normalized.contains("SELECT 0") &&
                !normalized.contains("WHERE KEY = ?") &&
                !normalized.contains("WHERE CLIENT_CONVERSATION_ID = ?")
        }

        fun cursorCell(cursor: Cursor, index: Int): SnapshotCell {
            return when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> SnapshotCell(Cursor.FIELD_TYPE_NULL)
                Cursor.FIELD_TYPE_INTEGER -> SnapshotCell(Cursor.FIELD_TYPE_INTEGER, longValue = cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> SnapshotCell(Cursor.FIELD_TYPE_FLOAT, doubleValue = cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> SnapshotCell(Cursor.FIELD_TYPE_STRING, stringValue = cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> SnapshotCell(
                    Cursor.FIELD_TYPE_BLOB,
                    blobValue = Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                )
                else -> SnapshotCell(Cursor.FIELD_TYPE_STRING, stringValue = cursor.getString(index))
            }
        }

        fun snapshotFromCursor(cursor: Cursor): CursorSnapshot {
            val columns = cursor.columnNames.toList()
            val rows = mutableListOf<List<SnapshotCell>>()
            if (cursor.moveToFirst()) {
                do {
                    rows += columns.indices.map { index -> cursorCell(cursor, index) }
                } while (cursor.moveToNext())
            }
            return CursorSnapshot(columns, rows)
        }

        fun snapshotToMatrixCursor(snapshot: CursorSnapshot): MatrixCursor {
            return MatrixCursor(snapshot.columns.toTypedArray(), snapshot.rows.size).also { matrixCursor ->
                snapshot.rows.forEach { row ->
                    matrixCursor.addRow(row.map { cell ->
                        when (cell.type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cell.longValue
                            Cursor.FIELD_TYPE_FLOAT -> cell.doubleValue
                            Cursor.FIELD_TYPE_BLOB -> cell.blobValue?.let { Base64.decode(it, Base64.NO_WRAP) }
                            else -> cell.stringValue
                        }
                    })
                }
            }
        }

        fun readSnapshot(file: File): CursorSnapshot? {
            return runCatching {
                if (!file.exists()) return null
                context.gson.fromJson(file.readText(Charsets.UTF_8), CursorSnapshot::class.java)
            }.getOrNull()
        }

        fun writeSnapshot(file: File, snapshot: CursorSnapshot) {
            runCatching {
                file.writeText(context.gson.toJson(snapshot), Charsets.UTF_8)
            }.onFailure {
                context.log.error("Failed to persist friend list snapshot", it, "PerformanceMode")
            }
        }

        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            if (!isMaxProfile) return@subscribe
            val url = event.url
            if (url.contains("ami/friends")) {
                if (chatFeedSnapshotFile.exists()) {
                    chatFeedSnapshotFile.delete()
                    context.log.info("Invalidated chat feed snapshot after friends mutation sync", "PerformanceMode")
                }
            }
            if (url.contains("messaging") || url.contains("conversation") || url.contains("feed")) {
                if (chatFeedSnapshotFile.exists()) {
                    chatFeedSnapshotFile.delete()
                    context.log.info("Invalidated chat feed snapshot after messaging/feed network activity", "PerformanceMode")
                }
            }
            if (url.contains("mapbox") && (url.contains("events.") || url.contains("telemetry"))) {
                event.canceled = true
                mapboxNetworkBlockLog("url=$url")
            }
        }

        HandlerThread::class.java.hookConstructor(HookStage.BEFORE) { param ->
            if (param.args().size < 2) return@hookConstructor
            val threadName = param.argNullable<String>(0)
            if (!isPerformanceSensitiveThread(threadName)) return@hookConstructor
            param.setArg(1, threadPriority)
            handlerThreadConstructorLog("name=$threadName priority=$threadPriority")
        }

        HandlerThread::class.java.hook("start", HookStage.AFTER) { param ->
            val thread = param.nullableThisObject<Any>() as? HandlerThread ?: return@hook
            if (!isPerformanceSensitiveThread(thread.name)) return@hook
            runCatching {
                val tid = thread.threadId
                if (tid > 0) {
                    Process.setThreadPriority(tid, threadPriority)
                }
            }
            handlerThreadStartLog("name=${thread.name} tid=${thread.threadId} priority=$threadPriority")
        }

        Thread::class.java.hook("start", HookStage.AFTER) { param ->
            val thread = param.thisObject<Thread>()
            if (!isPerformanceSensitiveThread(thread.name)) return@hook
            runCatching {
                thread.priority = Thread.MAX_PRIORITY
            }
            threadStartLog("name=${thread.name} priority=${thread.priority}")
            if ((thread.name ?: "").contains("map", ignoreCase = true) || (thread.name ?: "").contains("mapbox", ignoreCase = true)) {
                mapThreadLog("name=${thread.name} priority=${thread.priority}")
            }
        }

        ThreadPoolExecutor::class.java.hookConstructor(HookStage.AFTER) { param ->
            val executor = param.thisObject<ThreadPoolExecutor>()
            runCatching {
                val targetCorePoolSize = executor.maximumPoolSize.coerceAtLeast(1).coerceAtMost(minimumCoreThreads.coerceAtLeast(executor.corePoolSize))
                if (executor.corePoolSize < targetCorePoolSize) {
                    executor.corePoolSize = targetCorePoolSize
                }
                executor.allowCoreThreadTimeOut(false)
                executor.prestartAllCoreThreads()
                executorLog("core=${executor.corePoolSize} max=${executor.maximumPoolSize} active=${executor.activeCount}")
            }
        }

        Dispatcher::class.java.hookConstructor(HookStage.AFTER) { param ->
            val dispatcher = param.thisObject<Dispatcher>()
            runCatching {
                dispatcher.maxRequests = maxRequests
                dispatcher.maxRequestsPerHost = maxRequestsPerHost
                dispatcherLog("maxRequests=${dispatcher.maxRequests} maxRequestsPerHost=${dispatcher.maxRequestsPerHost}")
            }
        }

        ValueAnimator::class.java.hook("getDurationScale", HookStage.AFTER) { param ->
            param.setResult(durationScale)
            animatorLog("durationScale=$durationScale")
        }

        ValueAnimator::class.java.hook("setDuration", HookStage.BEFORE) { param ->
            val original = param.arg<Long>(0)
            val updated = original.coerceAtMost(maxAnimationDurationMs)
            if (updated != original) {
                param.setArg(0, updated)
            }
            animatorDurationLog("requested=$original applied=${param.arg<Long>(0)}")
            val thisObject = param.nullableThisObject<Any>()?.javaClass?.name ?: ""
            if (thisObject.contains("map", ignoreCase = true)) {
                mapCameraAnimLog("owner=$thisObject requested=$original applied=${param.arg<Long>(0)}")
            }
        }

        ViewPropertyAnimator::class.java.hook("setDuration", HookStage.BEFORE) { param ->
            val original = param.arg<Long>(0)
            val updated = original.coerceAtMost(maxAnimationDurationMs)
            if (updated != original) {
                param.setArg(0, updated)
            }
            viewAnimatorDurationLog("requested=$original applied=${param.arg<Long>(0)}")
        }

        Transition::class.java.hook("setDuration", HookStage.BEFORE) { param ->
            val original = param.arg<Long>(0)
            val updated = original.coerceAtMost(maxAnimationDurationMs)
            if (updated != original) {
                param.setArg(0, updated)
            }
            transitionDurationLog("requested=$original applied=${param.arg<Long>(0)}")
        }

        Animation::class.java.hook("setDuration", HookStage.BEFORE) { param ->
            val original = param.arg<Long>(0)
            val updated = original.coerceAtMost(maxAnimationDurationMs)
            if (updated != original) {
                param.setArg(0, updated)
            }
            animationDurationLog("requested=$original applied=${param.arg<Long>(0)}")
        }

        RecyclerView::class.java.hookConstructor(HookStage.AFTER) { param ->
            val recyclerView = param.thisObject<RecyclerView>()
            recyclerView.setItemViewCacheSize(recyclerViewCacheSize)
            recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
            recyclerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (isMaxProfile) {
                recyclerView.itemAnimator = null
            }
            recyclerCtorLog("cache=$recyclerViewCacheSize max=$isMaxProfile class=${recyclerView::class.java.name}")
        }

        RecyclerView::class.java.hook("setAdapter", HookStage.AFTER) { param ->
            val recyclerView = param.thisObject<RecyclerView>()
            recyclerView.setItemViewCacheSize(recyclerViewCacheSize)
            recyclerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (isMaxProfile) {
                recyclerView.itemAnimator = null
            }
            recyclerAdapterLog("cache=$recyclerViewCacheSize adapter=${param.argNullable<Any>(0)?.javaClass?.name}")
        }

        RecyclerView::class.java.hook("setLayoutManager", HookStage.AFTER) { param ->
            val recyclerView = param.thisObject<RecyclerView>()
            val layoutManager = param.argNullable<Any>(0)
            when (layoutManager) {
                is LinearLayoutManager -> {
                    layoutManager.isItemPrefetchEnabled = true
                    layoutManager.initialPrefetchItemCount = prefetchItemCount
                }
                is StaggeredGridLayoutManager -> {
                    layoutManager.isItemPrefetchEnabled = true
                    layoutManager.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
                }
            }
            recyclerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            recyclerLayoutManagerLog("layoutManager=${layoutManager?.javaClass?.name} prefetch=$prefetchItemCount")
        }

        fun SQLiteDatabase.applyPerformancePragmas() {
            runCatching { execSQL("PRAGMA synchronous = NORMAL") }
            runCatching { execSQL("PRAGMA temp_store = MEMORY") }
            runCatching { execSQL("PRAGMA cache_size = -32768") }
            runCatching { execSQL("PRAGMA mmap_size = 268435456") }
            runCatching { execSQL("PRAGMA journal_size_limit = 1048576") }
            runCatching { execSQL("PRAGMA optimize") }
        }

        SQLiteDatabase::class.java.hook("openDatabase", HookStage.AFTER) { param ->
            (param.getResult() as? SQLiteDatabase)?.also {
                it.applyPerformancePragmas()
                sqliteOpenLog("path=${param.argNullable<Any>(0)}")
            }
        }

        SQLiteDatabase::class.java.hook("openOrCreateDatabase", HookStage.AFTER) { param ->
            (param.getResult() as? SQLiteDatabase)?.also {
                it.applyPerformancePragmas()
                sqliteCreateLog("path=${param.argNullable<Any>(0)}")
            }
        }

        MediaRecorder::class.java.hook("setVideoFrameRate", HookStage.BEFORE) { param ->
            val currentRate = param.arg<Int>(0)
            if (currentRate < minimumFrameRate) {
                param.setArg(0, minimumFrameRate)
            }
            mediaRecorderLog("requested=$currentRate applied=${param.arg<Int>(0)}")
        }

        OverScroller::class.java.hook("startScroll", HookStage.BEFORE) { param ->
            if (param.args().size >= 5) {
                val original = param.arg<Int>(4)
                val updated = original.coerceAtMost(maxScrollDurationMs)
                if (updated != original) {
                    param.setArg(4, updated)
                }
                overScrollerLog("requested=$original applied=${param.arg<Int>(4)}")
            }
        }

        OverScroller::class.java.hook("fling", HookStage.BEFORE) { param ->
            if (param.args().size >= 10) {
                val overX = param.arg<Int>(8)
                val overY = param.arg<Int>(9)
                if (overX != 0) param.setArg(8, 0)
                if (overY != 0) param.setArg(9, 0)
            }
        }

        CaptureRequest.Builder::class.java.hook("set", HookStage.BEFORE) { param ->
            val key = param.arg<CaptureRequest.Key<*>>(0)
            captureRequestLog("key=${key.name} value=${param.argNullable<Any>(1)}")
        }

        fun applyActivityPerformanceTuning(activity: Activity) {
            runCatching {
                activity.window.decorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                val display = activity.display
                val targetRefreshRate = display?.supportedModes?.maxByOrNull { it.refreshRate }?.refreshRate
                    ?.coerceAtLeast(preferredRefreshRate) ?: preferredRefreshRate
                activity.window.attributes = activity.window.attributes.apply {
                    this.preferredRefreshRate = targetRefreshRate
                }
                refreshRateLog("activity=${activity::class.java.name} refreshRate=$targetRefreshRate")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isMaxProfile) {
                runCatching {
                    activity.window.setSustainedPerformanceMode(true)
                    sustainedModeLog("activity=${activity::class.java.name}")
                }
            }
        }

        onNextActivityCreate {
            applyActivityPerformanceTuning(it)
        }

        Dialog::class.java.hook("show", HookStage.AFTER) { param ->
            val dialog = param.nullableThisObject<Any>() as? Dialog ?: return@hook
            val window = dialog.window ?: return@hook
            runCatching {
                window.setWindowAnimations(0)
                window.decorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                window.attributes = window.attributes.apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                }
                if (dialog::class.java.name.contains("map", ignoreCase = true) || dialog::class.java.name.contains("snap", ignoreCase = true)) {
                    mapDialogLog("class=${dialog::class.java.name}")
                }
            }
        }

        runCatching {
            findClass("com.mapbox.mapboxsdk.maps.MapView").hookConstructor(HookStage.AFTER) { param ->
                val mapView = param.nullableThisObject<Any>() as? View ?: return@hookConstructor
                mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                mapView.overScrollMode = View.OVER_SCROLL_NEVER
                mapViewLog("class=${mapView::class.java.name}")
            }
        }

        runCatching {
            findClass("com.mapbox.mapboxsdk.maps.renderer.MapRenderer").hook("setMaximumFps", HookStage.BEFORE) { param ->
                val requested = param.arg<Int>(0)
                val applied = requested.coerceAtLeast(120)
                if (applied != requested) {
                    param.setArg(0, applied)
                }
                mapRendererFpsLog("requested=$requested applied=${param.arg<Int>(0)}")
            }
        }

        runCatching {
            findClass("io.requery.android.database.sqlite.SQLiteDatabase").hook("rawQueryWithFactory", HookStage.BEFORE) { param ->
                if (!isMaxProfile) return@hook
                val sql = param.argNullable<String>(1) ?: return@hook
                if (!isChatFeedQuery(sql)) return@hook
                readSnapshot(chatFeedSnapshotFile)?.let { snapshot ->
                    param.setResult(snapshotToMatrixCursor(snapshot))
                    chatFeedCacheServeLog("rows=${snapshot.rows.size} file=${chatFeedSnapshotFile.name}")
                }
            }

            findClass("io.requery.android.database.sqlite.SQLiteDatabase").hook("rawQueryWithFactory", HookStage.AFTER) { param ->
                if (!isMaxProfile) return@hook
                val sql = param.argNullable<String>(1) ?: return@hook
                if (!isChatFeedQuery(sql)) return@hook
                val cursor = param.getResult() as? Cursor ?: return@hook
                val snapshot = snapshotFromCursor(cursor)
                writeSnapshot(chatFeedSnapshotFile, snapshot)
                param.setResult(snapshotToMatrixCursor(snapshot))
                runCatching { cursor.close() }
                chatFeedCacheRefreshLog("rows=${snapshot.rows.size} file=${chatFeedSnapshotFile.name}")
            }
        }.onFailure {
            context.log.error("Failed to install chat feed cache hooks", it, "PerformanceMode")
        }
    }
}
