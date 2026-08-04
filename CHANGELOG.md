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

### Changed

- Base application theme no longer depends on the Material Components view
  library; a pure-Compose app themes at runtime, so the platform theme only
  supplies a no-action-bar window.
