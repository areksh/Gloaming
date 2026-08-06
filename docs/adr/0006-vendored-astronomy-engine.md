# 6. Vendor astronomy-engine rather than resolve it from JitPack

Date: 2026-08-06

## Status

Accepted

## Context

Phase 2 needs the darkness gate (Sec. 9.2), which is non-optional, and the
adaptive schedule (Sec. 9.3), which is built on knowing when it gets dark.
Phase 4 needs eclipses, conjunctions and moon phases from the same source.
`docs/sourcing and constraints.md` Sec. 7 records astronomy-engine as the
library, at `io.github.cosinekitty:astronomy:2.1.19`.

**That coordinate does not exist.** The library is not published to Maven
Central at all, under that group or any other. The only distribution for the
Kotlin/JVM edition is JitPack, as `com.github.cosinekitty:astronomy:v2.1.19` -
note the different group and the `v` in the version, both of which follow from
JitPack deriving coordinates from the GitHub tag.

That changes the decision the sourcing document thought it was making. Adding
the dependency now means adding JitPack to the repository list, and JitPack is
not a package registry: it builds the artefact from source, on demand, on its
own infrastructure. The consequences are:

- A third-party build service sits in the supply chain of a privacy-focused app.
- Artefacts are not immutable in the way a Maven Central release is; a rebuild
  can produce different bytes for the same coordinate.
- F-Droid inclusion and reproducible builds (Sec. 12) both get harder, and
  F-Droid is the primary distribution channel.

Against that, the library itself is unusually easy to vendor, and the sourcing
document already anticipated this: "It is MIT and self-contained, so vendoring
is the fallback if it breaks."

- One file, 10,674 lines, MIT.
- Zero dependencies. Twenty `kotlin.math` imports and nothing else - no
  `java.*`, no `java.time`, no `Calendar`, so no desugaring and no `minSdk`
  impact.
- Dormant upstream: last release December 2023, last repository push January
  2025. There is little stream of updates to forgo, and an ephemeris does not
  rot - VSOP87 is validated against NOVAS and JPL Horizons to within an
  arcminute and will say the same thing in ten years.

## Decision

Vendor the file, byte-identical, at
`app/src/main/vendor/io/github/cosinekitty/astronomy/astronomy.kt`, with its
MIT licence beside it and its provenance and SHA-256 recorded in
`app/src/main/vendor/README.md`.

`src/main/vendor` is a separate source directory declared in
`app/build.gradle.kts`, so vendored code is structurally distinct from the
project's own and cannot be edited by accident.

**Nothing vendored is ever modified.** Anything the app needs on top is written
as a wrapper - here `sh.arek.gloaming.astronomy.Twilight` - so an upgrade stays
a file replacement rather than a merge.

## Consequences

- No JitPack, no third-party build service, and one fewer thing that can change
  underneath a release. The repository list stays `google()` and
  `mavenCentral()`.
- Licence bookkeeping is explicit rather than transitive: the MIT text sits next
  to the code it covers, which is what GPL-3.0 distribution needs anyway.
- The vendored code costs about 536 KB of dex, unminified. Release builds
  currently disable R8; enabling it would cut most of that, since the app calls
  a handful of entry points out of a whole ephemeris. Worth revisiting before
  v1.0 rather than now.
- Upgrades become manual, and manual upgrades are the ones that get forgotten.
  The upgrade procedure and the current checksum are written down in
  `app/src/main/vendor/README.md` so the next person does not have to
  reconstruct where the file came from.
- The precedent is set for Phase 3, which already planned to vendor Look4Sat's
  SGP4/SDP4 port (GPL-3) for licence reasons. That code lands in the same tree
  under the same rules, and the architecture document's `:core:predict` module
  can be extracted if and when one file stops being enough.
- The sourcing document's Sec. 7 coordinate is wrong and is corrected in the
  same change.

The project now vendors two things for two different reasons - a crypto
primitive the platform does not supply at `minSdk 24` (ADR-0005), and a library
that is not published anywhere immutable. Neither is a general licence to copy
code in preference to depending on it. The bar remains: self-contained, a
licence that permits it, and a concrete reason the dependency does not work.
