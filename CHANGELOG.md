# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Architecture documentation (`docs/architecture.md`), decision records under
  `docs/adr/`, and the pre-development checklist (`docs/prerequisites.md`).
- Jetpack Compose application scaffold (replacing the initial Views/AppCompat
  project): `minSdk 24`, `compileSdk`/`targetSdk 37`, JDK 21, Kotlin 2.4.0 via
  AGP 9 built-in Kotlin, Compose BOM 2026.06.00, Material 3. A `MainActivity`
  and `GloamingTheme` render a placeholder screen; the debug APK builds green.
- GPL-3.0 licence and a README, including the endpoint-use note for forks.
- Cloudflare cron Worker publishing the Phase 1 data files: the packed OVATION
  aurora grid as `oval.bin`, and `status.json` composed from the SWPC and
  AuroraWatch UK sources on their separate schedules, with the documented
  `Cache-Control` set at PUT time.
- Signed manifest: `index.json` plus an Ed25519 detached signature at
  `index.json.sig`, republished only when its contents change. This is the only
  URL the app will bake in, so every other endpoint can move without stranding
  installs that never update.
- Health tracking and maintainer alerting: `health.json` records per-source
  state, last success and last error; a maintainer-only ntfy topic receives one
  push when a source starts failing and one when it recovers.
- Ed25519 signature verification for the app (`Ed25519Verifier`), so the signed
  manifest can be checked on Android 7.0 where the platform has no Ed25519.
  Verify-only, no dependency, validated against the RFC 8032 vectors.
- Manifest fetching for the app: verifies the signature over the exact bytes
  received, caches the verified copy, and falls back to the last known-good
  manifest - re-checking its signature too - whenever the network copy cannot be
  trusted.
- Aurora grid handling on the device: inflates the published grid and looks up
  probability for an observer position, wrapping longitudes and clamping at the
  poles. Both hemispheres, so the aurora australis works without extra code.
- Status fetching with conditional GET, and two-tier grid fetching that pulls
  `oval.bin` only when its ETag changes. Failures degrade to the cached copy
  rather than leaving the app with nothing to say.
- Scheduled background polling, with every wake jittered and the next always
  queued even after a failure.
- Alert decisions: per-user threshold, hysteresis so a value hovering at the
  threshold produces one notification rather than a stream, and a six-hour
  minimum between alerts that a substantially larger event can still escape.
- A screen: manual location entry, threshold, prominent data age, and prompts
  for the battery-optimisation exemption and notification permission.
- Optional before-unlock alerting, off by default, for devices that reboot on a
  timer. Keeps alerts working while the phone sits locked after a reboot, at the
  cost of copying the rounded grid cell - never the precise position - into
  device-protected storage.
- The darkness and cloud gates, quiet hours, and an opt-in override that lets an
  exceptional display through them. The gates run cheapest first - threshold,
  darkness, hysteresis, quiet hours and rate limiting all cost nothing - so only
  a poll that has already decided to alert pays for a cloud forecast. An
  unreachable forecast alerts anyway and says the cloud is unknown, rather than
  going quiet whenever a third party is down.
- Darkness windows and moon phase, computed on the device with no network at
  all. The polar cases are answered rather than guarded against: Shetland's
  simmer dim reports that no astronomical darkness arrives, and the polar night
  reports darkness with no dawn to find.
- Cloud cover from Open-Meteo, fetched by the device itself rather than through
  the project's Worker, because Open-Meteo's rate limits are per-IP. The
  position sent is rounded to about a kilometre - the resolution of the finest
  model Open-Meteo runs, so the forecast loses nothing - and readings are cached
  for an hour, which is also the cap their terms set.
- Test harnesses for the two things only a real phone can answer:
  `tools/bfu-check.sh` reboots a device and reports whether alerting survived
  the locked state, and `tools/doze-check.sh` forces deep Doze and measures how
  far apart the polls actually land. Both turn an overnight wait into minutes.

### Notes

- Instrumented tests verified on a Pixel 8a running GrapheneOS: storage
  partitioning, permission declarations, scheduling and notifications.
- Before-unlock alerting verified end to end on a Pixel 8a running GrapheneOS:
  after a reboot the boot receiver re-armed the alarm 50 seconds in, and two
  polls fetched, verified and cached the manifest, status and grid while the
  device was still locked. A subsequent run carried it through to a real
  notification - `Notified(probability=21)` from the live grid, posted on a
  phone that had not been unlocked since boot.
- Alerting verified in forced deep Doze on the same device, with the
  battery-optimisation exemption granted: three polls in twelve minutes, an
  alert raised and its notification posted from deep idle. The exemption does
  what it claims - the alarm carries `ALLOW_WHILE_IDLE_UNRESTRICTED` rather
  than the throttled compat form, and neither Doze nor app standby constrains
  it. Hysteresis held on real data across the run: the probability stayed at
  21% and the following polls returned `NotReArmed` rather than notifying
  again.
- Measured the cost of inexact alarms: a two-minute interval is delivered at
  roughly four, both in Doze and before unlock. `setAndAllowWhileIdle` is
  inexact by design and the system batches it, so the configured interval is a
  floor rather than a period. The adaptive-polling design should assume about
  double what it asks for.
- Three states stop alerting silently, and all are now checked before a test
  run: an app in the stopped state receives no broadcasts at all and sits there
  from installation until first opened; a never-opened app is in the `NEVER`
  standby bucket; and without the battery-optimisation exemption app standby
  defers alarms anyway. A deferred alarm still lists as pending.
- App standby, not Doze, is what stops overnight wakes. `setAndAllowWhileIdle`
  defeats Doze as documented, but app standby deferred the same alarm by 365
  days while it still appeared pending and healthy. The causes are an app in
  the `NEVER` bucket - where it sits until the user first opens it - and a
  missing battery-optimisation exemption.
- GrapheneOS defaults the USB-C port to charging-only while locked, so adb is
  unreachable for the whole before-unlock window. The harness gathers its
  evidence afterwards instead - from `last_poll_at` in device-encrypted storage,
  the cache file timestamps, and the `unlocked=false` the app logs at the moment
  of each poll - rather than requiring a live connection that cannot exist.

### Changed

- astronomy-engine is vendored (MIT, tag `v2.1.19`) rather than resolved as a
  dependency. It is not published to Maven Central at all - only to JitPack,
  which builds it on demand - and a third-party build service in the supply
  chain is a poor trade for one dependency-free file. See ADR-0006.
- Scheduling uses AlarmManager with Direct Boot aware receivers rather than
  WorkManager, which cannot run before first unlock - precisely the state a
  scheduled reboot leaves the device in.

- Base application theme no longer depends on the Material Components view
  library; a pure-Compose app themes at runtime, so the platform theme only
  supplies a no-action-bar window.
- Corrected the recorded SWPC solar wind endpoints: the paths under
  `/products/solar-wind/` now return 404, and the summary products are used
  instead.

### Notes

- Measured the packed aurora grid against live data: 1.6 KB gzipped in quiet
  conditions and about 2.5 KB under a simulated severe storm, comfortably inside
  the budgeted 5-10 KB. This closes an open question in the sourcing document.
- Flagged a free-plan risk: Cloudflare allows 10 ms CPU per cron invocation, and
  the OVATION refresh straddles it. Needs measuring on the deployed Worker.
