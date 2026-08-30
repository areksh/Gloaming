#!/usr/bin/env bash
#
# Compresses the overnight test into half an hour by forcing the device into
# deep Doze and measuring when the polls actually land.
#
# An overnight run conflates two things: whether alarms survive Doze at all, and
# how far apart they end up. Forcing the idle state answers both in one sitting,
# and reports the measured gap rather than the interval the app asked for -
# Doze rewrites that, and by how much depends on the app standby bucket and the
# battery-optimisation exemption.
#
# The device is put on simulated battery with the screen off. Everything is put
# back on exit, including after an interrupt.
#
# Usage: tools/doze-check.sh [--serial SERIAL] [--minutes N] [--bucket BUCKET]
#
#   --minutes N       how long to watch for (default 30)
#   --bucket BUCKET   force the standby bucket: active, working_set, frequent,
#                     rare or restricted. "restricted" is the worst case a
#                     rarely-opened app lands in, and the one worth proving.
#
set -euo pipefail

PACKAGE="sh.arek.gloaming"
SERIAL="${ANDROID_SERIAL:-}"
MINUTES=30
BUCKET=""
# The bucket the app was in before `--bucket` forced it, so `restore` can put it
# back. Set before anything is forced, and empty when nothing was.
ORIGINAL_BUCKET=""

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --minutes) MINUTES="$2"; shift 2 ;;
        --bucket) BUCKET="$2"; shift 2 ;;
        # Everything between the shebang and the first line of code, so adding
        # an option cannot leave the help text behind.
        -h|--help) sed -e '1d' -e '/^[^#]/,$d' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

adb() {
    if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi
}

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

DE_PREFS="/data/user_de/0/${PACKAGE}/shared_prefs"

# Runs a command as the app's own uid. The inner `sh -c` matters: without it the
# shell user expands the glob, and it cannot stat the app's directory.
as_app() {
    adb shell "run-as $PACKAGE sh -c '$1'" 2>/dev/null | tr -d '\r' || true
}

# When the app last decided something was worth interrupting someone for.
# Durable, so it can be compared across the run.
last_alert() {
    as_app "cat $DE_PREFS/*.xml 2>/dev/null" \
        | sed -n 's/.*name="last_alert_at" value="\([0-9]*\)".*/\1/p' || true
}

restore() {
    [ -n "${seen_file:-}" ] && rm -f "$seen_file"
    printf '\nRestoring the device.\n'
    adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    # Back to the bucket the app was in, not to `active`: forcing it to active
    # would leave the device in a state the run created rather than found.
    [ -n "$ORIGINAL_BUCKET" ] \
        && adb shell am set-standby-bucket "$PACKAGE" "$ORIGINAL_BUCKET" >/dev/null 2>&1 || true
    printf 'Done. The screen is still off; the device is otherwise back to normal.\n'
}

# Only EXIT runs `restore`. A signal handler that merely restores would put the
# device back and then let the watch loop carry on for the rest of its deadline,
# measuring poll spacing on a device that is no longer in Doze - so the signal
# handlers exit instead, and the EXIT trap does the restoring exactly once.
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! adb shell true >/dev/null 2>&1; then
    echo "no device reachable over adb" >&2
    exit 1
fi

if ! adb shell pm list packages 2>/dev/null | tr -d '\r' | has "^package:${PACKAGE}$"; then
    echo "$PACKAGE is not installed: ./gradlew :app:installDebug" >&2
    exit 1
fi

printf '== Setting up ==\n'

if adb shell dumpsys deviceidle whitelist 2>/dev/null | has "$PACKAGE"; then
    printf '  exempt from battery optimisation: alarms should fire on the app schedule\n'
else
    printf '  NOT exempt from battery optimisation: expect Doze to defer the wakes\n'
fi

if [ -n "$BUCKET" ]; then
    ORIGINAL_BUCKET=$(adb shell am get-standby-bucket "$PACKAGE" 2>/dev/null | tr -d '\r')
    adb shell am set-standby-bucket "$PACKAGE" "$BUCKET" >/dev/null 2>&1 \
        && printf '  standby bucket forced to %s\n' "$BUCKET" \
        || printf '  could not force the standby bucket to %s\n' "$BUCKET"
fi
printf '  standby bucket: %s\n' "$(adb shell am get-standby-bucket "$PACKAGE" 2>/dev/null | tr -d '\r')"

# Doze needs the device to believe it is unplugged, still and dark. force-idle
# skips the several minutes it would otherwise spend stepping down through the
# intermediate states.
adb shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
adb shell dumpsys battery unplug >/dev/null 2>&1 || true
adb shell dumpsys deviceidle enable deep >/dev/null 2>&1 || true
adb shell dumpsys deviceidle force-idle >/dev/null 2>&1 || true

idle_state=$(adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r')
printf '  deep idle state: %s\n' "$idle_state"
if [ "$idle_state" != "IDLE" ]; then
    printf '\n  The device did not enter deep idle, so this measures nothing.\n' >&2
    printf '  Usually the screen came back on, or something is holding a wake lock.\n' >&2
    exit 1
fi

printf '\n== Watching for %s minutes ==\n' "$MINUTES"
printf '  Each poll the app manages is printed with the gap since the previous one.\n'
printf '  Ctrl-C stops early and still restores the device.\n\n'

alert_before=$(last_alert)

# Polls are kept in a file rather than counted out of the log each pass.
#
# The log is a ring buffer, and on a busy phone it holds only minutes: an
# 84,000-line buffer here covered about twelve. Anything that reads the whole
# buffer each time and trusts the count to grow is wrong the moment it wraps -
# the earlier run of this script anchored on a marker line it had written, and
# once that marker aged out it matched nothing and reported one poll for a
# thirty-five minute run in which four had actually fired. Recording each poll
# as it is seen means a wrap can no longer un-count anything.
seen_file=$(mktemp)
started=$(date +%s)
deadline=$(( started + MINUTES * 60 ))

# The device clock, not this machine's: `logcat -v epoch` stamps lines with it,
# and a phone a few seconds adrift would otherwise hide the first poll.
start_epoch=$(adb shell date +%s 2>/dev/null | tr -d '\r')
[ -n "$start_epoch" ] || start_epoch="$started"

while [ "$(date +%s)" -lt "$deadline" ]; do
    # One awk, not grep piped into awk. `grep` exits 1 when it matches nothing,
    # which under `set -o pipefail` fails the pipeline and under `set -e` ends
    # the run - and matching nothing is the *normal* state at the start of a
    # watch, because the buffer holds about twelve minutes and the app polls
    # less often than that. So the harness aborted seconds in, having printed
    # the "watching" header, and restored the device as though it had finished a
    # measurement. awk reports no match by printing nothing and exiting 0.
    adb logcat -d -b all -v epoch 2>/dev/null | tr -d '\r' \
        | awk -v since="$start_epoch" \
            '/PollReceiver.*(poll outcome|skipping|poll failed)/ && int($1) >= since' \
        | while IFS= read -r line; do
            [ -z "$line" ] && continue
            has -Fx -- "$line" "$seen_file" && continue
            printf '%s\n' "$line" >> "$seen_file"

            epoch=$(printf '%s' "$line" | awk '{print $1}' | cut -d. -f1)
            when=$(date -d "@$epoch" '+%H:%M:%S' 2>/dev/null || printf '%s' "$epoch")
            # The gap this script promises to report, which it never printed.
            previous=$(tail -2 "$seen_file" | head -1 | awk '{print $1}' | cut -d. -f1)
            gap=""
            if [ -n "$previous" ] && [ "$previous" != "$epoch" ]; then
                gap=$(printf '  (+%dm%02ds)' $(( (epoch - previous) / 60 )) \
                    $(( (epoch - previous) % 60 )))
            fi
            printf '  %s  %s%s\n' "$when" \
                "$(printf '%s' "$line" | sed 's/.*PollReceiver[^:]*: //')" "$gap"
        done

    # force-idle can lapse; re-assert it so a long watch stays in deep Doze.
    if [ "$(adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r')" != "IDLE" ]; then
        adb shell dumpsys deviceidle force-idle >/dev/null 2>&1 || true
    fi

    sleep 20
done

elapsed=$(( ($(date +%s) - started) / 60 ))
# `wc -l`, not `grep -c .`: grep prints its count *and* exits 1 when that count
# is zero, so `grep -c . || printf '0'` yielded the two-line string "0\n0". That
# is not an integer, so the test below errored to stderr, took the branch for a
# run that saw polls, and divided by it. The run that measured nothing is
# exactly the one whose reason most needs printing.
seen=$(wc -l < "$seen_file" | tr -d ' ')
last_epoch=$(tail -1 "$seen_file" 2>/dev/null | awk '{print $1}' | cut -d. -f1)

printf '\n== Result ==\n'
printf '  %s minutes in forced deep Doze, %s polls\n' "$elapsed" "$seen"
if [ "${seen:-0}" -eq 0 ]; then
    printf '  Nothing fired at all. Either no alarm was pending, or Doze suppressed\n'
    printf '  every one. Check: adb shell dumpsys alarm | grep %s\n' "$PACKAGE"
elif [ "$elapsed" -gt 0 ]; then
    printf '  average one poll every %s minutes\n' "$(( elapsed / seen ))"
    printf '  Compare that against the interval configured in the app. Some of any\n'
    printf '  excess is the batching window the system attaches to an inexact alarm\n'
    printf '  rather than Doze: check `window=` and `maxWhenElapsed=` in\n'
    printf '  `adb shell dumpsys alarm` before concluding the exemption is failing.\n'
fi
[ -n "$last_epoch" ] && printf '  last poll at %s\n' "$(date -d "@$last_epoch" '+%H:%M:%S' 2>/dev/null)"

# Waking up is only half of it. Whether a notification can be posted from deep
# idle is the part that actually reaches the user, so it is checked separately
# rather than inferred from the poll having run.
alert_after=$(last_alert)
if [ -n "$alert_after" ] && [ "$alert_after" != "$alert_before" ]; then
    printf '  ALERT raised during the run at %s\n' \
        "$(date -d "@$((alert_after / 1000))" '+%H:%M:%S' 2>/dev/null)"
    if adb shell dumpsys notification --noredact 2>/dev/null | has "pkg=$PACKAGE"; then
        printf '  and the notification posted from deep idle\n'
    else
        printf '  but NO notification is present - posting was swallowed\n'
    fi
else
    printf '  no alert raised during the run (expected when the sky is quiet)\n'
fi
