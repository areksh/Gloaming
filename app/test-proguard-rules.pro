# R8 rules for the instrumentation APK only, never for anything released.
#
# `minified` is the release build with the debug key, so that the suite can run
# against the code R8 ships (app/build.gradle.kts). Minifying the app that way
# also minifies the test APK built against it, and the test-only dependency tree
# reaches one class Android does not have.
#
# Error Prone's annotations, which arrive under androidx.test, declare a
# javax.lang.model type in an annotation that nothing on a phone ever reads.
# R8 stops on the dangling reference. Suppressing it is the rule R8 itself
# generates. The app's own R8 run needs no rules at all, and this file must not
# become the place that quietly stops being true.
-dontwarn javax.lang.model.element.Modifier
