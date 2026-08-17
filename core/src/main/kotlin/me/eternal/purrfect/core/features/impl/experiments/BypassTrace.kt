package me.eternal.purrfect.core.features.impl.experiments

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.eternal.purrfect.core.features.Feature
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Penetration instrumentation for the bypass stack.
 *
 * One gate (`bypass_trace`) drives three things:
 *  1. Heavy trace (gate on): per-URI census from the Hermod taps (everything
 *     unmatched becomes visible, so the `/snap_token/pb/snap_session` gap can
 *     be re-matched), FSM decision internals, spoofer rotate/launder detail.
 *  2. Always-on light (once the gate is on): dedup'd first-occurrence latches
 *     (`seamFired`) + per-event counters, so a summary line every 60s shows
 *     what has and hasn't ever fired since process start.
 *  3. Seam-firing proof: `noteSeamFired` at every SecurityFeatures decision
 *     point — the runtime evidence that the static fixes actually execute.
 *
 * Everything writes through `android.util.Log` with tag `PurrfectTrace`, which
 * the in-app log tab hooks (CoreLogger BEFORE stage on `Log.println`), so all
 * trace lines land in the app log tab and the existing LogManager export with
 * zero extra plumbing. Every accessor is a no-op when the gate is off (single
 * volatile read).
 */
object BypassTrace {

    internal const val TAG = "PurrfectTrace"

    /** Master gate, written by [BypassTraceController] on init. */
    @Volatile
    var enabled: Boolean = false

    private val latched = Collections.synchronizedSet(mutableSetOf<String>())
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /** Level-1 event log; no-op when disabled. */
    fun note(subject: String, message: String) {
        if (!enabled) return
        Log.i(TAG, "$subject | $message")
    }

    /** First occurrence only — repeated identical events log exactly once per process. */
    fun latch(key: String, subject: String, message: String) {
        if (!enabled) return
        if (latched.add(key)) {
            Log.i(TAG, "$subject | $message")
        }
    }

    /** Monotonic counter, surfaced in the 60s summary. No-op when disabled. */
    fun inc(name: String) {
        if (!enabled) return
        counters.computeIfAbsent(name) { AtomicLong(0) }.incrementAndGet()
    }

    /** Latch (one-time log) + counter bump for a seam that actually executed. */
    fun noteSeamFired(seam: String) {
        if (!enabled) return
        latch("seam_$seam", "SEAM", "fired: $seam")
        counters.computeIfAbsent("seam_fired_$seam") { AtomicLong(0) }.incrementAndGet()
    }

    /** Snapshot of Kotlin-side counters (deltas are derived by the reader). */
    fun countersSnapshot(): Map<String, Long> =
        counters.entries.sortedBy { it.key }.associate { it.key to it.value.get() }
}

/**
 * Controller feature: sets the [BypassTrace] gate from config on init and runs
 * the 60s summary loop (Kotlin counters + native counters merged into one
 * line). Registered FIRST in FeatureManager so the gate is up before any seam
 * can emit.
 */
class BypassTraceController : Feature("Bypass Trace") {

    private fun isEnabled(): Boolean =
        context.config.experimental.nativeHooks.bypassTrace.get()

    override fun init() {
        BypassTrace.enabled = isEnabled()
        if (!BypassTrace.enabled) {
            context.log.info("Bypass trace disabled")
            return
        }
        context.log.info("Bypass trace enabled — watch tag PurrfectTrace")
        context.native.setBypassTrace(true)
        defer {
            while (currentCoroutineContext().isActive && BypassTrace.enabled) {
                delay(60_000)
                emitSummary()
            }
        }
    }

    private fun emitSummary() {
        try {
            val native = context.native.snapshotBypassTrace()
            val kotlin = BypassTrace.countersSnapshot()
            if (native.isBlank() && kotlin.isEmpty()) return
            val nativeSummary = native.takeIf { it.isNotBlank() } ?: "{}"
            val kotlinSummary = kotlin.entries.joinToString(" ") { "${it.key}=${it.value}" }
            context.log.info("Bypass summary | native=$nativeSummary kotlin=[$kotlinSummary]", BypassTrace.TAG)
        } catch (t: Throwable) {
            BypassTrace.note("BypassTraceController", "summary failed: ${t.message}")
        }
    }
}