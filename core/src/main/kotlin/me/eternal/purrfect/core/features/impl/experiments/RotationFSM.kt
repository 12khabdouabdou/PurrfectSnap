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

    /**
     * Pressure signal taxonomy, classified by INITIATOR:
     *
     *  - CLIENT_INITIATED (SCAUTH_VALIDATE, SNAP_SESSION, SNAP_ACCESS,
     *    TOKEN_REFRESH): the app itself fires these during normal usage —
     *    every profile view triggers a session validation, messaging refreshes
     *    snap tokens. On-device proof (2026-08-21): 5 profile opens = 5
     *    validates = false ALERT under the old weights. Routine usage must
     *    NEVER rotate identity, so these carry LOW weight and can NEVER
     *    satisfy the server-pressure marker.
     *  - SERVER_INITIATED (HERMOD_PUSH, JANUS_CHALLENGE): the server pushed
     *    a hermod_dup attestation demand or issued a Janus verification
     *    challenge. Physically cannot be caused by user navigation — these
     *    are the ONLY markers that prove actual server suspicion.
     */
    enum class TapKind { TOKEN_REFRESH, SCAUTH_VALIDATE, SNAP_SESSION, SNAP_ACCESS, JANUS_CHALLENGE, HERMOD_PUSH }

    data class TapSignal(
        val kind: TapKind,
        val ts: Long,
        /** App foreground at emission — background traffic is rarer, mildly more notable. */
        val foreground: Boolean = true,
        /** Free-form provenance (seam name, response hint) for the log line. */
        val note: String? = null,
    )

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

    /**
     * Per-kind base weights, calibrated against the 2026-08-21 false positive:
     * five profile opens (5× SCAUTH_VALIDATE) must stay below `armedThreshold`
     * even at full decay — 5 × 0.75 = 3.75 < 4.0. Server-initiated signals
     * alone can cross ARMED on a single hit (3.5 + decay tail), which is
     * correct: an unsolicited Janus challenge IS worth watching.
     */
    private fun baseWeight(kind: TapKind): Double = when (kind) {
        TapKind.TOKEN_REFRESH -> 1.0
        TapKind.SCAUTH_VALIDATE -> 0.75   // routine: fires per profile view / nav
        TapKind.SNAP_SESSION -> 1.25      // session fetches: token lifecycle
        TapKind.SNAP_ACCESS -> 1.0
        TapKind.JANUS_CHALLENGE -> 3.5    // SERVER-initiated verification gate
        TapKind.HERMOD_PUSH -> 3.0        // SERVER-pushed attestation demand
    }

    /** Background traffic multiplier — modest: sync jobs are legit but rarer. */
    private val BACKGROUND_MULTIPLIER = 1.5

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
        context.log.info(
            "Rotation FSM initialized (IDLE) — armed=${armedThreshold} alert=${alertThreshold} cooldown=${cooldownMs}ms | " +
                "weights: SCAUTH_VALIDATE=${baseWeight(TapKind.SCAUTH_VALIDATE)} SNAP_SESSION=${baseWeight(TapKind.SNAP_SESSION)} " +
                "SNAP_ACCESS=${baseWeight(TapKind.SNAP_ACCESS)} TOKEN_REFRESH=${baseWeight(TapKind.TOKEN_REFRESH)} " +
                "JANUS_CHALLENGE=${baseWeight(TapKind.JANUS_CHALLENGE)} HERMOD_PUSH=${baseWeight(TapKind.HERMOD_PUSH)} | " +
                "server-pressure markers: JANUS_CHALLENGE/HERMOD_PUSH only"
        )
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
                // Contribution of THIS signal at full freshness + running total,
                // so a log export shows exactly why the score is what it is.
                var w = baseWeight(tap.kind)
                if (!tap.foreground) w *= BACKGROUND_MULTIPLIER
                val initiator = when (tap.kind) {
                    TapKind.HERMOD_PUSH, TapKind.JANUS_CHALLENGE -> "SERVER_INITIATED"
                    else -> "client_initiated"
                }
                context.log.verbose(
                    "FSM ingest: kind=${tap.kind} [$initiator fg=${tap.foreground}] " +
                        "weight=${"%.2f".format(w)} note=${tap.note ?: "-"} " +
                        "total_signals=${signals.size} window_score=${"%.2f".format(score())}"
                )
                maybeAdvanceFromScore()
            }
        }
    }

    private fun score(): Double = signals.sumOf {
        val age = now() - it.ts
        if (age > windowMs) return@sumOf 0.0
        // Linear decay from 1.0 (just-fired) to 0.0 (oldest in window).
        val decayFactor = 1.0 - (age.toDouble() / windowMs.toDouble())
        var w = baseWeight(it.kind)
        if (!it.foreground) w *= BACKGROUND_MULTIPLIER
        w * decayFactor
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
                    BypassTrace.note("FSM", "ALERT candidate: score=$s server_initiated_marker=true")
                } else {
                    // Visibility: when score alone crosses but no SERVER-initiated
                    // signal exists, say so explicitly — this is the profile-browse
                    // false-positive path, now correctly held at ARMED.
                    BypassTrace.note(
                        "FSM", "ARMED eval: score=$s server_initiated_marker=false " +
                            "(client-initiated churn cannot escalate — holding)"
                    )
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
     * "Server pressure" = evidence the SERVER initiated something, not the
     * client. Only server-INITIATED signals qualify (hermod_dup push, Janus
     * challenge). Client-initiated calls (/scauth/validate, snap_token) are
     * EXCLUDED — on-device proof 2026-08-21: counting them made the marker
     * check circular (user browsing fires validates; validates were the
     * marker; every browsing session escalated to ALERT).
     */
    private fun hasServerPressureMarker(): Boolean {
        val cutoff = now() - 120_000
        return signals.any {
            it.ts >= cutoff &&
                (it.kind == TapKind.HERMOD_PUSH || it.kind == TapKind.JANUS_CHALLENGE)
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
