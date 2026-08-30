import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    // AGP 9 has built-in Kotlin support, so no kotlin-android plugin is applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

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

    buildTypes {
        release {
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
}
