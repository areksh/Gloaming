# 7. Keep the vendored propagator in `src/main/vendor`, not a `:core:predict` module

Date: 2026-08-22

## Status

Accepted

Supersedes the module layout recorded in `docs/architecture.md`, which planned
a `:core:predict` module for this code.

## Context

Phase 3 vendors Look4Sat's SGP4/SDP4 propagator (GPL-3.0). `docs/architecture.md`
planned it as its own Gradle module, `:core:predict`, "to isolate the vendored
source and preserve its licence headers", to be extracted when the propagator
landed.

Since that was written, ADR-0006 put astronomy-engine in a separate *source
directory* instead - `app/src/main/vendor`, declared in `app/build.gradle.kts` -
and that has been in place through Phase 2. It has done exactly the job the
module was meant to do:

- Vendored code is structurally distinct from the project's own and cannot be
  edited by accident.
- Licence headers and licence files sit with the code they cover.
- `app/src/main/vendor/README.md` records provenance, tag and per-file SHA-256,
  so an upgrade is a file replacement rather than a merge.

A module would add a build file, a source set, a dependency edge and a slower
configuration phase, in exchange for a boundary the source directory already
provides. The one thing a module would add that the directory does not is a
compile-time barrier stopping the app from reaching into the propagator's
internals - and the app deliberately does reach in, via a wrapper in
`sh.arek.gloaming.satellites`, exactly as it does for astronomy-engine.

The licence question turns out not to need a module either. GPL-3.0 code in a
GPL-3.0 application needs no separation at all: the whole work is under one
licence. Separation would matter if the app were under a permissive licence and
had to keep the GPL part at arm's length, which is not the case here and, given
ADR-0004, will not become the case.

## Decision

Vendor the propagator at
`app/src/main/vendor/com/rtbishop/look4sat/core/domain/predict/`, alongside
astronomy-engine and under the same never-edited rule. No `:core:predict`
module, and none planned.

Eleven files rather than the ten in the `predict` package: it imports
`toDegrees()` and `toRadians()` from `utility/GeoConverter.kt`, which is
vendored whole rather than reduced to those two functions, because reducing it
would be editing it.

The app's own code lives in `sh.arek.gloaming.satellites`: element
deserialisation, the pass search, and the qualitative descriptions.

## Consequences

- One fewer module to configure and build. The module layout in
  `docs/architecture.md` is now `:app` alone, with `:core:astronomy` also
  unbuilt and on the same footing: extracted if and when one directory stops
  being enough.
- Upgrading the propagator stays a file replacement, verified by the checksums
  in the vendored README and gated by the Vallado verification vectors.
- Nothing prevents the app calling the propagator directly. That is deliberate,
  and the wrapper is a convention rather than a compiler-enforced boundary.

## A correction worth recording

`docs/sourcing and constraints.md` Sec. 7 presents GPL-3 as the cost of getting
SGP4 at all, the MIT alternative having been unavailable. That is no longer
true: `predict4java` was relicensed to MIT in 2026. It was rejected here on
other grounds - it pulls in `commons-lang3`, `slf4j-api` and `joda-time` for
what is pure arithmetic, and it is TLE-oriented, so adopting it would mean
adding OMM support to someone else's library rather than to a local copy.

GPL-3.0 for this project is the choice recorded in ADR-0004, not something SGP4
forced. It should not be re-litigated as if it were.
