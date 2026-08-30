# R8 rules for the release build.
#
# One rule, and the reasoning for it matters more than the rule.
#
# R8 with nothing kept takes this app from 8.9 MB to 1.7 and from two dex files
# to one. This rule gives about 90 KB of that back - 1.8 MB rather than 1.7 -
# for two things worth a great deal more than 90 KB.
#
# The first is that the shipped build becomes testable. The instrumented suite
# is white-box: it calls BootAware, Notes and CalendarWriter directly, because
# the questions worth asking of this app - does a poll before first unlock write
# to device-encrypted storage, does the journal survive - are about internals no
# public surface exposes. Shrinking those internals away is exactly what R8 is
# for, so a fully shrunk build failed 50 of 55 tests on missing classes and
# missing fields. Keeping the app's own code makes `./go device-test --minified`
# run the 609 tests against the same bytecode a release ships, which is the only
# honest way to enable R8 at all in an app with no crash reporting.
#
# The second is that a stack trace stays readable. Nothing is reported from a
# user's phone by design, so the only bug report this project will ever see is
# one somebody copies out of `./go log` or a crash dialogue. Against an
# obfuscated build that is a page of single letters, useless without the mapping
# file for that exact release, which the person reporting it does not have.
#
# Everything else - Compose, OkHttp, kotlinx.serialization, the vendored
# propagator and astronomy engine - is shrunk normally, and that is where the
# bulk was. The app's own code is a fraction of the APK.
-keep class sh.arek.gloaming.** { *; }
