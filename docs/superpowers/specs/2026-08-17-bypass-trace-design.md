# Bypass Trace Instrumentation — Design (2026-08-17)

Approved via brainstorming (scope: tap-gap diagnosis + seam-firing proof +
lifecycle audit + counters; noise model: gated heavy, always-on light).

## Goal

Close the observable gaps in the bypass stack:

1. `/snap_token/pb/snap_session` taps were never observed in live logs — we
   need sight of ALL wire URIs to re-match the tap target.
2. The SecurityFeatures blocking seams (Argos ctor/headers/token, SC client
   attestation job, duplex hermod_dup, auth-context nulling, platform
   attestation mapper, Play Integrity + Graphene HTTP mutes) are proven
   statically only — never shown firing at runtime.
3. No runtime counters for the native hooks (property reads, launder hits,
   dl_filtered totals).

## Architecture

One gate, one singleton accessor, one controller feature:

- Config flag: `experimental.nativeHooks.bypass_trace` (`requireRestart`).
- `BypassTrace` object (core/features/impl/experiments/BypassTrace.kt):
  `enabled` gate, `note()`, `latch()` (first-occurrence dedup),
  `inc()` (counters), `noteSeamFired()`. All no-ops when disabled — single
  volatile read. Writes via `android.util.Log` tag `PurrfectTrace`, so
  CoreLogger's `Log.println` hook surfaces everything to the in-app log tab
  and the existing LogManager export — zero export plumbing changes.
- `BypassTraceController` feature: sets the gate on init, enables the native
  gate, runs a 60s summary loop emitting one merged line
  (`Bypass summary | native={JSON} kotlin=[k=v ...]`).

## Instrumented sites

| Site | Instrumentation |
|------|-----------------|
| HermodTapHooks.observeAndIngest | per-seam wire counter; per-known-path tap counters; **URI census** — every unmatched path logs once via latch (`CENSUS [seam] uri`) |
| RotationFSM | ingest/state counters; ARMED eval logs markerWithin90s outcome each tick |
| DeviceSpooferHook | `launder_map_pushed` seam + key list; rotation counters; indistinguishable-skip counter |
| SecurityFeatures | noteSeamFired at: unary_call_attestation_cancelled, argos_ctor_intercepted, argos_get_token_nulled, argos_attestation_headers_nulled, argos_create_instance_spoofed, sc_client_attestation_job_suppressed, duplex_hermod_dup_blocked, auth_context_attestation_nulled, platform_attestation_single_err, platform_attestation_nulled, play_integrity_http_muted, graphene_metrics_muted |
| property_hook.rs | `TRACE_ENABLED` atomic + counters (prop_reads, launder_hits, at_overrides, dl_enumerations, dl_filtered); gated increments; `set_bypass_trace` / `snapshot_bypass_trace` JNI (snapshot = JSON + reset → per-interval deltas) |
| lib.rs / NativeLib.kt | `setBypassTraceNative(Z)V`, `snapshotBypassTraceNative()Ljava/lang/String;` |

## Behavior when disabled

Gate off: every accessor returns immediately; native increments skipped via
relaxed atomic load; no log lines, no counters, no JNI traffic. Identical
behavior to the pre-trace build.

## Verification

Build via GH Actions (`assembleArmv8Debug`); on-device: enable `bypass_trace`,
export in-app log, expect `Bypass trace enabled`, `SEAM | fired: ...` lines,
and the 60s summary with nonzero native counters. Missing seam fires = the
seam never executed in-session (probe target for the next iteration).