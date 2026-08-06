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

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --minutes) MINUTES="$2"; shift 2 ;;
        --bucket) BUCKET="$2"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

adb() {
    if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi
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
    printf '\nRestoring the device.\n'
    adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    [ -n "$BUCKET" ] && adb shell am set-standby-bucket "$PACKAGE" active >/dev/null 2>&1 || true
    printf 'Done. The screen is still off; the device is otherwise back to normal.\n'
}
trap restore EXIT INT TERM

if ! adb shell true >/dev/null 2>&1; then
    echo "no device reachable over adb" >&2
    exit 1
fi

if ! adb shell pm list packages 2>/dev/null | grep -q "^package:${PACKAGE}$"; then
    echo "$PACKAGE is not installed: ./gradlew :app:installDebug" >&2
    exit 1
fi

printf '== Setting up ==\n'

if adb shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PACKAGE"; then
    printf '  exempt from battery optimisation: alarms should fire on the app schedule\n'
else
    printf '  NOT exempt from battery optimisation: expect Doze to defer the wakes\n'
fi

if [ -n "$BUCKET" ]; then
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

marker="gloaming-doze-$$"
adb shell log -t GloamingHarness "$marker" >/dev/null 2>&1 || true
alert_before=$(last_alert)

deadline=$(( $(date +%s) + MINUTES * 60 ))
started=$(date +%s)
seen=0
last_epoch=""

while [ "$(date +%s)" -lt "$deadline" ]; do
    lines=$(adb logcat -d -b all -v epoch 2>/dev/null | tr -d '\r' \
        | sed -n "/$marker/,\$p" | grep -E 'PollReceiver.*(poll outcome|skipping|poll failed)' || true)

    count=$(printf '%s' "$lines" | grep -c . || true)
    if [ "${count:-0}" -gt "$seen" ]; then
        printf '%s\n' "$lines" | tail -n +$((seen + 1)) | while IFS= read -r line; do
            [ -z "$line" ] && continue
            epoch=$(printf '%s' "$line" | awk '{print $1}' | cut -d. -f1)
            when=$(date -d "@$epoch" '+%H:%M:%S' 2>/dev/null || printf '%s' "$epoch")
            printf '  %s  %s\n' "$when" "$(printf '%s' "$line" | sed 's/.*PollReceiver[^:]*: //')"
        done
        # Recomputed outside the subshell, which cannot write back to it.
        last_epoch=$(printf '%s' "$lines" | tail -1 | awk '{print $1}' | cut -d. -f1)
        seen="$count"
    fi

    # force-idle can lapse; re-assert it so a long watch stays in deep Doze.
    if [ "$(adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r')" != "IDLE" ]; then
        adb shell dumpsys deviceidle force-idle >/dev/null 2>&1 || true
    fi

    sleep 20
done

elapsed=$(( ($(date +%s) - started) / 60 ))

printf '\n== Result ==\n'
printf '  %s minutes in forced deep Doze, %s polls\n' "$elapsed" "$seen"
if [ "${seen:-0}" -eq 0 ]; then
    printf '  Nothing fired at all. Either no alarm was pending, or Doze suppressed\n'
    printf '  every one. Check: adb shell dumpsys alarm | grep %s\n' "$PACKAGE"
elif [ "$elapsed" -gt 0 ]; then
    printf '  average one poll every %s minutes\n' "$(( elapsed / seen ))"
    printf '  Compare that against the interval configured in the app. A much larger\n'
    printf '  gap is Doze deferring the wakes, which the exemption is there to lift.\n'
fi
[ -n "$last_epoch" ] && printf '  last poll at %s\n' "$(date -d "@$last_epoch" '+%H:%M:%S' 2>/dev/null)"

# Waking up is only half of it. Whether a notification can be posted from deep
# idle is the part that actually reaches the user, so it is checked separately
# rather than inferred from the poll having run.
alert_after=$(last_alert)
if [ -n "$alert_after" ] && [ "$alert_after" != "$alert_before" ]; then
    printf '  ALERT raised during the run at %s\n' \
        "$(date -d "@$((alert_after / 1000))" '+%H:%M:%S' 2>/dev/null)"
    if adb shell dumpsys notification --noredact 2>/dev/null | grep -q "pkg=$PACKAGE"; then
        printf '  and the notification posted from deep idle\n'
    else
        printf '  but NO notification is present - posting was swallowed\n'
    fi
else
    printf '  no alert raised during the run (expected when the sky is quiet)\n'
fi
