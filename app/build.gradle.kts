import java.util.Properties
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    // AGP 9 has built-in Kotlin support, so no kotlin-android plugin is applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Signing material for the release build.
 *
 * Read from a gitignored `keystore.properties` beside this build, or from the
 * environment for a machine that has no file to read. Never from a committed
 * file: the passphrase to the one key every future update must be signed with
 * does not belong in a repository. `docs/prerequisites.md` has the keystore,
 * the property names and the backup discipline.
 */
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingMaterial(property: String, variable: String): String? =
    (signingProperties.getProperty(property) ?: providers.environmentVariable(variable).orNull)
        ?.takeIf { it.isNotBlank() }

val releaseKeystore = signingMaterial("storeFile", "GLOAMING_KEYSTORE")
val releaseKeystorePassword = signingMaterial("storePassword", "GLOAMING_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingMaterial("keyAlias", "GLOAMING_KEY_ALIAS") ?: "gloaming"
val releaseKeyPassword = signingMaterial("keyPassword", "GLOAMING_KEY_PASSWORD")
    ?: releaseKeystorePassword

/**
 * Run git and hand back what it said, or null if it could not be asked.
 *
 * Through `providers.exec` rather than a process started by hand, because that
 * is the one form the configuration cache understands: it re-runs the command
 * when it checks a cached entry, so a build after a commit reconfigures instead
 * of baking yesterday's answer into today's APK. A machine with no git, or a
 * source archive with no checkout in it, is a build that knows no commit rather
 * than a build that fails - hence the catch, which covers a process that could
 * not be started at all.
 */
fun gitOutput(vararg arguments: String): String? = try {
    val git = providers.exec {
        commandLine("git", *arguments)
        isIgnoreExitValue = true
    }
    if (git.result.get().exitValue != 0) null else git.standardOutput.asText.get().trim()
} catch (_: Exception) {
    null
}

/**
 * Which commit this APK was built from, for the line the settings screen shows.
 *
 * `versionName` does not move between releases, so it identifies a release and
 * nothing else: every test build in the month before one carries the same name
 * as the last, and a report quoting it says only which release the build was
 * somewhere after. The commit is what identifies the source, and the phone is
 * the only place the person making the report can read it.
 *
 * Modified is repo-wide, as `./go release` reports it: the claim is that the
 * build corresponds to no commit and cannot be rebuilt, and that is the
 * conservative reading of a tree with anything uncommitted in it.
 */
val buildCommit = gitOutput("rev-parse", "--short", "HEAD").orEmpty()
val buildModified = !gitOutput("status", "--porcelain").isNullOrBlank()

/**
 * The version code, which Android uses for one thing only: deciding whether an
 * APK is newer than the one installed. It must increase with every published
 * build, and nothing else about it is meaningful.
 *
 * The commit count, because a rolling development release publishes from every
 * push and a hand-edited number would have to be remembered every time. On a
 * linear `main` this only ever goes up, it needs no state outside the
 * repository, and two builds of the same commit agree on it - which the
 * reproducible builds F-Droid's main repo wants are going to need.
 *
 * Null on a machine that cannot be asked, exactly as the commit is. A debug
 * build carries on with 1, because nothing upgrades a debug build; the release
 * build refuses, below, because the wrong number there is an APK that either
 * cannot be installed over its predecessor or silently replaces something
 * newer.
 */
val buildNumber = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()

android {
    namespace = "sh.arek.gloaming"
    compileSdk = 37

    defaultConfig {
        applicationId = "sh.arek.gloaming"
        minSdk = 24
        targetSdk = 37
        versionCode = buildNumber ?: 1
        versionName = "1.0.0-rc3"
        buildConfigField("String", "GIT_COMMIT", "\"$buildCommit\"")
        buildConfigField("boolean", "GIT_MODIFIED", "$buildModified")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The harness package seeds device state for tools/bfu-check.sh and
        // deliberately leaves it behind, so it is not part of the test run.
        testInstrumentationRunnerArguments["notPackage"] = "sh.arek.gloaming.harness"
    }

    signingConfigs {
        create("release") {
            // Absent on a machine that holds no signing material, which is
            // every machine but the one that cuts releases. The check below
            // stops such a build before it packages anything.
            releaseKeystore?.let { storeFile = rootProject.file(it) }
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8. Measured on this app: 8.9 MB of APK down to 1.8, 23.6 MB of
            // dex down to 2.9, and two dex files down to one - which is a cold
            // background wake with a fraction of the class loading to do, on
            // the code path the whole app exists for. proguard-rules.pro holds
            // the single rule it needs, and why the last 90 KB is deliberately
            // left on the table.
            optimization {
                enable = true
            }
            proguardFiles("proguard-rules.pro")
        }
        // The release build with the debug key in place of the project's, so
        // the instrumented suite can run against the code R8 actually ships.
        // Nothing releases it and `./go install` does not touch it; it is built
        // by `./go device-test --minified` and nothing else. Without it the
        // shipped APK would be the one build nothing ever runs, and a keep rule
        // R8 turns out to need would first be heard about from a stranger whose
        // aurora alert never arrived.
        create("minified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            // Two more rule files on top of the release build's own, and
            // neither of them is about the app: one for what the shrunk app
            // must keep for the instrumentation runner to start, one for the
            // instrumentation APK itself. A rule the app needs belongs in
            // proguard-rules.pro, where it is part of what ships.
            proguardFiles("minified-proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
        }
    }
    // `debug` unless `./go device-test --minified` asks for the other one.
    // Compose's test rule needs an activity that only its own manifest declares,
    // so whichever build type the suite runs against has to carry a dependency
    // no released build may: hence a third build type rather than testing
    // `release` directly.
    testBuildType = providers.gradleProperty("gloaming.testBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        // For the version and the commit the settings screen reports. The app
        // is installed from an APK rather than a store, so the screen is the
        // only place that can say which build a phone is on.
        buildConfig = true
    }
    // Vendored third-party source, kept in its own tree so it is never mistaken
    // for the project's own and can be replaced wholesale on an upgrade. See
    // app/src/main/vendor/README.md.
    sourceSets.getByName("main") {
        kotlin.directories.add("src/main/vendor")
    }
    // The scenario library, in a tree of its own because both test source sets
    // need it and Gradle gives them nothing in common. It is fixtures only -
    // no JUnit, no Android - so that the JVM suite and the instrumented suite
    // describe the same nights rather than each inventing their own.
    sourceSets.getByName("test") {
        kotlin.directories.add("src/scenarios/java")
    }
    sourceSets.getByName("androidTest") {
        kotlin.directories.add("src/scenarios/java")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // The activity Compose's test rule launches into. It is a test manifest in
    // a shipping build type, which is exactly why `minified` is not `release`.
    "minifiedImplementation"(libs.androidx.ui.test.manifest)
}

/**
 * Refuse to package a release the project's own key did not sign.
 *
 * An APK signed with the debug key, or with none, installs and runs and looks
 * like a build that worked. What it cannot do is take an update from the real
 * key: Android rejects that outright, so every copy installed from it has to be
 * uninstalled by hand. Off-Play there is no Play App Signing to catch this on
 * the project's behalf, and the mistake is only visible months later.
 *
 * AGP stops the build on its own account, at `validateSigningRelease`, but says
 * only "Keystore file not set" - which is the one thing the reader already
 * knows. This runs at the same point to say where the key and its passphrase
 * are meant to come from.
 */
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
    val keystore = releaseKeystore
    val passphrase = releaseKeystorePassword
    val number = buildNumber
    doFirst {
        check(keystore != null && passphrase != null) {
            "No signing material for the release build. Put storeFile and " +
                "storePassword in keystore.properties, or set GLOAMING_KEYSTORE " +
                "and GLOAMING_KEYSTORE_PASSWORD. See docs/prerequisites.md."
        }
        // Falling back to 1 here would publish an APK that no installed copy
        // will accept as an update, and there is no way to correct that after
        // the fact for anyone who already has it.
        check(number != null) {
            "No version code for the release build: git could not be asked for " +
                "the commit count. Release builds must come from a checkout, " +
                "not from a source archive."
        }
    }
}

/**
 * Say what a failing test actually asserted.
 *
 * The default is the test's name, the word FAILED and a pointer to an HTML
 * report - so the one thing a reader wants, the assertion message, is the one
 * thing not on the screen. What happens instead is that somebody goes digging
 * in `build/test-results` for the XML and unescapes it by hand.
 */
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }

    // The vendored tree is read at run time by VendorChecksumsTest, not
    // compiled into it, so Gradle has no other way to know it matters here. Its
    // absence had the test skipped in exactly the case it exists for: an edit
    // that changes no behaviour - a reflow, a stray newline - leaves the
    // bytecode identical, the task up to date, and the check unrun.
    inputs.dir("src/main/vendor")
        .withPropertyName("vendoredSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
