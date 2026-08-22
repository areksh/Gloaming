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
  | `0 0,2,4,...,22 * * *` | CelesTrak OMM CSV | `sats.json`. Spelled out rather than stepped; see issue 10. |
  | `0 3 * * 1` | MPC comet elements (Phase 5) | `comets.json` |

- **`status.json` composition.** Two crons contribute to `status.json` on
  different cadences, so neither may PUT the whole object or it clobbers the
  other's fresh fields. Each source writes its own contribution as a small
  partial object in R2 (`internal/space.json`, `internal/uk.json`); a single
  `composeStatus()` step reads the partials and PUTs the composed
  `status.json`. This is the resolution to issue #1 below.

  Note the partials sit in the same public bucket and are therefore world
  readable. They hold only already-public derived data, so this is the same
  judgement as `health.json` (issue #9) rather than a new exposure.
- **`oval.bin` packer.** The SWPC OVATION 360x181 grid is packed to one byte per
  cell (probability 0-100), row-major, then gzipped at PUT. The pack/unpack pair
  is covered by a round-trip unit test.
- **OMM normalisation.** CelesTrak publishes GP data as OMM in CSV, never TLE
  for the 6-digit catalogue numbers issued since July 2026. The Worker parses it
  and publishes `sats.json` with fields mapping one-for-one onto the app's
  element type, including the epoch already converted from ISO-8601 to the
  packed `YYDDD.dddddd` every SGP4 implementation takes. Done here rather than
  on the device because the column mapping and the epoch conversion are the two
  things most likely to be wrong, and because `minSdk 24` predates `java.time`.
  Columns are read **by name from the header row**, never by position: reading a
  reordered document by index would not fail, it would publish plausible
  elements for the wrong orbit.
- **Circuit breaker (CelesTrak).** Any non-200 from CelesTrak writes a tripped
  flag plus the response code and timestamp to R2 at
  `internal/breaker/celestrak.json`; every invocation checks the flag first and
  does not query while it is set; **reset is manual**, by deleting that object.
  `sats.json` keeps serving its last-good copy meanwhile. Tripping POSTs the
  maintainer ntfy topic. This is the single most important CelesTrak requirement
  (Sec. 3): stop on any non-200, do not back off, do not retry.

  Two details settled in the building, both worth keeping:

  - **Redirects are not followed** (`redirect: "manual"`). A followed 301 would
    arrive as a 200 from wherever it pointed, and 301 is precisely the code that
    means "this URL is legacy and will stop being honoured".
  - **A connection that fails outright leaves the breaker closed.** No response
    arrived, so it is not the signal the policy is about, and a transient
    network fault must not need a human before elements can refresh again. It
    still fails the source, which is what `health.json` and its alert are for.

  The two groups are fetched **sequentially**. In parallel, the second request
  would be on the wire before the first response could forbid it.
- **Config, not hardcode.** The AuroraWatch base URL lives in Worker
  configuration and the Open-Meteo base URL lives in the manifest, never baked
  into a fetch or the APK, so a source URL swap is a config change (Sec. 1,
  Sec. 2).
- **Signing.** The Worker signs `index.json` with Ed25519 using the Workers
  SubtleCrypto Ed25519 support. The private key is a Wrangler secret; the public
  key is compiled into the APK (Sec. 10, ADR-0003).
- **Observability.** `health.json` in R2 carries last-success and last-error per
  source. One maintainer-only ntfy topic receives a POST when a breaker trips or
  a source fails silently.

  Sec. 3 asks whether Cloudflare's own alerting covers cron failure first, so as
  not to build what already exists. **Checked 2026-08-05: it does not.** There
  is no Workers or Cron Trigger entry in Cloudflare's notification catalogue at
  any tier, and webhook delivery is restricted to paid accounts regardless. Both
  halves of the alerting path therefore have to be built.

  Note this is developer-facing alerting only, and is the *one* place ntfy
  appears in the design. It is not how users are notified - see ADR-0003 and the
  distinction drawn in Sec. 5 below.

State stores: **R2 throughout** - the `status.json` partials, the per-source
health records, and the CelesTrak breaker flag (1M Class A writes/month free,
ample headroom). KV was planned for the breaker to stay clear of its
1,000-writes/day/namespace ceiling (issue #7), but a flag written a few times a
year does not justify provisioning a second store when the bucket is already
bound and already holds the internal objects.

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
| `status.json` | ~0.5 KB | 3 min | `public, max-age=60, stale-while-revalidate=120` | AuroraWatch UK level + Kp + solar wind + `global_activity` + `max_probability` + `oval_etag`. Latency-critical. |
| `oval.bin` | 1.6-2.5 KB gz | 5 min | `public, max-age=120, stale-while-revalidate=180` | Packed OVATION grid: 1 byte/cell, row-major by latitude, 360x181, gzipped. Fetched conditionally on `oval_etag` change (Sec. 4). Sizes measured against live data, quiet to simulated severe storm. |
| `sats.json` | ~20-40 KB | 2 h | `public, max-age=1800` | OMM elements: `stations` + `visual`, deduplicated by catalogue number and sorted, plus standard magnitudes where one can be stood behind. |
| `comets.json` | ~2 KB | weekly | `public, max-age=21600` | Curated MPC elements + H/slope. Phase 5. |
| `showers.json` | ~2 KB | annually | n/a | Shipped in the APK; remote override optional. Phase 4. |
| `health.json` | small | every run | `public, max-age=60` | Per-source state, last success, last error, consecutive failures. World-readable (issue #9). |

The manifest is the only URL in the APK. It carries `schema`,
`min_supported_version` (the kill switch), `issued_at` (unix seconds, the
rollback guard), `message` (a broadcast channel rendered as inert plain text)
and the `endpoints` map including the Open-Meteo `weather` URL (Sec. 10).

`issued_at` exists because a signature never expires: without it a correctly
signed older manifest stays acceptable for ever, and anyone able to answer for
the domain could replay one. The app refuses anything older than the copy it
holds; the Worker stamps the wall clock at which the contents last changed, and
excludes the stamp when deciding whether they changed.

It advertises only endpoints that exist. `sats` and `comets` arrive with Phases
3 and 5; because the manifest is re-fetched daily, adding a key then costs
nothing, whereas advertising a URL that 404s today would have the app handling a
failure that means nothing. The Worker republishes it only when the contents
change, and does so on every cron rather than at deploy time, so a deleted
manifest repairs itself within one cycle.

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

- **`:app`** - everything: Compose UI, notification channels, scheduling,
  wiring, and the domain code, separated by package.
- **`:core:astronomy`**, **`:core:predict`** - planned, and not built. Both were
  to isolate vendored source; the vendor source directory does that already.
  See ADR-0007.

Vendored third-party source lives in a separate source directory,
`app/src/main/vendor`, declared in `app/build.gradle.kts` and documented in
`app/src/main/vendor/README.md`. That is what isolates vendored code and its
licence headers: astronomy-engine (ADR-0006) and Look4Sat's SGP4/SDP4
(ADR-0007). A module is the heavier version of the same separation, worth
extracting when one directory stops being enough rather than in advance.
Nothing vendored is ever edited: the app wraps it instead - `astronomy.Twilight`
over the ephemeris, `satellites.PassFinder` over the propagator - so an upgrade
is a file replacement.

### Layers

- **data:** OkHttp + kotlinx-serialization clients (`ManifestClient`,
  `StatusClient`, `OvalClient`, `WeatherClient`); Room for cached status, oval
  and computed events; DataStore (Preferences) for settings. A `WeatherSource`
  interface is defined from the start (Open-Meteo the only shipped
  implementation) to leave room for BYO keys without a later refactor (Sec. 15).
- **satellites:** `Elements` (deserialising `sats.json`), `PassFinder` (the
  horizon-crossing search over the vendored propagator, which offers no search
  of its own), and `PassDescription` (qualitative buckets - never a magnitude).
  Pure and clock-free; the propagator itself is gated on Vallado's verification
  vectors.
- **domain:** the packed-grid lookup; one internal likelihood model (OVATION
  cell, or AuroraWatch inside the UK bounding box) so the UI never branches on
  which source is in play; the decision engine (hysteresis `T_on`/`T_off`,
  minimum re-alert interval, quiet hours, per-user threshold, the non-optional
  darkness gate and the cloud gate); the astronomy calculators; and the
  adaptive-polling scheduler policy.
- **scheduling:** `AlarmManager.setWindow()` or chained `OneTimeWorkRequest`s
  (WorkManager's 15-minute floor is too coarse in both directions, Sec. 9.3);
  every scheduled wake is jittered - spread either side of the requested time
  rather than added to it, so de-aliasing installs does not double as a delay
  (Sec. 2); the battery-optimisation-exemption
  request flow; and storm-mode as a `specialUse` foreground service that runs
  only for the duration of an event.
- **notification:** one channel per category - `aurora`, `iss-passes`,
  `bright-satellites`, `comets`, `eclipses`, `meteor-showers`, `conjunctions` -
  created behind the `SDK_INT >= 26` branch.

  **These are local notifications, posted by the device to itself.** The app
  wakes on its own schedule, fetches, decides, and posts. No server is involved
  in the decision, nothing is delivered to the device from outside, and the app
  has no push dependency of any kind - not Firebase, not UnifiedPush, not ntfy
  (ADR-0003). The developer ntfy topic in Sec. 3 is an entirely separate thing
  that reaches one phone, the maintainer's, and is not part of the user-facing
  path. The two are easily conflated because both are called "notifications".
- **ui:** Compose screens; a prominent staleness display that distinguishes
  "quiet/dormant" from "not looked recently" (Sec. 10); red night-vision mode
  and a home-screen widget (Phase 2).

### Manifest verification

The app verifies the Ed25519 detached signature over `index.json` before
trusting it, and falls back to its last known-good manifest on an absent or bad
signature; `message` renders as inert plain text with no clickable links
regardless (Sec. 10). Verification needs a bundled Ed25519 verifier because the
platform primitive is not available at `minSdk 24` (issue #4).

### Dependency injection

Hand-written wiring in `Graph`, not Hilt, for now. The graph is a handful of
objects with no scoping to speak of, and a background wake wants to be cheap to
start. Hilt works fully off-Play and remains the intended answer once the graph
outgrows a file that states it plainly; adding it before then would be apparatus
the project does not yet need.

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
- **The load-bearing milestone** is overnight delivery on a real phone
  (Sec. 14 step 6). Everything else is decoration on top of it. `tools/` holds
  two harnesses that answer it in minutes rather than a night:
  `bfu-check.sh` reboots a device, holds it at the lock screen and reports on
  each link in the chain separately; `doze-check.sh` forces deep idle and
  measures the interval the polls actually land at.
- **App standby, not Doze, is the binding constraint.** Measured on a Pixel 8a:
  `setAndAllowWhileIdle` defeats Doze exactly as documented (`device_idle=--`
  in the alarm dump), while app standby deferred the same alarm by 365 days.
  Two things cause that, and a user can hit both - an app in the `NEVER` bucket
  because it has not been opened yet, and no battery-optimisation exemption. A
  deferred alarm is still listed as pending and looks healthy; only the
  `policyWhenElapsed` line distinguishes it, which is why the harness reads it.
  The exemption request flow is therefore load-bearing, not a nicety - and the
  exemption is a requirement rather than a refinement, so the app states it
  plainly and the non-exempt schedule is deliberately not accommodated.
- **CI:** none yet. Codeberg CI was the plan, and is not available to this
  project; an alternative host has still to be chosen, so the suites are run
  locally in the meantime. Reproducible builds for the F-Droid main repo are a
  post-v1 concern (Sec. 12).

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
2. **Cloudflare free-plan limits: checked 2026-08-04, and one is a real risk.**
   - *Cron Trigger count:* the free plan allows **5 per account**, not per
     Worker. The design needs four (AuroraWatch, SWPC, CelesTrak, MPC), so it
     fits with one spare - but any other Worker on the same account competes for
     the same five. Worth knowing before the account is used for anything else.
   - *CPU time:* **10 ms per cron invocation** on the free plan, the same as for
     an HTTP request. The OVATION refresh is CPU-bound - roughly 900 KB of JSON
     to parse, 65,160 cells to pack, then gzip - and measured off-runtime at
     about 5-15 ms depending on JIT warmth, which straddled the limit.

     **Settled in production, 2026-08-04: the free plan carries it.** Across
     invocations of both schedules on the deployed Worker every outcome was
     `Ok`, with objects published and `status.json` refreshing on cadence. A
     Worker over the CPU limit is terminated with an `exceededCpu` outcome and
     publishes nothing, so completing successfully is the answer. The exact
     millisecond figure needs the dashboard's Worker metrics; the operational
     question is closed either way. If a future change pushes it over, the
     upgrade path is Workers Paid at $5/month.
3. ~~**The CDN does not honour the caching contract.**~~ **Resolved
   2026-08-04 by a Cache Rule on `sky.arek.sh/v1/*`.** Recorded because the
   default behaviour is a trap worth knowing about, and because nothing in the
   Worker's own configuration would have revealed it:
   - `status.json` was never edge-cached (`cf-cache-status: DYNAMIC` on every
     request), because Cloudflare's default cache policy keys off file extension
     and `.json` is not in it. Every device poll reached R2 - precisely the
     fan-out ADR-0002 exists to prevent.
   - `oval.bin` was published with `max-age=120` and served with
     `max-age=14400`, the zone's 4-hour Browser Cache TTL overriding the origin
     header. A device would have held a stale aurora grid for four hours.

   The rule makes `/v1/*` cache-eligible and sets browser and edge TTL to
   respect the origin. Verified live: `status.json` goes MISS then HIT with
   `max-age=60` intact, `oval.bin` reports `max-age=120`, and conditional GETs
   return 304 with a zero-byte body. A cold poll of both objects is about
   2.2 KB.

   The general lesson, worth remembering before adding an object: the caching
   contract in Sec. 2 is only real if the zone is configured to honour it. Check
   the served headers, not the ones that were set.

4. ~~**Ed25519 verification at `minSdk 24`.**~~ **Resolved: a vendored
   verify-only implementation** in `sh.arek.gloaming.crypto.Ed25519Verifier`
   (ADR-0005). The platform primitive only arrives around API 33, and a general
   crypto library costs megabytes for one primitive. Verification handles no
   secret, so the hazard is correctness rather than side channels; it is
   validated against all three RFC 8032 section 7.1 vectors and against a real
   signature from the deployed Worker.
5. **GPL-3.0 is a preference, not forced by SGP4.** Sec. 1 says an MIT licence
   was available only in the aurora-only scope, but Sec. 7 notes predict4java was
   relicensed to MIT in 2026, so full scope could stay permissive via
   predict4java instead of vendoring Look4Sat. The project has chosen GPL-3.0
   from day one (ADR-0004), so this is settled - but the sourcing wording should
   be corrected so it is not later re-litigated as if SGP4 forced GPL.
6. **Name inconsistency.** The sourcing document says "Sky Alerts" throughout;
   the project is Gloaming. Resolved (ADR-0001); the sourcing document should be
   updated or annotated so the names do not drift.
7. **`specialUse` foreground service manifest property.** On `targetSdk` 34+ the
   service needs `android:foregroundServiceType="specialUse"` plus the
   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` `<property>`. Off-Play there is no reviewer,
   but the declaration is still required to run. An implementation note, not an
   error.
8. **KV free-tier write ceiling (1,000/day/namespace).** Keeping the
   `status.json` composition buffer in KV would approach the cap from the 3- and
   5-minute crons alone. Use R2 for the partials and reserve KV for the
   rarely-written breaker flag (Sec. 3 above).
9. **`health.json` and the `internal/` partials are world-readable** under the
   public domain. An R2 custom domain serves a whole bucket and cannot be
   path-restricted without putting a Worker in the read path, which is the one
   thing the topology exists to avoid.

   **Decided 2026-08-22: accepted.** Everything exposed is derived from public
   upstreams - per-source error codes, timestamps, and the same space-weather
   values `status.json` already publishes - so there is nothing here that is
   secret anywhere else. If that stops being true, the fix is a second R2
   bucket with no custom domain bound to it, holding `internal/` and the health
   objects, reached through a second binding. That is provisioning rather than
   code, and nothing in the pipelines assumes one bucket.

10. **The two-hourly Cron Trigger did not fire. Confirmed 2026-08-22, worked
    around.** The satellites pipeline never ran in production. `0 */2 * * *`
    was registered - the API listed it, created 11:14:34Z - and then fired at
    neither 12:00 nor 14:00, while `*/3 * * * *` and `*/5 * * * *` on the same
    Worker fired every time, including at 14:00:21. Established with a
    continuous `wrangler tail` from 12:42 to 15:57 (100 invocations, none of
    them the two-hourly one) and confirmed independently by `health.json`,
    which recorded no CelesTrak outcome at all in that window.

    Not a syntax error: Cloudflare documents `/` as supported in the hours
    field. Not the account trigger limit either - this is the only Worker on
    the account and it has three of the five.

    Worked around by spelling the schedule out,
    `0 0,2,4,6,8,10,12,14,16,18,20,22 * * *`. Whether the step form is at
    fault or something else is, this removes the only part of the expression
    that differs from the two schedules that do work. **The expression must
    stay byte-identical in `wrangler.jsonc` and in `index.ts`**, because the
    handler looks the schedule up before doing anything and throws on a miss -
    and that throw happens before any source is recorded, so a mismatch would
    look exactly like this same silence.

11. **CelesTrak answered a Cloudflare Worker with 522 twice. Unresolved, and
    now known to be a smaller claim than it looked, 2026-08-22.** Two preview
    invocations, at 12:06:33 and 12:09:15 UTC, each got `HTTP 522` after
    roughly 24 seconds. 522 is Cloudflare-generated and means its egress could
    not open a connection to the origin; CelesTrak is not itself behind
    Cloudflare (`Microsoft-IIS/10.0`, no Cloudflare headers).

    Two things keep this from being a conclusion. `celestrak.org` was itself
    unreachable from an ordinary domestic connection for most of that morning,
    from about 10:20 UTC until about 12:08, so the first attempt is explained
    by their outage and the second is a single sample taken a minute after
    recovery. And both were `wrangler dev --remote` preview runs; because of
    issue 10 above, **no production invocation has ever attempted the fetch**,
    so the deployed path is still untested.

    If it does turn out that Cloudflare's egress is blocked, note that **a
    Worker's egress address cannot be chosen** on any plan. The options are:
    ask Kelso, since he is reachable and the policy invites contact; move the
    fetch to a host with a stable dedicated IP and have it write to R2, keeping
    the fan-out where it is - the parser is pure TypeScript and would run
    unchanged under Node; or take the same GP data from Space-Track, which
    identifies the caller by account rather than by IP but restricts
    redistribution in a way CelesTrak's policy notably does not.

The open questions the sourcing document already lists in Sec. 15 - default alert
thresholds, adaptive-polling escalation values, storm-mode exit condition,
OVATION storm-time gzip size, Shetland per-region alerting - are calibration and
product decisions, not architectural blockers, and are carried forward unchanged.

## 10. Decision records

- ADR-0001 - adopt the engineering practices and the Gloaming name.
- ADR-0002 - R2 object store with a cron Worker out of the read path.
- ADR-0003 - all computation on-device; polling only, no push.
- ADR-0004 - GPL-3.0 from day one.
- ADR-0005 - a vendored Ed25519 verifier rather than a crypto library.
- ADR-0006 - vendor astronomy-engine rather than resolve it from JitPack.
