package me.eternal.purrfect.core.features.impl.experiments

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.eternal.purrfect.core.features.Feature

/**
 * Rotation FSM — converts HermodTapHooks observations into automatic responses.
 *
 * Closes sanitizer coverage gaps #8 (co-visitation pressure), #9 (rotation
 * observability-vs-action), #13 (rotation timing heuristics) from
 * `BYPASS_GAP_MATRIX.md`. Tier-1 of the perfect-bypass work.
 *
 * State machine:
 *
 *   IDLE  --burst in cool window-->          ARMED
 *   ARMED --confirmed server pressure-->    ALERT
 *   ARMED --score decays-->                 IDLE
 *   ALERT --(identity rotation scheduled)--> ROTATING
 *   ROTATING --rotation completes-->        COOLDOWN
 *   COOLDOWN --timer elapses-->             IDLE
 *
 * Classifier (the A+cooldown hybrid LO approved):
 *  - score is a decayed sum over a 10-minute sliding window of tap emissions.
 *  - ARMED fires on any burst over `armedThreshold` (default 4).
 *  - ALERT fires when score over `alertThreshold` (default 8) AND at least one
 *    server-pressure marker (scauth/validate OR snap_session) within last 90s.
 *    This distinguishes real server heat (mixed evidence clustered tightly)
 *    from client-retry churn alone (only TokenRefresh durable jobs firing).
 *
 * Responders:
 *  - On ARMED/ALERT: throttle Hermod taps (toggle off for `cooldownMs`) so the
 *    taps don't feed the FSM with the purrfect-self-induced retry loop.
 *  - On ALERT: trigger `DeviceSpooferHook.rotateProfile()` — live identity swap.
 *    Rate-limited: min 2h between rotations, max 4/day.
 *
 * All state transitions emit `context.log.warn` so LO can watch the FSM in the
 * app log tab. Toggled via `config.experimental.nativeHooks.rotationFsm`.
 */
class RotationFSM : Feature("Rotation FSM") {

    enum class State { IDLE, ARMED, ALERT, ROTATING, COOLDOWN }
    enum class TapKind { TOKEN_REFRESH, SCAUTH_VALIDATE, SNAP_SESSION, SNAP_ACCESS }
    data class TapSignal(val kind: TapKind, val ts: Long)

    private val mutex = Mutex()
    @Volatile
    private var state: State = State.IDLE
    private val signals = mutableListOf<TapSignal>()

    @Volatile
    private var lastRotationAt = 0L
    private val rotationsIn24h = mutableListOf<Long>()
    @Volatile
    private var cooldownUntil = 0L

    private val windowMs = 10 * 60 * 1000L
    private val armedThreshold = 4.0
    private val alertThreshold = 8.0

    private val cooldownMs = 5 * 60 * 1000L
    private val minRotationIntervalMs = 2 * 60 * 60 * 1000L
    private val maxRotationsPer24h = 4

    private fun isEnabled(): Boolean =
        context.config.experimental.nativeHooks.rotationFsm.get()

    override fun init() {
        if (!isEnabled()) {
            context.log.info("Rotation FSM disabled")
            return
        }
        context.log.info("Rotation FSM initialized (IDLE) — armed=${armedThreshold} alert=${alertThreshold} cooldown=${cooldownMs}ms")
        defer {
            while (currentCoroutineContext().isActive && isEnabled()) {
                delay(60_000)
                mutex.withLock {
                    pruneStale()
                    maybeAdvanceFromScore()
                }
            }
        }
    }

    /**
     * True while the FSM is in cooldown. HermodTapHooks consults this to gate
     * tap emission (spec responder #1: suppress the observation taps during
     * cooldown so purrfect's own retry churn can't re-arm the FSM against a
     * monotype client burst).
     */
    fun suppressTaps(): Boolean = state == State.COOLDOWN

    /**
     * Called by HermodTapHooks whenever a tap emission fires. Signals are
     * dropped while in cooldown — the cooldown both throttles rotations AND
     * suppresses evidence collection, so the burst that triggered the rotation
     * can't immediately re-arm the FSM once the cooldown expires the natural
     * way (signals cleared on cooldown exit).
     */
    fun ingest(tap: TapSignal) {
        if (!isEnabled()) return
        if (suppressTaps()) return
        BypassTrace.inc("fsm_ingest_${tap.kind.name}")
        defer {
            mutex.withLock {
                signals.add(tap)
                context.log.verbose("FSM ingest: $tap total=${signals.size}")
                maybeAdvanceFromScore()
            }
        }
    }

    private fun score(): Double = signals.sumOf {
        val age = now() - it.ts
        if (age > windowMs) return@sumOf 0.0
        // Linear decay from 1.0 (just-fired) to 0.0 (oldest in window).
        val decayFactor = 1.0 - (age.toDouble() / windowMs.toDouble())
        val weight = when (it.kind) {
            TapKind.TOKEN_REFRESH -> 1.0
            TapKind.SCAUTH_VALIDATE -> 2.0
            TapKind.SNAP_SESSION -> 2.0
            TapKind.SNAP_ACCESS -> 1.0
        }
        weight * decayFactor
    }

    private fun maybeAdvanceFromScore() {
        val s = score()
        when (state) {
            State.IDLE -> {
                if (s >= armedThreshold) {
                    setState(State.ARMED, s)
                }
            }
            State.ARMED -> {
                val marker = hasServerPressureMarker()
                if (s >= alertThreshold && marker) {
                    BypassTrace.note("FSM", "ALERT candidate: score=$s markerWithin90s=true")
                } else {
                    BypassTrace.note("FSM", "ARMED eval: score=$s markerWithin90s=$marker")
                }
                when {
                    s >= alertThreshold && marker -> {
                        setState(State.ALERT, s)
                        onAlert()
                    }
                    s < armedThreshold * 0.5 -> {
                        setState(State.IDLE, s)
                    }
                }
            }
            State.ALERT -> {
                // already triggered onAlert; waiting for rotation completion.
            }
            State.ROTATING -> {
                // waiting for rotation to ack via startCooldown()
            }
            State.COOLDOWN -> {
                if (now() >= cooldownUntil) {
                    signals.clear()
                    setState(State.IDLE, 0.0)
                }
            }
        }
    }

    /**
     * "Server pressure" = evidence that the server is actually engaging the
     * attestation/rotation surface, not just the client retry-loop burning on
     * its own. Requires a SC-AUTH/validate OR a snap_session fetch within the
     * last 90 seconds, clustered with the burst.
     */
    private fun hasServerPressureMarker(): Boolean {
        val cutoff = now() - 90_000
        return signals.any {
            it.ts >= cutoff &&
                (it.kind == TapKind.SCAUTH_VALIDATE || it.kind == TapKind.SNAP_SESSION)
        }
    }

    private fun setState(next: State, score: Double) {
        if (state == next) return
        context.log.warn("Rotation FSM: state=$state -> $next score=${"%.2f".format(score)}")
        BypassTrace.inc("fsm_state_${next.name}")
        state = next
    }

    private fun onAlert() {
        setState(State.ROTATING, score())
        defer {
            try {
                val now = now()
                rotationsIn24h.removeAll { it < now - 24 * 60 * 60 * 1000L }
                when {
                    now - lastRotationAt < minRotationIntervalMs -> {
                        context.log.warn("Rotation FSM: skip rotate (min interval not elapsed; ${now - lastRotationAt}ms since last)")
                    }
                    rotationsIn24h.size >= maxRotationsPer24h -> {
                        context.log.warn("Rotation FSM: skip rotate (daily cap hit at ${rotationsIn24h.size})")
                    }
                    else -> {
                        val spoofer = context.features.get(DeviceSpooferHook::class)
                        if (spoofer == null) {
                            context.log.warn("Rotation FSM: DeviceSpooferHook unavailable; cannot rotate")
                        } else {
                            val fresh = spoofer.rotateProfile()
                            if (fresh != null) {
                                rotationsIn24h.add(now)
                                lastRotationAt = now
                                context.log.info("Rotation FSM: identity rotation committed (model=${fresh.deviceInfo.model})")
                            } else {
                                context.log.warn("Rotation FSM: rotateProfile returned null (randomization toggle off?)")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                context.log.error("Rotation FSM rotation failed", t)
            }
            startCooldown()
        }
    }

    private fun startCooldown() {
        cooldownUntil = now() + cooldownMs
        setState(State.COOLDOWN, score())
        context.log.info("Rotation FSM: cooldown for ${cooldownMs}ms (until $cooldownUntil)")
    }

    private fun pruneStale() {
        val cutoff = now() - windowMs
        signals.removeAll { it.ts < cutoff }
    }

    /** Test seam — overridable for deterministic unit tests. */
    internal open fun now(): Long = System.currentTimeMillis()
}
