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
- A home-screen widget showing the local probability and its age, so "is it
  worth checking?" can be answered at a glance without an interruption. Plain
  `RemoteViews`, no Glance: it is three pieces of text, and a Compose runtime in
  the launcher's process would be a poor trade. It never refreshes on a schedule
  of its own - each poll redraws it, so it is exactly as fresh as the data
  behind it and costs no wakes - and a reading older than twelve hours is not
  shown at all rather than being presented as current.
- Red night-vision mode: pure red on unlit black, with the screen dimmed. Not a
  red-tinted dark theme - the green and blue channels are zero, because a dimmed
  white screen still emits across the spectrum and costs the twenty minutes of
  dark adaptation it looks like it is protecting.
- The AuroraWatch UK enhancement layer. Inside a UK bounding box a real
  magnetometer reading can raise what the global model says, feeding one
  internal likelihood so nothing downstream branches on which source is in play.
  It can only raise: AuroraWatch's alerts come from a single site that defaults
  to the Lancaster magnetometer and get steadily more pessimistic further north,
  and a green status silencing a grid that says 65% over Shetland would be the
  app failing at its one job. Notifications say when a reading came from
  AuroraWatch, which their terms require and which the user should be able to
  tell anyway.
- Storm mode: while a large display is actually overhead on a dark, clear night,
  the app follows it every couple of minutes behind a `specialUse` foreground
  service, so someone standing outside sees it strengthen or fade. It costs
  nothing the rest of the time because it does not run, and it stops as soon as
  the display fades, the sky closes in or it gets light - with a four-hour cap
  as a safety net, since it holds a wake lock to keep the cadence honest.
- Adaptive polling. The app sleeps until dusk rather than checking through the
  day, goes dormant where the sun does not set, slows to hourly under heavy
  cloud, and escalates to five minutes when the solar wind turns southward or Kp
  rises - which, because Bz is measured an hour upstream at L1, is warning of a
  substorm that has not started yet. The schedule reads cloud from the cache
  rather than fetching it: spending a radio wake to decide how often to wake the
  radio would undo the saving.
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
- A "watch now" switch, which starts the same foreground service storm mode
  uses and keeps it running until the user stops it or its four-hour cap does.
  Everything else in the app is scheduled through `AlarmManager`, which the
  system is free to defer; a foreground service runs its own clock and is the
  only thing Android will not delay. It is a button rather than a default
  because it costs a wake lock and a notification that will not go away.
- The status card now says why the app is not checking - "sun is up, nothing to
  see until it gets dark, around 22:40", or that this time of year has no
  darkness in it at all. The adaptive schedule sleeps for up to twelve hours at
  a stretch, and without this a correct long gap is indistinguishable from a
  background service the system has killed.
- A notice while battery saver is on, next to the watch switch that is immune
  to it. Raised only while the saver is actually on, so it does not become
  another standing warning to scroll past.
- Credits and licences on the screen itself. AuroraWatch UK's conditions
  require their attribution beside their data, Open-Meteo publish under CC BY
  4.0, and the vendored astronomy code is MIT - which requires its notice in
  every copy of the software, and an installed APK is a copy. Until now that
  notice existed only in the repository, which nobody who installs the app ever
  sees. The AuroraWatch credit follows their reading rather than standing
  permanently: crediting them for a global-grid number would misattribute one
  they had no part in. A unit test compares the shipped MIT notice against the
  vendored licence file, so an upgrade cannot quietly leave a stale one behind.
- The screen says when a reading came from AuroraWatch rather than the global
  grid. The notification already did, but most polls are quiet and post
  nothing, and the number they produced stays on the screen until the next one.

### Changed

- The battery-optimisation exemption is stated as a requirement rather than a
  refinement. The screen said alerts would be "delayed" without it, which
  undersold it: an aurora app that cannot check overnight is not doing
  anything, and no other setting works around it. Running without the exemption
  is out of scope, and the non-exempt schedule is deliberately neither measured
  nor accommodated.
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

### Fixed

- Backup is switched off, and device-to-device transfer is excluded separately,
  because `allowBackup` does not cover it. Both rule files were still the
  unedited template, which means include-everything - so the precise position
  the user typed was eligible to be copied off the phone by the backup system,
  going around the whole reason it is kept in credential-encrypted storage. On
  GrapheneOS that is a live path rather than a theoretical one, because
  Seedvault honours these declarations.
- "Check now" now actually checks, and the screen shows that it did. It went
  through `AlarmManager`, which is inexact - a "one second" alarm can be batched
  up to two minutes out - and the screen only re-read the poll result when the
  app was resumed, so the button appeared to do nothing at all. A check the user
  asked for is run directly, reports itself as busy while it runs, and re-arms
  the schedule from the plan it produces. That last part matters after a
  location change: the pending wake was worked out for the old position, and
  dusk somewhere else is at a different time.
- Tapping an aurora alert opens the app. It carried no content intent, so the
  tap did nothing whatever - the notification did not even dismiss, because
  `setAutoCancel` only takes effect once there is somewhere to go. Storm mode's
  notification had one from the start; the alert that actually wakes people did
  not.

### Notes

- The worked example for provoking a real alert was stale in two ways: it gave
  a longitude in the grid's 0-359 form rather than the -180 to 180 the app
  takes, and it predated the darkness and cloud gates, so following it produced
  no alert and looked like a broken harness. It now says to pick a cell on the
  night side and why.
- The instrumented suite needs the device awake and unlocked. Behind the
  keyguard an activity resumes and pauses without drawing, and every Compose
  assertion fails with "No compose hierarchies found in the app".
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
  floor rather than a period. The overhead looks like a roughly fixed cost
  rather than a multiplier - a batching window just under two minutes was
  observed in the alarm dump, on top of the jitter the scheduler adds itself -
  which would put a five-minute request at about eight and a thirty-minute one
  at about thirty-nine. Adaptive polling is built on that reading and therefore
  does not compensate, since asking for half of what is wanted would double the
  cost on any device that delivers punctually. Inferred from one measurement at
  one interval, so it wants confirming with `tools/doze-check.sh` at five and
  thirty minutes.
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

- Measured the packed aurora grid against live data: 1.6 KB gzipped in quiet
  conditions and about 2.5 KB under a simulated severe storm, comfortably inside
  the budgeted 5-10 KB. This closes an open question in the sourcing document.
- Flagged a free-plan risk: Cloudflare allows 10 ms CPU per cron invocation, and
  the OVATION refresh straddles it. Needs measuring on the deployed Worker.
- Battery saver measured on a Pixel 10a, and it costs nothing detectable. In
  forced deep Doze with the exemption granted, a five-minute interval was
  delivered every 9-10 minutes with the saver on and every 9-10 minutes with it
  off. That confirms the fixed-overhead reading taken on the 8a, which
  predicted about eight minutes for a five-minute request. The delay is the
  batching window rather than Doze: `device_idle` was recorded as not applying
  on every sample, and the alarm runs past its requested time to fire at
  `maxWhenElapsed`. Networking was never restricted either, even in deep Doze
  with the saver on - `blocked=BATTERY_SAVER|DOZE|APP_BACKGROUND`,
  `allowed=POWER_SAVE_ALLOWLIST`, `effective=NONE`.
- The restricted standby bucket cannot be reached while the exemption is
  granted: `am set-standby-bucket restricted` reports success and the bucket
  stays ACTIVE. The worst case an exempt app can land in is better than
  assumed, and worth remembering before designing around a bucket it cannot
  occupy.
- One caution about how the above was measured. An earlier run reported a
  sevenfold deferral that was not happening, because `tools/doze-check.sh`
  anchored on a marker line in `logcat` and the ring buffer - about twelve
  minutes, 84,351 lines, on this phone - wrapped past it mid-run. The harness
  then matched nothing and reported the single poll it had seen before going
  blind. Both the alarm dump and `logcat` contradicted it at the time. A
  measurement tool that invents a failure is worse than one that measures
  nothing, so the tool now records each poll as it sees it and selects by
  device-clock epoch instead.
