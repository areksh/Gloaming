#!/usr/bin/env bash
#
# Exercises the before-first-unlock path on a real device, in minutes.
#
# The overnight run is the honest test of alerting, but it answers one question a
# day and only ever says "no alert arrived" - never why. This reboots the phone,
# leaves it at the lock screen for a soak period, and then reads back what
# happened, so a failure names itself.
#
# It does not need adb while the device is locked, and must not: GrapheneOS
# defaults the USB-C port to charging-only when locked, so adb disappears the
# moment the phone reboots and does not return until someone unlocks it. The
# evidence is gathered afterwards instead, from three places that outlive the
# locked window:
#
#   - the app's own log lines, which carry `unlocked=false` at the point of the
#     poll, in the logcat ring buffer (survives unlock, cleared by reboot);
#   - `last_poll_at` in device-encrypted preferences, which survives anything;
#   - the timestamps on the cache files in device-encrypted storage.
#
# Usage: tools/bfu-check.sh [options]
#
#   --seed          switch before-unlock alerting on first, through the same save
#                   path the settings screen uses. Needs the androidTest APK:
#                   ./gradlew :app:installDebug :app:installDebugAndroidTest
#   --interval N    poll interval in minutes to seed (default 2, so the soak
#                   sees several wakes; the app default is 15)
#   --soak N        minutes to leave the device locked (default 6)
#   --resume        skip the reboot and just read the evidence. Use this in the
#                   morning after a real overnight run.
#   --serial S      target a specific device
#
set -euo pipefail

PACKAGE="sh.arek.gloaming"
SERIAL="${ANDROID_SERIAL:-}"
SEED=0
RESUME=0
INTERVAL=2
SOAK=6
RECONNECT_TIMEOUT=600

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --seed) SEED=1; shift ;;
        --interval) INTERVAL="$2"; shift 2 ;;
        --soak) SOAK="$2"; shift 2 ;;
        --resume) RESUME=1; shift ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

adb() {
    if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi
}

PASS=0
FAIL=0
SKIP=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; return 0; }
skip() { SKIP=$((SKIP + 1)); printf '  SKIP  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; return 0; }
step() { printf '\n== %s ==\n' "$1"; }

DE_PREFS="/data/user_de/0/${PACKAGE}/shared_prefs"
DE_CACHE="/data/user_de/0/${PACKAGE}/files/sky"

# Runs a command as the app's own uid. The inner `sh -c` matters: without it the
# shell user expands any glob, and it cannot stat the app's directory, so the
# pattern reaches the command unexpanded and every read looks like an empty file.
as_app() {
    adb shell "run-as $PACKAGE sh -c '$1'" 2>/dev/null | tr -d '\r' || true
}

device_present() { adb shell true >/dev/null 2>&1; }

user_state() {
    adb shell dumpsys user 2>/dev/null | grep -m1 'State:' | tr -d '\r' | awk '{print $2}'
}

# Only alarms currently pending count. "Removal history" further down the dump
# records each cancelled alarm under the very same tag, so searching the whole
# dump reads the record of a cancellation as proof of a live alarm.
#
# Matched on the full tag rather than the package name: the pending region also
# names packages in unrelated bookkeeping, and a bare package match reports an
# alarm that is not there.
poll_alarm() {
    adb shell dumpsys alarm 2>/dev/null | tr -d '\r' \
        | sed -n '1,/App Alarm history/p' \
        | grep -A 4 "walarm\*:${PACKAGE}/\.work\.PollReceiver" || true
}

# App standby buckets, from UsageStatsManager. NEVER is what an app sits in
# until the user opens it for the first time, and its alarms are deferred so far
# out that they may as well not exist.
bucket_name() {
    case "$1" in
        5)  printf 'EXEMPTED - on the power allowlist' ;;
        10) printf 'ACTIVE' ;;
        20) printf 'WORKING_SET' ;;
        30) printf 'FREQUENT' ;;
        40) printf 'RARE' ;;
        45) printf 'RESTRICTED' ;;
        50) printf 'NEVER - the app has never been opened by the user' ;;
        *)  printf 'unknown (%s)' "$1" ;;
    esac
}

# Log lines from our own tags, minus logcat's own banner, so "nothing was
# logged" can be told apart from "the tag logged nothing".
our_log() {
    adb logcat -d -b all -s "$1" 2>/dev/null | tr -d '\r' | grep -v '^---------' || true
}

# ---------------------------------------------------------------- preflight --

if [ "$RESUME" -eq 0 ]; then
    step "Preconditions"

    if ! device_present; then
        echo "  no device reachable over adb" >&2
        exit 1
    fi
    printf '  device: %s (GrapheneOS: %s)\n' \
        "$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')" \
        "$(adb shell pm list packages 2>/dev/null | grep -qi graphene && echo yes || echo no)"

    if ! adb shell pm list packages 2>/dev/null | grep -q "^package:${PACKAGE}$"; then
        echo "  FAIL  $PACKAGE is not installed: ./gradlew :app:installDebug" >&2
        exit 1
    fi
    pass "$PACKAGE is installed"

    # Without a lock credential the user is unlocked the moment it boots, so
    # there is no before-first-unlock state and every assertion below would pass
    # for the wrong reason. This is the most likely way to "test BFU" and learn
    # nothing at all.
    if [ "$(adb shell locksettings get-disabled 2>/dev/null | tr -d '\r')" = "true" ]; then
        echo "  FAIL  no lock screen credential is set" >&2
        echo "        Without one the device is never locked after boot, so this" >&2
        echo "        would pass without testing anything. Set a PIN." >&2
        exit 1
    fi
    pass "a lock screen credential is set"

    if [ "$SEED" -eq 1 ]; then
        printf '  seeding: before-unlock alerting on, %s minute interval\n' "$INTERVAL"
        if ! adb shell am instrument -w \
            -e class sh.arek.gloaming.harness.BeforeUnlockFixture \
            -e interval "$INTERVAL" \
            "${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 \
            | tr -d '\r' | grep -qE 'OK \(1 test\)'; then
            fail "could not seed the settings" \
                "Install the test APK: ./gradlew :app:installDebugAndroidTest"
        fi
    fi

    mirror=$(as_app "cat $DE_PREFS/*.xml 2>/dev/null")
    if [ -z "$mirror" ]; then
        fail "device-encrypted settings are empty" \
            "Run again with --seed, or switch the option on in the app."
    elif printf '%s' "$mirror" | grep -q 'name="alerts_before_unlock" value="true"'; then
        pass "before-unlock alerting is on in device-encrypted storage"
    else
        fail "before-unlock alerting is off in device-encrypted storage" \
            "Every locked wake will skip. Run again with --seed."
    fi

    # The exemption is what lifts app-standby deferral, and app standby - not
    # Doze - is what actually stops the overnight wakes. Without it an alarm can
    # be pushed a year out while still looking perfectly healthy in dumpsys.
    if adb shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PACKAGE"; then
        pass "exempt from battery optimisation"
    else
        fail "not exempt from battery optimisation" \
            "App standby will defer the wakes. Grant it in the app, or for a test: adb shell dumpsys deviceidle whitelist +$PACKAGE"
    fi

    bucket=$(adb shell am get-standby-bucket "$PACKAGE" 2>/dev/null | tr -d '\r')
    if [ "${bucket:-50}" -ge 45 ] 2>/dev/null; then
        fail "app standby bucket is $bucket: $(bucket_name "$bucket")" \
            "Alarms in this bucket are deferred indefinitely. Open the app once."
    else
        pass "app standby bucket is $bucket ($(bucket_name "$bucket"))"
    fi

    if adb shell dumpsys package "$PACKAGE" 2>/dev/null \
        | grep -q 'POST_NOTIFICATIONS: granted=true'; then
        pass "notification permission is granted"
    else
        fail "notification permission is not granted" \
            "A poll can decide to alert and still post nothing. Grant it in the app, or: adb shell pm grant $PACKAGE android.permission.POST_NOTIFICATIONS"
    fi

    [ "$FAIL" -eq 0 ] || { printf '\nFix the above before rebooting.\n'; exit 1; }

    # ------------------------------------------------------------- the soak --

    step "Rebooting and leaving it locked for $SOAK minutes"
    boot_started=$(date +%s)
    adb reboot
    printf '  rebooted at %s\n' "$(date '+%H:%M:%S')"
    printf '  Do not unlock the phone until asked.\n\n'

    # If adb comes back by itself the port allows data while locked, and the
    # locked state can be observed live. If it does not, that is the GrapheneOS
    # default rather than a fault, and the run continues regardless.
    live=0
    waited=0
    while [ "$waited" -lt $((SOAK * 60)) ]; do
        if device_present && [ "$(user_state || true)" = "RUNNING_LOCKED" ]; then
            live=1
            break
        fi
        sleep 10
        waited=$((waited + 10))
        printf '\r  soaking, %ss elapsed  ' "$waited"
    done
    printf '\n'

    if [ "$live" -eq 1 ]; then
        printf '  adb is reachable while locked, so the port allows data when locked.\n'
        remaining=$((SOAK * 60 - waited))
        [ "$remaining" -gt 0 ] && sleep "$remaining"
    else
        printf '  adb did not return while locked. Expected on GrapheneOS, whose\n'
        printf '  USB-C port defaults to charging-only when the device is locked.\n'
    fi

    if ! device_present; then
        printf '\n  >>> Unlock the phone now. Waiting for it to come back. <<<\n'
        waited=0
        while [ "$waited" -lt "$RECONNECT_TIMEOUT" ]; do
            device_present && break
            sleep 5
            waited=$((waited + 5))
        done
        if ! device_present; then
            echo "  device never came back; nothing to read" >&2
            exit 1
        fi
    fi
    printf '  device is back at %s\n' "$(date '+%H:%M:%S')"
fi

# ----------------------------------------------------------------- evidence --

if ! device_present; then
    echo "no device reachable over adb" >&2
    exit 1
fi

step "Did the boot receiver re-arm the alarm before unlock?"

# Filtered to our own process: the platform has a BootReceiver of its own that
# logs under exactly the same tag.
boot_log=$(our_log BootReceiver | grep -v 'tombstone' || true)
if printf '%s' "$boot_log" | grep -q 'LOCKED_BOOT_COMPLETED'; then
    pass "BootReceiver ran on LOCKED_BOOT_COMPLETED"
    printf '%s\n' "$boot_log" | grep 'LOCKED_BOOT_COMPLETED' | tail -2 | sed 's/^/        /'
elif [ -z "$boot_log" ]; then
    skip "no BootReceiver lines in the log buffer" \
        "The buffer may have rolled over. last_poll_at below is the durable check."
else
    fail "BootReceiver never logged a LOCKED_BOOT_COMPLETED" \
        "Without it no alarm is armed and the app sleeps until someone unlocks."
fi

step "Did a poll actually run while the device was locked?"

# The app logs the unlock state at the moment of the poll, so this needs no
# inference about when the unlock happened.
poll_log=$(our_log PollReceiver)
locked_polls=$(printf '%s' "$poll_log" | grep -c 'unlocked=false' || true)
skipped=$(printf '%s' "$poll_log" | grep -c 'before-unlock alerting is off' || true)

if [ "${locked_polls:-0}" -gt 0 ]; then
    pass "$locked_polls poll(s) completed before first unlock"
    printf '%s\n' "$poll_log" | grep 'unlocked=false' | tail -3 | sed 's/^/        /'
elif [ "${skipped:-0}" -gt 0 ]; then
    fail "the wake happened but every poll skipped" \
        "canPollNow said no: the settings mirror is missing, not the alarm."
elif [ -z "$poll_log" ]; then
    fail "the poll receiver never ran at all" \
        "No alarm fired. Check the exemption and the standby bucket."
else
    fail "polls ran, but only after unlock" \
        "$(printf '%s' "$poll_log" | tail -1)"
fi

failures=$(printf '%s' "$poll_log" | grep -c 'poll failed' || true)
if [ "${failures:-0}" -gt 0 ]; then
    fail "$failures poll(s) threw" \
        "$(printf '%s' "$poll_log" | grep 'poll failed' | tail -1)"
fi

step "Durable evidence in device-encrypted storage"

# Survives reboots and log rollover, so this is the check that still works after
# a genuine overnight run.
prefs=$(as_app "cat $DE_PREFS/*.xml 2>/dev/null")
last_poll=$(printf '%s' "$prefs" | sed -n 's/.*name="last_poll_at" value="\([0-9]*\)".*/\1/p')
if [ -n "$last_poll" ]; then
    when=$(date -d "@$((last_poll / 1000))" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$last_poll")
    age=$(( ($(date +%s) - last_poll / 1000) / 60 ))
    pass "last poll recorded at $when (${age} minutes ago)"
else
    fail "no last_poll_at recorded" "No poll has ever completed on this device."
fi

last_error=$(printf '%s' "$prefs" | sed -n 's/.*name="last_error" value="\([^"]*\)".*/\1/p')
[ -n "$last_error" ] && printf '  NOTE  last error recorded: %s\n' "$last_error"

listing=$(as_app "ls -l $DE_CACHE 2>/dev/null")
if [ -n "$listing" ]; then
    pass "the cache in device-encrypted storage is populated"
    printf '%s\n' "$listing" | sed 's/^/        /'
else
    fail "the device-encrypted cache is empty" \
        "Nothing was ever fetched and stored where a locked wake can reach it."
fi

alarm=$(poll_alarm)
if [ -z "$alarm" ]; then
    fail "no alarm is pending" "The chain stopped; nothing will wake the app again."
else
    # A pending alarm is not the same as an alarm that will fire. The system
    # records the time the app asked for and the time each policy will actually
    # allow, and reports the latest of them. An alarm deferred by days is
    # indistinguishable from a healthy one unless that line is read.
    when=$(printf '%s' "$alarm" | sed -n 's/.*[^x]whenElapsed=+\([^ ]*\).*/\1/p' | head -1)
    policy=$(printf '%s' "$alarm" | grep -m1 'policyWhenElapsed' | sed 's/^ *//')
    if printf '%s' "$when" | grep -qE '^[0-9]+d'; then
        fail "an alarm is pending but deferred to +$when" \
            "It will never fire. $policy"
    elif [ -n "$when" ]; then
        pass "an alarm is pending and due in $when"
    else
        pass "an alarm is pending"
    fi
fi

step "Result"
printf '  %d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
