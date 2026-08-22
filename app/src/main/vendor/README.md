# Vendored source

Third-party code copied into this repository rather than resolved as a
dependency. It compiles as part of `:app` through a source set declared in
`app/build.gradle.kts`, and is kept in its own tree so it is never mistaken for
the project's own code.

**Nothing here is edited.** Every file is byte-identical to its upstream
release, so an upgrade is a straight replacement rather than a merge. Anything
the app needs on top of a vendored library is written as a wrapper in
`sh.arek.gloaming.*`.

## astronomy-engine 2.1.19

| | |
|---|---|
| Upstream | <https://github.com/cosinekitty/astronomy> |
| Author | Don Cross |
| Licence | MIT (`io/github/cosinekitty/astronomy/LICENSE`) |
| Tag | `v2.1.19` |
| Source | `source/kotlin/src/main/kotlin/io/github/cosinekitty/astronomy/astronomy.kt` |
| SHA-256 | `b698f7564fdbd6db78fa5777778c2538b377c1506470dfaa5669c57399c81c9a` |

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
