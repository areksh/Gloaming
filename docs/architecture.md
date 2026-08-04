# Gloaming - application architecture

Status: draft, 22 July 2026. Companion to `docs/sourcing and constraints.md`,
which records the project's external constraints (sources, licences, hosting
topology, the attention pipeline). That document deliberately stops short of an
application architecture; this document is the piece it defers. Where the two
overlap, the sourcing document is the source of truth for *why* a constraint
exists and this document is the source of truth for *how* the code is shaped
around it. Section references written "Sec. N" point into the sourcing document.

Decisions of record are captured as ADRs under `docs/adr/`; this document is the
narrative overview and links to them.

## 1. What Gloaming is

A free, ad-free, Play-Services-free Android app that tells you when to look up:
aurora first, then satellite passes, the sky calendar and comets. Global by
design, UK-enhanced. Code and releases on Codeberg; data on Cloudflare behind a
project-owned domain. The v1.0 release gate is the end of Phase 2 (Sec. 14):
aurora, cloud cover and darkness windows, delivered reliably through Doze.

The app is named **Gloaming**. "Sky Alerts" in older notes is a stale working
title (see the issues section and ADR-0001).

## 2. System topology

Two independently deployable pieces plus one direct-from-device dependency:

```
  Cloudflare cron Worker  --PUT-->  R2 + public custom domain (sky.<domain>/v1/)
   (write side only)                  (read side, CDN serves objects directly)
                                             ^
                                             | HTTPS conditional GET (mostly 304)
                                             | outbound only, device-initiated
                                        Android app  --direct-->  Open-Meteo
```

- The **cron Worker** is never in the read path. It fetches upstreams on a
  schedule and writes derived objects to R2.
- **R2 + a public custom domain** serves those objects through the CDN with
  `Cache-Control` and ETags. No Worker invocation per read.
- The **Android app** does all location maths and all alert decisions on-device
  and polls on its own schedule. Nothing can contact the app.
- **Open-Meteo** is called direct from each device, deliberately unproxied, so
  its per-IP limits are spread across clients rather than concentrated on Worker
  egress IPs (Sec. 2).

Rationale for R2-over-Pages and Worker-out-of-read-path is ADR-0002; the
all-on-device, polling-only, no-push posture is ADR-0003.

## 3. Backend: the cron Worker

- **Tooling:** TypeScript, deployed with Wrangler. Unit tests on Vitest against
  the workerd runtime (Miniflare). The sourcing document does not pick a
  language; TypeScript is chosen here for the first-party Cloudflare types and
  the testability of the fetch/compose/pack logic.
- **Cron dispatch:** one Worker carrying several Cron Triggers. The `scheduled`
  handler branches on `event.cron` and runs only the due fetcher:

  | Schedule | Source | Produces |
  |---|---|---|
  | `*/3 * * * *` | AuroraWatch UK (UK layer) | UK fields of `status.json` |
  | `*/5 * * * *` | SWPC OVATION grid + Kp + solar wind | `oval.bin`, space-weather fields of `status.json` |
  | `0 */2 * * *` | CelesTrak OMM CSV (Phase 3) | `sats.json` |
  | `0 3 * * 1` | MPC comet elements (Phase 5) | `comets.json` |

- **`status.json` composition.** Two crons contribute to `status.json` on
  different cadences, so neither may PUT the whole object or it clobbers the
  other's fresh fields. Each source writes its own contribution as a small
  partial object in R2; a single `composeStatus()` step reads the partials and
  PUTs the composed `status.json`. This is the resolution to issue #1 below.
- **`oval.bin` packer.** The SWPC OVATION 360x181 grid is packed to one byte per
  cell (probability 0-100), row-major, then gzipped at PUT. The pack/unpack pair
  is covered by a round-trip unit test.
- **Circuit breaker (Phase 3, CelesTrak).** Any non-200 from CelesTrak writes a
  tripped flag plus the response code and timestamp to KV; every invocation
  checks the flag first and does not query while it is set; **reset is manual**.
  `sats.json` keeps serving its last-good copy meanwhile. Tripping POSTs the
  maintainer ntfy topic. This is the single most important CelesTrak requirement
  (Sec. 3): stop on any non-200, do not back off, do not retry.
- **Config, not hardcode.** The AuroraWatch base URL lives in Worker
  configuration and the Open-Meteo base URL lives in the manifest, never baked
  into a fetch or the APK, so a source URL swap is a config change (Sec. 1,
  Sec. 2).
- **Signing.** The Worker signs `index.json` with Ed25519 using the Workers
  SubtleCrypto Ed25519 support. The private key is a Wrangler secret; the public
  key is compiled into the APK (Sec. 10, ADR-0003).
- **Observability.** `health.json` in R2 carries last-success and last-error per
  source. One maintainer-only ntfy topic receives a POST when a breaker trips or
  a source fails silently. Check whether Cloudflare's own free-plan Workers
  alerting covers cron failure before building more than this (Sec. 3).

State stores: use **R2 for the `status.json` partials** (1M Class A writes/month
free, ample headroom) and **KV only for the rarely-written breaker flag**, to
stay clear of the KV 1,000-writes/day/namespace ceiling (issue #7).

## 4. Read side: R2 and the custom domain

- A public R2 bucket bound to `sky.<domain>`; objects served directly by the
  CDN, no Worker per request.
- `Cache-Control` is set on object metadata at PUT time (the table in Sec. 2);
  the CDN honours it and clients revalidate with `If-None-Match` against R2's
  ETag. "ETagged" without `max-age` does not absorb the fan-out and is not a
  caching strategy.
- A versioned path prefix `/v1/` means a breaking schema change is a new prefix,
  not a silent break of installed clients.

### Hosted objects (data contract)

Cross-checked against Sec. 10 and the caching table in Sec. 2.

| Object | Size | Refresh | `Cache-Control` | Notes |
|---|---|---|---|---|
| `index.json` | ~200 B | on change | `public, max-age=3600` | Manifest. The only URL baked into the APK. Signed. |
| `index.json.sig` | 64 B | on change | `public, max-age=3600` | Ed25519 detached signature over `index.json`. |
| `status.json` | ~1 KB | 3 min | `public, max-age=60, stale-while-revalidate=120` | AuroraWatch UK level + Kp + solar wind + `global_activity` + `oval_etag`. Latency-critical. |
| `oval.bin` | ~5-10 KB gz | 5 min | `public, max-age=120, stale-while-revalidate=180` | Packed OVATION grid: 1 byte/cell, row-major, 360x181. Fetched conditionally on `oval_etag` change (Sec. 4). |
| `sats.json` | ~20 KB | 2 h | `public, max-age=1800` | OMM elements: `stations` + `visual`, plus hardcoded standard magnitudes. Phase 3. |
| `comets.json` | ~2 KB | weekly | `public, max-age=21600` | Curated MPC elements + H/slope. Phase 5. |
| `showers.json` | ~2 KB | annually | n/a | Shipped in the APK; remote override optional. Phase 4. |
| `health.json` | small | on change | short | Per-source last-success/last-error. World-readable (issue #8). |

The manifest is the only URL in the APK. It carries `schema`,
`min_supported_version` (the kill switch), `message` (a broadcast channel
rendered as inert plain text) and the `endpoints` map including the Open-Meteo
`weather` URL (Sec. 10).

## 5. Android app

Recreated as a Compose project matching Sec. 7: `minSdk 24`,
`compileSdk`/`targetSdk 37`, JDK 21, Kotlin 2.4.x, Compose BOM 2026.06.x, AGP 9.
No Play Services anywhere; `LocationManager` only (never the fused provider),
with manual location entry as a first-class input and the app fully functional
with location permission permanently denied (Sec. 7, Sec. 11).

Note on the build: AGP 9 has built-in Kotlin support, so the
`org.jetbrains.kotlin.android` plugin is not applied (AGP rejects it). Only
`com.android.application` and the Compose compiler plugin
(`org.jetbrains.kotlin.plugin.compose`) are applied; Kotlin options go in a
top-level `kotlin { }` block and `jvmTarget` defaults to
`compileOptions.targetCompatibility`.

### Module layout

Proportionate to start, growing with the phases:

- **`:app`** - Compose UI, notification channels, scheduling, DI wiring.
- **`:core:astronomy`** - the astronomy-engine wrapper and the comet Kepler
  solver (Phase 5).
- **`:core:predict`** - the vendored Look4Sat SGP4/SDP4 code (GPL-3), added in
  Phase 3 and kept in its own module to isolate the vendored source and preserve
  its licence headers.

Package-based separation inside `:app` is enough for Phases 1-2; the
`:core:predict` module is extracted when the propagator lands.

### Layers

- **data:** OkHttp + kotlinx-serialization clients (`ManifestClient`,
  `StatusClient`, `OvalClient`, `WeatherClient`); Room for cached status, oval
  and computed events; DataStore (Preferences) for settings. A `WeatherSource`
  interface is defined from the start (Open-Meteo the only shipped
  implementation) to leave room for BYO keys without a later refactor (Sec. 15).
- **domain:** the packed-grid lookup; one internal likelihood model (OVATION
  cell, or AuroraWatch inside the UK bounding box) so the UI never branches on
  which source is in play; the decision engine (hysteresis `T_on`/`T_off`,
  minimum re-alert interval, quiet hours, per-user threshold, the non-optional
  darkness gate and the cloud gate); the astronomy calculators; and the
  adaptive-polling scheduler policy.
- **scheduling:** `AlarmManager.setWindow()` or chained `OneTimeWorkRequest`s
  (WorkManager's 15-minute floor is too coarse in both directions, Sec. 9.3);
  every scheduled wake is jittered (Sec. 2); the battery-optimisation-exemption
  request flow; and storm-mode as a `specialUse` foreground service that runs
  only for the duration of an event.
- **notification:** one channel per category - `aurora`, `iss-passes`,
  `bright-satellites`, `comets`, `eclipses`, `meteor-showers`, `conjunctions` -
  created behind the `SDK_INT >= 26` branch.
- **ui:** Compose screens; a prominent staleness display that distinguishes
  "quiet/dormant" from "not looked recently" (Sec. 10); red night-vision mode
  and a home-screen widget (Phase 2).

### Manifest verification

The app verifies the Ed25519 detached signature over `index.json` before
trusting it, and falls back to its last known-good manifest on an absent or bad
signature; `message` renders as inert plain text with no clickable links
regardless (Sec. 10). Verification needs a bundled Ed25519 verifier because the
platform primitive is not available at `minSdk 24` (issue #3).

### Dependency injection

Hilt. It works fully off-Play (it is a compile-time processor, not a Play
Services dependency) and is the mainstream choice for this stack. Recorded here
rather than asked because it is low-cost to change.

## 6. Cross-cutting concerns

- **Privacy (ADR-0003).** No data collected; location never leaves the device;
  the aurora grid is shipped whole and indexed locally, never queried by
  coordinate; all traffic is outbound and device-initiated. The Data Safety
  story is "no data collected", and it is the app's actual differentiator.
- **Staleness.** The app shows the age of the data whenever it exceeds one
  refresh interval, and says so when the manifest has been unreachable past a
  threshold rather than serving stale state as current (Sec. 10).
- **Config over hardcoding.** Anything that can move - upstream URLs above all -
  lives in Worker config or the manifest, never in the APK.

## 7. Testing and CI

Greenfield, held to the full TDD bar (ADR-0001).

- **App:** JUnit, with Robolectric where a test needs framework classes. Unit
  tests for the grid lookup, the likelihood model, the decision engine
  (hysteresis and each gate), the `oval.bin` pack/unpack round-trip, and the
  null-safe polar-darkness path (Sec. 5). Phase 3 runs Vallado's SGP4
  verification vectors in CI against the vendored propagator (Sec. 7) - a
  checklist gate before Phase 3 ships, not an aspiration.
- **Worker:** Vitest against workerd for the fetchers, `composeStatus()`, the
  packer and the circuit breaker.
- **The load-bearing manual milestone** is the overnight real-phone Doze test
  (Sec. 14 step 6). Everything else is decoration on top of reliable delivery.
- **CI:** Codeberg CI (Woodpecker or Forgejo Actions) builds and tests the app
  and deploys the Worker via Wrangler. Reproducible builds for the F-Droid main
  repo are a post-v1 concern (Sec. 12).

## 8. Build order

The phasing in Sec. 14 stands unchanged; this document supplies the module,
schema and test detail each phase needs, and does not re-order anything. The
release gate remains the end of Phase 2. Before development starts, work through
`docs/prerequisites.md`.

## 9. Issues and clarifications in the sourcing document

Refinements found while drafting this architecture. Most are clarifications, not
errors; the sourcing document is strong.

1. **`status.json` has two writers on different cadences.** The 3-minute
   AuroraWatch cron and the 5-minute SWPC cron both contribute; without a
   composition step, whichever writes last clobbers the other's fresh field.
   Resolved by per-source partials plus a single `composeStatus()` step (Sec. 3
   above). The sourcing document treats `status.json` as single-writer.
2. **Cloudflare free-plan Cron Trigger limits are unverified.** Confirm the free
   plan permits the number of schedules needed (four) and sub-5-minute (3-minute)
   cron before relying on it. Believed fine at 1-minute granularity, but it is
   exactly the kind of provider limit the sourcing document otherwise pins down.
3. **Ed25519 verification at `minSdk 24`.** The platform primitive
   (`java.security` EdDSA via Conscrypt) only arrives around API 33, so a bundled
   verifier is required (BouncyCastle, Lazysodium, or a small vendored
   implementation). A concrete dependency the "afternoon of work" estimate in
   Sec. 10 omits. The Worker side is unaffected (SubtleCrypto).
4. **GPL-3.0 is a preference, not forced by SGP4.** Sec. 1 says an MIT licence
   was available only in the aurora-only scope, but Sec. 7 notes predict4java was
   relicensed to MIT in 2026, so full scope could stay permissive via
   predict4java instead of vendoring Look4Sat. The project has chosen GPL-3.0
   from day one (ADR-0004), so this is settled - but the sourcing wording should
   be corrected so it is not later re-litigated as if SGP4 forced GPL.
5. **Name inconsistency.** The sourcing document says "Sky Alerts" throughout;
   the project is Gloaming. Resolved (ADR-0001); the sourcing document should be
   updated or annotated so the names do not drift.
6. **`specialUse` foreground service manifest property.** On `targetSdk` 34+ the
   service needs `android:foregroundServiceType="specialUse"` plus the
   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` `<property>`. Off-Play there is no reviewer,
   but the declaration is still required to run. An implementation note, not an
   error.
7. **KV free-tier write ceiling (1,000/day/namespace).** Keeping the
   `status.json` composition buffer in KV would approach the cap from the 3- and
   5-minute crons alone. Use R2 for the partials and reserve KV for the
   rarely-written breaker flag (Sec. 3 above).
8. **`health.json` is world-readable** under the public domain, exposing
   per-source error codes and timestamps. Low sensitivity (all upstreams are
   public), but worth a conscious keep-public-or-gate decision.

The open questions the sourcing document already lists in Sec. 15 - default alert
thresholds, adaptive-polling escalation values, storm-mode exit condition,
OVATION storm-time gzip size, Shetland per-region alerting - are calibration and
product decisions, not architectural blockers, and are carried forward unchanged.

## 10. Decision records

- ADR-0001 - adopt the engineering practices and the Gloaming name.
- ADR-0002 - R2 object store with a cron Worker out of the read path.
- ADR-0003 - all computation on-device; polling only, no push.
- ADR-0004 - GPL-3.0 from day one.
