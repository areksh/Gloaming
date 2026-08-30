# R8 rules for the `minified` build type only, which is the release build under
# the debug key so that the suite can run against the code R8 ships. Nothing
# here reaches a released APK, and nothing here may be about the app: a rule the
# app needs belongs in proguard-rules.pro, where it is part of what ships.
#
# What lands here is the cost of the arrangement, and it has one shape. An
# instrumentation APK does not carry its own copy of the libraries the app it
# tests already has; it borrows them. Shrinking the app removes whatever the app
# itself does not call, and androidx.test and compose-ui-test call plenty the
# app never does - so the runner dies in onCreate, or the first Compose test
# dies attaching to the hierarchy, on a class that was quite correctly removed.
# It surfaced one library per run, and always the same two families: androidx
# and Kotlin. androidx.tracing from the runner's onCreate, the standard library
# from a constructor in androidx.test.platform, coroutines from Compose's
# idling-resource registry, then Compose's platform package, then Compose's
# runtime, then androidx.collection. Chasing them one at a time is a build and a
# thirty-minute run per class, for no more certainty than naming the families.
#
# So the rule is the families rather than the incidents. What it does not
# include is deliberate. `kotlinx.serialization` is left to be shrunk, because
# generated serialisers are the one part of this app with a real R8 risk and
# the point of the exercise is to run that risk here rather than on a phone in
# somebody's pocket. So are OkHttp and the vendored propagator and astronomy
# engine, and so are the resources - all of them shrunk in this build exactly as
# a release shrinks them. The app's own code is kept by proguard-rules.pro, in
# this build and the released one alike, so it is dexed and packaged identically
# in both.
#
# The tested APK is therefore bigger than the released one. It is not a smaller
# claim: what R8 could plausibly break in this app is still being broken here if
# it is going to break at all.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.** { *; }
