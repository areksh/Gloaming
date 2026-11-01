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
   (write side)                    ^   (read side, CDN serves objects directly)
                                   |         ^
  elements fetcher  --PUT----------+         | HTTPS conditional GET (mostly 304)
   (one host, own IP)                        | outbound only, device-initiated
                                        Android app  --direct-->  Open-Meteo
```

- The **cron Worker** is never in the read path. It fetches upstreams on a
  schedule and writes derived objects to R2.
- The **elements fetcher** is a second writer, and exists only because
  Cloudflare's egress cannot reach CelesTrak (issue 11, ADR-0008). It is a
  single bundled Node file on a host with an address of its own, run by a
  systemd timer every two hours, writing `sats.json` and its own health record
  to the same bucket. It holds no local state. See `fetcher/README.md`.
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

  **This table used to carry a third row, `0 3 * * 1` for MPC comet elements. It was wrong
  and Phase 5 corrected it**, on exactly the ADR-0008 precedent: the MPC is a similarly
  old-school academic host and COBS is a small observatory in Slovenia, and betting a phase
  on Cloudflare's egress reaching either is the bet that already cost a phase once. Comets
  are fetched from the same machine as CelesTrak. See "Not CelesTrak" below, which now
  covers comets too.

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
- **Not CelesTrak, and not comets either.** Both are fetched from outside Cloudflare;
  see ADR-0008 for the first and ADR-0010 for the second. `celestrak` and `comets` are
  still listed among the Worker's sources so that the health records the fetcher writes
  keep appearing in `health.json` - the Worker composes them without needing to know where
  either came from.
- **Comet normalisation** (in the fetcher). The MPC publishes comet elements as fixed-width
  Fortran and COBS publishes observations as JSON; the fetcher reduces both into one small
  `comets.json`. Column positions come from the MPC's own format document rather than from
  counting a sample, for the same reason CelesTrak's columns are read by name: a
  fixed-width field read one place out does not fail, it publishes a plausible orbit for
  the wrong comet.

  Two rules do the work that a weekly human judgement was going to do:

  - **A median over independent observers, in the shortest window that has enough of
    them.** Both halves earn their place. On 2026-08-25 one COBS observer was reporting
    C/2020 F3 (NEOWISE) at magnitude 1.2 - six years after it faded - and C/2014 UN271,
    which is out past Saturn, at 0.5. Seven reports, one person. A median over that
    observer's own numbers still says 1.5.
  - **A measurement decides and the prediction is only the fallback**, and then only for a
    comet near enough to perihelion in time for it to mean anything. Without the second
    clause the file publishes Hale-Bopp forever: it has the brightest absolute magnitude in
    the catalogue and a perihelion in March 1997.
- **OMM normalisation** (in the fetcher, not the Worker). CelesTrak publishes GP
  data as OMM in CSV, never TLE for the 6-digit catalogue numbers issued since
  July 2026 - and the `visual` group acquired its first one, `SOYUZ-MS 29`, by
  2026-08-24. The fetcher parses it and publishes `sats.json` with fields
  mapping one-for-one onto the app's element type, including the epoch already
  converted from ISO-8601 to the packed `YYDDD.dddddd` every SGP4
  implementation takes. Done off the device because the column mapping and the
  epoch conversion are the two things most likely to be wrong, and because
  `minSdk 24` predates `java.time`.
  Columns are read **by name from the header row**, never by position: reading a
  reordered document by index would not fail, it would publish plausible
  elements for the wrong orbit.
- **Circuit breaker (CelesTrak, now in the fetcher).** Any non-200 from CelesTrak
  writes a tripped flag plus the response code and timestamp to R2 at
  `internal/breaker/celestrak.json`; every invocation checks the flag first and
  does not query while it is set. **A human gets two days to reset it** by
  deleting that object; after that one request is let through, a 200 deletes
  the flag, and anything else trips it afresh for another two days (ADR-0015).
  `sats.json` keeps serving its last-good copy meanwhile. Tripping POSTs the
  maintainer ntfy topic, saying when the next attempt will be. This is the
  single most important CelesTrak requirement (Sec. 3): stop on any non-200, do
  not back off, do not retry.

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
  a source fails silently. A recorded error names what was being reached for and
  the reason underneath it: both runtimes describe every network fault with one
  generic string of their own - `Network connection lost.` in the Worker,
  `fetch failed` in Node - which distinguishes neither the host nor the fault,
  so the URL is added and the nested cause read out before it is recorded.

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
| `sats.json` | ~50 KB | 2 h | `public, max-age=1800` | OMM elements: `stations` + `visual`, deduplicated by catalogue number and sorted, plus standard magnitudes where one can be stood behind. **Written by the fetcher, not the Worker** (ADR-0008). Measured 2026-08-24: 51.6 KB for 176 satellites, against the 20 KB Sec. 10 budgeted. |
| `comets.json` | ~6 KB | twice daily | `public, max-age=21600` | MPC elements + H/slope, with the magnitude COBS observers have measured and the attribution CC BY-NC-SA requires. **Written by the fetcher, not the Worker** (ADR-0010). Nothing in it is curated: the cutoff is a threshold, not a weekly judgement (Sec. 8, Sec. 10). Measured 2026-08-26: 5.8 KB for 30 comets. |
| `showers.json` | - | never | n/a | **Not a hosted file at all.** Shipped in the APK, with no remote override: peaks are stored as solar longitude, so the same rows resolve for any year and there is nothing for an override to correct (Sec. 10). |
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

"Re-fetched daily" is enforced in `ManifestRepository` rather than assumed of
its callers. All three pollers ask it for the manifest on every wake, so
without the gate an elevated night at five-minute polls would spend hundreds of
requests, and two signature verifications apiece, on a document that changes a
few times a year. A verified copy younger than a day is simply the answer; a
copy stamped in the future is treated as stale, so a clock correction cannot
freeze one in place.

## 5. Android app

Recreated as a Compose project matching Sec. 7: `minSdk 24`,
`compileSdk`/`targetSdk 37`, JDK 21, Kotlin 2.4.x, Compose BOM 2026.06.x, AGP 9.
No Play Services anywhere; `LocationManager` only (never the fused provider),
with manual location entry as a first-class input and the app fully functional
with location permission permanently denied (Sec. 7, Sec. 11). Detecting the
position is offered as of 2026-08-25 and is coarse - see Sec. 7 of the sourcing
document for its limits and why each one holds. As of 2026-10-31 the user may
also choose to have it refreshed in the background on a cadence of their own
(ADR-0016); otherwise it is read when the app is opened or when asked.

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

- **data:** one OkHttp boundary (`HttpFetcher`, conditional GET and a bounded
  read) behind kotlinx-serialization repositories - `ManifestRepository`,
  `SkyRepository`, `SatelliteRepository`, `CometRepository` - each of which
  parses before it caches, so an unreadable body never replaces a good one.
  A `WeatherSource` interface is defined from the start (Open-Meteo the only
  shipped implementation) to leave room for BYO keys without a later refactor
  (Sec. 15).

  **Every fetch is recorded, by a decorator rather than by the repositories.**
  `net.ReportingFetcher` wraps that one boundary in `Graph` and writes each
  attempt into `net.Transfers` - in flight, fresh with its size, unchanged, or
  failed with the cause chain read out. It changes no answer: the ETag goes
  through, and a failure is recorded and rethrown so the repositories' falling
  back to cache stays live. It is in memory and lasts one process.

  How *old* the held copy is is a different question with a different answer,
  and both are needed: the failure that prompted all this was a fortnight of
  *successful* conditional GETs against an object nobody upstream was
  republishing any more, so only the age had anything to say. Every cache
  answers it - `SkyCache.statusWrittenAt`/`gridWrittenAt`,
  `ManifestStore.writtenAt`, `CachedCloud.fetchedAt`, and the two
  `cachedAgeMillis` that were already there - reaching the screen as
  `ui.downloadRows`, which merges the two sources into one row per document.
  The two added to `SkyCache` and `WeatherSource` are defaulted to null, so a
  fake in a poller test need not answer something no poller reads.

  Shown as text under Check now in Tonight's checks sheet rather than as a
  card, and absent there unless it has something to say: while a check runs or
  after one has been run. When something is wrong it speaks asked or not:
  `ui.describeTrouble` names the documents to blame on the status line at the
  head of Tonight, which is on the screen without opening anything. That
  clause is the point rather than a hedge; a report that only speaks when
  pressed would have sat silent through the whole fortnight. Only the elements
  (three days) and the comets (a month) are ever marked stale, because those
  are the two whose age silently corrupts an answer.

  **Neither Room nor DataStore is used, and neither is a dependency.** Earlier
  drafts of this document named both; what is built is plain files for the
  caches and plain `SharedPreferences` for settings and alert state. The reason
  is the same one that keeps the graph hand-wired: everything here is read on a
  background wake that wants to be cheap to start, often before the device has
  been unlocked. The cached documents are opaque blobs served from a public URL
  and gain nothing from a schema, and the two preference stores have to be named
  separately at the call site because which one is authoritative - the
  credential-encrypted store or the device-encrypted mirror a locked wake can
  reach - is the whole point of the split. `sh.arek.gloaming.data.BootAware`
  records that split and what may be written to which; it is not repeated here.

  Two small additions sit in it. `OutlookStore` holds Tonight's opening
  sentence for the widget, which redraws where the whole of tonight cannot be
  worked out. It is keyed by the grid cell, never the precise position, so
  `BootAware.outlookStore` chooses its store by the same rule as the settings
  mirror. It is stamped with the check it was decided against:
  `WidgetContent.update` decides what the screen leaves there, never a live
  aurora reading, and `WidgetContent.of` quotes it only until a newer check
  lands. The aurora alert does not read it; its sentence comes from the poll's
  own reading. `LocationCadence` is the stored choice of how often a detected
  position is refreshed (ADR-0016).
- **satellites:** `Elements` (deserialising `sats.json`), `PassFinder` (the
  horizon-crossing search over the vendored propagator, which offers no search
  of its own), and `PassDescription` (qualitative buckets - never a magnitude).
  Pure and clock-free; the propagator itself is gated on Vallado's verification
  vectors.
- **domain:** the packed-grid lookup; one internal likelihood model (OVATION
  cell, or AuroraWatch inside the UK bounding box) so the UI never branches on
  which source is in play; the decision engine (hysteresis `T_on`/`T_off`,
  minimum re-alert interval, quiet hours, per-user threshold, the non-optional
  darkness gate and the cloud gate); the astronomy calculators, among them
  `astronomy.SkyPositions`, which places a body or a fixed point at an instant
  for Find; `Outlook`, which decides Tonight's opening sentence from the same
  documents by putting every candidate through the gates in order and keeping
  the picks and the exclusions; and the adaptive-polling scheduler policy.
- **scheduling:** `AlarmManager.setWindow()` or chained `OneTimeWorkRequest`s
  (WorkManager's 15-minute floor is too coarse in both directions, Sec. 9.3);
  every scheduled wake is jittered - spread either side of the requested time
  rather than added to it, so de-aliasing installs does not double as a delay
  (Sec. 2); the battery-optimisation-exemption
  request flow; and storm-mode as a `specialUse` foreground service that runs
  only for the duration of an event.

  **The background location refresh has an alarm of its own** (ADR-0016).
  `work.LocationAlarm` arms an inexact `setAndAllowWhileIdle` at
  `domain.LocationRefresh.nextAt` - one interval after the last refresh, never
  sooner than a quarter of an hour, and that quarter of an hour when there has
  never been one - and cancels it for any cadence the background does not
  answer. It is armed when a save from the screen changes the location mode or
  the cadence, and by `BootReceiver` once the phone is unlocked after a boot or
  an update. Detecting the position on the screen stamps `locationUpdatedAt`
  too. `work.LocationReceiver` is deliberately not Direct Boot aware: it
  returns at once while locked, because its settings are credential-encrypted
  and the reboot that locked the phone cleared the alarm anyway. Unlocked, it
  asks `LocationRefresh.skip` whether to run, takes one fresh coarse fix inside
  a thirty-second budget, saves it with `locationUpdatedAt`, and re-arms. A
  withdrawn background grant puts the cadence back to opening the app and sets
  `backgroundLocationRevoked` for the screen to explain. The journal says
  whether a refresh happened, never where.

  The cadence is picked on the settings screen's location card, which reports
  the choice and leaves the asking to `MainActivity`: an interval without the
  background grant asks Android for it first - one request for both grants on
  API 29, coarse and then background on its own from API 30 (which Android
  answers with its settings page), nothing below 29 - as
  `AndroidLocationSource.backgroundRequest` works out. `domain.CadenceRequest`
  decides each step: a pick made while a request is still with Android is
  dropped, since Android answers a second one at once with nothing granted; a
  refused background grant stores "each time you open Gloaming" and says so,
  and a refused coarse grant goes back to typing it in, as the switch does.
  The interval being asked for survives the activity being rebuilt meanwhile.
  The custom number of hours is sent on Done or when the field loses focus,
  never per keystroke. Picking any cadence clears `backgroundLocationRevoked`.
  Opening the app detects the position when the cadence says it is due, read
  from the store rather than the screen's copy, and leaves the screen's note
  alone, so the answer from Android's settings page is not wiped on return.
  Every save from the screen goes through `LocationRefresh.keepNewerFix`, so a
  screen brought forward before a background refresh landed cannot write the
  older position back over the newer one.
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
  and a home-screen widget (Phase 2). Also the guide - `Guide.kt` is pure data
  like `Credits.kt`, read both by its own page under Settings and by the sheet
  each row on Tonight's timeline opens (`helpTopic` picks the entries), so a
  term is worded once and shown in two places.

  **First run is a setup flow, not a screen of its own.** `GloamingRoot`
  (`Setup.kt`) shows `SetupFlow` until `UserSettings.setupDone`, and
  `GloamingNav` after. The flow is three steps (`SetupStep`: location,
  notifications - left out below Android 13 - and background checks), each
  the Settings page for the same question rather than a copy of it, each
  skippable; the step is `rememberSaveable` and system Back on a later step
  goes to the one before. `setupDone` is stored with the other settings, and a
  store with no record of it reads as "has a location", so an install from
  before setup existed is not asked again. The device-encrypted mirror carries
  it along and nothing reads it there.

  **Colour, type and callbacks are each in one place.** `ui.theme.Palette`
  holds every colour by role (the sky ramp, the cloud hatch, the dimmed row),
  read from `LocalPalette` with the Material scheme derived from the same
  values; there is a dark, a light and a night-vision palette, and no
  wallpaper-derived colour. `UiActions` bundles every callback the screens can
  raise, each defaulting to a no-op, and `MainActivity` is the one place that
  builds the real one. `TimeOfDay.kt` parses and prints the typed `HH:MM` and
  the device-zone clock and date strings the screens share.

  **Navigation is a bottom bar, and each section scrolls itself.** `GloamingNav`
  is a `Scaffold` with a `NavigationBar` over the `Section`s (Tonight, Find,
  Ahead, Settings); the guide is not one, and is a Settings page.
  Settings is an index (`SettingsIndex.kt`): `SettingsGroup`s, seven questions
  in order, each listing its `SettingsPage`s as rows that show the page's
  current value (`summary`, with `summaryAlarming` marking the ones that report
  something broken, drawn in the alarm colour and in bold so night vision still
  shows them). A page's body is `SettingsPageContent` in `SettingsScreen.kt`.
  The page back stack is one nullable page held in `GloamingNav`: system Back
  returns to the index, and choosing a section on the bar, Settings included,
  lands on its index. Both are scrolling columns through `ScrollingSection`,
  which holds the padding and spacing the sections share. Tonight brings its
  own column, `TonightScreen`, with the same figures: a `PullToRefreshBox`
  whose pull runs a check, over the scroll, and the Watch tonight button pinned
  beneath it rather than scrolled (it opens `WatchSheet`, the choice of how
  long). Its head is `TonightHeader` - a banner while
  background checks or notifications are not allowed, then the status line,
  which stays under it and opens the checks sheet (`ChecksSheet`: Check now,
  the last error, the downloads report, battery saver); then the date and the
  outlook. The outlook is decided in `MainActivity` in the same off-main-thread
  pass as the tonight list, from the same held documents and the held hourly
  forecast, so the sentence and the night beneath it cannot disagree. The
  elements' age it is given is how long ago `sats.json` says they were
  published, not how long the phone has held them, since a 304 keeps the
  latter young for ever. The same pass lays the night out as a `Timeline`
  (`NightTimeline.of`: rows, cloud spans, lanes and the sun's altitude every
  quarter hour, matching rows to the outlook's picks and exclusions so a row's
  weight is the outlook's verdict). Under the header, `NightTimelineView` draws
  it at 56 dp an hour: one `Canvas` for the sky column, hatching, lanes and
  the now line, heard by a screen reader as `NightTimeline.summary`, with the
  rows as real tappable nodes over it placed by `NightTimeline.layout`, so
  TalkBack reads them in time order. With no sunset or sunrise to bound it -
  the polar night, the midnight sun - there is no timeline, and the darkness
  thresholds are said in words in its place. A row opens `RowSheet` - the detail the
  old cards carried, the caveat, the guide's notes, Share and Export for a
  dated event. `TonightLists` works out the two lists beneath: "Also up
  tonight" (whatever is up with no row and was not set aside) and "Not worth
  it tonight" (the outlook's exclusions in `OutlookWords.excluded`, then the
  satellites when there are none to list, saying whether the sky is empty or
  the elements are missing). Ahead (`AheadScreen`) is a `LazyColumn` owning its own
  scrolling, because a year ahead is around a hundred entries and composing
  them all cost a frame of 1300ms on opening and every frame of a scroll
  afterwards - and because a lazy list measured inside a scrolling column is
  given unbounded height, which defeats the laziness and throws. The scroll
  positions are remembered per section rather than shared. Each month on
  Ahead opens with a `MonthStrip`: one `NightCell` per night, shaded by
  `Twilight.moonInterference`, a dot on the days that have an event, and the
  longest run below `MonthStrips.DARK_BELOW` named in words and read out whole
  by TalkBack, so the shading never carries the meaning alone. The strips are
  worked out in the same background pass as the calendar and held in
  `MainUiState.months`. An event row opens the shared `RowSheet` (Share and
  Export live there); the kinds sit behind one "Showing N of 14 kinds" button.

  **Find points the phone at a target, from pure parts behind one sensor
  seam.** `FindScreen` offers `domain.FindTargets.available` as chips, places
  the chosen one with `FindTargets.fix` once a second (the rise search behind
  it is memoised), and turns each compass reading into a direction with
  `Pointing.direction` - the phone's top edge, corrected by
  `data.magneticDeclination` (the platform's `GeomagneticField`, so nothing is
  fetched) and the user's stored `compassOffsetDegrees` - and into guidance
  with `Guide.of` and `GuideWords`. The view is one `Canvas` sixty degrees
  across, heard by TalkBack as the instruction and where; the instruction line
  is a polite live region. The words are pure functions in `FindWords.kt`
  (`whereLine`, `findInstruction`, `chipLabel`, `lineUpRefusal`, and
  `findTarget`, which maps a timeline row to what Find can point at), so they
  are tested on the JVM. Readings come through `data.CompassSource`
  (`CompassSensor.kt`): `AndroidCompassSource` registers the rotation vector
  for attitude and the magnetometer for its calibration status when its flow
  is collected and unregisters in `awaitClose`, and the screen collects it
  with `collectAsStateWithLifecycle` at `RESUMED`, so the sensors are held only
  while Find (or the Compass settings page) is composed and the app is in
  front. `GloamingNav` takes the source as a parameter, defaulting to
  `NoCompass`, which is what lets every UI test run without a real sensor; a
  phone with no rotation vector is told so instead of shown a pointer. Find
  keeps the screen on through the view's `keepScreenOn` (restoring the
  previous value), and buzzes on coming within `Guide.ALIGNED_DEGREES`,
  re-arming only past `AlignBuzz.REARM_DEGREES` so a wavering hand does not
  buzz on every crossing. With `nightVisionWhenFinding` on it wraps itself
  alone in the night-vision theme and dims the window through `DimScreen`
  (`DimScreen.kt`, shared with the app-wide night vision in `MainActivity`),
  which restores the brightness it found when Find is left.
  `CompassCheck.shouldOffer` opens `CompassSheet` on a low or unreliable
  reading at most once a week, stamping `compassOfferedAt`; the sheet shows the
  accuracy live, Done stamps `compassCheckedAt`, and Line up, offered while the
  moon is up, stores `CompassCheck.offsetFromMoon` or says why it refused.
  Find it on a row's sheet switches to Find aimed at that row; `GloamingNav`
  holds the target as its encoded string. Find it on a notification
  (`notify/FindIt.kt`) sends the same string as an extra to `MainActivity`,
  which is `singleTop` so a second tap while the app is open reaches
  `onNewIntent`; it hands the string to `GloamingNav` and clears it once shown,
  ignores one it cannot decode, and drops it while setup is unfinished or when
  the launch came from Recents, which on Android 11 and below hands back the
  intent that created the task. Passes and the aurora always carry the action;
  an event does where `findTarget` (shared with the row mapping) gives it a
  target. `findTargetFrom`, called from `MainActivity`, is the one place a
  handed-in target is read. The satellite elements Tonight was
  worked out from ride along in `MainUiState.elements`, so a pass is followed
  from the same copy without reading the cache on the main thread. A pass is
  found in tonight's list where it is there, and otherwise in its own
  satellite's elements over `FindTargets.PASS_MATCH_MILLIS` either side of its
  rise, memoised: Tonight's list is rebuilt from now onwards and `PassFinder`
  leaves out a pass already under way, so once a pass has risen only the
  elements still hold it. Two passes of one satellite rising within that
  tolerance are the same target (`FindTargets.same`), which is how a
  notification's rise, from the poller's own search grid, selects tonight's
  chip rather than adding a second. Until tonight has been worked out, a pass
  that cannot be placed reads "Working out tonight..." rather than gone.

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
- **The redesigned screens are tested at the pure seam first.** The words and
  the decisions are functions with JVM tests: `OutlookTest` (the picks and
  exclusions), `OutlookWordsTest`, `NightTimelineTest` (rows, lanes and
  layout), `TonightListsTest`, `AheadRowsTest`, `MonthStripTest`,
  `SettingsSummaryTest`, `SetupStepsTest`, `RowTopicTest`, `PaletteTest`,
  `OutlookStoreTest`, `SkyPositionsTest`, and for Find `FindTargetsTest`,
  `PointingTest`, `CompassCheckTest` and `FindWordsTest`. The background
  position refresh has `LocationCadenceTest`, `LocationRefreshTest`,
  `CadenceRequestTest`, `CadenceWordingTest` and `BackgroundRequestTest`. What
  has no JVM seam is on the phone: `GloamingNavTest`, `AheadScreenTest`,
  `FindScreenTest` (against `FakeCompassSource`, so no real sensor is needed),
  `SettingsPagesTest`, `SetupTest` and `CompassSensorTest`.
- **Worker:** Vitest against workerd for the fetchers, `composeStatus()`, the
  packer and the circuit breaker.
- **A catalogue of nights, shared by both suites.** `app/src/scenarios/` is a
  third source tree, added to `test` and `androidTest` alike because Gradle
  gives them nothing in common. It holds no assertions and no framework: each
  entry is a place, an instant, and the documents a device would already have
  cached, and the shipped calendar and tonight code produce the rest. That is
  what makes it worth having rather than a set of canned screens - a stored
  `TonightList` would go on agreeing with itself long after the engine beneath
  it had rotted. Each night also declares what it should demonstrate, which the
  JVM suite checks, so a screen test cannot pass against a scenario that has
  quietly gone empty. Two of the engine defects found in this way - a shower
  missing on the night it peaks, a conjunction invisible for the six hours
  before it - were reachable no other way.
- **The shipped build is shrunk, and the suite runs against that too.** R8 is on
  for release, which by itself would leave the APK that goes out unlike every
  build the suite has ever run - the worst possible place for the one difference
  to be. A third build type closes it: `minified` is the release build under the
  debug key, and `./go device-test --minified` runs the whole instrumented suite
  against R8's output. It is a third build type rather than `release` itself
  because Compose's test rule needs an activity only its own manifest declares,
  and no released build may carry that. R8 keeps `sh.arek.gloaming.**` and
  shrinks everything around it; `app/proguard-rules.pro` records why, and the
  short of it is that the suite is white-box and there is no crash reporting to
  fall back on. ADR-0013.
- **The instrumented suite runs against real device state, and puts it back.**
  The preference stores and the poll alarm both, because neither has a seam
  worth inventing one for. What matters is the restore: a suite that finishes
  with the settings cleared or the alarm cancelled takes the phone off watch for
  the night and says nothing about it, with the app installed, exempt and not
  stopped. `RealSchedule.restore` re-arms exactly what `BootReceiver` re-arms
  after an update, so a phone that has just run the suite is in the state a
  phone that has just taken an update is in.
- **The load-bearing milestone** is overnight delivery on a real phone
  (Sec. 14 step 6). Everything else is decoration on top of it. `tools/` holds
  two harnesses that answer it in minutes rather than a night:
  `bfu-check.sh` reboots a device, holds it at the lock screen and reports on
  each link in the chain separately; `doze-check.sh` forces deep idle and
  measures how far behind its own request the app's wakes actually land. That
  second one needs the app to be waking at all, which `PollingPolicy` decides
  from the sky rather than from the settings, so it borrows a dark clear
  position out of `tools/observers.txt` and restores the phone's own afterwards.
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
- **CI: none, deliberately (ADR-0014).** The gate is two things on the machine
  that holds the signing key and has the phone plugged in. `tools/hooks/pre-push`
  runs `./go check` before every push and refuses the push if it fails; it lives
  in the tree with `core.hooksPath` pointed at it by `./go setup`, so it cannot
  diverge from the one in the repository. `./go release <version>` runs
  everything else - the instrumented suite, that suite against R8's output, then
  both harnesses - before it bumps, signs, tags and publishes. A hosted runner
  could only have run `./go check`, since everything that has ever caught a
  defect in the alerting path needs a real device, and letting one publish would
  mean putting the release key in a third party's secret store. `./go push`
  publishes the tip of `main` as a rolling development release in between.
  Reproducible builds for the F-Droid main repo are a post-v1 concern (Sec. 12)
  and are the answer to the one thing this arrangement does not provide: an
  attestation from outside the project that an APK matches its commit.

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

     ~~**Settled in production, 2026-08-04: the free plan carries it.**~~
     Across invocations of both schedules on the deployed Worker every outcome
     was `Ok`, with objects published and `status.json` refreshing on cadence.
     A Worker over the CPU limit is terminated with an `exceededCpu` outcome and
     publishes nothing, so completing successfully was read as the answer.

     **Reopened 2026-08-29: it does not carry it, and the reading was wrong.**
     The millisecond figures the note above wanted are now in hand, and they
     settle it the other way:

     | Invocation | CPU per run | Outcome |
     |---|---|---|
     | `*/3` AuroraWatch | 7-9 ms | never terminated |
     | `*/5` SWPC | 32-49 ms | terminated in episodes, at 11-15 ms |

     The SWPC refresh has been three to five times over the limit all along.
     The limit is not enforced on every invocation, which is why months of `Ok`
     outcomes meant nothing: the Worker is running on tolerance, and tolerance
     is not a guarantee. When enforcement does bite it bites for hours at a
     time - 24 Aug 00:00-08:00, 25 Aug 04:00-07:00, 28 Aug 03:00-10:00, 29 Aug
     06:20-12:05 UTC, always the SWPC schedule, never AuroraWatch in the same
     minute, and always ending on its own.

     A terminated invocation records nothing at all, so `health.json` goes on
     saying `ok` while the grid ages. Only the staleness check in Sec. 3 catches
     it, an hour later, which is what it is for.

     Optimisation is not a way out. Replacing `JSON.parse` with a byte scan
     packing straight into the grid measures 11.2 ms -> 5.3 ms off-runtime, so
     it would take the invocation from roughly 42 ms to 30 ms: a real saving
     against no useful threshold, since the budget is 10 ms and the manifest
     step and startup already account for most of an AuroraWatch run. The
     honest options were Workers Paid at $5/month, or moving the SWPC fetch to
     the host that already runs CelesTrak (ADR-0008) and accepting that the most
     time-critical source then depends on a machine at home.

     **Closed 2026-08-29 by taking Workers Paid.** The CPU ceiling is 30 s per
     invocation, which the SWPC refresh clears by three orders of magnitude
     rather than by a hair - it has been observed at 60 ms since, and the point
     of paying is that the figure no longer has to be watched. SWPC stays on
     Cloudflare with everything else, so the only source running off a machine
     at home is still the one that has to (ADR-0008).

     What that buys, beyond the outage: the free plan's 100,000 requests/day
     and 5 crons per *account* were both live constraints on the design. Neither
     binds now. Note the Worker sets no `limits.cpu_ms`, so it takes the 30 s
     default; a lower explicit value would reintroduce exactly this failure,
     invisibly.
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
9. ~~**`health.json` and the `internal/` partials are world-readable**~~ under
   the public domain. An R2 custom domain serves a whole bucket, ~~and cannot be
   path-restricted without putting a Worker in the read path, which is the one
   thing the topology exists to avoid.~~

   ~~**Decided 2026-08-22: accepted.**~~ Everything exposed is derived from
   public upstreams - per-source error codes, timestamps, and the same
   space-weather values `status.json` already publishes - so there is nothing
   here that is secret anywhere else.

   **Resolved 2026-08-29 by a WAF custom rule**, blocking any request to
   `sky.arek.sh` whose path does not start with `/v1/`. The premise above was
   wrong: a custom domain is an ordinary hostname on the zone, so the whole
   ruleset engine applies to it and a path restriction costs one rule and no
   Worker. Accepting the exposure was the right call on the facts as understood,
   but the facts were not checked.

   Written as an allowlist rather than a block on `/internal/*`, so a new
   internal prefix is covered without anyone having to remember. Verified: every
   `v1/` object answers 200, every `internal/` path and the bucket root answer
   403, and the Worker is unaffected because it reaches R2 through its binding
   and never through the edge. Recorded here for the same reason as the Cache
   Rule in issue 3 - nothing in the Worker's own configuration reveals it, and a
   rebuild of the zone that omits it silently reopens the bucket.

   The second-bucket option stands unused if the exposure ever needs to be
   structural rather than a rule: nothing in the pipelines assumes one bucket.

10. **The two-hourly Cron Trigger did not fire with a step in the hours field.
    Confirmed and fixed, 2026-08-22.** The satellites pipeline never ran in production. `0 */2 * * *`
    was registered - the API listed it, created 11:14:34Z - and then fired at
    neither 12:00 nor 14:00, while `*/3 * * * *` and `*/5 * * * *` on the same
    Worker fired every time, including at 14:00:21. Established with a
    continuous `wrangler tail` from 12:42 to 15:57 (100 invocations, none of
    them the two-hourly one) and confirmed independently by `health.json`,
    which recorded no CelesTrak outcome at all in that window.

    Not a syntax error: Cloudflare documents `/` as supported in the hours
    field. Not the account trigger limit either - this is the only Worker on
    the account and it has three of the five.

    Fixed by spelling the schedule out,
    `0 0,2,4,6,8,10,12,14,16,18,20,22 * * *`, which removed the only part of
    the expression that differed from the two schedules that did work. **It
    fired at the next scheduled time**, 18:00:45Z, first time of asking. So the
    step form in the hours field is the cause, whatever the mechanism, and it
    should be avoided here regardless of what the syntax table says. **The expression must
    stay byte-identical in `wrangler.jsonc` and in `index.ts`**, because the
    handler looks the schedule up before doing anything and throws on a miss -
    and that throw happens before any source is recorded, so a mismatch would
    look exactly like this same silence.

11. **Cloudflare's network cannot reach CelesTrak. Unresolved and blocking,
    2026-08-22.** Three fetch attempts from Cloudflare, three `HTTP 522`, each
    after roughly 24 seconds: 12:06:33 and 12:09:15 from `wrangler dev
    --remote` previews, and 18:01:09 from the deployed Worker on its own cron.
    522 is Cloudflare-generated and means its egress could not open a
    connection to the origin; CelesTrak is not itself behind Cloudflare
    (`Microsoft-IIS/10.0`, no Cloudflare headers).

    The controlled comparison is the 18:00 run. The same URL, with the same
    User-Agent, was requested from an ordinary domestic connection **in the
    same minute**: `HTTP 200` in 1.96 seconds. Worker: 522 after 24 seconds.
    That pairing is what the earlier attempts lacked - `celestrak.org` was
    genuinely unreachable from everywhere for much of that morning, which
    confounded them - and it removes the two obvious alternative explanations
    at once. Production and preview fail identically, so it is not an artefact
    of preview egress; and the origin was demonstrably healthy at that instant,
    so it is not an outage.

    A 24-second timeout rather than a refusal means packets are being dropped
    rather than rejected. Whether that is CelesTrak firewalling Cloudflare
    Workers' shared egress ranges - their policy is explicit that shared-egress
    users get blocked by each other's behaviour, and Workers share egress
    across every customer on the platform - or a routing problem between
    Cloudflare and their host, cannot be told apart from this side. The
    practical consequence is the same either way.

    **No further attempts were made.** Three is enough to establish it, and
    repeatedly probing a source that may already have blocked the range is
    precisely the behaviour the usage policy warns about.

    **Resolved by moving the fetch off Cloudflare** - see ADR-0008 and
    `fetcher/README.md`. Asking Kelso is still worth doing, and is the only
    route to knowing whether the block is deliberate, but the app no longer
    waits on the answer.

    **A Worker's egress address cannot be chosen** on any plan, so there is
    nothing to configure and the fetch has to move. The options are:
    ask Kelso, since he is reachable and the policy invites contact; move the
    fetch to a host with a stable dedicated IP and have it write to R2, keeping
    the fan-out where it is - the parser is pure TypeScript and would run
    unchanged under Node; or take the same GP data from Space-Track, which
    identifies the caller by account rather than by IP but restricts
    redistribution in a way CelesTrak's policy notably does not.

12. **Pass alerts had no rate limit, and the volume is much higher than the
    design assumed. Found and fixed 2026-08-24.** Running the first live
    `sats.json` through the app for Edinburgh gives **40 visible passes above
    30 degrees in a six-hour window** - all classic bright objects, mostly
    rocket bodies. The planner announces the next visible pass, sleeps until it
    is over, then considers the one behind it, so on a clear night that is
    potentially dozens of notifications.

    Sec. 9.2 already has the answer and it was simply not applied to passes: a
    **minimum re-alert interval, per channel, default six hours**. Quiet hours,
    the cloud gate and one-alert-per-pass are all implemented; this one gate is
    not. With it, `bright-satellites` would settle to one or two a night and
    `iss-passes` would keep its own budget, which is the point of their being
    separate channels.

    **Fixed, and made the user's to set.** All three intervals - aurora, the
    station, and everything else - are settings with six hours as their
    starting point, not constants. The aurora one had the same problem in a
    quieter form: it was a constructor default nobody could reach. How often an
    app may interrupt someone is their call, the same as quiet hours.

    One thing fell out of building it that is worth keeping. A rate limit
    applied to "the next pass" announces whichever came first and then falls
    silent through the far better one twenty minutes later, which is a worse
    app than one that said nothing. So the interval is also the **selection
    window**: of the passes a channel could announce before it must go quiet,
    the planner takes the best. Read together, the setting says "tell me about
    the best pass, at most this often" - and a longer interval buys better
    alerts rather than merely fewer. Verified on the phone against live
    elements: offered a 12-degree pass and an 86-degree one, it chose the
    86-degree one.

13. **The latitude ceiling had nowhere to live. Built 2026-08-24.** Sec. 14's
    item 17 asks that high-latitude users "get no ISS and are told why rather
    than shown an empty list". The prediction was always right - the vendored
    `willBeSeen` discards any orbit that cannot reach the observer, so a Tromso
    user simply gets no station passes - but the app has **no pass list at
    all**. It speaks only through notifications, so there was no empty list for
    the explanation to sit above, and without one an unreachable station and a
    broken app look identical from outside.

    It went under the station's own interval row in the settings card, shown
    only where the answer is not "covered". The sentence states the orbit's
    limit *and* the user's latitude, so it is checkable rather than an
    assertion. The interval control stays visible and the channel stays on: the
    point is to explain the silence, not to take a setting away.

14. **Live pass mode needs a foreground service Android may refuse to start.
    Built 2026-08-24; one question only a device settles.** Sec. 9.4's argument
    for the design holds and is the reason it is shaped this way: a service
    started ten minutes before a pass **owns its own clock**, so the moment that
    matters - the satellite clearing the horizon - never depends on the system
    waking anything precisely, and the exact-alarm permission is never needed.

    What Sec. 9.4 does not mention is that from Android 12 a foreground service
    generally cannot be *started* from the background, and an **inexact alarm
    does not grant the exemption** - only an exact one does. Whether this app's
    battery-optimisation exemption covers the alarm-driven start is not settled
    by reading the documentation. Two mitigations, both built: the "Follow it"
    notification action starts the service directly where the lead has already
    run out, and a notification the user has just pressed *is* one of the
    exemptions; and a refusal is caught and logged rather than crashing, because
    the alternative to a tracker is the notification the user already has.

    The service is otherwise storm mode applied twice, so no ADR: same
    `specialUse` type, same ongoing silent notification, same wake lock under a
    hard cap. Two deliberate differences. It **touches no network at all**,
    which is what lets it run in a field with no signal - the elements are on
    the device before it starts. And it is never automatic across the bright
    catalogue: `PassPlanner.shouldFollow` takes the channel, so the wrong answer
    is unrepresentable rather than merely defaulted off.

15. **A train's channel is not a property of its catalogue number. Found while
    building item 20, 2026-08-24.** `PassChannel.of(catalogue)` worked as long as
    what a satellite *is* could be read off its number. It cannot: a Starlink
    still bunched into a train is a different thing to announce from the same
    object a fortnight later, and only the document it arrived in knows which it
    is. So a pass now **carries** its channel, set where the pass is built, with
    the finder taking it as a parameter defaulting to the old derivation. A pure
    refactor, landed on its own before the feature that needed it.

    Two consequences worth recording. The channel moved from the planner into
    the satellites package, being a property of a pass rather than of planning;
    and the notifier's separate channel argument went away, since passing
    alongside a pass something the pass already knows is one more place for the
    two to disagree.

    The size problem is the other half, and it is in the sourcing document's
    Sec. 10 rather than here because it is a data-contract decision: only the
    two ends of each train are published, because the group is thousands of
    objects and the document every device pulls two-hourly is fifty kilobytes.
    The device-side consequence is that the two ends have to be dropped from the
    ordinary sweep - their elements are published so the train can be worked out,
    but they are also two Starlinks, and without that the same launch is
    announced twice: once as a train, and again minutes later as an
    unremarkable bright satellite.

16. **The circuit breaker, tripped deliberately and end to end. 2026-08-24.**
    It had never been fired on purpose - only by CelesTrak's own 522s, which is
    not the same thing, because a real outage does not let you check what
    happens on the *second* attempt. Pointed at a 404 that is deliberately not
    CelesTrak (`sky.arek.sh/v1/deliberately-not-there`) through a `/run` systemd
    drop-in, which cannot survive a reboot and so cannot quietly disable the
    fetch if it is forgotten. A deliberate bad request *to* CelesTrak is
    precisely the behaviour their policy warns about, and the breaker cares
    about the response code rather than the host, so there was no need for one.

    All five behaviours confirmed. The run exits 1 and logs the code. The
    breaker object records `status: 404`, the URL and `tripped_at`. `sats.json`
    goes on serving its last-good copy through the CDN with its original
    `last-modified` and byte count. `health.json` shows `celestrak` failing
    while the other three stay `ok`.

    The one that matters most is the second attempt. Started again three
    minutes later, the service **refused without querying anything**: it quoted
    the *original* `tripped_at` rather than a new one, and `tripped_at` in R2
    was unchanged. `consecutive_failures` went 1 to 2 and `last_error_at`
    moved, so the refusal is recorded without a request going out, and no
    second push was sent. That is what the policy actually asks for - stop, and
    tell a human - rather than backoff dressed up as compliance.

    Two pushes arrive on the first trip and this is deliberate rather than a
    duplicate: the breaker sends one saying what the response was, and the
    health record sends another when the source crosses into failing. They say
    different things. Nothing further is sent while it stays tripped.

    One incidental confirmation from the journal: a manual run at 16:24:19Z was
    followed by an unattended one at 18:27:33Z, two hours and three minutes
    later. `OnUnitActiveSec` spaces from the last run rather than from the
    clock, which is the property that keeps the two-hourly cap, and it had been
    reasoned about twice and corrected twice without ever being watched.

17. **Train detection found nothing, and would have found nothing ever. Fixed
    2026-08-24, on first contact with the live document.** The unit tests were
    green and the logic read correctly. Run against the real supplemental
    Starlink list - 11,051 objects - it returned zero trains, and every launch
    in the file measured 280 to 330 degrees of mean-anomaly spread, which is
    the whole orbit.

    **Every satellite in a SupGP document carries its own epoch**, and within
    one launch they span about a hundred minutes - roughly a full orbit at
    these altitudes. The published mean anomalies are therefore not comparable
    with one another at all: two satellites flying side by side are listed half
    an orbit apart purely because their element sets were produced an hour
    apart. The bunching test has to propagate each member to a common instant
    first, which is now what it does.

    The lesson is about the fixtures rather than the arithmetic. Every fixture
    shared a single epoch - the natural thing to write - and that is precisely
    the case where the wrong code gives the right answer. A test is now built by
    staging epochs across an orbit and winding the published anomalies back to
    match, and the only thing that would have caught it sooner is doing what was
    done here: running it against the real document before trusting it.

    Propagated properly, the live document resolves cleanly. Launch 2026-193,
    27 satellites within 21 degrees: a train. The launch before it, 167 degrees.
    Everything older, 280 to 330. That is the feature expiring on its own as a
    launch spreads out, which was the design intent and is now observed rather
    than asserted.

18. **The newest Starlinks have no catalogue number yet. Found the same day.**
    The 80 most recently launched objects in the supplemental document carry
    operator-assigned placeholders - 799501484 and up - rather than NORAD
    numbers, because 18 SDS has not catalogued them. They work perfectly well as
    keys within the document, which is all the app uses them for.

    The risk is the ceiling. The app's `catalogue` is a 32-bit signed integer,
    and kotlinx.serialization fails **the whole document** on a value past it -
    so one oversized placeholder would cost every install the station and the
    bright catalogue, not merely the train. There is headroom today and the
    numbering scheme belongs to the operator, so nothing about it is promised.
    The fetcher now drops anything unreadable, where it costs one object.

    This is the same shape of failure as the one above and worth naming as a
    pattern: the data is supplied by a third party, the format is documented but
    the *values* are not, and the failure mode is total rather than partial.

19. **The first untouched overnight run, and what it found. 2026-08-25.** The
    milestone itself passed: an alert for a rocket body was delivered at
    03:00:53 for a peak at 03:28:23 - a 27.5-minute lead - on an unplugged
    phone in Doze that nobody had touched since the evening. That is the Phase
    3 equivalent of the Phase 1 result, and nothing short of it would have done.

    It also found a real defect, which is what it was for. The station's pass
    at 03:48:24 was never announced. `nextWake` returned the announced pass's
    own set for `Decision.Announce` - "the pass has been dealt with, wait until
    it is over" - which is sound about that pass and says nothing about the
    other channel. The station's alert fell due at 03:18:24, inside the window
    the planner had decided to sleep through. The channels have separate
    budgets precisely so one does not crowd out the other, and this was that
    happening. Fixed: after an announcement the planner wakes at the earlier of
    the pass's end and the soonest alert due elsewhere.

    A second cause is not addressed. The wake that *was* armed landed 27 minutes
    late, and by then the pass had peaked and was correctly discarded. A
    punctual wake would still have caught it, with a shortened lead. That is a
    scheduling question rather than a planning one and wants its own
    observation before anything is changed on a guess.

    The other lesson was about the evidence. Reconstructing this took preference
    values and a simulation, because logcat is the system's ring buffer and had
    long since rolled past the window in question - which is the only window
    this app works in. The app now keeps its own log.

20. **A source stopped being attempted, and health could not see it. Fixed
    2026-08-25.** The SWPC cron stopped firing at 04:30:54 and the health
    record went on reporting `ok` with zero failures for two and a half hours,
    while `status.json` served a solar wind reading from 04:25. NOAA was fine
    throughout - checked directly, serving 06:48 data. It was found by accident,
    looking for something else.

    `recordOutcome` catches every throw and writes a failure, so a source that
    *runs* and breaks is reported. Health recorded attempts and only attempts,
    which meant a source nobody asks looked exactly like a source in perfect
    order. That is precisely the silent failure the channel was built for.

    Health now judges whether the last success is recent enough to believe and
    labels the source `stale`, against a per-source expectation, and pushes on
    the edge - including recovery, which cannot come from `recordOutcome`
    because a source that merely stopped was never marked failing.

    On the cause: **redeploying the Worker revived it**. SWPC resumed at
    07:15:55, immediately after a deploy whose only relevant effect was
    re-registering the triggers. That is the same family as issue 10, where a
    documented cron expression registered without complaint and never fired,
    and it is worth knowing that a Cloudflare cron can stop for no visible
    reason and that a redeploy is a plausible first remedy.

21. **The calendar's real problem is relevance, not computation. Found building
    item 21, 2026-08-25.** Sec. 14 calls the astronomy-engine events "mostly
    wiring", and of the computation that is exactly right: every kind is one
    call into the vendored ephemeris. What the estimate missed is that most of
    what those calls return is not worth telling anybody about. Measured for
    Edinburgh over the following years:

    - Three of the next six lunar eclipses are **penumbral**, obscuration 0.000.
      The moon misses the umbra entirely and the disc looks unchanged. These are
      not dim events, they are invisible ones, and they are not produced at all.
    - Three of the next six local solar eclipses have the **sun below the
      horizon** at maximum. One of them, 26 January 2028, begins with the sun 5.6
      degrees up and reaches maximum after it has set - which is neither "good"
      nor "not visible" and needed a third verdict of its own.
    - Close planet pairings inside three degrees arrive roughly **fortnightly**,
      and most of them peak with the pair below the horizon. The moon passes
      within four arcminutes of Venus on 14 September 2026, at ten in the
      morning.

    So a conjunction is reported **as it is seen** rather than as it is closest,
    and one that is no longer close by the time it can be seen is dropped: six of
    the fourteen the search found in the first two hundred days were the moon
    being vaguely near a planet by the time either cleared the horizon. Reading a
    season of the generated output is what surfaced all of this; none of it came
    out of reasoning about the design beforehand, and it is the reason the
    calendar has a visibility verdict rather than a boolean.

22. **A lunar eclipse is a full moon, and the calendar printed both. Same day.**
    Seven minutes apart, on 28 August 2026. A solar eclipse is a new moon and a
    lunar one is a full moon by definition, so the two entries are the same
    alignment seen twice; the moon phase is now suppressed when an eclipse
    coincides. Trivial to fix and invisible until the output was read.

23. **`equator` is topocentric, and for the moon that is not a detail.** The moon
    has up to a degree of parallax, so a geocentric separation can be a whole
    moon's width from what somebody actually sees - and the moon is involved in
    most close approaches. A conjunction is a fact about a place, not about the
    Earth, which is also why an app can compute it on the device and a server
    could not.

24. **The app's eclipse coverage figure disagrees with every eclipse table,
    and both are right. Found doing the Phase 4 verification, 2026-08-25.**
    Checking the eclipse of 2 August 2027 against timeanddate confirmed the
    contact times to within a minute - first contact 08:11 UTC against "around
    9.10am" BST, maximum 09:00 against "around 10am", last contact 09:51 against
    "around 10.50am" - and disagreed flatly on how much of the sun is covered:
    38 per cent there, 27 per cent here.

    Neither is wrong. **Magnitude** is the fraction of the sun's *diameter*
    covered and is what eclipse tables and the press print; **obscuration** is
    the fraction of its *area* and is what the vendored ephemeris returns. They
    are never equal and magnitude is always the larger. This is the same trap
    Sec. 6 of the sourcing document describes for satellite magnitudes - two
    conventions that look like one number - arriving from a different direction.

    The app now says "27 per cent of the sun's disc covered by area", so a user
    who reads 38 per cent elsewhere has the word that explains the difference
    rather than concluding the prediction is broken. Worth knowing that this
    would never have surfaced from the test suite: the fixtures come from the
    same ephemeris as the code, so they agreed with each other perfectly. It
    took comparing against something outside the project.

25. **A continuity test that could not fail, and the mutation that found it.
    Phase 5.** The comet solver has three branches - ellipse, parabola,
    hyperbola - which are separately derived and must agree where they meet at
    `e = 1`. The test asserting that used `e = 1 +/- 1e-9`, which is *inside*
    the parabolic band, so all three calls took the parabolic branch and the
    test compared it against itself. It passed with the constant in Barker's
    equation deliberately altered.

    Found by mutation rather than by review: changing `sqrt(2)` to `2` and
    expecting a failure that did not come. The test now straddles the band, and
    a second test asserts that it does, so widening the band later cannot
    silently disarm it. The general lesson is that a test whose subject is
    "these two paths agree" is worthless unless something proves two paths were
    taken.

26. **JPL Horizons answers in a different time scale than it labels, and a
    geocentric position is not a topocentric one. Phase 5.** Getting the comet
    positions cross-checked took three attempts, and the two failures are worth
    keeping because both looked like bugs in the app.

    Asking Horizons for an ephemeris at a stated Terrestrial Time applies TT to
    the body but reports a sidereal time on the other clock, which put the
    azimuth 0.18 degrees out. That was chased as far as computing GMST from the
    IAU expression by hand, which agreed with the vendored library and not with
    Horizons' own column. Requesting plain UT removes the ambiguity, and is the
    app's real code path anyway.

    The remaining 15 arcseconds on Halley were the observer's displacement from
    the centre of the Earth. The app computes geocentric positions and does not
    model diurnal parallax; it is 7 arcseconds for a comet at 1.3 AU and 21 for
    one at 0.4, and it does not matter to an app asking how high something is.
    Against Horizons' *geocentric* answer the residual is 1.4 arcseconds, which
    is the library's Earth against the one JPL integrates.

27. **A position test is a weak guard for a time scale, and the fix was to test
    the conversion instead. Phase 5.** The Minor Planet Center publishes
    perihelion passage in Terrestrial Time; the phone's clock is UTC; they
    differ by about seventy seconds. The test meant to catch that mix-up used
    the fastest-moving fixture available - Halley at 0.42 AU, crossing six
    degrees of sky a day - and still could not: the mistake moves it under eight
    arcseconds, which sits inside any tolerance loose enough to survive the
    difference between two solar system ephemerides. It passed with the
    conversion deliberately broken.

    The conversion is now one line with a test of its own, asserting the offset
    is the 55.2 seconds TT actually ran ahead of UTC in April 1986. That fails
    when broken. Where a quantity is small in its effect but categorical in its
    correctness, assert the quantity rather than its consequences.

28. **Two settings shipped without ever being written to disk, and the second
    was invisible to the test added for the first. Phase 5.** Phase 4 shipped
    four calendar settings that were never persisted; the round-trip test was
    widened to compare the whole settings object, which fixed those. It did not
    fix `hiddenEventKinds`, which was also never persisted: a field the test
    fixture leaves at its default reads back as its default whether the store
    wrote it or not, so the two compare equal and the test passes.

    The fixture now has a guard of its own, asserting by reflection that it
    differs from the defaults in *every* field. Adding a setting and forgetting
    to persist it fails, and so does adding one and forgetting to put it in the
    fixture. The same guard was applied to the alert state, which passed as it
    stood.

29. **The first thing with no moment of its own broke two pieces of correct
    machinery. Phase 5.** Every event the app had until comets has a moment
    fixed by orbital mechanics: an eclipse is at its instant whenever you ask.
    A comet has none, so the calendar invents one - the best night from now -
    which moves every time the calendar is recomputed.

    Starring keyed on kind and time, which for a comet meant a star lost within
    a day of being set: exactly the control somebody would most want on a comet,
    since an unmeasured one is always marginal. Worse, the announced record is
    pruned by parsing the key's second field as a timestamp, and a name does not
    parse - so an announced comet was discarded immediately and would have been
    announced afresh on every wake, twice a day, for as long as it was up.

    Both were found by asking what the identity of a comet actually is rather
    than by a failing test. Comet keys now carry the name, and are kept until
    the comet fades.

30. **The Starlink train alerting worked overnight, through a seventeen-hour
    upstream outage. 2026-08-26.** A train notification arrived on the device
    without anybody watching for it - the first end-to-end confirmation of the
    Phase 3 train work outside a test, where item 20 had only ever been
    verified against fixtures.

    What makes it worth recording is what was happening upstream at the time.
    The CelesTrak breaker had tripped at 14:28 the previous afternoon on an
    HTTP 503, and every scheduled run since had correctly refused to query, so
    the elements behind that notification were up to seventeen hours old. The
    claim that "a tripped breaker degrades pass prediction rather than breaking
    it" was written as a design intention and has now been demonstrated by
    accident, on real hardware, without anybody arranging it. A 503 is exactly
    the transient a manual-reset breaker is least suited to and most defensible
    about: the cost of waiting for a human was one day of element freshness and
    nothing a user could perceive.

    The maintainer alert fired as designed at the moment it tripped. It was
    missed rather than absent, which is a different problem and not one the
    fetcher can solve.

31. **The reduction window was absorbing the signal it existed to carry.
    2026-08-26.** Asked how often the comet sources should be fetched, the
    honest answer turned out to be that the fetch interval was never the
    binding constraint and the reduction was.

    Magnitudes were reduced to a median over a fixed fortnight, chosen to have
    enough independent observers to reject a wild one. Measured against 180 days
    of real COBS data, that also flattened a 3.8-magnitude outburst of
    29P/Schwassmann-Wachmann into a 0.1-magnitude wobble. No fetch cadence
    whatever could have surfaced that, because the published number barely
    moved: polling hourly would have transferred a megabyte an hour to report
    that nothing had happened.

    The window is now a ladder from two days to a fortnight, taking the shortest
    rung that holds four separate observers and falling back to two for a comet
    nobody watches closely. Tracking error against the day's own median roughly
    halves. The four is not decoration: individual comet estimates are far
    noisier than they look - six observers put 10P/Tempel between magnitude 7.3
    and 12.0 on one night - so a pair is not enough to justify ignoring a
    fortnight of other reports, and requiring four costs 0.02 magnitudes of mean
    accuracy while lifting the thinnest tenth of cases off a coin toss.

    The change was visible immediately on live data: 220P/McNaught, which the
    fortnight median still had at 9.5, has in fact faded past magnitude 10 over
    the past week and now correctly drops below the publishing bar.

    The general shape is worth keeping. When asked to make a pipeline more
    responsive, check where the lag actually is before touching the part that
    was asked about. Here the request interval, the CDN cache and the device
    poll were all faster than the smoothing sitting in the middle.

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
- ADR-0007 - keep the vendored propagator in its own source tree.
- ADR-0008 - fetch CelesTrak from outside Cloudflare.
- ADR-0009 - three specific pollers, not one event engine.
- ADR-0010 - fetch the comet sources outside Cloudflare, and without a breaker.
- ADR-0011 - a derived-settings layer rather than writing calibrated values.
- ADR-0012 - constellations as the one exception to the star exclusion.
- ADR-0013 - R8 on for release, with the app's own classes kept.
- ADR-0014 - no CI: a hook and a release command.
- ADR-0015 - the CelesTrak breaker clears itself after two days.
- ADR-0016 - background location on a cadence the user chooses.
