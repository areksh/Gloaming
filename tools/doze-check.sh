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
# It reports PASS and FAIL per check and exits non-zero on any failure, so a
# release gate can depend on it. What it will not decide for you is the spacing:
# the interval Doze settles on depends on the standby bucket, the exemption and
# the batching window the system attaches to an inexact alarm, so the measured
# lateness is a failure only when you say what you allow with --expect-late.
#
# It measures nothing at all unless the app is on a schedule worth watching, and
# PollingPolicy decides that from the sky. --observer borrows a position where
# there is something to watch, and puts the phone's own back afterwards.
#
# Usage: tools/doze-check.sh [--serial SERIAL] [--minutes N] [--bucket BUCKET]
#
#   --minutes N       how long to watch for (default 30)
#   --bucket BUCKET   force the standby bucket: active, working_set, frequent,
#                     rare or restricted. "restricted" is the worst case a
#                     rarely-opened app lands in, and the one worth proving.
#   --expect-late N   treat delivery more than N minutes behind what the app
#                     asked for as a failure. Without it the lateness is
#                     measured and reported but never fails.
#
#                     Against the app's own request, not against a number typed
#                     here, because the request is the only thing Doze can be
#                     said to have deferred. `dumpsys alarm` prints it beside
#                     each policy's opinion:
#
#                       policyWhenElapsed: requester=+30m app_standby=-35m
#
#                     and the gap between that and when the poll actually landed
#                     is the whole quantity this harness exists to measure. One
#                     tolerance then fits every interval the policy picks: the
#                     overhead is a batching window of a few minutes, a roughly
#                     fixed cost rather than a multiplier (see PollingPolicy's
#                     note on ELEVATED_MINUTES).
#
#                     The gap it is measured from is the mean interval between
#                     consecutive polls, so it takes two polls to have one: a
#                     run that saw fewer says so rather than offering a number.
#
#   --observer WHERE  borrow somewhere the app will actually be polling from,
#                     and put the phone's own position back afterwards.
#
#                     "auto" asks tools/pick-observer.sh for the clearest site
#                     that is dark for the whole run; "LAT,LON" names one. Both
#                     need the instrumentation APK, because seeding goes through
#                     the same save path the settings screen uses:
#                       ./go install --test
#
#                     Without this the harness measures whatever the phone's own
#                     sky is doing, which in daylight is a wake hours away and
#                     nothing to see. PollingPolicy plans from the sky, not from
#                     the interval in the settings, so a short interval seeded
#                     there does not produce a short schedule on a phone that
#                     knows where it is.
#
#   --settle N        minutes to let the app re-plan from the borrowed sky
#                     before the watch starts (default 8). The seeded interval
#                     arms the first wake; that poll is the one that reads the
#                     new position and sets the real schedule, so the watch
#                     begins after it rather than measuring it.
#
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PACKAGE="sh.arek.gloaming"
SERIAL="${ANDROID_SERIAL:-}"
MINUTES=30
BUCKET=""
EXPECT_LATE=""
OBSERVER=""
SETTLE=8
# The interval seeded purely to arm the first wake. Never the schedule under
# test: that one is PollingPolicy's, and the settling poll below is what
# replaces this with it.
ARM_INTERVAL=2
# The bucket the app was in before `--bucket` forced it, so `restore` can put it
# back. Set before anything is forced, and empty when nothing was.
ORIGINAL_BUCKET=""

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --minutes) MINUTES="$2"; shift 2 ;;
        --bucket) BUCKET="$2"; shift 2 ;;
        --expect-late) EXPECT_LATE="$2"; shift 2 ;;
        --observer) OBSERVER="$2"; shift 2 ;;
        --settle) SETTLE="$2"; shift 2 ;;
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

# Same shape as tools/bfu-check.sh, and for the same reason: a harness that
# prints its findings and exits 0 regardless cannot gate anything, and reading
# the output is exactly the step a release skips.
PASS=0
FAIL=0
SKIP=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; return 0; }
skip() { SKIP=$((SKIP + 1)); printf '  SKIP  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; return 0; }

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

# The poll interval the app is actually set to, for the reader comparing it
# against the gap measured below. Device-encrypted, because a poll before first
# unlock has to be able to read it. Absent on a phone whose settings have never
# been written, which is not a failure - the default applies and the app polls.
configured_interval() {
    as_app "cat $DE_PREFS/*.xml 2>/dev/null" \
        | sed -n 's/.*name="poll_minutes" value="\([0-9]*\)".*/\1/p' | head -1 || true
}

# One value out of the device-encrypted settings mirror.
pref_value() {
    as_app "cat $DE_PREFS/*.xml 2>/dev/null" \
        | sed -n "s/.*name=\"$1\" value=\"\([^\"]*\)\".*/\1/p" | head -1
}

# Coordinates are stored as `Double.toRawBits()`, so the number in the file is
# not a coordinate. Node rather than a shell arithmetic trick because IEEE-754
# is not something to reimplement in `expr`, and through the environment rather
# than as an argument because every negative coordinate has the sign bit set,
# making its raw bits a negative integer that node's option parser takes for a
# flag. Same function as `./go` has, for the same two reasons.
bits_to_double() {
    GLOAMING_RAW_BITS="$1" node -e 'const b = Buffer.alloc(8);
             b.writeBigInt64BE(BigInt(process.env.GLOAMING_RAW_BITS));
             console.log(b.readDoubleBE(0));'
}

# The phone's own settings, read before anything is seeded over them.
#
# BeforeUnlockFixture writes a whole UserSettings rather than editing a field,
# so seeding without passing these back puts Edinburgh and the default threshold
# on whatever phone the harness ran on - and the position it replaced is the one
# thing here nobody can reconstruct.
SAVED_LATITUDE=""
SAVED_LONGITUDE=""
SAVED_THRESHOLD=""
SAVED_INTERVAL=""
SEEDED=0

capture_settings() {
    local latitude longitude
    latitude=$(pref_value latitude)
    longitude=$(pref_value longitude)
    SAVED_THRESHOLD=$(pref_value threshold)
    SAVED_INTERVAL=$(pref_value poll_minutes)
    [ -n "$latitude" ] && SAVED_LATITUDE=$(bits_to_double "$latitude")
    [ -n "$longitude" ] && SAVED_LONGITUDE=$(bits_to_double "$longitude")
    if [ -z "$SAVED_LATITUDE" ] || [ -z "$SAVED_LONGITUDE" ]; then
        echo "the phone has no observer position recorded, so borrowing one would" >&2
        echo "leave it there. Open the app and set a location first." >&2
        exit 1
    fi
}

# Writes a whole settings object through the same path the settings screen uses.
# Everything not being changed is passed straight back in.
seed_settings() {
    local latitude="$1" longitude="$2" interval="$3"
    local args=(-e latitude "$latitude" -e longitude "$longitude" -e interval "$interval")
    [ -n "$SAVED_THRESHOLD" ] && args+=(-e threshold "$SAVED_THRESHOLD")
    adb shell am instrument -w \
        -e class sh.arek.gloaming.harness.BeforeUnlockFixture \
        "${args[@]}" \
        "${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 \
        | tr -d '\r' | has -E 'OK \(1 test\)'
}

# The pending poll alarm, matched on the receiver rather than the package.
# `dumpsys alarm` names packages in unrelated bookkeeping, and a bare package
# match reports an alarm that is not there. The history section is cut off
# first, or an alarm that has already fired reads as one still to come.
poll_alarm() {
    adb shell dumpsys alarm 2>/dev/null | tr -d '\r' \
        | sed -n '1,/App Alarm history/p' \
        | grep -A 4 "walarm\*:${PACKAGE}/\.work\.PollReceiver" || true
}

# The first of `dumpsys alarm`'s duration fields to match, in seconds from now.
# Each argument after the dump is an awk pattern, tried in order.
#
# The trailing milliseconds are stripped before anything else. Android formats
# these as `+1d2h3m4s567ms`, and a parser that walks the suffixes left to right
# reads that final `m` as minutes and adds nine hours to the answer.
alarm_field_seconds() {
    local dump="$1"
    shift
    # Newline-separated, not space-separated: every pattern here contains a
    # `[^ ]*`, so splitting them on whitespace cut each one in half and awk
    # rejected the halves as invalid regexps.
    printf '%s' "$dump" | awk -v patterns="$(printf '%s\n' "$@")" '
        BEGIN { count = split(patterns, pattern, "\n") }
        {
            field = ""
            for (i = 1; i <= count && field == ""; i++)
                if (match($0, pattern[i]))
                    field = substr($0, RSTART, RLENGTH)
            if (field == "") next
            sub(/.*=/, "", field)
            # Already past: due now, not due in the past.
            if (substr(field, 1, 1) == "-") { print 0; exit }
            sub(/^\+/, "", field)
            sub(/[0-9]+ms$/, "", field)
            d = h = m = sec = 0
            if (match(field, /[0-9]+d/)) d   = substr(field, RSTART, RLENGTH - 1)
            if (match(field, /[0-9]+h/)) h   = substr(field, RSTART, RLENGTH - 1)
            if (match(field, /[0-9]+m/)) m   = substr(field, RSTART, RLENGTH - 1)
            if (match(field, /[0-9]+s/)) sec = substr(field, RSTART, RLENGTH - 1)
            print d * 86400 + h * 3600 + m * 60 + sec
            exit
        }'
}

# The latest the pending alarm can fire, in seconds from now. This is the only
# honest reference for "should something have fired by now": the configured
# interval is not it, because adaptive polling backs the real one off to hours
# when there is nothing to watch.
#
# `maxWhenElapsed` in preference to `whenElapsed`, because the two answer
# different questions. `whenElapsed` is routinely *negative* on a dozing phone -
# the alarm was due seconds ago and the system is holding it - and reading that
# as "overdue, so suppression" would fail a device that is about to fire well
# inside the window the system reserved. `maxWhenElapsed` is the deadline the
# policies actually allow, so a watch shorter than it proves nothing.
alarm_due_seconds() {
    alarm_field_seconds "$1" 'maxWhenElapsed=[+-][^ ]*' '[^x]whenElapsed=[+-][^ ]*'
}

# How far out the *app* asked for the next wake, in seconds from now.
#
# This is the field that separates the two findings this harness is confused
# between. `dumpsys alarm` prints the request alongside each policy's opinion:
#
#   policyWhenElapsed: requester=+4h57m app_standby=-35m device_idle=-5m
#
# A gap wider than expected is Doze deferring the wakes only when the policies
# moved the alarm; when `requester` is already hours out, the app asked for that
# itself and there is nothing here to fail. It routinely does: `PollingPolicy`
# sleeps until dark in daylight and backs off to `QUIET_MINUTES` on a clear
# quiet night, whatever poll interval the settings hold.
alarm_requested_seconds() {
    alarm_field_seconds "$1" 'requester=[+-][^ ]*'
}

# The longest request seen so far, which is the closest thing to the interval
# the app actually asked for.
#
# `requester=` is an absolute time rendered as an offset from now, so it counts
# down: sampled half way through a half-hour wait it reads fifteen minutes, and
# calling that "the interval" would compare a full gap against a partial one and
# report a healthy phone as fifteen minutes late. It only equals the interval in
# the moment after the alarm is set, so this takes the maximum across the run
# instead of trusting any single reading. Sampled every watch cycle, which on a
# half-hour interval catches the peak within twenty seconds.
#
# Taking the maximum is also the forgiving direction: a stray long reading makes
# the measured lateness smaller, never larger, so it cannot invent a failure.
ASKED_INTERVAL=""
note_request() {
    local seen
    seen=$(alarm_requested_seconds "$(poll_alarm)")
    [ -n "$seen" ] || return 0
    if [ -z "$ASKED_INTERVAL" ] || [ "$seen" -gt "$ASKED_INTERVAL" ]; then
        ASKED_INTERVAL="$seen"
    fi
}

restore() {
    [ -n "${seen_file:-}" ] && rm -f "$seen_file"
    printf '\nRestoring the device.\n'
    adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    # The borrowed position goes back first, and loudly if it cannot: leaving a
    # phone pointed at a mountain in Chile would quietly stop it alerting for
    # wherever its owner actually is, and nothing else here would say so.
    if [ "$SEEDED" -eq 1 ]; then
        printf '  putting the position back to %s, %s\n' "$SAVED_LATITUDE" "$SAVED_LONGITUDE"
        if ! seed_settings "$SAVED_LATITUDE" "$SAVED_LONGITUDE" "${SAVED_INTERVAL:-15}"; then
            printf '  WARNING: could not put the settings back. Check ./go status.\n' >&2
        fi
    fi
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

# Printed for the reader comparing numbers, with the caveat attached: this is
# the floor the app falls back to without a position, not the schedule it keeps.
# Read on its own next to a wake hours away it looks like a fault, and is not.
CONFIGURED=$(configured_interval)
if [ -n "$CONFIGURED" ]; then
    printf '  the settings hold a %s minute interval, which applies only without a position\n' \
        "$CONFIGURED"
fi

# Borrowing a sky the app will poll under.
#
# The order matters. The position is seeded first, then the app is opened, which
# is what arms a wake at the seeded interval (MainActivity schedules on start).
# That first poll reads the new position and replaces the alarm with the one
# PollingPolicy really wants - and it is that second alarm the watch is here to
# measure, so the settle below waits for it rather than starting the clock on a
# schedule that is about to be thrown away.
if [ -n "$OBSERVER" ]; then
    if ! adb shell pm list packages 2>/dev/null | tr -d '\r' | has "^package:${PACKAGE}.test$"; then
        echo "--observer seeds settings through the instrumentation APK, which is not" >&2
        echo "installed: ./go install --test" >&2
        exit 1
    fi
    capture_settings

    case "$OBSERVER" in
        auto)
            # Dark for the watch and the settle, with an interval's margin on
            # top: a site whose dawn arrives during the last poll would have the
            # app re-plan to DAYLIGHT, and that gap reads exactly like a
            # deferral.
            picked=$("$HERE/pick-observer.sh" --dark-for $((MINUTES + SETTLE + 60))) \
                || { echo "could not choose an observer" >&2; exit 1; }
            OBS_LAT=$(printf '%s' "$picked" | cut -f1)
            OBS_LON=$(printf '%s' "$picked" | cut -f2)
            OBS_SUN=$(printf '%s' "$picked" | cut -f3)
            OBS_CLOUD=$(printf '%s' "$picked" | cut -f4)
            OBS_NAME=$(printf '%s' "$picked" | cut -f5)
            ;;
        *,*)
            OBS_LAT=${OBSERVER%%,*}
            OBS_LON=${OBSERVER##*,}
            OBS_SUN="?"; OBS_CLOUD="?"; OBS_NAME="given on the command line"
            ;;
        *)
            echo "--observer takes 'auto' or 'LAT,LON', not '$OBSERVER'" >&2
            exit 2
            ;;
    esac

    printf '  observer: %s  (%s, %s)\n' "$OBS_NAME" "$OBS_LAT" "$OBS_LON"
    printf '    sun %s deg, cloud %s%%\n' "$OBS_SUN" "$OBS_CLOUD"
    printf '    the phone is at %s, %s, and goes back there afterwards\n' \
        "$SAVED_LATITUDE" "$SAVED_LONGITUDE"

    # Set before the seed rather than after it, so an interrupt between the two
    # still restores. A seed that half-succeeded is exactly the case that must
    # not be left behind.
    SEEDED=1
    if ! seed_settings "$OBS_LAT" "$OBS_LON" "$ARM_INTERVAL"; then
        echo "  could not seed the borrowed position" >&2
        exit 1
    fi
    # Opening the app is what arms the wake; HOME puts it back in the background
    # so the device can reach deep idle afterwards.
    adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 3
    adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    sleep 1
fi

# Checked before the watch rather than inferred from its silence afterwards.
# Half an hour spent measuring a phone with nothing pending reports "nothing
# fired" and means nothing at all by it, and that is indistinguishable in the
# output from Doze having suppressed every wake - which is the finding this
# harness exists to produce. Fail fast instead, as bfu-check does before it
# spends a reboot.
ALARM=$(poll_alarm)
if [ -z "$ALARM" ]; then
    fail "no alarm is pending" \
        "Nothing will wake the app, so there is nothing here to measure. Open the app once and check ./go status."
    printf '\nFix the above before watching.\n'
    exit 1
fi
ALARM_DUE=$(alarm_due_seconds "$ALARM")
# What the app asked for, read before the watch rather than after it: the polls
# below overwrite the pending alarm, so by the end the request on record is the
# one the last poll made, not the one the run set out to measure.
ALARM_ASKED=$(alarm_requested_seconds "$ALARM")
ASKED_INTERVAL="$ALARM_ASKED"
if [ -n "$ALARM_ASKED" ]; then
    printf '  the app asked for its next wake in %sm %ss\n' \
        "$((ALARM_ASKED / 60))" "$((ALARM_ASKED % 60))"
fi
if [ -n "$ALARM_DUE" ]; then
    pass "an alarm is pending, and must fire within $((ALARM_DUE / 60))m $((ALARM_DUE % 60))s"
    if [ "$ALARM_DUE" -gt $((MINUTES * 60)) ]; then
        printf '  NOTE: that is beyond the %sm being watched, so a quiet run proves\n' "$MINUTES"
        printf '        nothing either way. PollingPolicy backs the interval off when\n'
        printf '        there is nothing to watch, and the interval in the settings does\n'
        printf '        not override it on a phone that has a position. Watch for longer,\n'
        printf '        or measure when the sky has the app polling fast.\n'
    fi
else
    pass "an alarm is pending"
fi

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

# Let the seeded wake fire once, so the schedule being measured is the one
# PollingPolicy chose from the borrowed sky rather than the two minutes used to
# arm it. Done inside deep idle, because a poll that lands here is also the
# first evidence that wakes are getting through at all.
if [ -n "$OBSERVER" ]; then
    printf '\n== Settling ==\n'
    printf '  waiting up to %sm for the first poll to re-plan from the new position\n' "$SETTLE"
    settle_from=$(adb shell date +%s 2>/dev/null | tr -d '\r')
    [ -n "$settle_from" ] || settle_from=$(date +%s)
    settle_deadline=$(( $(date +%s) + SETTLE * 60 ))
    polled=0
    settled=0

    # Two things have to happen, and waiting for the first is not waiting for
    # the second. PollReceiver arms a provisional alarm at the seeded interval
    # *before* it does any work, and only replaces it with the real one in its
    # `finally`, after the pass and calendar checks have run too. Measured on
    # this phone that is around thirty seconds after the outcome is logged:
    #
    #   11:08:36  poll outcome: Quiet(...) (next in 30m: QUIET)
    #   11:08:50  pass check: ...
    #   11:09:04  calendar check: ...
    #
    # So a settle that re-read the alarm the moment the outcome appeared caught
    # the provisional one every time, and the watch then measured the two-minute
    # arming interval while reporting it as the app's own schedule. The
    # condition below is the honest one: wait until the pending request is
    # longer than the interval used to arm it, which is what "the app has
    # re-planned" actually means. ELEVATED_MINUTES is five, so any schedule the
    # policy can choose clears the arming interval and its minute of slack.
    arming_seconds=$((ARM_INTERVAL * 60 + 60))
    while [ "$(date +%s)" -lt "$settle_deadline" ]; do
        if [ "$polled" -eq 0 ] && adb logcat -d -b all -v epoch 2>/dev/null | tr -d '\r' \
            | awk -v since="$settle_from" \
                '/PollReceiver.*(poll outcome|skipping|poll failed)/ && int($1) >= since' \
            | has . ; then
            polled=1
        fi
        if [ "$polled" -eq 1 ]; then
            asked_now=$(alarm_requested_seconds "$(poll_alarm)")
            if [ -n "$asked_now" ] && [ "$asked_now" -gt "$arming_seconds" ]; then
                settled=1
                break
            fi
        fi
        if [ "$(adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r')" != "IDLE" ]; then
            adb shell dumpsys deviceidle force-idle >/dev/null 2>&1 || true
        fi
        sleep 10
    done

    if [ "$settled" -eq 1 ]; then
        # Re-read in full, because that poll replaced the alarm. Everything
        # below compares against this, not against the arming interval.
        ALARM=$(poll_alarm)
        ALARM_DUE=$(alarm_due_seconds "$ALARM")
        ALARM_ASKED=$(alarm_requested_seconds "$ALARM")
        # Read in the moment after the reschedule, so this one is the interval
        # itself rather than a countdown part way through it.
        ASKED_INTERVAL="$ALARM_ASKED"
        pass "the app re-planned from the borrowed sky"
        printf '  it now asks to wake every %sm %ss\n' \
            "$((ALARM_ASKED / 60))" "$((ALARM_ASKED % 60))"
    elif [ "$polled" -eq 1 ]; then
        fail "a poll landed but the schedule stayed at the arming interval" \
            "The poll most likely failed and fell back to the flat interval, so there is no plan from the borrowed sky to measure. Check ./go log."
    else
        fail "no poll landed in ${SETTLE}m of deep Doze, so nothing re-planned" \
            "The watch below would measure the arming interval, not the app's own schedule."
    fi
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

    # Every cycle, because the app re-plans at every poll and the request decays
    # in between. The peak is what the interval was.
    note_request

    sleep 20
done

elapsed=$(( ($(date +%s) - started) / 60 ))
# `wc -l`, not `grep -c .`: grep prints its count *and* exits 1 when that count
# is zero, so `grep -c . || printf '0'` yielded the two-line string "0\n0". That
# is not an integer, so the test below errored to stderr, took the branch for a
# run that saw polls, and divided by it. The run that measured nothing is
# exactly the one whose reason most needs printing.
seen=$(wc -l < "$seen_file" | tr -d ' ')
# Sorted rather than taken from the ends of the file. The polls are appended in
# the order the log yields them, which is chronological today and is not a
# property worth betting an arithmetic mean on.
first_epoch=$(awk '{print int($1)}' "$seen_file" 2>/dev/null | sort -n | head -1)
last_epoch=$(awk '{print int($1)}' "$seen_file" 2>/dev/null | sort -n | tail -1)

# The mean interval between consecutive polls.
#
# Not the watch divided by the number of polls, which is what this measured
# before and is not a gap at all: n polls have n-1 gaps between them, and the
# watch begins and ends mid-interval, so both ends contribute time that lies
# outside any gap. A single poll has no gap whatsoever, and dividing by it
# reported the whole watch as one - half an hour of watching a phone that had
# fired once turned into "an average gap of 30m", which then failed the gate on
# the strength of no measurement at all.
GAP=""
if [ "${seen:-0}" -ge 2 ]; then
    GAP=$(( (last_epoch - first_epoch) / (seen - 1) ))
fi

printf '\n== Result ==\n'
printf '  %s minutes in forced deep Doze, %s polls\n' "$elapsed" "$seen"

# An alarm was pending when the watch started - that was checked above - so
# silence here is Doze suppressing every wake, and there is nothing else it can
# be. That is the whole finding, and it is a failure.
if [ "${seen:-0}" -eq 0 ] && [ -n "$ALARM_DUE" ] && [ "$ALARM_DUE" -gt $((elapsed * 60)) ]; then
    # Nothing fired, but nothing was due to. That is the app working exactly as
    # designed - adaptive polling had backed the interval off past the window -
    # and it must not read as a failure, or a release gate would block on a
    # perfectly healthy phone on any quiet night.
    skip "nothing fired, and the alarm was not due within the ${elapsed}m watched" \
        "Not evidence either way. Watch for longer than the alarm is out, or measure when the sky has the app polling fast."
elif [ "${seen:-0}" -eq 0 ]; then
    fail "nothing fired in $elapsed minutes of deep Doze" \
        "An alarm was pending throughout, so every wake was suppressed. Check: adb shell dumpsys alarm | grep $PACKAGE"
elif [ -n "$GAP" ]; then
    pass "$seen polls fired, on average one every $((GAP / 60))m $((GAP % 60))s"
else
    pass "1 poll fired, which is too few to measure a gap between"
fi

# The gap is a measurement, not a verdict, until someone says what they allow of
# it: how far Doze pushes an inexact alarm out depends on the standby bucket,
# the exemption, and the batching window the system attaches - check `window=`
# and `maxWhenElapsed=` in `dumpsys alarm` before concluding the exemption is
# failing. --expect-late is that allowance made explicit.
# How far behind the app's own request the polls actually landed. That
# difference is the whole quantity here: the app names a time, the policies
# either honour it or push it out, and the gap between the two is Doze's doing
# and nothing else's.
#
# Checked against the request rather than a number typed on the command line,
# because no fixed number is right for every interval the policy picks - it
# ranges from ELEVATED_MINUTES to half a day. The overhead being measured is a
# batching window of a few minutes, a roughly fixed cost rather than a
# multiplier, so one tolerance fits all of them.
LATE=""
if [ -n "$GAP" ] && [ -n "$ASKED_INTERVAL" ] && [ "$ASKED_INTERVAL" -gt 0 ]; then
    LATE=$(( GAP - ASKED_INTERVAL ))
    [ "$LATE" -lt 0 ] && LATE=0
    printf '        the app asked for %sm %ss, so delivery ran %sm %ss behind\n' \
        "$((ASKED_INTERVAL / 60))" "$((ASKED_INTERVAL % 60))" \
        "$((LATE / 60))" "$((LATE % 60))"
fi

if [ -z "$EXPECT_LATE" ]; then
    :
elif [ -n "$LATE" ]; then
    if [ "$LATE" -le $((EXPECT_LATE * 60)) ]; then
        pass "delivery is within the ${EXPECT_LATE}m allowed of what the app asked for"
    else
        fail "delivery ran $((LATE / 60))m $((LATE % 60))s behind the app's request, beyond the ${EXPECT_LATE}m allowed" \
            "Doze is deferring the wakes. Check the exemption and the standby bucket."
    fi
# One poll leaves no gap to measure - but it is still a finding when the app
# asked for a wake often enough that the watch had room for several. Silence
# after the first is the same suppression the zero-poll branch above fails on,
# and skipping it would let a phone that stopped waking through the gate.
elif [ -n "$ASKED_INTERVAL" ] && [ "$ASKED_INTERVAL" -gt 0 ] \
    && [ $((ASKED_INTERVAL * 2)) -le $((elapsed * 60)) ]; then
    fail "1 poll in ${elapsed}m, where the app asked to wake every $((ASKED_INTERVAL / 60))m $((ASKED_INTERVAL % 60))s" \
        "The wakes after the first were suppressed. Check: adb shell dumpsys alarm | grep $PACKAGE"
elif [ -z "$ASKED_INTERVAL" ] || [ "$ASKED_INTERVAL" -eq 0 ]; then
    skip "nothing to compare against: the pending alarm names no request" \
        "An alarm already past its requested time carries no interval to judge delivery by."
else
    skip "no gap to compare against the app's request" \
        "Fewer than two polls landed, and two is the fewest that have a gap between them."
fi

# Not `[ -n "$x" ] && printf`: as a statement of its own that returns 1 when the
# string is empty, and under `set -e` it ended the run right here - on the
# zero-poll path, whose empty value is precisely the case whose reasons most
# need printing, and before the notification check below had run at all.
if [ -n "$last_epoch" ]; then
    printf '  last poll at %s\n' "$(date -d "@$last_epoch" '+%H:%M:%S' 2>/dev/null)"
fi

# Waking up is only half of it. Whether a notification can be posted from deep
# idle is the part that actually reaches the user, so it is checked separately
# rather than inferred from the poll having run.
alert_after=$(last_alert)
if [ -n "$alert_after" ] && [ "$alert_after" != "$alert_before" ]; then
    printf '  an alert was raised during the run at %s\n' \
        "$(date -d "@$((alert_after / 1000))" '+%H:%M:%S' 2>/dev/null)"
    if adb shell dumpsys notification --noredact 2>/dev/null | has "pkg=$PACKAGE"; then
        pass "the notification posted from deep idle"
    else
        fail "an alert was raised but no notification is present" \
            "Posting was swallowed - most likely POST_NOTIFICATIONS is denied."
    fi
else
    # A quiet sky is the ordinary case and says nothing against the app, so it
    # is a note rather than a failure. bfu-check draws the same line, and puts
    # --expect-alert behind provoking one deliberately.
    skip "no alert raised during the run" \
        "Expected when the sky is quiet. It leaves posting from deep idle untested."
fi

printf '\n  %d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
