# Vendored source

Third-party code copied into this repository rather than resolved as a
dependency. It compiles as part of `:app` through a source set declared in
`app/build.gradle.kts`, and is kept in its own tree so it is never mistaken for
the project's own code.

**Nothing here is edited.** Every file is byte-identical to its upstream
release, so an upgrade is a straight replacement rather than a merge. Anything
the app needs on top of a vendored library is written as a wrapper in
`sh.arek.gloaming.*`.

The checksums below are what says so, and a unit test
(`VendorChecksumsTest`) holds the two together in both directions: a file whose
hash is not recorded here fails, and a hash recorded here that belongs to no
file fails. An edit that would otherwise have surfaced years later as a conflict
during an upgrade fails on the next run of the suite instead, and the failure
prints the hash to record once the change is a deliberate one.

## astronomy-engine 2.1.19

| | |
|---|---|
| Upstream | <https://github.com/cosinekitty/astronomy> |
| Author | Don Cross |
| Licence | MIT (`io/github/cosinekitty/astronomy/LICENSE`) |
| Tag | `v2.1.19` |
| Source | `source/kotlin/src/main/kotlin/io/github/cosinekitty/astronomy/astronomy.kt` |

| File | SHA-256 |
|---|---|
| `astronomy.kt` | `b698f7564fdbd6db78fa5777778c2538b377c1506470dfaa5669c57399c81c9a` |
| `LICENSE` | `690dd98cb13ba4db77c6327deea852a816892bb9debbad5943405c66972f8023` |

Vendored rather than depended on because it is not published to Maven Central -
only to JitPack, which builds it on demand from the GitHub tag. See
[ADR-0006](../../../../docs/adr/0006-vendored-astronomy-engine.md).

The MIT licence requires its notice in every copy of the software, and an
installed APK is a copy - so the notice also travels in the app itself, as
`ASTRONOMY_ENGINE_LICENCE` in `sh.arek.gloaming.ui.Credits`. A unit test
compares the two, so the pair cannot drift apart unnoticed.

To upgrade, replace the file from the new tag, update the version, tag and
checksum above, copy the new `LICENSE` into `ASTRONOMY_ENGINE_LICENCE`, and run
the suite. It has no dependencies beyond `kotlin.math`, so nothing else moves
with it.

```sh
curl -sL -o app/src/main/vendor/io/github/cosinekitty/astronomy/astronomy.kt \
    https://raw.githubusercontent.com/cosinekitty/astronomy/vX.Y.Z/source/kotlin/src/main/kotlin/io/github/cosinekitty/astronomy/astronomy.kt
sha256sum app/src/main/vendor/io/github/cosinekitty/astronomy/astronomy.kt
```

The wrapper the app actually calls is `sh.arek.gloaming.astronomy.Darkness`.

## Look4Sat 4.4.6 (SGP4/SDP4 propagator)

| | |
|---|---|
| Upstream | <https://github.com/rt-bishop/Look4Sat> |
| Author | Arty Bishop and contributors |
| Licence | GPL-3.0-or-later (`com/rtbishop/look4sat/LICENSE`) |
| Tag | `v4.4.6` (commit `7cdc2952dc8e40bc8063df32364a83b7eea97101`) |
| Source | `core/domain/src/main/java/com/rtbishop/look4sat/` |

| File | SHA-256 |
|---|---|
| `core/domain/predict/CelestialComputer.kt` | `98d9e80c3e1ccbd9373a69e644f63dea0a662226d16b6685b60a4d0a05764c29` |
| `core/domain/predict/Constants.kt` | `b51b52f7cb30d0108056c1d48befd9d79197c57aba238c556b033a54bf77f349` |
| `core/domain/predict/DeepSpaceObject.kt` | `a0fc7d0adfd1a47ee60524d48e3fed3b1de34de9820006bcc5f40e156c5c6ee3` |
| `core/domain/predict/GeoPos.kt` | `740549a7ab4be4d5d811494ccaffbb5e84493e165b9e14769b5f1635198f9c7f` |
| `core/domain/predict/NearEarthObject.kt` | `5641bab09e411982db7c1833d80a8ce9b41abb3f4797963c2cbcd92fe445efc2` |
| `core/domain/predict/OrbitalData.kt` | `fdd71fb76d72aca0b8c94fef10b7355e0a4d27f17af90ae51882b178315ea1c2` |
| `core/domain/predict/OrbitalMath.kt` | `a8a81923411ece8b8e53bfd29b019332c80bdeedd34a9037504386a127c14d61` |
| `core/domain/predict/OrbitalObject.kt` | `1a19c1d1f97bf08c18b2a3ee00b5de67baf1e9ceaad638993c72a6ef579e7005` |
| `core/domain/predict/OrbitalPass.kt` | `9b939a688f8a903673b5e345935c81a9038741ca5146014e2282ee18a393fc4e` |
| `core/domain/predict/OrbitalPos.kt` | `193bff39efbb9d55bea455a98c8f3c27dadaffa4dc8078df2565c9e23aa9eabd` |
| `core/domain/utility/GeoConverter.kt` | `821ef4128e2458f55014e8f42bb504b388959f075c2a84bc36b1db2b4f59f890` |
| `LICENSE` | `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986` |

SGP4/SDP4 in pure Kotlin, with no dependency beyond `kotlin.math`. Vendored
rather than depended on because Look4Sat publishes an app, not a library: there
is no artefact to resolve.

`utility/GeoConverter.kt` is here only because `predict/` imports
`toDegrees()` and `toRadians()` from it. It arrives whole rather than reduced to
those two functions, under the never-edited rule above; the rest of it is
unused.

GPL-3.0 into a GPL-3.0 app needs no permission, only compliance: the copyright
headers and licence stay intact, and Look4Sat is credited in the app itself
(`sh.arek.gloaming.ui.Credits`). Unlike MIT, GPL-3 does not ask for its text in
every copy - it asks for the corresponding source, which the source link on the
credits screen provides.

The package contains no pass search. It computes a position, an elevation and an
eclipse state for one instant; finding when a pass starts and ends is the app's
own code, in `sh.arek.gloaming.satellites`.

To upgrade, replace the files from the new tag, update the version, tag, commit
and checksums above, and run the suite - including the Vallado verification
vectors, which are what would actually catch a propagator regression.

```sh
BASE=https://raw.githubusercontent.com/rt-bishop/Look4Sat/<commit>
curl -sL -o app/src/main/vendor/com/rtbishop/look4sat/core/domain/predict/OrbitalData.kt \
    $BASE/core/domain/src/main/java/com/rtbishop/look4sat/core/domain/predict/OrbitalData.kt
sha256sum app/src/main/vendor/com/rtbishop/look4sat/core/domain/predict/OrbitalData.kt
```
