# 13. R8 on, with the app's own classes kept

Date: 2026-08-30

## Status

Accepted.

## Context

The release build type has carried `optimization { enable = false }` since the
Compose scaffold in 77f482b. Nothing decided that; it is the value Android
Studio writes into a new project. Preparing the first signed release made it a
question that had to be answered rather than inherited.

Measured on this app, three ways:

| | APK | dex | dex files |
|---|---|---|---|
| No shrinking | 8.9 MB | 23.6 MB | 2 |
| R8, nothing kept | 1.7 MB | 2.7 MB | 1 |
| R8, `sh.arek.gloaming.**` kept | 1.8 MB | 2.9 MB | 1 |

The middle row is the obvious answer and is wrong for this project, which the
first attempt at running the suite against it demonstrated: the run collapsed
after 55 tests with **50 of them failed**, on `NoClassDefFoundError` for the
app's own classes and `NoSuchFieldError` for their fields.

That is not a missing keep rule. The instrumented suite is white-box by design.
The questions worth asking of this app - does a poll before first unlock write
to device-encrypted storage, does the journal survive a reboot, does the widget
read what the poller wrote - are about internals that no public surface exposes,
so the tests call `BootAware`, `Notes` and `CalendarWriter` directly. Removing
and renaming unreachable internals is precisely R8's job. Keeping enough of them
for the suite to run means keeping most of the app, at which point the thing
being verified has been switched off in order to verify it.

So the middle row buys its last 90 KB by making the shipped build the one build
no automated test can ever run against. Two further facts bear on that, both
particular to this project rather than general:

- **There is no crash reporting and never will be.** Nothing is reported off a
  user's phone by design (ADR-0003). The only stack trace this project will ever
  see is one somebody copies out of `./go log` or a crash dialogue. Against an
  obfuscated build that is a page of single letters, useless without the mapping
  file for that exact release, which the person reporting it does not have.
- **The app's own code is a small fraction of the APK.** The bulk is Compose,
  OkHttp, kotlinx.serialization and the vendored propagator and astronomy
  engine. Shrinking those is where the win is.

## Decision

R8 is on for the release build, with one keep rule:

```
-keep class sh.arek.gloaming.** { *; }
```

Everything else is shrunk and obfuscated normally. A third build type,
`minified`, is the release build under the debug key, and
`./go device-test --minified` runs the whole instrumented suite against it. It
is a separate build type rather than `release` itself because Compose's test rule
needs an activity that only `ui-test-manifest` declares, and no released build
may carry a test manifest.

## Consequences

The APK is a fifth of its former size and loads one dex file instead of two,
which is the cold background wake this whole app is built around. About 90 KB of
the available saving is deliberately left on the table, and buys two things:

- **The shipped bytecode is the tested bytecode.** All 609 instrumented tests
  run against R8's output before a release, not against a debug build that
  resembles it.
- **A stack trace from a stranger's phone is readable.** Without a mapping file,
  and there is no channel by which one could be collected anyway.

The costs, all accepted:

- The app's own code is not inlined or devirtualised. Immaterial next to the
  class loading, and unmeasurable against a poll that spends its time in I/O.
- `minified` needs keep rules of its own, in `app/minified-proguard-rules.pro`.
  An instrumentation APK borrows its libraries from the app it tests rather than
  carrying them, and androidx.test and compose-ui-test call a great deal the app
  never does, so shrinking the app takes the harness apart from underneath. The
  answer is to keep whole the frameworks the test infrastructure is written
  against - Compose, lifecycle, coroutines, the Kotlin standard library,
  androidx.tracing. None of them is about the app, and none of them may become
  about the app: a rule the app needs belongs in `app/proguard-rules.pro` where
  it is part of what ships. The tested build is therefore larger than the
  released one, and every R8 failure mode that could touch this app is still
  live in it - the app's own classes, kotlinx.serialization, OkHttp, the
  vendored code and the resource shrinker all behave as they do in a release.
- Running the suite against `minified` costs a full R8 pass on top of an
  already long run, so it is a pre-release step rather than part of `testall`.
- Class names are visible in the shipped APK. This is a GPL-3.0 project whose
  source is public; there is nothing to hide and obfuscation was never the point.

Revisit if the size ever becomes the binding constraint, or if the suite stops
being white-box - neither of which is in prospect.
