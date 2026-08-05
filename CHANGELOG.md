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

### Changed

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
