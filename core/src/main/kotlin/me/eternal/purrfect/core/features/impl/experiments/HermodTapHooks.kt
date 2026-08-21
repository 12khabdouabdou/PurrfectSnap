package me.eternal.purrfect.core.features.impl.experiments

import me.eternal.purrfect.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfect.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor

/**
 * Hermod instrumentation taps for the RotationFSM.
 *
 * Anchored the SAME way purrfectsnap hooks HTTP/grpc request paths: the
 * universal `UnaryCallEvent` seam (the one `SecurityFeatures`,
 * `FriendMutationObserver`, `AddFriendSourceSpoof`, `BestFriendPinning`,
 * `BetterLocation`, `ProfilePictureDownloader` all subscribe to). Every unary
 * HTTP call fires the event with its request URI in `event.uri`, so we can
 * observe the residual rotation/refresh pressure that purrfect's blocking
 * strategy fails to suppress WITHOUT depending on a concrete class — the
 * security-package `*HttpInterface`s here are plain Retrofit-style Java
 * interfaces (no `$CppProxy` generated for them; the impls are DI-generated
 * at runtime and unhookable by name), so class-level hooking is the wrong
 * seam. The event seam sees the calls either way.
 *
 * Observed pressure surfaces (URIs verified in the decompile against the
 * `@LMce` path annotations on the underlying interfaces):
 *  - TokenRefreshDurableJob ctor       -> force token-refresh job scheduled
 *                                        (concrete durable-job shell, hooked
 *                                        by constructor — the Md8.a
 *                                        "ForceArgosTokenRefresh" fallout when
 *                                        attestation is blocked).
 *  - /scauth/validate                 -> UserSessionValidationHttpInterface
 *                                        `validateSession` (SC-AUTH/validate).
 *  - /snap_token/pb/snap_session       -> SnapTokenApiGatewayHttpInterface
 *                                        `fetchSessionRequest`.
 *  - /snap_token/pb/snap_access_tokens -> SnapTokenApiGatewayHttpInterface
 *                                        `fetchSnapAccessTokens`.
 *
 * These `*HttpInterface`s are Retrofit-style HTTP (Content-Type: application/
 * x-protobuf, `@LMce` path annotations) so they traverse `NetworkApi.submit`
 * (the `NetworkApiRequestEvent` seam — `event.url`), but in builds that route
 * the same paths through `UnifiedGrpcService.unaryCall` we ALSO subscribe to
 * `UnaryCallEvent` (matching `event.uri`). Matching either root lets the FSM
 * see its server-pressure markers regardless of which transport a given
 * build chooses — the same dual-seam approach `FriendMutationObserver` uses.
 *
 * Observability-only: we do NOT cancel, redirect, or mutate the call here. The
 * FSM consumes TapSignals and decides whether to rotate identity; blocking is
 * purrfect's existing SecurityFeatures + native risk-blocklist job.
 */
class HermodTapHooks : Feature("Hermod Taps") {

    companion object {
        /**
         * Foreground tracker — counts started-but-not-stopped activities via
         * application-level lifecycle callbacks. Cheap, no per-view hooks.
         * Used to classify tap signals: user-driven traffic happens in the
         * foreground; background bursts are rarer and mildly more notable
         * (FSM applies a small multiplier).
         */
        @Volatile
        private var resumedCount: Int = 0

        fun isAppForeground(): Boolean = resumedCount > 0

        private fun registerForegroundTracker(feature: Feature) {
            runCatching {
                val app = feature.context.androidContext as? android.app.Application ?: return
                app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityStarted(activity: android.app.Activity) { resumedCount++ }
                    override fun onActivityStopped(activity: android.app.Activity) { resumedCount = (resumedCount - 1).coerceAtLeast(0) }
                    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                    override fun onActivityResumed(activity: android.app.Activity) {}
                    override fun onActivityPaused(activity: android.app.Activity) {}
                    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                    override fun onActivityDestroyed(activity: android.app.Activity) {}
                })
                feature.context.log.verbose("Tap foreground tracker registered (resumed=${resumedCount})")
            }.onFailure {
                feature.context.log.warn("Foreground tracker unavailable (${it.message}) — taps default to fg=true")
            }
        }
    }

    override fun init() {
        if (!isEnabled()) {
            context.log.info(
                "Hermod taps skipped (hermodTaps=${context.config.experimental.nativeHooks.hermodTaps.get()} " +
                    "rotationFsm=${context.config.experimental.nativeHooks.rotationFsm.get()} — both off)"
            )
            return
        }
        registerForegroundTracker(this)
        runCatching {
            installTokenRefreshTap()
            installUnaryCallTaps()
            context.log.info("Hermod taps installed")
        }.onFailure {
            context.log.warn("Failed to install Hermod taps: ${it.message}")
        }
    }

    /**
     * Taps are live when either the observation toggle (`hermodTaps`) OR the
     * rotation toggle (`rotationFsm`) is on — the FSM is blind without its tap
     * input, so rotation-only mode must still install the taps.
     */
    private fun isEnabled(): Boolean =
        context.config.experimental.nativeHooks.hermodTaps.get() ||
            context.config.experimental.nativeHooks.rotationFsm.get()

    /**
     * FSM cooldown gate — during cooldown the FSM drops signals anyway, so the
     * taps short-circuit before logging to avoid feeding the retry churn.
     */
    private fun isSuppressedByFsm(): Boolean = fsmIfRotationActive()?.suppressTaps() == true

    /**
     * DurableJob shells are concrete classes instantiated by the durable-job
     * framework when a job is scheduled — hooking the constructor fires exactly
     * on enqueue, on every version where the job exists.
     */
    private fun installTokenRefreshTap() {
        runCatching {
            val tokenRefresh = findClass("com.snap.security.devicetoken.TokenRefreshDurableJob")
            tokenRefresh.hookConstructor(HookStage.AFTER) { param ->
                if (!isEnabled()) return@hookConstructor
                if (isSuppressedByFsm()) return@hookConstructor
                val args = param.args().joinToString(", ") { it?.javaClass?.simpleName ?: "null" }
                context.log.info("Hermod tap: TokenRefreshDurableJob scheduled [fg=${isAppForeground()}] [$args]")
                fsmIfRotationActive()?.ingest(
                    RotationFSM.TapSignal(
                        RotationFSM.TapKind.TOKEN_REFRESH,
                        System.currentTimeMillis(),
                        foreground = isAppForeground(),
                        note = "durable_job_ctor"
                    )
                )
            }
        }.onFailure {
            context.log.warn("Hermod tap: TokenRefreshDurableJob unavailable: ${it.message}")
        }
    }

    /**
     * The HTTP-level pressure taps. Subscribes to purrfect's universal unary
     * call seam and ingests FSM signals based on `event.uri`. This is the same
     * mechanism `SecurityFeatures` already uses for attestation cancellation —
     * no concrete class needed, so it survives R8 renames of the (runtime-only)
     * HttpInterface implementations.
     */
    private fun installUnaryCallTaps() {
        context.event.subscribe(UnaryCallEvent::class) { event ->
            val matched = observeAndIngest(event.uri, "UnaryCallEvent")
            if (matched) {
                // Response-size capture: attach a response callback that logs
                // how much data came back. A routine validate returns a tiny
                // ack; a challenge-bearing response carries payload. Log-only.
                runCatching {
                    event.addResponseCallback {
                        // Receiver is the response UnaryCallEvent — `buffer`
                        // below is ITS buffer, not the request's.
                        context.log.verbose(
                            "Hermod tap response: uri=${uri} bytes=${buffer.size} [fg=${isAppForeground()}]"
                        )
                        BypassTrace.inc("tap_response_${buffer.size.coerceAtMost(4096) / 1024}k")
                    }
                }
            }
        }
        // Retrofit-style `*HttpInterface` calls (scauth/validate, snap_token) go
        // through NetworkApi.submit, so we ALSO subscribe on the URL event — the
        // dual-seam approach `FriendMutationObserver` uses (`ami/friends`).
        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            observeAndIngest(event.url, "NetworkApiRequestEvent")
        }
    }

    /**
     * Shared filter+ingest: emit the relevant FSM TapSignal when the URI/URL
     * matches a pressure-marker path. Returns true when this URI was consumed
     * as a known tap (caller may enrich with response callbacks). Every log
     * line carries the initiator classification and foreground state so a log
     * export alone explains any FSM decision.
     */
    private fun observeAndIngest(uriOrUrl: String, seam: String): Boolean {
        if (!isEnabled()) return false
        BypassTrace.inc("wire_${seam}")
        val fsm = fsmIfRotationActive()
        // Cooldown suppresses BOTH evidence collection AND the observation
        // logs that feed the retry churn. When FSM is off (hermodTaps-only
        // observation mode) there is no cooldown to respect.
        if (fsm != null && fsm.suppressTaps()) return false
        val now = System.currentTimeMillis()
        val fg = isAppForeground()
        // Strip query strings / fragments and trailing slashes before matching —
        // a bare endsWith silently misses "/scauth/validate?foo=bar" or a
        // trailing-slash variant (PREBUILD_AUDIT LOW-4).
        val path = uriOrUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        when {
            // SERVER-INITIATED: Janus verification challenge. The server only
            // issues these when it demands proof — highest-value signal.
            path.contains("challengeorchestration") || (path.contains("janus") && path.contains("verifychallenge")) -> {
                BypassTrace.inc("tap_janus_challenge")
                context.log.info("Hermod tap: JANUS CHALLENGE (server-initiated!) [$seam] [fg=$fg] uri=$uriOrUrl")
                fsm?.ingest(
                    RotationFSM.TapSignal(
                        RotationFSM.TapKind.JANUS_CHALLENGE, now,
                        foreground = fg, note = seam
                    )
                )
                true
            }
            // CLIENT-initiated: fires per profile view / navigation. LOW weight.
            path.endsWith("/scauth/validate") || (path.contains("scauth") && path.contains("validate")) -> {
                BypassTrace.inc("tap_scauth_validate")
                context.log.info("Hermod tap: /scauth/validate (client-initiated, routine) [$seam] [fg=$fg] uri=$uriOrUrl")
                fsm?.ingest(
                    RotationFSM.TapSignal(
                        RotationFSM.TapKind.SCAUTH_VALIDATE, now,
                        foreground = fg, note = seam
                    )
                )
                true
            }
            path.endsWith("/snap_token/pb/snap_session") || (path.contains("snap_token") && path.contains("snap_session")) -> {
                BypassTrace.inc("tap_snap_session")
                context.log.info("Hermod tap: /snap_token/pb/snap_session (client-initiated) [$seam] [fg=$fg] uri=$uriOrUrl")
                fsm?.ingest(
                    RotationFSM.TapSignal(
                        RotationFSM.TapKind.SNAP_SESSION, now,
                        foreground = fg, note = seam
                    )
                )
                true
            }
            path.endsWith("/snap_token/pb/snap_access_tokens") || (path.contains("snap_token") && path.contains("snap_access")) -> {
                BypassTrace.inc("tap_snap_access")
                context.log.verbose("Hermod tap: /snap_token/pb/snap_access_tokens (client-initiated) [$seam] [fg=$fg] uri=$uriOrUrl")
                fsm?.ingest(
                    RotationFSM.TapSignal(
                        RotationFSM.TapKind.SNAP_ACCESS, now,
                        foreground = fg, note = seam
                    )
                )
                true
            }
            // URI census: every unmatched endpoint becomes visible (latched, so
            // one line per unique URI per process) — the raw material to
            // re-match missed pressure paths.
            else -> {
                BypassTrace.inc("census_unmatched_${seam}")
                BypassTrace.latch("census_${seam}_$path", "CENSUS", "[$seam] $uriOrUrl")
                false
            }
        }
    }

    /**
     * Returns the live RotationFSM instance if (and only if) the FSM is
     * enabled in config. Taps must not block on FSM absence: rotation is an
     * observability-only layer unless the user has opted in.
     */
    private fun fsmIfRotationActive(): RotationFSM? =
        if (context.config.experimental.nativeHooks.rotationFsm.get()) {
            context.features.get(RotationFSM::class)
        } else {
            null
        }
}
