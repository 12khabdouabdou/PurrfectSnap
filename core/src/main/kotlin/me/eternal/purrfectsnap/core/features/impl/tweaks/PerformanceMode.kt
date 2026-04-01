package me.eternal.purrfectsnap.core.features.impl.tweaks

import android.animation.ValueAnimator
import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.transition.Transition
import android.os.HandlerThread
import android.os.Process
import android.util.Range
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.Animation
import android.widget.OverScroller
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import java.lang.Thread
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadPoolExecutor
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import okhttp3.Dispatcher

class PerformanceMode : Feature("Performance Mode") {
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

        fun isPerformanceSensitiveThread(name: String?): Boolean {
            val normalizedName = name?.lowercase() ?: return false
            return listOf("camera", "preview", "codec", "render", "gl", "transcod", "lens", "feed", "story", "opera", "messag", "network", "db", "disk").any {
                normalizedName.contains(it)
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

        CaptureRequest.Builder::class.java.hook("set", HookStage.BEFORE) { param ->
            val key = param.arg<CaptureRequest.Key<*>>(0)
            when (key) {
                CaptureRequest.EDGE_MODE -> param.setArg(1, CaptureRequest.EDGE_MODE_FAST)
                CaptureRequest.NOISE_REDUCTION_MODE -> param.setArg(1, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                CaptureRequest.HOT_PIXEL_MODE -> param.setArg(1, CaptureRequest.HOT_PIXEL_MODE_FAST)
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE -> param.setArg(1, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
                CaptureRequest.CONTROL_AF_MODE -> param.setArg(1, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE -> param.setArg(1, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE -> {
                    val currentRange = param.argNullable<Any>(1) as? Range<*>
                    val lower = (currentRange?.lower as? Int) ?: minimumFrameRate
                    val upper = (currentRange?.upper as? Int) ?: minimumFrameRate
                    if (upper < minimumFrameRate) {
                        param.setArg(1, Range(lower.coerceAtMost(minimumFrameRate), minimumFrameRate))
                    }
                }
            }
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
    }
}
