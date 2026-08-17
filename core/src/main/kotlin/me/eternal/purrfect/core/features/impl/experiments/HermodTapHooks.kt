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

    override fun init() {
        if (!isEnabled()) {
            context.log.info(
                "Hermod taps skipped (hermodTaps=${context.config.experimental.nativeHooks.hermodTaps.get()} " +
                    "rotationFsm=${context.config.experimental.nativeHooks.rotationFsm.get()} — both off)"
            )
            return
        }
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
                context.log.info("Hermod tap: TokenRefreshDurableJob scheduled [$args]")
                fsmIfRotationActive()?.ingest(
                    RotationFSM.TapSignal(RotationFSM.TapKind.TOKEN_REFRESH, System.currentTimeMillis())
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
            observeAndIngest(event.uri, "UnaryCallEvent")
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
     * ends with one of the pressure-marker paths (verified against the
     * decompiled `@LMce` annotations). Surfaces which seam fired in the log
     * line so LO can confirm which transport a build actually routes through.
     */
    private fun observeAndIngest(uriOrUrl: String, seam: String) {
        if (!isEnabled()) return
        BypassTrace.inc("wire_${seam}")
        val fsm = fsmIfRotationActive()
        // Cooldown suppresses BOTH evidence collection AND the observation
        // logs that feed the retry churn. When FSM is off (hermodTaps-only
        // observation mode) there is no cooldown to respect.
        if (fsm != null && fsm.suppressTaps()) return
        val now = System.currentTimeMillis()
        // Strip query strings / fragments and trailing slashes before matching —
        // a bare endsWith silently misses "/scauth/validate?foo=bar" or a
        // trailing-slash variant (PREBUILD_AUDIT LOW-4).
        val path = uriOrUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        when {
            path.endsWith("/scauth/validate") -> {
                BypassTrace.inc("tap_scauth_validate")
                context.log.info("Hermod tap: /scauth/validate (UserSessionValidation) [$seam] uri=$uriOrUrl")
                fsm?.ingest(RotationFSM.TapSignal(RotationFSM.TapKind.SCAUTH_VALIDATE, now))
            }
            path.endsWith("/snap_token/pb/snap_session") -> {
                BypassTrace.inc("tap_snap_session")
                context.log.info("Hermod tap: /snap_token/pb/snap_session [$seam] uri=$uriOrUrl")
                fsm?.ingest(RotationFSM.TapSignal(RotationFSM.TapKind.SNAP_SESSION, now))
            }
            path.endsWith("/snap_token/pb/snap_access_tokens") -> {
                BypassTrace.inc("tap_snap_access")
                context.log.verbose("Hermod tap: /snap_token/pb/snap_access_tokens [$seam] uri=$uriOrUrl")
                fsm?.ingest(RotationFSM.TapSignal(RotationFSM.TapKind.SNAP_ACCESS, now))
            }
            // URI census: every unmatched endpoint becomes visible (latched, so
            // one line per unique URI per process) — the raw material to
            // re-match /snap_token/pb/snap_session when this build routes it
            // through a path variant the endsWith matcher misses.
            else -> {
                BypassTrace.inc("census_unmatched_${seam}")
                BypassTrace.latch("census_${seam}_$path", "CENSUS", "[$seam] $uriOrUrl")
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
