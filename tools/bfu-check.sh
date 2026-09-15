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
#   --latitude D    observer latitude to seed (default 55.95)
#   --longitude D   observer longitude to seed (default -3.19)
#   --threshold N   alert threshold to seed. To provoke a genuine alert from
#                   real data, point it at the busiest cell in the live grid
#                   and set this just under that cell's value:
#                     curl -s https://sky.arek.sh/v1/oval.bin | gzip -dc | \
#                       od -An -tu1 -v | ...   (65160 bytes, row-major,
#                     181 rows of latitude -90..90, 360 columns of longitude)
#   --expect-alert  treat "no alert raised" as a failure. Without it a quiet
#                   sky reports as a note, which is the honest default.
#   --prepare       put the device in the state a real install is in: open the
#                   app once, grant notifications, allow it past battery
#                   optimisation. A fresh install has none of these, and
#                   connectedAndroidTest uninstalls the app when it finishes,
#                   so this is needed more often than it looks.
#   --resume        skip the reboot and just read the evidence. Use this in the
#                   morning after a real overnight run.
#   --check-only    read the device's state and stop: no reboot, no soak, no
#                   change of any kind. Answers "is this phone set up to alert
#                   tonight" in seconds, which is worth knowing before an
#                   overnight run and again the morning after one that produced
#                   nothing. `./go status` is this.
#   --reconnect-timeout N
#                   how many seconds to wait, after the soak, for the phone to
#                   be unlocked and come back (default 600). 0 waits with no
#                   limit, which is what an unattended release gate wants: the
#                   run has already spent its soak, and giving up ten minutes
#                   later throws that away over nobody being in the room.
#   --serial S      target a specific device
#
set -euo pipefail

PACKAGE="sh.arek.gloaming"
SERIAL="${ANDROID_SERIAL:-}"
SEED=0
RESUME=0
INTERVAL=2
SOAK=6
LATITUDE=""
LONGITUDE=""
THRESHOLD=""
EXPECT_ALERT=0
PREPARE=0
CHECK_ONLY=0
RECONNECT_TIMEOUT=600

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --seed) SEED=1; shift ;;
        --interval) INTERVAL="$2"; shift 2 ;;
        --soak) SOAK="$2"; shift 2 ;;
        --latitude) LATITUDE="$2"; shift 2 ;;
        --longitude) LONGITUDE="$2"; shift 2 ;;
        --threshold) THRESHOLD="$2"; shift 2 ;;
        --expect-alert) EXPECT_ALERT=1; shift ;;
        --prepare) PREPARE=1; shift ;;
        --resume) RESUME=1; shift ;;
        --check-only) CHECK_ONLY=1; shift ;;
        --reconnect-timeout) RECONNECT_TIMEOUT="$2"; shift 2 ;;
        # Everything between the shebang and the first line of code. A fixed
        # line range silently truncated the option list as it grew.
        -h|--help) sed -e '1d' -e '/^[^#]/,$d' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

adb() {
    if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi
}

# --check-only promises to change nothing, so it cannot be combined with the
# flags that exist to change something, and there is nothing for it to resume.
if [ "$CHECK_ONLY" -eq 1 ] && [ $((SEED + PREPARE + RESUME)) -gt 0 ]; then
    echo "--check-only reads what is already there and changes nothing, so it" >&2
    echo "cannot be combined with --seed, --prepare or --resume." >&2
    exit 2
fi

# Matches without stopping early. `grep -q` exits at the first match and closes
# the pipe, which kills whatever is still writing upstream with SIGPIPE - and
# under `set -o pipefail` that turns a successful match into a failed pipeline,
# so a match reads as "not found". Counting reads the stream to the end.
#
# This bit for real: the install check below reported the app missing on a phone
# with 306 packages, because the match came at line 104 and `pm` was still
# writing the other 202.
has() {
    grep -c "$@" >/dev/null
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

# Whether the app is stopped, and whether it has ever been opened. Both matter:
# a stopped app receives no broadcasts at all, and an app that has never been
# opened sits in the NEVER standby bucket.
#
# Every pipeline here ends in `|| true`. An early-exiting reader such as
# `grep -m1` or `head` closes the pipe on whatever is upstream, and under
# `pipefail` that surfaces as a failed command substitution which `set -e` then
# treats as fatal - killing the run over a match that in fact succeeded.
package_state() {
    adb shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r' \
        | grep -m1 -oE 'stopped=[a-z]+ notLaunched=[a-z]+' || true
}

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

# Epoch milliseconds of the last boot, read from the device's own clock so host
# and device do not have to agree.
#
# `last_alert_at` is durable and survives reboots, which is what makes it useful
# after an overnight run and also what makes it misleading: a timestamp from
# days ago looks exactly like one from this run. Everything this harness cares
# about happened after the reboot, so the boot moment is the cut.
boot_epoch_ms() {
    local uptime now
    uptime=$(adb shell cat /proc/uptime 2>/dev/null | tr -d '\r' | awk '{print int($1)}' || true)
    now=$(adb shell date +%s 2>/dev/null | tr -d '\r' || true)
    case "$uptime" in ''|*[!0-9]*) return 0 ;; esac
    case "$now" in ''|*[!0-9]*) return 0 ;; esac
    printf '%s' $(( (now - uptime) * 1000 ))
}

durable_evidence() {
    step "Durable evidence in device-encrypted storage"

    # Survives reboots and log rollover, so this is the check that still
    # works after a genuine overnight run.
    prefs=$(as_app "cat $DE_PREFS/*.xml 2>/dev/null")
    last_poll=$(printf '%s' "$prefs" | sed -n 's/.*name="last_poll_at" value="\([0-9]*\)".*/\1/p')
    if [ -n "$last_poll" ]; then
        when=$(date -d "@$((last_poll / 1000))" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$last_poll")
        age=$(( ($(date +%s) - last_poll / 1000) / 60 ))
        pass "last poll recorded at $when (${age} minutes ago)"
    else
        fail "no last_poll_at recorded" "No poll has ever completed on this device."
    fi

    # Strings are the one preference type without a `value` attribute: they are
    # written as `<string name="k">text</string>`, so the numeric form used above
    # matches nothing here and the last error went unreported.
    last_error=$(printf '%s' "$prefs" \
        | sed -n 's|.*<string name="last_error">\(.*\)</string>.*|\1|p')
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
        when=$(printf '%s' "$alarm" | sed -n 's/.*[^x]whenElapsed=+\([^ ]*\).*/\1/p' | head -1 || true)
        policy=$(printf '%s' "$alarm" | grep -m1 'policyWhenElapsed' | sed 's/^ *//' || true)
        if printf '%s' "$when" | has -E '^[0-9]+d'; then
            fail "an alarm is pending but deferred to +$when" \
                "It will never fire. $policy"
        elif [ -n "$when" ]; then
            pass "an alarm is pending and due in $when"
        else
            pass "an alarm is pending"
        fi
    fi
}

# ---------------------------------------------------------------- preflight --

if [ "$RESUME" -eq 0 ]; then
    if [ "$CHECK_ONLY" -eq 1 ]; then
        step "Whether this phone is set up to alert"
    else
        step "Preconditions"
    fi

    if ! device_present; then
        echo "  no device reachable over adb" >&2
        exit 1
    fi
    printf '  device: %s (GrapheneOS: %s)\n' \
        "$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')" \
        "$(adb shell pm list packages 2>/dev/null | has -i graphene && echo yes || echo no)"

    if ! adb shell pm list packages 2>/dev/null | tr -d '\r' | has "^package:${PACKAGE}$"; then
        echo "  FAIL  $PACKAGE is not installed: ./gradlew :app:installDebug" >&2
        exit 1
    fi
    pass "$PACKAGE is installed"

    # Without a lock credential the user is unlocked the moment it boots, so
    # there is no before-first-unlock state and every assertion below would pass
    # for the wrong reason. This is the most likely way to "test BFU" and learn
    # nothing at all.
    if [ "$(adb shell locksettings get-disabled 2>/dev/null | tr -d '\r')" = "true" ]; then
        if [ "$CHECK_ONLY" -eq 1 ]; then
            # Nothing below needs one. It is said anyway, because a phone that
            # is never locked cannot demonstrate the path the alerting rests on.
            skip "no lock screen credential is set" \
                "Alerting is unaffected, but this phone is unlocked from the moment it boots, so the before-first-unlock path can never happen on it."
        else
            echo "  FAIL  no lock screen credential is set" >&2
            echo "        Without one the device is never locked after boot, so this" >&2
            echo "        would pass without testing anything. Set a PIN." >&2
            exit 1
        fi
    else
        pass "a lock screen credential is set"
    fi

    # Opening the app is not cosmetic: until the user launches it once it sits
    # in the NEVER standby bucket, where alarms are deferred by a year, and a
    # freshly installed app stays in the stopped state, where it receives no
    # broadcasts at all - including the boot broadcast this whole thing rests on.
    if [ "$PREPARE" -eq 1 ]; then
        printf '  preparing: opening the app, granting notifications, exempting it\n'
        started=$(adb shell am start -n "$PACKAGE/.MainActivity" 2>&1 | tr -d '\r' || true)
        case "$started" in *Error*) printf '        %s\n' "$started" ;; esac

        # Verified rather than assumed. This launch failed silently once,
        # straight after an install, and every later check then reported a
        # fault the app did not have.
        waited=0
        while [ "$waited" -lt 20 ]; do
            case "$(package_state)" in *notLaunched=false*) break ;; esac
            sleep 2
            waited=$((waited + 2))
        done

        adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
        adb shell dumpsys deviceidle whitelist "+$PACKAGE" >/dev/null 2>&1 || true
        adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
        sleep 2
    fi

    # A stopped app receives no broadcasts, so nothing below can possibly work.
    # An app is stopped from the moment it is installed until it is first
    # opened, and force-stopping puts it back there.
    state=$(package_state)
    case "$state" in
        *stopped=false*notLaunched=false*) pass "the app has been opened and is not stopped" ;;
        *) fail "the app is stopped or has never been opened ($state)" \
               "It will receive no broadcasts, including the boot one. Open it, or pass --prepare." ;;
    esac

    if [ "$SEED" -eq 1 ]; then
        seed_args="-e interval $INTERVAL"
        [ -n "$LATITUDE" ] && seed_args="$seed_args -e latitude $LATITUDE"
        [ -n "$LONGITUDE" ] && seed_args="$seed_args -e longitude $LONGITUDE"
        [ -n "$THRESHOLD" ] && seed_args="$seed_args -e threshold $THRESHOLD"
        printf '  seeding: before-unlock alerting on, %s minute interval%s\n' \
            "$INTERVAL" \
            "$([ -n "$THRESHOLD" ] && printf ', threshold %s' "$THRESHOLD")"
        # shellcheck disable=SC2086
        if ! adb shell am instrument -w \
            -e class sh.arek.gloaming.harness.BeforeUnlockFixture \
            $seed_args \
            "${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 \
            | tr -d '\r' | has -E 'OK \(1 test\)'; then
            fail "could not seed the settings" \
                "Install the test APK: ./gradlew :app:installDebugAndroidTest"
        fi
    fi

    mirror=$(as_app "cat $DE_PREFS/*.xml 2>/dev/null")
    if [ -z "$mirror" ]; then
        fail "device-encrypted settings are empty" \
            "Run again with --seed, or switch the option on in the app."
    elif printf '%s' "$mirror" | has 'name="alerts_before_unlock" value="true"'; then
        pass "before-unlock alerting is on in device-encrypted storage"
    else
        fail "before-unlock alerting is off in device-encrypted storage" \
            "Every locked wake will skip. Run again with --seed."
    fi

    # The exemption is what lifts app-standby deferral, and app standby - not
    # Doze - is what actually stops the overnight wakes. Without it an alarm can
    # be pushed a year out while still looking perfectly healthy in dumpsys.
    if adb shell dumpsys deviceidle whitelist 2>/dev/null | has "$PACKAGE"; then
        pass "exempt from battery optimisation"
    else
        fail "not exempt from battery optimisation" \
            "App standby will defer the wakes. Grant it in the app, or for a test: adb shell dumpsys deviceidle whitelist +$PACKAGE"
    fi

    # A bucket that cannot be read is a failure, not a pass. Comparing an empty
    # or non-numeric value with `-ge` errors, and an errored test is false - so
    # the unreadable case used to report the precondition as satisfied.
    bucket=$(adb shell am get-standby-bucket "$PACKAGE" 2>/dev/null | tr -d '\r')
    if ! printf '%s' "$bucket" | has -E '^[0-9]+$'; then
        fail "could not read the app standby bucket (got '$bucket')" \
            "Without it the deferral that silences overnight wakes goes unchecked."
    elif [ "$bucket" -ge 45 ]; then
        fail "app standby bucket is $bucket: $(bucket_name "$bucket")" \
            "Alarms in this bucket are deferred indefinitely. Open the app once."
    else
        pass "app standby bucket is $bucket ($(bucket_name "$bucket"))"
    fi

    if adb shell dumpsys package "$PACKAGE" 2>/dev/null \
        | has 'POST_NOTIFICATIONS: granted=true'; then
        pass "notification permission is granted"
    else
        fail "notification permission is not granted" \
            "A poll can decide to alert and still post nothing. Grant it in the app, or: adb shell pm grant $PACKAGE android.permission.POST_NOTIFICATIONS"
    fi

    if [ "$CHECK_ONLY" -eq 1 ]; then
        # What a poll has already managed is as much of the answer as anything
        # above: an exemption granted and an alarm pending say it should work,
        # a poll recorded an hour ago says it does.
        durable_evidence
        step "Result"
        printf '  %d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
        [ "$FAIL" -eq 0 ] || exit 1
        exit 0
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
        while ! device_present; do
            if [ "$RECONNECT_TIMEOUT" -ne 0 ] && [ "$waited" -ge "$RECONNECT_TIMEOUT" ]; then
                echo "  device never came back; nothing to read" >&2
                exit 1
            fi
            sleep 5
            waited=$((waited + 5))
            # An unlimited wait and a hung script look identical from the other
            # side of the room, so it says where it is once a minute.
            if [ $((waited % 60)) -eq 0 ]; then
                printf '  still locked, %sm elapsed; the run resumes on unlock\n' \
                    $((waited / 60))
            fi
        done
    fi
    printf '  device is back at %s\n' "$(date '+%H:%M:%S')"
fi

# ----------------------------------------------------------------- evidence --

if ! device_present; then
    echo "no device reachable over adb" >&2
    exit 1
fi

# The app's own journal, read once for every check below that wants a record
# older than logcat can hold. It lives in device-encrypted storage precisely so
# a locked poll can write to it. It once could not: the retention setting was
# read from the credential-encrypted store, which throws before first unlock,
# and the catch around it swallowed every entry - so the journal was empty for
# exactly the window this harness exercises while the logcat check looked
# healthy. Logcat rolls over in minutes, so by morning it is the only record.
#
# Entries from before this boot are excluded, or a line from last night's run
# would satisfy a check for ever. ISO-8601 stamps in UTC compare
# lexicographically, so the filter is a string comparison.
journal=$(as_app "cat $DE_CACHE/gloaming.log 2>/dev/null")
journal_boot=$(boot_epoch_ms)
booted_iso=""
if [ -n "$journal_boot" ]; then
    booted_iso=$(date -u -d "@$((journal_boot / 1000))" '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || true)
fi
since_boot() {
    awk -v boot="$booted_iso" 'boot == "" || $1 >= boot'
}

step "Did the boot receiver re-arm the alarm before unlock?"

# The journal first, logcat only as a supplement.
#
# This line is written seconds after boot, and the soak plus the wait for
# somebody to unlock routinely leave it further back than the ring buffer
# reaches - on a resumed overnight run, hours further. The rollover guard could
# not catch that by itself, because unlocking makes this same receiver log
# BOOT_COMPLETED at that moment: a fresh line proving the buffer is alive while
# the line that matters has already aged out of it. So the most load-bearing
# link in the whole chain reported itself broken on a run where the journal
# showed it had done its job 36 seconds after boot.
#
# Logcat is still read, because it is there before the journal has been written
# and its line carries the tag. It is filtered to our own messages: the platform
# has a BootReceiver of its own logging under exactly the same tag, and matching
# on what this receiver actually says is what keeps those out.
journalled_boot=$(printf '%s' "$journal" | grep 'BootReceiver' | since_boot || true)
boot_log=$(our_log BootReceiver | grep -E 'reschedul' || true)

if printf '%s' "$journalled_boot" | has 'LOCKED_BOOT_COMPLETED'; then
    pass "BootReceiver ran on LOCKED_BOOT_COMPLETED"
    printf '%s\n' "$journalled_boot" | grep 'LOCKED_BOOT_COMPLETED' | tail -2 | sed 's/^/        /'
elif printf '%s' "$boot_log" | has 'LOCKED_BOOT_COMPLETED'; then
    pass "BootReceiver ran on LOCKED_BOOT_COMPLETED"
    printf '%s\n' "$boot_log" | grep 'LOCKED_BOOT_COMPLETED' | tail -2 | sed 's/^/        /'
elif [ -n "$journalled_boot" ]; then
    # The journal is durable and has recorded this receiver since the boot, so
    # the absence of the locked broadcast is real rather than a rollover.
    fail "BootReceiver never logged a LOCKED_BOOT_COMPLETED" \
        "Without it no alarm is armed and the app sleeps until someone unlocks."
elif [ -z "$journal" ]; then
    skip "no journal, and no LOCKED_BOOT_COMPLETED left in the log buffer" \
        "Nothing durable to read it from. last_poll_at below is the check that stands."
else
    skip "no BootReceiver lines since this boot" \
        "The buffer may have rolled over. last_poll_at below is the durable check."
fi

step "Did a poll actually run while the device was locked?"

# The app logs the unlock state at the moment of the poll, so this needs no
# inference about when the unlock happened.
#
# Answered from the app's own journal first, and only then from logcat. The
# journal is durable; logcat is a ring buffer holding about twelve minutes on
# this phone, and the whole point of this harness is a device left locked for
# longer than that - a six minute soak plus however long it waits for somebody
# to come and unlock it. Forty minutes of waiting was enough to age every locked
# poll out of the buffer, and this then reported "the poll receiver never ran at
# all" while the very next check found two of them in the journal. A release
# gate refusing to publish on that is refusing on evidence that expired.
poll_log=$(our_log PollReceiver)
journal_polls=$(printf '%s' "$journal" | grep 'PollReceiver' | since_boot || true)
locked_polls=$(printf '%s' "$journal_polls" | grep -c 'unlocked=false' || true)
logcat_locked=$(printf '%s' "$poll_log" | grep -c 'unlocked=false' || true)
[ "${locked_polls:-0}" -eq 0 ] && locked_polls="$logcat_locked"
skipped=$(printf '%s\n%s' "$journal_polls" "$poll_log" \
    | grep -c 'before-unlock alerting is off' || true)

if [ "${locked_polls:-0}" -gt 0 ]; then
    pass "$locked_polls poll(s) completed before first unlock"
    printf '%s\n' "$journal_polls" | grep 'unlocked=false' | tail -3 | sed 's/^/        /'
elif [ "${skipped:-0}" -gt 0 ]; then
    fail "the wake happened but every poll skipped" \
        "canPollNow said no: the settings mirror is missing, not the alarm."
elif [ -n "$journal_polls" ]; then
    fail "polls ran, but only after unlock" \
        "$(printf '%s' "$journal_polls" | tail -1)"
elif [ -z "$journal" ]; then
    skip "no journal, and logcat has rolled past the soak" \
        "Nothing durable to read. Switch the app's log on, or read last_poll_at below."
else
    fail "the poll receiver never ran at all" \
        "No alarm fired. Check the exemption and the standby bucket."
fi

failures=$(printf '%s\n%s' "$journal_polls" "$poll_log" | grep -c 'poll failed' || true)
if [ "${failures:-0}" -gt 0 ]; then
    fail "$failures poll(s) threw" \
        "$(printf '%s\n%s' "$journal_polls" "$poll_log" | grep 'poll failed' | tail -1)"
fi

step "Did the app's own journal record the locked polls?"

# Read above, with the reasoning for reading it at all.
# PollReceiver only. Counting every line carrying `unlocked=false` swept in the
# BootReceiver's own re-arm, so a soak with one poll in it reported two - a
# check that says "polls" and counts something else.
journalled_locked=$(printf '%s' "$journal" | grep 'PollReceiver' | grep 'unlocked=false' \
    | since_boot | grep -c . || true)
if [ "${journalled_locked:-0}" -gt 0 ]; then
    pass "$journalled_locked locked poll(s) are in the app's own journal"
elif [ "${locked_polls:-0}" -gt 0 ]; then
    fail "polls ran while locked but the journal has none of them" \
        "A journal blind before unlock explains nothing by morning; check Notes reads the poll's own settings store."
elif [ -z "$journal" ]; then
    skip "no journal to read" \
        "Log retention may be off, or nothing has been journalled yet."
else
    skip "no locked polls for the journal to have recorded"
fi

step "Did an alert actually post?"

# Quiet conditions are the normal case and produce no alert, so this only
# becomes a failure when the run was set up to expect one. Two independent
# records are checked: the notification the system still holds, and the
# timestamp the app wrote before it posted.
#
# The timestamp is durable, so it is dated against the boot rather than taken at
# face value. Without that an alert from days ago reads as a pass and satisfies
# --expect-alert, which is precisely the false result this harness exists to
# rule out.
recorded_at=$(as_app "cat $DE_PREFS/*.xml 2>/dev/null" \
    | sed -n 's/.*name="last_alert_at" value="\([0-9]*\)".*/\1/p')
booted_at=$(boot_epoch_ms)
posted=$(adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' \
    | grep -c "pkg=$PACKAGE" || true)

alert_at=""
stale_alert=""
if [ -n "$recorded_at" ]; then
    if [ -z "$booted_at" ] || [ "$recorded_at" -ge "$booted_at" ]; then
        alert_at="$recorded_at"
    else
        stale_alert="$recorded_at"
    fi
fi

if [ -n "$alert_at" ]; then
    when=$(date -d "@$((alert_at / 1000))" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$alert_at")
    pass "an alert was raised at $when"
elif [ "$EXPECT_ALERT" -eq 1 ]; then
    fail "no alert was raised" \
        "The poll ran but the decision never reached Notify."
else
    printf '  NOTE  no alert raised; expected when conditions are quiet\n'
fi

if [ -n "$stale_alert" ]; then
    when=$(date -d "@$((stale_alert / 1000))" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$stale_alert")
    printf '  NOTE  the most recent alert on record is %s, from before this boot\n' "$when"
fi

if [ "${posted:-0}" -gt 0 ]; then
    pass "a notification from $PACKAGE is live in the shade"
    { adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' \
        | grep -A 3 "pkg=$PACKAGE" | grep -E 'channel|when=|pkg=' | head -4 \
        | sed 's/^/        /'; } || true
elif [ -n "$alert_at" ]; then
    fail "an alert was raised but no notification is present" \
        "Posting was swallowed - most likely POST_NOTIFICATIONS is denied."
elif [ "$EXPECT_ALERT" -eq 1 ]; then
    fail "no notification was posted"
fi

durable_evidence

step "Result"
printf '  %d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
