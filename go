#!/usr/bin/env bash
#
# One way in to building, testing and installing Gloaming.
#
# Underneath are the same commands the README documents. What this adds is the
# part that is easy to forget and expensive to get wrong: holding the phone
# awake for an instrumented run, leaving the app installed when it finishes,
# and turning a class name into the whole incantation the runner wants.
#
# Usage: ./go <command> [options]
#
#   test [name]        Unit tests on the JVM, no device needed. A bare word
#                      matches loosely, so `./go test Scenarios` runs anything
#                      called *Scenarios*; a name with a dot or a wildcard in it
#                      goes to Gradle as it stands.
#   device-test [name] Instrumented tests on a connected phone. A bare class
#                      name is resolved to its package first, so
#                      `./go device-test ScreensTest` is enough.
#   scenarios          The catalogue of nights in app/src/scenarios, checked on
#                      the JVM. With --device, drawn through the real screens
#                      and posted as real notifications on the phone.
#   apk                Build the debug APK and say where it landed.
#   release            Build the release APK. It comes out unsigned: signing is
#                      deliberately not wired into the build yet, see
#                      docs/prerequisites.md.
#   install            Install the debug APK. With --test, the instrumentation
#                      APK as well, which tools/bfu-check.sh --seed needs.
#   check              Everything that runs without a phone: the app's unit tests,
#                      its instrumented sources compiled but not run, and both
#                      Node projects' tests, typechecks and builds.
#   clean              Delete the build output.
#
# Options:
#
#   --serial S    target a particular device (or set ANDROID_SERIAL)
#   --device      run `scenarios` on the phone rather than on the JVM
#   --test        install the instrumentation APK too (`install` only)
#   --uninstall   let an instrumented run uninstall the app when it finishes,
#                 which is Gradle's default and takes the app's settings and its
#                 battery-optimisation exemption with it. Without this the app
#                 is left installed, because a phone set up to watch something
#                 overnight is quietly back to a fresh install otherwise.
#
# Whatever a device command changes is put back afterwards, including after an
# interrupt: the screen timeout and the stay-awake setting.
#
# The two harnesses for overnight delivery are not wrapped here - they have
# their own options and their own reasons. See tools/bfu-check.sh (the
# before-first-unlock path) and tools/doze-check.sh (the interval polls really
# land at).
#
set -euo pipefail

cd "$(dirname "$0")"

SERIAL="${ANDROID_SERIAL:-}"
COMMAND=""
TARGET=""
ON_DEVICE=0
WITH_TEST=0
LEAVE_INSTALLED=1

# Device settings as they were before a run, so they can be put back. Empty
# means nothing was changed and there is nothing to restore.
SAVED_TIMEOUT=""
SAVED_STAY_ON=""
KEEPER_PID=""

die() {
    echo "$*" >&2
    exit 1
}

usage() {
    # Everything between the shebang and the first line of code, so adding a
    # command cannot leave the help text behind.
    sed -e '1d' -e '/^[^#]/,$d' "$0" | sed 's/^# \?//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help|help) usage; exit 0 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --device) ON_DEVICE=1; shift ;;
        --test) WITH_TEST=1; shift ;;
        --uninstall) LEAVE_INSTALLED=0; shift ;;
        -*) die "unknown option: $1" ;;
        *)
            if [ -z "$COMMAND" ]; then COMMAND="$1"; else TARGET="$1"; fi
            shift
            ;;
    esac
done

if [ -z "$COMMAND" ]; then
    usage
    exit 0
fi

adb() {
    if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi
}

require_device() {
    command -v adb >/dev/null 2>&1 || die "adb is not on PATH"
    local attached
    attached=$(command adb devices | sed -e '1d' -e '/^$/d' | grep -c "device$" || true)
    if [ -z "$SERIAL" ]; then
        [ "$attached" -gt 0 ] || die "no device: plug a phone in with USB debugging on, or pass --serial"
        [ "$attached" -lt 2 ] || die "$attached devices attached: say which with --serial, or set ANDROID_SERIAL"
        return 0
    fi
    # The Android Gradle plugin takes the device from the environment, so a
    # --serial here reaches the connected-test task as well as our own adb.
    export ANDROID_SERIAL="$SERIAL"
}

screen_state() {
    # ON_UNLOCKED, ON_LOCKED, OFF_UNLOCKED or OFF_LOCKED. An odd place to read
    # it from, but it is the one line that reports the screen and the keyguard
    # together: dumpsys power knows nothing about the keyguard, and the window
    # manager's keyguard fields have been renamed between releases. Empty on a
    # device with no NFC service, which the caller treats as "cannot tell".
    #
    # The dump is captured before it is matched rather than piped into a match
    # that stops early: closing the pipe on `dumpsys` kills it with SIGPIPE, and
    # under `set -o pipefail` that turns a successful match into a failed
    # pipeline.
    local dump
    dump=$(adb shell dumpsys nfc 2>/dev/null || true)
    printf '%s' "$dump" | sed -n 's/.*mScreenState=\([A-Z_]*\).*/\1/p' | tr -d '\r' | tail -1
}

ensure_awake_and_unlocked() {
    local state
    state=$(screen_state)
    if [ -z "$state" ]; then
        echo "note: cannot read the screen state from this device; carrying on"
        return 0
    fi
    case "$state" in
        OFF_*) adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true ;;
    esac
    # A keyguard with nothing behind it goes away when asked. One with a PIN
    # behind it does not, and no script can do anything about that.
    case "$(screen_state)" in
        *_LOCKED) adb shell wm dismiss-keyguard >/dev/null 2>&1 || true ;;
    esac
    local waited=0
    while [ "$waited" -lt 10 ]; do
        state=$(screen_state)
        if [ "$state" = "ON_UNLOCKED" ]; then
            return 0
        fi
        sleep 0.5
        waited=$((waited + 1))
    done
    die "the phone is $state, and the tests need it awake and unlocked. An
activity launched behind the keyguard reaches resumed and pauses again without
ever drawing, so the Compose harness finds no semantics tree and every
assertion fails with \"No compose hierarchies found in the app\" - which reads
like a broken test setup and is not. Unlock it and run this again."
}

hold_screen_awake() {
    local timeout stay_on
    timeout=$(adb shell settings get system screen_off_timeout 2>/dev/null | tr -d '\r' || true)
    stay_on=$(adb shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r' || true)

    # Only touch a setting whose old value came back, so there is never a value
    # left behind that this script invented.
    case "$timeout" in
        ''|null) ;;
        *) SAVED_TIMEOUT="$timeout"; adb shell settings put system screen_off_timeout 1800000 >/dev/null ;;
    esac
    case "$stay_on" in
        ''|null) ;;
        *) SAVED_STAY_ON="$stay_on"; adb shell settings put global stay_on_while_plugged_in 7 >/dev/null ;;
    esac

    # Raising the timeout is not enough on its own. stay_on_while_plugged_in
    # stops applying the moment the battery reaches a charge limit - GrapheneOS
    # holds at 80 per cent, and a phone that has stopped charging is not
    # "plugged in" as far as the setting is concerned - so on a long run the
    # ordinary timeout takes over part-way through and every test from that
    # point fails as though the code were broken. Waking the screen on a timer
    # is the part that holds regardless. KEYCODE_WAKEUP is a no-op on a screen
    # that is already on, so nothing here reaches the app under test.
    #
    # It counts short sleeps rather than sleeping for the whole interval, and
    # writes nowhere. Killing the loop does not kill the `sleep` it is sitting
    # in - that is a child of its own - and a long one left holding the
    # inherited stdout keeps any pipe this script is part of open long after it
    # has finished, so `./go device-test | tail` hangs for minutes on a run that
    # took seconds.
    {
        keeper_ticks=0
        while true; do
            sleep 5
            keeper_ticks=$((keeper_ticks + 1))
            if [ "$keeper_ticks" -ge 48 ]; then
                adb shell input keyevent KEYCODE_WAKEUP || true
                keeper_ticks=0
            fi
        done
    } >/dev/null 2>&1 &
    KEEPER_PID=$!

    echo "holding the screen awake for the run; settings put back at the end"
}

restore_device() {
    if [ -n "$KEEPER_PID" ]; then
        kill "$KEEPER_PID" 2>/dev/null || true
        KEEPER_PID=""
    fi
    if [ -n "$SAVED_TIMEOUT" ]; then
        adb shell settings put system screen_off_timeout "$SAVED_TIMEOUT" >/dev/null 2>&1 || true
        SAVED_TIMEOUT=""
    fi
    if [ -n "$SAVED_STAY_ON" ]; then
        adb shell settings put global stay_on_while_plugged_in "$SAVED_STAY_ON" >/dev/null 2>&1 || true
        SAVED_STAY_ON=""
    fi
}

trap restore_device EXIT
trap 'exit 130' INT TERM

resolve_test_class() {
    # The instrumentation runner filters by whole class name and takes no
    # wildcards, so a bare name is looked up and given its package.
    local name="$1" file package
    case "$name" in
        *.*) printf '%s' "$name"; return 0 ;;
    esac
    file=$(find app/src/androidTest -name "${name}.kt" -print -quit)
    [ -n "$file" ] || die "no instrumented test called $name under app/src/androidTest"
    package=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file" | tail -1)
    [ -n "$package" ] || die "$file declares no package"
    printf '%s.%s' "$package" "$name"
}

run_on_device() {
    local filter="${1:-}"
    require_device
    ensure_awake_and_unlocked
    hold_screen_awake
    local args=(:app:connectedDebugAndroidTest)
    if [ "$LEAVE_INSTALLED" -eq 1 ]; then
        args+=(-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true)
    fi
    if [ -n "$filter" ]; then
        args+=("$filter")
    fi
    ./gradlew "${args[@]}"
}

node_project() {
    local directory="$1"
    [ -d "$directory/node_modules" ] || die "$directory: dependencies are not installed - cd $directory && npm install"
    echo
    echo "== $directory =="
    # The build runs too, where there is one. Partly to catch what the
    # typechecker cannot - a dependency that resolves for `tsc` but not for the
    # bundler, or syntax past the build target - but mostly so that what gets
    # copied to the server is what was just tested: `dist/` is not in the tree,
    # and nothing else stops a check passing and a stale bundle being shipped a
    # minute later. `--if-present` is for the Worker, which is deployed from
    # source and has no build of its own.
    (cd "$directory" && npm test && npm run typecheck && npm run build --if-present)
}

case "$COMMAND" in
    test)
        gradle_args=(:app:testDebugUnitTest)
        if [ -n "$TARGET" ]; then
            case "$TARGET" in
                *[.*]*) gradle_args+=(--tests "$TARGET") ;;
                *) gradle_args+=(--tests "*${TARGET}*") ;;
            esac
        fi
        ./gradlew "${gradle_args[@]}"
        ;;

    device-test)
        if [ -n "$TARGET" ]; then
            run_on_device "-Pandroid.testInstrumentationRunnerArguments.class=$(resolve_test_class "$TARGET")"
        else
            run_on_device
        fi
        ;;

    scenarios)
        if [ "$ON_DEVICE" -eq 1 ]; then
            # The whole package, so the screens each night draws and the
            # notification each one would post are both exercised.
            run_on_device "-Pandroid.testInstrumentationRunnerArguments.package=sh.arek.gloaming.scenarios"
        else
            ./gradlew :app:testDebugUnitTest --tests '*ScenariosTest'
        fi
        ;;

    apk)
        ./gradlew :app:assembleDebug
        echo
        echo "APK: app/build/outputs/apk/debug/app-debug.apk"
        ;;

    release)
        ./gradlew :app:assembleRelease
        built=$(find app/build/outputs/apk/release -name '*.apk' -print -quit 2>/dev/null || true)
        echo
        if [ -n "$built" ]; then
            echo "APK: $built"
        fi
        echo "Unsigned, and it will not install until it is signed. Wiring the"
        echo "release key into the build is deliberately left until there is"
        echo "something to release: see docs/prerequisites.md."
        ;;

    install)
        require_device
        gradle_args=(:app:installDebug)
        if [ "$WITH_TEST" -eq 1 ]; then
            gradle_args+=(:app:installDebugAndroidTest)
        fi
        ./gradlew "${gradle_args[@]}"
        ;;

    check)
        # The instrumented sources are compiled but not run: compiling needs no
        # phone, and a rename in main that breaks them would otherwise stay
        # invisible until the next time one is plugged in - which is the most
        # expensive place to find out, and the least likely to be a moment when
        # anybody wants to be fixing a compile error.
        ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
        node_project worker
        node_project fetcher
        ;;

    clean)
        ./gradlew clean
        ;;

    *)
        die "unknown command: $COMMAND (try ./go help)"
        ;;
esac
