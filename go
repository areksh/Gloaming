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
#                      `./go device-test ScreensTest` is enough. With --minified,
#                      against the shrunk build instead of the debug one, which
#                      is the only way the code R8 ships is ever run.
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
#   results [which]    The last test run, read out of the XML nobody wants to
#                      read: totals, and every failure grouped by what actually
#                      went wrong rather than listed once per test that hit it.
#                      `unit` or `device` picks one; the default is both.
#   verdicts           What the "was it worth it?" buttons have recorded, off
#                      the phone. This is the evidence a season of real nights
#                      exists to produce, and nothing else can see it:
#                      verdicts.json sits in device-encrypted storage, which no
#                      file manager shows and `adb pull` cannot reach. With
#                      --json, the file as it stands, to feed to something else.
#   apk                Build the debug APK and say where it landed.
#   release [version]  With a version - `./go release 1.0.0-rc2` - cut it: the
#                      whole gate first (check, the instrumented suite, that
#                      suite against R8's output, and both overnight harnesses,
#                      about an hour with the phone plugged in), then the
#                      version bump, the changelog, the tag, and the release
#                      published to Codeberg. It asks before anything leaves
#                      the machine, and the phone's own location and threshold
#                      are put back after the harnesses have seeded over them.
#                      Without a version, build the signed, shrunk release APK
#                      and say whether the key that signed it is the one
#                      README.md publishes. It refuses to build without the
#                      project's key rather than emitting something that
#                      installs and can then never be updated: put the keystore
#                      and its passphrase in keystore.properties, or in the
#                      environment. See docs/prerequisites.md.
#   push               Push main, then publish the tip as the one development
#                      release: the signed release APK, under a rolling `dev`
#                      tag that moves with every push, its notes listing every
#                      commit since the last version tag. Exactly one exists at
#                      a time, and it replaces itself. `./go check` runs first
#                      because the pre-push hook runs it, and a tree with
#                      uncommitted changes is refused - a published APK that
#                      corresponds to no commit cannot be rebuilt by anyone.
#                      With --no-release, push and stop there.
#   install            Install the debug APK. With --test, the instrumentation
#                      APK as well, which tools/bfu-check.sh --seed needs.
#   setup              Install what the two Node projects need, from their
#                      lockfiles, so that `check` can run, and point git at the
#                      repository's own hooks so that `check` runs before every
#                      push. Everything else - JDK 21, the Android SDK - is in
#                      docs/prerequisites.md.
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
#   --minified    run `device-test` against the shrunk build rather than the
#                 debug one (`device-test` only)
#   --json        print the verdicts file as it stands rather than a table
#                 (`verdicts` only)
#   --no-release  push without publishing a development release (`push` only)
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
# Beside the journal and device-encrypted for the same reason: a verdict pressed
# from a notification before the phone has been unlocked since boot still has to
# land. sh.arek.gloaming.data.FileVerdictLog.
VERDICTS="/data/user_de/0/${PACKAGE}/files/sky/verdicts.json"
# Where the settings mirror a locked wake reads lives. The release gate seeds a
# short poll interval to make the overnight harnesses measurable in minutes, and
# has to put the phone's own settings back afterwards.
DE_PREFS="/data/user_de/0/${PACKAGE}/shared_prefs"

SERIAL="${ANDROID_SERIAL:-}"
COMMAND=""
TARGET=""
ON_DEVICE=0
WITH_TEST=0
MINIFIED=0
AS_JSON=0
PUBLISH=1
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
        --minified) MINIFIED=1; shift ;;
        --json) AS_JSON=1; shift ;;
        --no-release) PUBLISH=0; shift ;;
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

# One or more JUnit XML files, reduced to what a reader wants.
summarise_results() {
    local program
    program=$(cat <<'AWK'
# One test-results XML file, or many, reduced to what a reader wants: the
# totals, and every failure grouped by the thing that actually went wrong.
#
# Gradle and AGP both write JUnit XML and neither writes the same shape. AGP
# wraps its suites in <testsuites> and gives <failure> no attributes; Gradle
# writes one <testsuite> per file with message= and type= on the failure. Both
# are handled by reading the tag rather than the layout.
function unescape(s) {
    gsub(/&quot;/, "\"", s); gsub(/&apos;/, "'", s)
    gsub(/&lt;/, "<", s); gsub(/&gt;/, ">", s)
    gsub(/&#13;|&#xd;|&#xD;/, "", s)
    gsub(/&amp;/, "\\&", s)
    return s
}
function attr(line, name,   m) {
    if (!match(line, name "=\"[^\"]*\"")) return ""
    m = substr(line, RSTART, RLENGTH)
    sub(name "=\"", "", m); sub(/"$/, "", m)
    return unescape(m)
}
# <testsuites> carries the same totals as its children added up, so only the
# children are counted or a wrapped file doubles.
/<testsuite / {
    tests += attr($0, "tests"); failures += attr($0, "failures")
    errors += attr($0, "errors"); skipped += attr($0, "skipped")
}
/<testcase / { subject = attr($0, "classname") "." attr($0, "name") }
/<(failure|error)[ >]/ {
    capturing = 1; body = ""
    line = $0
    sub(/^[^<]*<(failure|error)[^>]*>/, "", line)
    if (line != "") body = unescape(line) "\n"
}
capturing && !/<(failure|error)[ >]/ {
    line = $0
    if (line ~ /<\/(failure|error)>/) sub(/<\/(failure|error)>.*$/, "", line)
    if (line != "" || body != "") body = body unescape(line) "\n"
}
capturing && /<\/(failure|error)>/ {
    capturing = 0
    # The first line is the cause. Everything under it is the same defect
    # reported from a different test, which is what makes a wall of hundreds
    # readable: 343 failures were one missing class.
    split(body, lines, "\n")
    cause = lines[1]
    if (cause == "") cause = "(no message)"
    if (!(cause in seen)) { order[++causes] = cause; example[cause] = subject; detail[cause] = body }
    seen[cause]++
}
END {
    printf "  %d tests, %d failures, %d errors, %d skipped\n", tests, failures, errors, skipped
    for (i = 1; i <= causes; i++) {
        c = order[i]
        printf "\n  %d x %s\n", seen[c], c
        printf "        first: %s\n", example[c]
        n = split(detail[c], d, "\n")
        shown = 0
        for (j = 2; j <= n && shown < 6; j++) {
            if (d[j] == "") continue
            printf "        %s\n", d[j]
            shown++
        }
        rest = 0
        for (k = j; k <= n; k++) if (d[k] != "") rest++
        if (rest == 1) printf "        ... 1 more line\n"
        else if (rest > 1) printf "        ... %d more lines\n", rest
    }
}
AWK
)
    awk "$program" "$@"
}

# Bytes, in the units a person reads APK sizes in.
human() {
    awk -v b="$1" 'BEGIN {
        if (b >= 1048576) printf "%.1f MB", b / 1048576
        else if (b >= 1024) printf "%.0f KB", b / 1024
        else printf "%d bytes", b
    }'
}

# What the APK costs, and in how many dex files. The whole reason R8 is on is a
# cold background wake, so a dependency that pushes the dex back over the limit
# into a second file is the regression that matters here and that nothing else
# would report.
report_size() {
    local apk="$1" bytes dex total files plural
    bytes=$(stat -c%s "$apk" 2>/dev/null || echo 0)
    dex=$(unzip -l "$apk" 2>/dev/null \
        | awk '/classes[0-9]*\.dex$/ { total += $1; files++ } END { printf "%d %d", total + 0, files + 0 }')
    total=${dex% *}
    files=${dex#* }
    plural=s
    [ "$files" != 1 ] || plural=""
    echo "Size: $(human "$bytes") APK, $(human "$total") of dex in $files file$plural."
}

# Which commit this APK is, and whether it is a commit at all. One built from
# uncommitted changes cannot be rebuilt by anybody, including whoever built it,
# and a published release that no commit corresponds to is one nothing can ever
# be diffed against or bisected through.
report_provenance() {
    local commit
    commit=$(git rev-parse --short HEAD 2>/dev/null) || return 0
    if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
        echo "Built from $commit with uncommitted changes, so this APK matches no"
        echo "commit and cannot be rebuilt. Commit before publishing it."
    else
        echo "Built from $commit, clean tree."
    fi
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
    # The task is named after the build type under test, and --minified moves
    # that from `debug` to the build type that carries R8's output. Nothing else
    # differs: the same suite, the same phone, the shrunk classes underneath.
    local args=(:app:connectedDebugAndroidTest)
    if [ "$MINIFIED" -eq 1 ]; then
        args=(:app:connectedMinifiedAndroidTest -Pgloaming.testBuildType=minified)
    fi
    if [ "$LEAVE_INSTALLED" -eq 1 ]; then
        args+=(-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true)
    fi
    if [ -n "$filter" ]; then
        args+=("$filter")
    fi
    ./gradlew "${args[@]}"
    if [ "$MINIFIED" -eq 1 ]; then
        echo
        echo "The phone is left with the shrunk build, which is not debuggable."
        echo "./go status, ./go log, ./go verdicts and both harnesses in tools/ all"
        echo "need run-as, and will say so until ./go install puts the debug one back."
    fi
}

# ---------------------------------------------------------------- Codeberg --
#
# Releases are published through the API rather than the web UI, so that
# cutting one is a command rather than a sequence of steps that can be left
# half-done - a tag pushed with no release on it, or a release with last week's
# APK still attached, are both worse than nothing.

CODEBERG_API="https://codeberg.org/api/v1"
TOKEN_FILE="secrets/codeberg-token.txt"
TOKEN_PLACEHOLDER="paste-your-codeberg-token-here"
CODEBERG_TOKEN_VALUE=""
CODEBERG_REPO=""

# Everything the publishing path needs, resolved once and in the calling shell.
# Not inside a command substitution: `die` there exits the subshell, the caller
# carries on with an empty token, and the first thing it learns is a 401.
require_codeberg() {
    command -v curl >/dev/null 2>&1 || die "curl is not on PATH"
    command -v jq >/dev/null 2>&1 \
        || die "jq is not on PATH, and the publishing path reads the API's JSON with it"

    # The remote rather than a hardcoded name, so a fork publishes to the fork.
    CODEBERG_REPO=$(git config --get remote.origin.url 2>/dev/null \
        | sed -e 's#^ssh://git@codeberg\.org/##' \
              -e 's#^git@codeberg\.org:##' \
              -e 's#^https://codeberg\.org/##' \
              -e 's#\.git$##')
    [ -n "$CODEBERG_REPO" ] || die "no origin remote to publish to"

    # The file first, then the environment, which is the order the signing
    # material uses and for the same reason.
    CODEBERG_TOKEN_VALUE=""
    [ -f "$TOKEN_FILE" ] && CODEBERG_TOKEN_VALUE=$(tr -d ' \t\n\r' < "$TOKEN_FILE")
    [ -n "$CODEBERG_TOKEN_VALUE" ] || CODEBERG_TOKEN_VALUE="${CODEBERG_TOKEN:-}"
    # An unfilled template is not a token. Sending it produces a 401 that reads
    # as a revoked token rather than as one never pasted in.
    [ "$CODEBERG_TOKEN_VALUE" != "$TOKEN_PLACEHOLDER" ] || CODEBERG_TOKEN_VALUE=""
    [ -n "$CODEBERG_TOKEN_VALUE" ] || die "No Codeberg token. Put one in $TOKEN_FILE
or set CODEBERG_TOKEN. Generate it at codeberg.org under Settings ->
Applications -> Access Tokens, with write access to repository."
}

# One API call. Prints the status on the first line and the body after it, and
# never fails on its own account: a 404 is the ordinary answer to "is there a
# release for this tag" and only the caller knows whether it is a problem.
codeberg_call() {
    local method="$1" path="$2" body="${3:-}" raw
    if [ -n "$body" ]; then
        raw=$(curl -sS -w '\n%{http_code}' -X "$method" \
            -H "Authorization: token $CODEBERG_TOKEN_VALUE" \
            -H "Content-Type: application/json" \
            --data-binary "$body" "$CODEBERG_API/$path")
    else
        raw=$(curl -sS -w '\n%{http_code}' -X "$method" \
            -H "Authorization: token $CODEBERG_TOKEN_VALUE" \
            "$CODEBERG_API/$path")
    fi
    # curl appends the status; the callers want it first, where a one-line read
    # gets it whatever the body turns out to be.
    printf '%s\n%s' "$(printf '%s' "$raw" | tail -1)" "$(printf '%s' "$raw" | sed '$d')"
}

# The same, for calls where anything but success is a failure. The API says why
# in the body, so the body is what gets printed - a bare status sends the reader
# to the web UI to guess.
codeberg_api() {
    local response status
    response=$(codeberg_call "$@")
    status=$(printf '%s' "$response" | head -1)
    case "$status" in
        2*) printf '%s' "$response" | sed '1d' ;;
        *) die "Codeberg API $1 $2 returned $status:
$(printf '%s' "$response" | sed '1d')" ;;
    esac
}

# Create the release for a tag or bring the existing one up to date, and leave
# exactly one APK attached to it.
publish_release() {
    local tag="$1" title="$2" notes="$3" prerelease="$4" apk="$5"
    local payload existing status id

    payload=$(jq -n \
        --arg tag "$tag" --arg name "$title" --arg body "$notes" \
        --arg target "$(git rev-parse HEAD)" --argjson pre "$prerelease" \
        '{tag_name: $tag, name: $name, body: $body, prerelease: $pre,
          draft: false, target_commitish: $target}')

    existing=$(codeberg_call GET "repos/$CODEBERG_REPO/releases/tags/$tag")
    status=$(printf '%s' "$existing" | head -1)
    if [ "$status" = "200" ]; then
        id=$(printf '%s' "$existing" | sed '1d' | jq -r '.id')
        echo "  updating the release on $tag"
        codeberg_api PATCH "repos/$CODEBERG_REPO/releases/$id" "$payload" >/dev/null
        # Every asset, not the one matching by name: the APK carries its commit
        # in the filename, so each push would otherwise leave the last one
        # attached beside it and the release would accumulate for ever.
        printf '%s' "$existing" | sed '1d' | jq -r '.assets[]?.id' \
            | while read -r asset; do
                [ -n "$asset" ] || continue
                codeberg_api DELETE "repos/$CODEBERG_REPO/releases/$id/assets/$asset" >/dev/null
            done
    else
        echo "  creating the release on $tag"
        id=$(codeberg_api POST "repos/$CODEBERG_REPO/releases" "$payload" | jq -r '.id')
    fi
    [ -n "$id" ] && [ "$id" != "null" ] || die "the API returned no release id"

    echo "  uploading $(basename "$apk")"
    local upload
    upload=$(curl -sS -o - -w '\n%{http_code}' -X POST \
        -H "Authorization: token $CODEBERG_TOKEN_VALUE" \
        -F "attachment=@$apk" \
        "$CODEBERG_API/repos/$CODEBERG_REPO/releases/$id/assets?name=$(basename "$apk")")
    case "$(printf '%s' "$upload" | tail -1)" in
        2*) ;;
        *) die "uploading the APK failed: $upload" ;;
    esac
    echo "  https://codeberg.org/$CODEBERG_REPO/releases/tag/$tag"
}

# The last released version, which is what a development build's notes are
# measured from. Only `v*` tags: the rolling `dev` tag is a pointer at the tip
# and counting commits since it would always come back empty.
last_version_tag() {
    git describe --tags --abbrev=0 --match 'v*' HEAD 2>/dev/null || true
}

# Where assembleRelease left the APK.
built_release_apk() {
    find app/build/outputs/apk/release -name '*.apk' -print -quit 2>/dev/null || true
}

# The version the build is carrying, for a release title. It is deliberately
# the same between releases - only the commit says which build is which - so
# the number beside it is the version code, which does move.
version_name() {
    sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1
}

# One value out of the device-encrypted settings mirror.
pref_value() {
    as_app "cat $DE_PREFS/*.xml 2>/dev/null" \
        | sed -n "s/.*name=\"$1\" value=\"\([^\"]*\)\".*/\1/p" | head -1
}

# Coordinates are stored as `Double.toRawBits()`, so the number in the file is
# not a coordinate and handing it back as one would put the observer in the
# middle of nowhere. Node rather than a shell arithmetic trick because IEEE-754
# is not something to reimplement in `expr`.
#
# The bits go in through the environment rather than as an argument, and that is
# not fastidiousness. Every negative coordinate has the sign bit set, so its raw
# bits are a negative integer, so the argument starts with a `-` and node's own
# option parser takes it for a flag: `node: bad option: -4614894397677710907`.
# Which means this worked only for an observer east of Greenwich and north of
# the equator, and `./go release` died on the spot anywhere else - about three
# quarters of the planet, this country included.
bits_to_double() {
    # `process.stdout.write(String(...))`, not `console.log(...)`. console.log
    # formats a number through util.inspect, which colours its output when it
    # thinks a terminal is watching - and FORCE_COLOR in the environment makes
    # it think so even down a pipe. The coordinate then comes back as
    # "\033[33m53\033[39m", which is not a number any more: the seed rejects
    # it and the position is never put back. That is how a phone was left
    # pointing at Palomar after a run meant to restore it.
    GLOAMING_RAW_BITS="$1" node -e 'const b = Buffer.alloc(8);
             b.writeBigInt64BE(BigInt(process.env.GLOAMING_RAW_BITS));
             process.stdout.write(String(b.readDoubleBE(0)));'
}

# The phone's own settings, read before the gate seeds over them.
#
# BeforeUnlockFixture writes a whole UserSettings rather than editing fields, so
# seeding without passing these puts Edinburgh and the default threshold on
# whatever phone the gate ran on - the same failure the instrumented suite was
# fixed for once already. Captured here, passed back in on every seed, and put
# back at the end.
SAVED_LATITUDE=""
SAVED_LONGITUDE=""
SAVED_THRESHOLD=""
SAVED_INTERVAL=""

capture_device_settings() {
    local latitude longitude
    latitude=$(pref_value latitude)
    longitude=$(pref_value longitude)
    SAVED_THRESHOLD=$(pref_value threshold)
    SAVED_INTERVAL=$(pref_value poll_minutes)
    [ -n "$latitude" ] && SAVED_LATITUDE=$(bits_to_double "$latitude")
    [ -n "$longitude" ] && SAVED_LONGITUDE=$(bits_to_double "$longitude")
    if [ -z "$SAVED_LATITUDE" ] || [ -z "$SAVED_LONGITUDE" ]; then
        die "the phone has no observer position recorded, so seeding the harness
would invent one and leave it there. Open the app and set a location first."
    fi
    echo "  the phone's settings: $SAVED_LATITUDE, $SAVED_LONGITUDE, threshold ${SAVED_THRESHOLD:-default}, every ${SAVED_INTERVAL:-default} min"
}

# The before-unlock harness, seeded with the phone's own location and threshold
# so that only the interval changes. ANDROID_SERIAL is exported by this script
# and both harnesses read it, so there is no --serial to pass on.
#
# --reconnect-timeout 0 because the gate is unattended until this point and the
# soak has already been spent: giving up ten minutes after it, over nobody being
# in the room yet, would throw the whole run away.
run_bfu_seeded() {
    local interval="$1"
    local args=(--seed --interval "$interval" --reconnect-timeout 0
                --latitude "$SAVED_LATITUDE" --longitude "$SAVED_LONGITUDE")
    if [ -n "$SAVED_THRESHOLD" ]; then
        args+=(--threshold "$SAVED_THRESHOLD")
    fi
    tools/bfu-check.sh "${args[@]}"
}

# Wait for the app's schedule to come back after a reboot.
#
# Read the same way doze-check reads it, so that what this waits for is exactly
# what that then requires: the pending-alarm section of `dumpsys alarm`, before
# the history that also mentions the receiver by name.
#
# This does not fail on a timeout. If the schedule genuinely never returns then
# something is wrong with the app rather than with the waiting, and doze-check
# says so in its own words a moment later - it has the better message and the
# advice to go with it.
wait_for_poll_alarm() {
    local waited=0
    while [ "$waited" -lt 180 ]; do
        if adb shell dumpsys alarm 2>/dev/null | tr -d '\r' \
            | sed -n '1,/App Alarm history/p' \
            | grep -q "walarm\*:${PACKAGE}/\.work\.PollReceiver"; then
            if [ "$waited" -gt 0 ]; then
                echo "  the app re-armed its schedule ${waited}s after the reboot"
            fi
            return 0
        fi
        sleep 5
        waited=$((waited + 5))
    done
    echo "  no alarm pending after ${waited}s of waiting"
}

# Put the interval back. The fixture is invoked directly rather than through
# `bfu-check.sh --seed`, which would reboot the phone a second time to restore a
# setting - the reboot is the harness's subject, not part of writing settings.
restore_device_settings() {
    local interval="${SAVED_INTERVAL:-15}"
    local args=(-e interval "$interval"
                -e latitude "$SAVED_LATITUDE" -e longitude "$SAVED_LONGITUDE")
    if [ -n "$SAVED_THRESHOLD" ]; then
        args+=(-e threshold "$SAVED_THRESHOLD")
    fi
    echo "  putting the interval back to ${interval} minutes"
    adb shell am instrument -w \
        -e class sh.arek.gloaming.harness.BeforeUnlockFixture \
        "${args[@]}" \
        "${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 \
        | tr -d '\r' | grep -qE 'OK \(1 test\)' \
        || echo "  WARNING: could not put the settings back. Check ./go status." >&2
}

# Checked before anything is pushed rather than at packageRelease, which is
# several minutes in. The build has the same check and a better message; this
# one exists only to have it earlier.
require_signing() {
    [ -f keystore.properties ] || [ -n "${GLOAMING_KEYSTORE:-}" ] \
        || die "No signing material, so no release APK can be built:
    cp keystore.properties.example keystore.properties
and fill in the passphrase. See docs/prerequisites.md."
}

# Is there anything under Unreleased to cut a release from? A release whose
# notes are empty is one nobody can tell from the last.
changelog_has_unreleased() {
    awk '/^## \[Unreleased\]/ { inside = 1; next }
         /^## \[/ { inside = 0 }
         inside && NF { found = 1 }
         END { exit !found }' CHANGELOG.md
}

# What the changelog now says about this version, for the release notes.
changelog_section() {
    awk -v heading="## [$1]" '
        index($0, heading) == 1 { inside = 1; next }
        inside && /^## \[/ { exit }
        inside { print }' CHANGELOG.md
}

# Cut a version: the whole gate, then the bump, then publish.
#
# The gate is everything the project can check, in the order the phone allows.
# The minified suite leaves the shrunk build installed and that build is not
# debuggable, so `run-as` stops working and both overnight harnesses would fail
# on a phone that is perfectly healthy - hence the reinstall between them. The
# screen keeper is let go before the harnesses for the same class of reason:
# it wakes the screen every four minutes, which is exactly what stops a device
# reaching deep idle for doze-check to measure.
run_full_release() {
    local version="$1" tag date built apk notes prerelease answer

    case "$version" in
        v*) die "give the version without the leading v: ./go release ${version#v}" ;;
        [0-9]*.[0-9]*.[0-9]*) ;;
        *) die "'$version' does not look like a version. Expected 1.0.0, or 1.0.0-rc2." ;;
    esac
    tag="v$version"

    # Everything that can refuse, before anything that takes an hour.
    [ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] \
        || die "releases are cut from main only, and this is $(git rev-parse --abbrev-ref HEAD)"
    [ -z "$(git status --porcelain)" ] \
        || die "commit or stash the working tree first: a release must correspond to a commit"
    ! git rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1 \
        || die "$tag already exists. Releases are not re-cut; pick the next version."
    changelog_has_unreleased \
        || die "CHANGELOG.md has nothing under Unreleased, so this release would say
nothing about itself. Write the entry first."
    require_signing
    require_codeberg
    require_device
    capture_device_settings

    cat <<NOTICE

Releasing $version from $(git rev-parse --short HEAD).

Everything runs: the unit tests, the whole instrumented suite, that suite again
against R8's output, and both overnight harnesses. About two hours, and the
phone must stay plugged in throughout. It is unattended until the before-unlock
harness reboots the phone, at which point it waits - with no time limit - for
you to unlock it.

Nothing is published until you are asked.

NOTICE

    echo "== 1/6  everything that needs no phone =="
    run_check

    echo
    echo "== 2/6  the instrumented suite, the catalogue of nights with it =="
    run_on_device

    echo
    echo "== 3/6  the same suite against what R8 actually ships =="
    MINIFIED=1
    run_on_device
    MINIFIED=0

    echo
    echo "== 4/6  putting the debuggable build back =="
    # The harnesses below read the app's own files through run-as, which the
    # shrunk build does not allow, and the instrumentation APK is what seeds
    # the before-unlock settings.
    ./gradlew :app:installDebug :app:installDebugAndroidTest

    # Let the screen go before anything wants the phone dark or rebooted.
    restore_device

    echo
    echo "== 5/6  before first unlock =="
    run_bfu_seeded 2

    echo
    echo "== 6/6  the interval the polls really land at =="
    # Stage 5 rebooted the phone, and the app re-arms from a boot broadcast.
    # Until that is handled there is nothing pending, and doze-check refuses to
    # spend half an hour watching a phone with nothing to fire - which is the
    # right call on its own and the wrong outcome here, because the device is
    # healthy and simply has not caught up. Waiting is the whole fix.
    wait_for_poll_alarm
    # --observer borrows somewhere dark and clear, because the phone's own sky
    # decides the schedule and a release cut at eleven in the morning has the
    # app asleep until dusk - nothing to measure, and the interval seeded above
    # does not override it (SkyPoller.nextPoll consults that only without a
    # position). The phone's own position goes back when the harness exits.
    #
    # Ninety minutes rather than thirty. The best a borrowed site can buy is
    # QUIET_MINUTES - ELEVATED needs the real solar wind, and no location
    # changes that - so the polls come half an hour apart and it takes two of
    # them to make a single gap. Ninety leaves room for three, which is the
    # difference between a stage that usually measures something and one that
    # reliably does: at seventy-five a single late poll drops it back to one,
    # and one poll is the skip this gate exists to stop producing.
    #
    # --expect-late is slack on the deadline the alarm publishes for itself
    # (maxWhenElapsed), so this one number holds whatever interval the sky puts
    # the app on - and the interval does move inside a run, because the schedule
    # is the minimum of three pollers and a satellite pass drops it from half an
    # hour to nine minutes and back.
    tools/doze-check.sh --observer auto --minutes 90 --settle 8 --expect-late 10

    echo
    echo "== Putting the phone's settings back =="
    restore_device_settings
    echo "  Note: the harness switched alerting and before-unlock alerting on,"
    echo "  and cannot tell whether they were on before. ./go status reads back"
    echo "  what the phone is set to now."

    echo
    echo "== The bump =="
    date=$(date -u '+%Y-%m-%d')
    sed -i "s/^\( *versionName = \)\".*\"/\1\"$version\"/" app/build.gradle.kts
    sed -i "0,/^## \[Unreleased\]$/s//## [Unreleased]\n\n## [$version] - $date/" CHANGELOG.md
    git add app/build.gradle.kts CHANGELOG.md
    git commit -q -m "chore(app): version $version, and cut the changelog against it"
    echo "  versionName $version, changelog cut, committed as $(git rev-parse --short HEAD)"

    echo
    echo "== Building the release APK =="
    ./gradlew :app:assembleRelease
    built=$(built_release_apk)
    [ -n "$built" ] || die "assembleRelease produced no APK"
    apk="app/build/outputs/apk/release/gloaming-${version}.apk"
    cp "$built" "$apk"
    report_size "$apk"
    check_signature "$apk" || die "refusing to publish an APK signed with the wrong key"
    report_provenance

    case "$version" in
        *-*) prerelease=true ;;
        *) prerelease=false ;;
    esac
    notes="$(changelog_section "$version")"

    cat <<CONFIRM

Everything passed. What happens next cannot be undone:

  tag      $tag on $(git rev-parse --short HEAD)
  push     main and the tag to origin
  publish  $(basename "$apk") as a $([ "$prerelease" = true ] && echo "pre-release" || echo "release") on Codeberg

CONFIRM
    printf 'Publish? [y/N] '
    read -r answer
    case "$answer" in
        y|Y|yes|YES) ;;
        *) die "Stopped. The version bump is committed but nothing was pushed;
undo it with: git reset --hard HEAD~1" ;;
    esac

    git tag -a "$tag" -m "Version $version"
    git push origin main "refs/tags/$tag"
    echo
    echo "== Publishing =="
    publish_release "$tag" "$version" "$notes" "$prerelease" "$apk"
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

    results)
        unit_dir="app/build/test-results/testDebugUnitTest"
        found=0
        if [ -n "$TARGET" ] && [ "$TARGET" != "unit" ] && [ "$TARGET" != "device" ]; then
            die "results takes 'unit' or 'device', or nothing for both"
        fi
        if [ "$TARGET" != "device" ] && compgen -G "$unit_dir/*.xml" > /dev/null; then
            found=1
            echo "== Unit tests, JVM ($(date -r "$unit_dir" '+%H:%M on %-d %b')) =="
            summarise_results "$unit_dir"/*.xml
            echo
        fi
        if [ "$TARGET" != "unit" ]; then
            # One directory per build type under test, and the interesting one
            # is whichever ran last rather than whichever sorts first.
            for dir in $(ls -dt app/build/outputs/androidTest-results/connected/*/ 2>/dev/null); do
                compgen -G "$dir"TEST-*.xml > /dev/null || continue
                found=1
                echo "== Instrumented tests, $(basename "$dir") ($(date -r "$dir" '+%H:%M on %-d %b')) =="
                summarise_results "$dir"TEST-*.xml
                echo
            done
        fi
        if [ "$found" -eq 0 ]; then
            echo "No test results here yet. ./go test, ./go device-test, or ./go check."
        fi
        ;;

    verdicts)
        require_device
        [ "$(as_app 'echo ok')" = "ok" ] || die "cannot read as $PACKAGE. run-as works only on a
debuggable build, so this is a release APK: ./go install puts the debug one back."

        recorded=$(as_app "cat $VERDICTS 2>/dev/null")
        if [ -z "$recorded" ] || [ "$recorded" = "[]" ]; then
            echo "No verdicts recorded on this phone yet."
            echo
            echo "They arrive by somebody pressing one of the two buttons on an alert,"
            echo "so an empty file means either no alert has been answered or none has"
            echo "been sent. ./go log says which."
            exit 0
        fi

        if [ "$AS_JSON" -eq 1 ]; then
            printf '%s\n' "$recorded"
            exit 0
        fi

        # Records are split on the first field's name rather than on a brace,
        # because conditions is nested and its closing brace looks identical.
        # Reading by name is the same rule the upstream parsers follow.
        rows=$(printf '%s' "$recorded" | awk '
            function field(rec, name,   m) {
                if (match(rec, "\"" name "\":\"[^\"]*\"")) {
                    m = substr(rec, RSTART, RLENGTH)
                    sub("\"" name "\":\"", "", m); sub(/"$/, "", m)
                    return m
                }
                if (match(rec, "\"" name "\":-?[0-9.]+")) {
                    m = substr(rec, RSTART, RLENGTH)
                    sub("\"" name "\":", "", m)
                    return m
                }
                return ""
            }
            function part(label, value, suffix) {
                return value == "" ? "" : label value suffix " "
            }
            # The two buttons say "Worth it" and "Not worth it", and the third
            # answer the app records for an alert nobody answered says "Did not
            # look". Reading the enum name back would be a different vocabulary
            # from the one the person pressing them saw.
            function label(a) {
                if (a == "Worth") return "Worth it"
                if (a == "NotWorth") return "Not worth it"
                if (a == "DidNotLook") return "Did not look"
                return a
            }
            {
                body = $0
                gsub(/\},\{"answeredAtMillis"/, "}\n{\"answeredAtMillis\"", body)
                n = split(body, recs, "\n")
                for (i = n; i >= 1; i--) {
                    r = recs[i]
                    when = field(r, "answeredAtMillis")
                    if (when == "") continue
                    detail = part("p=", field(r, "probability"), "") \
                        part("", field(r, "source"), "") \
                        part("Kp=", field(r, "kp"), "") \
                        part("Bz=", field(r, "bz"), "") \
                        part("peak=", field(r, "peakElevation"), " deg") \
                        part("mag=", field(r, "magnitude"), "") \
                        part("cloud=", field(r, "cloudTotal"), "%") \
                        part("threshold=", field(r, "threshold"), "") \
                        part("", field(r, "subject"), "")
                    sub(/ +$/, "", detail)
                    answer = field(r, "answer")
                    printf "%d\t%s\t%s\t%s\t%s\n", int(when / 1000), field(r, "channel"),
                        label(answer), answer, detail
                }
            }')

        echo "== What the alerts on this phone were worth =="
        printf '  %-17s %-8s %-13s %s\n' "ANSWERED" "CHANNEL" "ANSWER" "AT THE TIME"
        printf '%s\n' "$rows" | while IFS=$'\t' read -r epoch channel answer raw detail; do
            [ -n "$epoch" ] || continue
            printf '  %-17s %-8s %-13s %s\n' \
                "$(date -d "@$epoch" '+%Y-%m-%d %H:%M')" "$channel" "$answer" "$detail"
        done

        echo
        printf '%s\n' "$rows" | awk -F'\t' '
            $1 != "" { total[$2]++; by[$2 "/" $4]++; order[$2] = 1 }
            END {
                for (c in order) {
                    printf "  %-8s %3d: %d worth it, %d not, %d not looked at\n", c, total[c],
                        by[c "/Worth"] + 0, by[c "/NotWorth"] + 0, by[c "/DidNotLook"] + 0
                }
            }' | sort
        echo
        echo "Nothing here records where you were. The app keeps what the sky was"
        echo "doing and what you thought of it, and nothing else - see Verdict.kt."
        ;;

    apk)
        ./gradlew :app:assembleDebug
        echo
        echo "APK: app/build/outputs/apk/debug/app-debug.apk"
        ;;

    release)
        # With a version, the whole thing: the gate, the bump, and publishing.
        # Without one, what this has always done - build the APK and report on
        # it - which is what you want while working on the release build itself.
        if [ -n "$TARGET" ]; then
            run_full_release "$TARGET"
            exit 0
        fi
        ./gradlew :app:assembleRelease
        built=$(find app/build/outputs/apk/release -name '*.apk' -print -quit 2>/dev/null || true)
        echo
        if [ -n "$built" ]; then
            echo "APK: $built"
            report_size "$built"
            # The signature verdict is held rather than acted on, so that a
            # build signed with the wrong key still reports everything else
            # before it fails. Which commit it came from is exactly what you
            # want to know at that moment.
            signed_right=0
            check_signature "$built" || signed_right=1
            report_provenance
            [ "$signed_right" -eq 0 ] || exit 1
        fi
        ;;

    push)
        # Development releases publish from main only. A feature branch pushing
        # one would move the rolling tag onto work nobody has merged, and the
        # single release everybody installs from would silently become it.
        branch=$(git rev-parse --abbrev-ref HEAD)
        [ "$branch" = "main" ] \
            || die "on branch $branch: development releases publish from main only.
Push it with git, or merge to main first."

        # A published APK that corresponds to no commit cannot be rebuilt by
        # anyone, including by the person who published it. The build stamps
        # such a build as modified and the settings screen says so, which is
        # the right behaviour for a local build and no basis for a release.
        [ -z "$(git status --porcelain)" ] \
            || die "the tree has uncommitted changes, so a build from it matches no
commit and nothing could reproduce it. Commit them first."

        if [ "$PUBLISH" -eq 1 ]; then
            require_signing
            require_codeberg
        fi

        # main and the rolling tag in one push, so the pre-push hook runs the
        # suite once rather than once per ref. `+` forces the tag refspec only;
        # main is never force-pushed by this.
        echo "== Pushing =="
        if [ "$PUBLISH" -eq 1 ]; then
            git push origin main "+HEAD:refs/tags/dev"
        else
            git push origin main
            echo
            echo "Pushed. No development release was published (--no-release)."
            exit 0
        fi

        echo
        echo "== Building the release APK =="
        ./gradlew :app:assembleRelease
        built=$(built_release_apk)
        [ -n "$built" ] || die "assembleRelease produced no APK"
        check_signature "$built" || die "refusing to publish an APK signed with the wrong key"

        # The filename carries the commit because the version name does not
        # move between releases: every development build between 1.0.0-rc1 and
        # whatever follows it is called 1.0.0-rc1, and only the commit
        # distinguishes the one somebody has downloaded.
        commit=$(git rev-parse --short HEAD)
        apk="app/build/outputs/apk/release/gloaming-dev-${commit}.apk"
        cp "$built" "$apk"
        report_size "$apk"

        since=$(last_version_tag)
        if [ -n "$since" ]; then
            changes=$(git log --format='- %s' "${since}..HEAD")
            [ -n "$changes" ] || changes="- nothing since ${since}"
            heading="Changes since ${since}:"
        else
            changes=$(git log --format='- %s' -20)
            heading="The last twenty commits (there is no version tag to count from):"
        fi

        notes="Built from \`${commit}\` on $(date -u '+%Y-%m-%d %H:%M UTC'), signed with the
project's release key - so it installs over a release and a release installs
over it, keeping settings, the battery-optimisation exemption and the record
of what your alerts were worth.

This is the tip of main rather than a release. It carries the version name of
the last release, $(version_name), because that name identifies a release and
does not move between them; the commit above is what identifies this build, and
the settings screen shows it on the phone.

${heading}

${changes}"

        echo
        echo "== Publishing =="
        publish_release "dev" "Development build ($commit)" "$notes" true "$apk"
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
        # core.hooksPath rather than a copy into .git/hooks: the hook is then
        # the version-controlled one, and cannot drift from it unnoticed on a
        # checkout somebody set up months ago. It is per-clone configuration,
        # so it has to be set once per clone, which is what setup is for.
        echo
        echo "== hooks =="
        git config core.hooksPath tools/hooks
        echo "core.hooksPath -> tools/hooks; ./go check now runs before each push."

        echo
        echo "The Node projects are ready and the hooks are wired up. Nothing else"
        echo "is installed by this: JDK 21 and the Android SDK with platform"
        echo "android-37.0 are yours to provide, and docs/prerequisites.md is the"
        echo "list."
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
