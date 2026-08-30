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
#   status             What state the phone is in: whether the app is installed
#                      and has been opened, whether it is exempt from battery
#                      optimisation, what standby bucket it is in, whether an
#                      alarm is pending and when a poll last completed. Reads
#                      only; changes nothing. This is tools/bfu-check.sh
#                      --check-only, which is the part of that harness worth
#                      having to hand rather than reserved for a soak.
#   log                The app's own log, off the phone. It keeps one because
#                      logcat is a ring buffer holding about twelve minutes and
#                      the hours this app works in are the ones nobody is
#                      watching. With --live, logcat instead, followed and
#                      filtered to the app's uid and its tags - the tags are
#                      not ours alone, the system logs under some of them too.
#   apk                Build the debug APK and say where it landed.
#   release            Build the signed, shrunk release APK, and say whether the
#                      key that signed it is the one README.md publishes. It
#                      refuses to build without the project's key rather than
#                      emitting something that installs and can then never be
#                      updated: put the keystore and its passphrase in
#                      keystore.properties, or in the environment. See
#                      docs/prerequisites.md.
#   install            Install the debug APK. With --test, the instrumentation
#                      APK as well, which tools/bfu-check.sh --seed needs.
#   setup              Install what the two Node projects need, from their
#                      lockfiles, so that `check` can run. Everything else -
#                      JDK 21, the Android SDK - is in docs/prerequisites.md.
#   check              Everything that runs without a phone: the app's unit tests,
#                      its instrumented sources compiled but not run, and both
#                      Node projects' tests, typechecks and builds.
#   testall            Everything there is: `check`, and then the whole
#                      instrumented suite - the catalogue of nights with it - on
#                      the phone, if one is plugged in. If none is, it says so at
#                      the end rather than reporting a pass for having run less.
#   clean              Delete the build output.
#
# Options:
#
#   --serial S    target a particular device (or set ANDROID_SERIAL)
#   --device      run `scenarios` on the phone rather than on the JVM
#   --live        follow logcat rather than printing the app's own log (`log`)
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
# their own options and their own reasons, and each takes the phone out of use
# for as long as it runs. See tools/bfu-check.sh (the before-first-unlock path)
# and tools/doze-check.sh (the interval polls really land at). The one part with
# none of those reasons is wrapped: `status` is bfu-check's read-only mode.
#
set -euo pipefail

cd "$(dirname "$0")"

PACKAGE="sh.arek.gloaming"
# Device-encrypted storage, because a poll before first unlock has to be able to
# write to it. sh.arek.gloaming.data.Journal and BootAware.CACHE_DIRECTORY.
JOURNAL="/data/user_de/0/${PACKAGE}/files/sky/gloaming.log"

SERIAL="${ANDROID_SERIAL:-}"
COMMAND=""
TARGET=""
ON_DEVICE=0
WITH_TEST=0
LEAVE_INSTALLED=1
LIVE=0

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
        --live) LIVE=1; shift ;;
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

attached_devices() {
    # How many phones adb can see. Unlike require_device this decides nothing
    # and stops nothing: `testall` runs with a phone or without one, and needs
    # to know which before it starts rather than to be halted by the answer.
    command -v adb >/dev/null 2>&1 || { echo 0; return 0; }
    command adb devices | sed -e '1d' -e '/^$/d' | grep -c "device$" || true
}

as_app() {
    # As the app's own uid, which is the only one that can read its files. The
    # inner `sh -c` is not decoration: without it the shell user expands the
    # glob, cannot stat the app's directory, and the pattern arrives unexpanded
    # so every read looks like an empty file.
    adb shell "run-as $PACKAGE sh -c '$1'" 2>/dev/null | tr -d '\r' || true
}

package_uid() {
    # The app's uid, or nothing if it is not installed - so this answers both
    # questions in one call.
    #
    # `pm list packages` is given the name to filter on rather than being piped
    # through a match: the full list runs to hundreds of lines and a reader that
    # stops early closes the pipe on it, which under pipefail turns a successful
    # match into a failed pipeline. That filter is a substring match, so
    # sh.arek.gloaming.test comes back too and the space before `uid:` is what
    # tells the two apart. A phone with a second user answers with two uids,
    # comma-separated, which is the form `logcat --uid` wants anyway.
    local listing escaped
    escaped=${PACKAGE//./\\.}
    listing=$(adb shell pm list packages -U "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
    printf '%s\n' "$listing" | sed -n "s/^package:${escaped} uid://p"
}

app_log_tags() {
    # Read out of the source, so a tag added tomorrow is followed tomorrow
    # rather than the day somebody notices this list is short. AndroidRuntime is
    # not one of ours and is here anyway: a crash is logged under it, and a
    # filter that hides the crash is worse than no filter.
    {
        grep -rh 'const val TAG = "' app/src/main/java --include='*.kt' \
            | sed -n 's/.*const val TAG = "\([^"]*\)".*/\1/p'
        echo AndroidRuntime
    } | sort -u
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
    # Once per run, whatever calls it. A second call would read the timeout
    # this one has already put there and save 1800000 as the value to restore,
    # and would overwrite the keeper's pid with a new one, leaving the first
    # looping over a screen nobody is going to switch off until the reboot.
    [ -z "$KEEPER_PID" ] || return 0

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

# Where apksigner lives, from whichever of the three places names the SDK.
find_apksigner() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f local.properties ]; then
        sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)
    fi
    [ -n "$sdk" ] || return 1
    find "$sdk/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null \
        | sort -V | tail -1 | grep . || return 1
}

# The fingerprint the README publishes, as 64 lowercase hex characters. It is
# written there as two colon-separated lines, for a reader with an APK and a
# question about where it came from.
published_fingerprint() {
    awk '/^SHA-256:/ { found = 1 } found { print; if (++n == 2) exit }' README.md \
        | tr -d ' \n' | sed 's/^SHA-256://' | tr -d ':' | tr 'A-Z' 'a-z'
}

# Say which key signed the APK that was just built, and whether it is the one
# the project publishes. A release signed with the wrong key installs, runs and
# looks entirely well - and can never be updated from the right one, so every
# copy of it has to be uninstalled by hand. There is no undoing that after it
# has been downloaded, which is why the comparison happens here rather than
# being left as an instruction someone remembers.
check_signature() {
    local apk="$1" signer expected actual
    signer=$(find_apksigner) || {
        echo "Signature unchecked: no apksigner found. Set ANDROID_HOME, or see"
        echo "the fingerprint in README.md and check it by hand."
        return 0
    }
    actual=$("$signer" verify --print-certs "$apk" 2>/dev/null \
        | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
    expected=$(published_fingerprint)
    if [ -z "$actual" ]; then
        echo "Signature unchecked: apksigner read no certificate out of it."
    elif [ "$actual" = "$expected" ]; then
        echo "Signed with the project's key: the fingerprint matches README.md."
    else
        echo "SIGNED WITH THE WRONG KEY. Do not publish this."
        echo "  README.md says: $expected"
        echo "  this APK says:  $actual"
        return 1
    fi
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

run_check() {
    # The instrumented sources are compiled but not run: compiling needs no
    # phone, and a rename in main that breaks them would otherwise stay
    # invisible until the next time one is plugged in - which is the most
    # expensive place to find out, and the least likely to be a moment when
    # anybody wants to be fixing a compile error.
    ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
    node_project worker
    node_project fetcher
}

node_project() {
    local directory="$1"
    [ -d "$directory/node_modules" ] || die "$directory: dependencies are not installed - run ./go setup"
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

    status)
        require_device
        # --serial as well as the exported ANDROID_SERIAL, so this does not
        # depend on which of the two the harness happens to read.
        status_args=(--check-only)
        if [ -n "$SERIAL" ]; then
            status_args+=(--serial "$SERIAL")
        fi
        tools/bfu-check.sh "${status_args[@]}"
        ;;

    log)
        require_device
        uid=$(package_uid)
        [ -n "$uid" ] || die "$PACKAGE is not installed: ./go install"

        if [ "$LIVE" -eq 1 ]; then
            tags=$(app_log_tags)
            echo "Following logcat for: $(printf '%s' "$tags" | tr '\n' ' ')"
            echo "This is the system's buffer, not the app's: it holds about twelve"
            echo "minutes, so anything older than that is already gone and ./go log"
            echo "without --live is the record that is not. Ctrl-C to stop."
            echo
            # Filtered by uid as well as by tag. Tags are not ours alone -
            # the system logs plenty under BootReceiver, and on this phone it
            # was the loudest thing on the screen - and the uid holds across
            # the short-lived processes a poll wakes up in, which a --pid
            # would not.
            #
            # Deliberately unquoted: each tag is its own filterspec argument.
            # shellcheck disable=SC2086
            adb logcat -b all --uid="$uid" -s $tags
            exit 0
        fi

        # An empty read has three quite different causes and one appearance, so
        # they are told apart rather than reported as "no log".
        [ "$(as_app 'echo ok')" = "ok" ] || die "cannot read as $PACKAGE. run-as works only on a
debuggable build, so this is a release APK: ./go install puts the debug one back."

        journal=$(as_app "cat $JOURNAL 2>/dev/null")
        if [ -n "$journal" ]; then
            printf '%s\n' "$journal"
        else
            echo "The app has no log on this phone."
            echo
            echo "Either nothing has been logged yet - a fresh install that has not"
            echo "polled - or log retention is switched off in the app's settings, in"
            echo "which case Notes writes to logcat and nowhere else. ./go log --live"
            echo "shows what is being logged right now either way."
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
            check_signature "$built"
        fi
        ;;

    install)
        require_device
        gradle_args=(:app:installDebug)
        if [ "$WITH_TEST" -eq 1 ]; then
            gradle_args+=(:app:installDebugAndroidTest)
        fi
        ./gradlew "${gradle_args[@]}"
        ;;

    setup)
        # From the lockfile rather than the manifest: a check that passes here
        # and fails on the server over a version nobody chose is the thing a
        # lockfile is for. `npm ci` refusing a lockfile that has drifted from
        # package.json is the same discipline, and says so plainly.
        for directory in worker fetcher; do
            echo
            echo "== $directory =="
            (cd "$directory" && npm ci)
        done
        echo
        echo "The Node projects are ready. Nothing else is installed by this:"
        echo "JDK 21 and the Android SDK with platform android-37.0 are yours to"
        echo "provide, and docs/prerequisites.md is the list."
        ;;

    check)
        run_check
        ;;

    testall)
        # Whether the phone is in is settled before anything runs, and the
        # screen taken in hand there and then. A locked phone is the one thing
        # no script can get past, and finding out in the first seconds beats
        # finding out after the unit tests and both Node projects have had
        # their minutes; holding the screen from here covers the whole run
        # rather than only the part that talks to the device.
        with_device=0
        if [ -n "$SERIAL" ]; then
            with_device=1
        else
            attached=$(attached_devices)
            [ "$attached" -lt 2 ] || die "$attached devices attached: say which with --serial, or set ANDROID_SERIAL"
            [ "$attached" -eq 0 ] || with_device=1
        fi
        if [ "$with_device" -eq 1 ]; then
            require_device
            ensure_awake_and_unlocked
            hold_screen_awake
        fi

        run_check

        if [ "$with_device" -eq 1 ]; then
            echo
            echo "== device =="
            # No filter, so the catalogue of nights is drawn and its
            # notifications posted as part of it and `scenarios --device` has
            # nothing left to add. The harness package stays out either way:
            # it seeds device state for tools/bfu-check.sh and deliberately
            # leaves it behind, and build.gradle.kts excludes it.
            run_on_device
        fi

        echo
        if [ "$with_device" -eq 1 ]; then
            echo "Ran everything: the unit tests, both Node projects, and the whole"
            echo "instrumented suite on the phone. The two overnight harnesses are not"
            echo "part of it - tools/bfu-check.sh and tools/doze-check.sh reboot the"
            echo "phone or wait out real Doze windows, and have their own options."
        else
            echo "No phone was plugged in, so this was ./go check and nothing more: the"
            echo "seams only a device can reach - the two preference stores, the manifest,"
            echo "notifications, the nights drawn on a real screen - were not covered."
        fi
        ;;

    clean)
        ./gradlew clean
        ;;

    *)
        die "unknown command: $COMMAND (try ./go help)"
        ;;
esac
