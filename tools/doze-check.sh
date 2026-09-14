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
# gap is a failure only when you say what you expected with --expect-within.
#
# Usage: tools/doze-check.sh [--serial SERIAL] [--minutes N] [--bucket BUCKET]
#
#   --minutes N       how long to watch for (default 30)
#   --bucket BUCKET   force the standby bucket: active, working_set, frequent,
#                     rare or restricted. "restricted" is the worst case a
#                     rarely-opened app lands in, and the one worth proving.
#   --expect-within N treat an average gap wider than N minutes as a failure.
#                     Without it the gap is measured and reported but never
#                     fails, because no single number is right for every bucket
#                     and exemption. With it, this is a gate: a release can
#                     refuse to go out on a phone whose alarms Doze has pushed
#                     out past what the app asked for. The gap is the mean
#                     interval between consecutive polls, so it takes two polls
#                     to have one: a run that saw fewer says so rather than
#                     offering a number.
#
#                     Only ever a claim about Doze. When the app's own pending
#                     alarm already asks for longer than N, the run skips this
#                     check rather than failing it: the interval is the app's
#                     choice and Doze has done nothing to it. PollingPolicy
#                     makes that choice often - it sleeps until dark while the
#                     sun is up, and backs off to QUIET_MINUTES on a clear quiet
#                     night, whatever the poll interval in the settings says. So
#                     a short interval seeded into the settings does not produce
#                     a short one on a phone that knows where it is.
#
set -euo pipefail

PACKAGE="sh.arek.gloaming"
SERIAL="${ANDROID_SERIAL:-}"
MINUTES=30
BUCKET=""
EXPECT_WITHIN=""
# The bucket the app was in before `--bucket` forced it, so `restore` can put it
# back. Set before anything is forced, and empty when nothing was.
ORIGINAL_BUCKET=""

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --minutes) MINUTES="$2"; shift 2 ;;
        --bucket) BUCKET="$2"; shift 2 ;;
        --expect-within) EXPECT_WITHIN="$2"; shift 2 ;;
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

# Printed for the reader comparing numbers, with the caveat attached: this is
# the floor the app falls back to without a position, not the schedule it keeps.
# Read on its own next to a wake hours away it looks like a fault, and is not.
CONFIGURED=$(configured_interval)
if [ -n "$CONFIGURED" ]; then
    printf '  the settings hold a %s minute interval, which applies only without a position\n' \
        "$CONFIGURED"
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
# fired once turned into "an average gap of 30m", which then failed against
# --expect-within on the strength of no measurement at all.
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

# The gap is a measurement, not a verdict, until someone says what they
# expected of it: how far Doze pushes an inexact alarm out depends on the
# standby bucket, the exemption, and the batching window the system attaches -
# check `window=` and `maxWhenElapsed=` in `dumpsys alarm` before concluding
# the exemption is failing. --expect-within is that expectation made explicit.
#
# It can only ever be an expectation of *Doze*, though, which is the whole
# subject of this harness. When the app itself asked for a wake wider than the
# expectation there is nothing for Doze to have done wrong, and failing the run
# would blame the system for a decision `PollingPolicy` made on purpose - it
# sleeps until dark in daylight, and to QUIET_MINUTES on a clear quiet night,
# regardless of the interval in the settings.
expectation_seconds=$(( ${EXPECT_WITHIN:-0} * 60 ))
if [ -z "$EXPECT_WITHIN" ]; then
    if [ -n "$ALARM_ASKED" ] && [ -n "$GAP" ]; then
        printf '        against the %sm the app asked for\n' \
            "$(( ${ALARM_ASKED:-0} / 60 ))"
    fi
elif [ -n "$ALARM_ASKED" ] && [ "$ALARM_ASKED" -gt "$expectation_seconds" ]; then
    skip "the app asked for its next wake $((ALARM_ASKED / 60))m out, beyond the ${EXPECT_WITHIN}m expected" \
        "That is PollingPolicy's decision, not Doze's, so there is nothing here to fail. Either expect what the app asks for, or measure under conditions it polls fast in."
elif [ -n "$GAP" ]; then
    if [ "$GAP" -le "$expectation_seconds" ]; then
        pass "the average gap is within the ${EXPECT_WITHIN}m expected"
    else
        fail "the average gap of $((GAP / 60))m $((GAP % 60))s is wider than the ${EXPECT_WITHIN}m expected" \
            "Doze is deferring the wakes further than the app asked for. Check the exemption and the standby bucket."
    fi
# One poll leaves no gap to measure - but it is still a finding when the app
# asked for a wake often enough that the watch had room for several. Silence
# after the first is the same suppression the zero-poll branch above fails on,
# and skipping it would let a phone that stopped waking through the gate.
elif [ -n "$ALARM_ASKED" ] && [ "$ALARM_ASKED" -gt 0 ] \
    && [ $((ALARM_ASKED * 2)) -le $((elapsed * 60)) ]; then
    fail "1 poll in ${elapsed}m, where the app asked to wake every $((ALARM_ASKED / 60))m $((ALARM_ASKED % 60))s" \
        "The wakes after the first were suppressed. Check: adb shell dumpsys alarm | grep $PACKAGE"
else
    skip "no gap to compare against the ${EXPECT_WITHIN}m expected" \
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
