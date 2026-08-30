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

android {
    namespace = "sh.arek.gloaming"
    compileSdk = 37

    defaultConfig {
        applicationId = "sh.arek.gloaming"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

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
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
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
    doFirst {
        check(keystore != null && passphrase != null) {
            "No signing material for the release build. Put storeFile and " +
                "storePassword in keystore.properties, or set GLOAMING_KEYSTORE " +
                "and GLOAMING_KEYSTORE_PASSWORD. See docs/prerequisites.md."
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
