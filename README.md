# Gloaming

A free, ad-free, Play-Services-free Android app that tells you when to look up:
aurora, satellite passes, comets, eclipses, meteor showers, conjunctions and the
constellations. Global by design, UK-enhanced.

Your location is never sent to us. All the astronomy is computed on-device, all
alert decisions are made on-device, and nothing can contact the app - it reaches
out on its own schedule and nothing reaches in.

You can type your position in or let the phone tell it, whichever you prefer,
and switch between the two whenever you like. Letting the phone tell it asks for
**coarse** location and nothing more, only while the app is open, and never in
the background - Gloaming does not even turn the GPS on, it reads the last
position the phone already had. Typing it in works with location denied forever,
and is also how you ask about somewhere you are not.

Satellite passes follow the same rule: the orbital elements are published once,
for everybody, and every prediction happens on your phone. CelesTrak never sees
an install, and could not tell where one was if it did.

The one thing that does leave the phone is a rounded position, at most once an
hour, to Open-Meteo for the cloud forecast - a question about a place cannot be
asked without naming one. It is rounded to about a kilometre, which is the
resolution of the finest model they run, and it travels with no account, key or
identifier of any kind. Nothing this project runs ever learns where you are.

**Status: pre-release.** Everything in the v1.0 aurora scope is built - cloud
cover, darkness windows, the full decision rules, adaptive polling, storm mode,
the AuroraWatch UK layer, a home-screen widget, a watch-now mode for nights the
background schedule cannot be trusted, and a night-vision mode - on top
of alerting already verified on a real phone in deep Doze and before first
unlock. Satellite passes are now built on top of that: the app predicts when the
ISS and other bright satellites cross your sky and tells you half an hour
before, with which way to look and how high it gets. It will also follow a pass
live while it happens, counting down and saying where to look with no signal
needed, and it spots the Starlink launches still strung out in a train. What
remains before release
is the part no test suite can do for you: a season of real nights, to calibrate
the thresholds and confirm the app is worth keeping installed.

On top of that sits a sky calendar, on a tab of its own: eclipses, meteor
showers, close pairings of planets, Mars, Jupiter and Saturn at opposition,
Venus at its brightest, new and full moon, the night each constellation stands
highest at midnight, and the equinoxes and solstices - all
worked out on the phone for the year ahead and judged against where you are
standing rather than listed as though everything is visible from everywhere. An
eclipse that reaches maximum after the sun has set where you are says so.
Anything under way as you read the list is marked as happening now, and stays
on the list until it is genuinely over rather than dropping off at the moment it
starts. You can filter out the kinds you do not care about, and star the ones
you do.
Eclipses carry a countdown you can follow contact by contact, and summer nights
carry a prompt for noctilucent clouds - which is a prompt and not a forecast,
because nothing has been able to forecast those since 2023.

Comets sit on the calendar too, and are the one thing here the app cannot work
out from arithmetic alone. Where a comet will be comes from its orbit, which is
solved on the phone; how bright it will look comes from what observers have
actually reported, because the two-hundred-year-old formula for that is
routinely wrong by several magnitudes in either direction. On 25 August 2026 it
had one comet six magnitudes too faint and another eight too bright, on the same
day. So the app quotes a measured brightness where anybody has looked recently,
falls back to the prediction where nobody has, and always says which of the two
it is showing you. You set how faint is worth hearing about, how high it has to
get, how much moonlight you will tolerate - which matters far more for a comet
than for a planet, since a comet is a smudge rather than a point - and how stale
a report may be. When a comet fades, you get one quiet notice and then silence.

Most years have no comet you could see without binoculars, and the app will
honestly show you nothing rather than dressing up a faint one.

When the moon passes in front of a planet rather than merely near it, the
calendar says so and gives the minute it goes out at the moon's edge and the
minute it comes back. Those are worked out for where you are standing rather
than for the Earth: the moon's parallax is about two of its own diameters, so
whether an occultation happens at all is a fact about a place.

Nothing in it needs a telescope. If it cannot be seen with your eyes or a pair
of binoculars, it is out of scope, which is why there are no Uranus oppositions
and no transits of Mercury.

A third tab answers the questions the calendar structurally cannot. A year of
eclipses fits in a list, but a clear night here has hundreds of satellite
passes, so the split is by shape - dated and sparse in the calendar, nightly and
dense on Tonight, which leads with the best few rather than the earliest and
always finds room for the space station. Beside them it puts the three things
that decide whether any of it is worth going outside for: the cloud overhead and
the next gap in it, the moon and how much of the night it spoils, and how dark
the sky actually gets - both civil twilight, which an aurora shows through, and
proper astronomical darkness, which at this latitude does not arrive at all
between May and August. The planets are there too, with how high each gets,
which way to look, and which constellation each is sitting in.

The constellations have a card of their own, because which patterns are up is a
season rather than an occasion and a calendar of them would be an almanac. It
leads with a handful worth going out for, with where to look and one sentence on
how to pick each one out - three stars in a line make Orion's belt, the end of
the Plough's bowl points at Polaris. The ones that never set from where you are
get named in a line at the end rather than a row each: they are the same every
night of the year, and ranked by height they would fill the card and crowd out
whatever has changed since last month. Whichever pattern is at its best tonight
comes first however low it is. If you would rather see every one above the
horizon, that is a setting. Individual stars and deep-sky objects are
deliberately not here: that is a catalogue, and Stellarium does it better.

None of which helps if you do not know the words. "On the far side of Polaris
from the Plough" is a perfectly good direction and useless to somebody who knows
neither, so there is a Guide tab: the landmarks every direction in the app is
measured from, the vocabulary the other tabs use - magnitude, opposition,
occultation, radiant, umbra - and the part nobody tells you, which is that your
eyes take twenty minutes to adapt to the dark and an aurora rarely looks like
the photographs. Each card on Tonight will also explain its own words in place,
underneath its answer rather than in front of it, because the question gets
asked while looking at the sentence that raised it. None of it needs a location,
a forecast or a network, so it is the one screen that works before anything is
set up.

Tonight also says what the app is working from. Everything it knows arrives as a
handful of small files fetched from a CDN, and the failure mode that matters is
not a loud one: an upstream can stop republishing while every request from the
phone goes on politely answering "not modified", and the app will predict
satellite passes from a fortnight-old catalogue without a word. So a downloads
card carries both halves - how old the copy on this phone actually is, marked
when the satellite elements have gone off, and what happened the last time
anything was fetched, with the reason when it failed. Pressing "check now" shows
what is being downloaded while it happens.

Any dated event can be shared as a message or exported as an `.ics` for whatever
calendar you keep. Neither needs a permission. If you would rather the starred
ones simply appeared in your calendar, that is a setting - into a local calendar
Gloaming creates and nothing syncs, or into your default one, wherever that
already syncs to. It is off until you choose, the permission is asked for at
that moment rather than on first run, and unstarring an event takes its entry
back out again.

Every alert asks, afterwards, whether it was worth going out for, and that
answer is the one thing in the app that cannot be worked out from a formula. The
default thresholds have always been guesses - a bar that suits somebody in Kent
is wrong for somebody in Shetland - and this is how they stop being guesses.
There is a switch to let the app set them for you from your own nights; with it
off it still says what it would have chosen and leaves the choice to you. Nothing
about it leaves the phone.

Gloaming needs Android's battery-optimisation exemption, and asks for it on
first run. That is a requirement rather than a refinement: without it the system
holds background checks until the phone next wakes, which overnight means hours,
and an aurora app that cannot alert overnight is not doing anything. Running
without the exemption is out of scope - the app says so plainly rather than
degrading quietly, and there is no setting that works around it. The **watch
now** switch is the answer for a night that matters, since a foreground service
runs its own clock and no alarm policy reaches it.

## Quick start

### Android app

Requires JDK 21 and the Android SDK with platform `android-37.0`.

```sh
./go setup         # Node projects and the git hooks, once per clone
./go apk           # build the debug APK
./go test          # unit tests, no device needed
./go device-test   # instrumented tests, needs a device
./go results       # the last run's totals, and every failure grouped by cause
./go check         # everything that needs no phone, before a commit
./go testall       # all of it, the phone's part too if one is plugged in
./go help          # every command, and what each option is for
```

`./go setup` also points `core.hooksPath` at `tools/hooks`, so `./go check` runs
before every push and a push it fails does not happen. There is no CI: that hook
and `./go release` are the whole of the gate between a change and a published
APK, which is why the hook is version-controlled rather than copied into
`.git/hooks` where it could quietly diverge.

`./go results` is worth knowing about before the first red run. Gradle reports a
failure as a name, the word FAILED and a link to an HTML page, so the assertion
message - the one thing anybody wants - ends up being dug out of the XML under
`build/test-results` by hand. It reads that XML, and groups failures by what
actually went wrong rather than listing the same defect once per test that hit
it, which is the difference between three hundred lines and one.

`./go help` lists the rest: the release build, installing on a phone, and the
catalogue of nights below. Underneath are ordinary Gradle tasks and nothing
stops you calling them directly - but on a device the wrapper also does the two
things that are easy to forget and expensive to get wrong, both explained
below. It holds the phone awake for the run, and it leaves the app installed
when the run finishes.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

The instrumented suite runs against the app's **real** preference stores, and
puts them back when it finishes. It did not always: clearing them took the
location off whatever phone the suite ran on, silently, because the app stayed
installed and the exemption survived - so the next overnight run sat at
"no location" until morning and tested nothing. If you add a test that writes
to a real store, save and restore rather than clearing, and change the one field
you are testing rather than writing a fresh settings object over the rest.

The same rule holds for the **alarm**, and for the same reason. The scheduling
tests cancel and re-arm the real one, because there is no seam between the app
and `AlarmManager` and inventing one would test a fake - but they finish by
putting the phone's own schedule back (`RealSchedule.restore`), not by leaving
it cancelled. A suite run in the evening would otherwise take the phone off
watch for the night without a word: installed, exempt, not stopped, and simply
not going to wake up.

**The plain form uninstalls the app when it finishes**, which takes the app's
settings and its battery-optimisation exemption with it. That is easy to miss
and expensive when it happens: a phone set up for an overnight run and then used
to run the suite is quietly back to a fresh install, and the night tests
nothing. Pass `leaveApksInstalledAfterRun` on any device that is also being used
to watch something; `./go device-test` passes it unless you ask for the opposite
with `--uninstall`.

The instrumented tests cover the seams a JVM test cannot reach: that the
credential- and device-encrypted preference stores really are separate, that the
manifest declares every permission the code uses and nothing more, and that
notifications post without throwing. They need a device connected over adb with
USB debugging enabled, and **awake and unlocked**: an activity launched behind
the keyguard reaches resumed and pauses again without ever drawing, so the
Compose harness finds no semantics tree and every assertion fails with "No
compose hierarchies found in the app" - which reads like a broken test setup and
is not. By hand that is `adb shell input keyevent KEYCODE_WAKEUP` before a run,
and `settings put global stay_on_while_plugged_in 7` to keep it that way.
`./go device-test` does both, and stops with the reason rather than starting if
the keyguard is still up - a phone with a PIN on it is the one case no script
can get past.

That second one stops working the moment the battery reaches a charge limit.
GrapheneOS holds at 80 per cent by default, and a phone that has stopped
charging is no longer "plugged in" as far as the setting is concerned - so the
ordinary screen timeout takes over part-way through a long run and the tests
from that point on fail with the message above. Nothing in the output says the
screen went off; it reads as a real failure in whichever tests happened to be
running. On a run long enough to reach the limit, raise the timeout as well and
put it back afterwards:

```sh
adb shell settings get system screen_off_timeout      # note it down first
adb shell settings put system screen_off_timeout 1800000
```

`./go device-test` raises it, puts it back afterwards - including after an
interrupt - and wakes the screen every few minutes for the length of the run,
which is the part that still holds once the charge limit has made the setting
meaningless.

The APK lands in `app/build/outputs/apk/debug/`.

`./go release` builds the one that ships. It is shrunk by R8 - 8.9 MB down to
1.8, and two dex files down to one, which is a cold background wake with a
fraction of the class loading to do - and it will not build without the
project's signing key, because an APK signed with anything else installs, runs,
looks entirely well and can then never be updated from the right key.
[`docs/prerequisites.md`](docs/prerequisites.md) says where the key and its
passphrase go.

Shrinking leaves the shipped APK unlike every build the suite has run against,
so there is a third build type for it: `minified`, which is the release build
under the debug key. `./go device-test --minified` runs the whole suite against
that. It is worth doing before a release and at few other times, since it adds a
full R8 pass to a run that already takes half an hour.

R8 keeps this app's own classes and shrinks everything around them, which costs
about 90 KB against shrinking the lot. `app/proguard-rules.pro` has the
reasoning; the short of it is that the instrumented suite is white-box, so a
fully shrunk build fails most of it on classes R8 was entirely right to remove,
and that nothing is reported off a user's phone by design, so the only stack
trace this project will ever see is one somebody copies out by hand.

### Looking at a night that has not happened

Most of what this app is for happens a few times a decade. Waiting for a total
lunar eclipse to find out whether the eclipse card lays out properly is not a
plan, so `app/src/scenarios/` holds a catalogue of nights, and both suites read
it:

```sh
# The nights are still real - the ephemeris is asked, not a fixture
./go scenarios

# Every night drawn on the phone, and the notification each one would post
./go scenarios --device
```

The same by hand. `connectedDebugAndroidTest` takes no `--tests`: the
instrumentation runner does the filtering, and the class name must be whole.

```sh
./gradlew :app:testDebugUnitTest --tests '*ScenariosTest'

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=sh.arek.gloaming.scenarios.ScenarioScreensTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

**Nothing in it is fake astronomy.** A scenario supplies arguments - where you
are standing, what the time is, and the documents the device would already hold
- and the shipped code does the rest. The total lunar eclipse of December 2029
is in the catalogue because it really happens then and really is high over
Edinburgh, not because a fixture says so. What is genuinely invented is the
small part the sky does not decide: the cloud forecast, the satellite elements
and the aurora reading.

Each night declares what it is supposed to show, and `ScenariosTest` proves it -
without that, a screen test against a scenario that had quietly gone empty would
pass exactly as happily as one against a full one. `ScenarioScreensTest` then
renders every one of them through the real Tonight tab on the device, and
`ScenarioNotificationsTest` posts the notification each would produce.

Two dates in the catalogue are not free choices. The satellite nights sit within
a few days of the station's real element epoch, because SGP4 is a near-epoch
model. And a scenario has to be set *before* the moment an event peaks: the
calendar answers "what is ahead", so a shower whose maximum passed this morning
is already off it.

### Testing overnight delivery

Whether an alert arrives at three in the morning is the only thing that matters
and the hardest thing to test. Two harnesses answer it in minutes rather than a
night:

```sh
./go status                   # what state the phone is in; reads only, seconds
./go install --test           # the app, and the APK these two harnesses need
tools/bfu-check.sh --seed     # reboot, hold at the lock screen, report back
tools/doze-check.sh           # force deep Doze, measure the real poll spacing
```

`./go status` is the cheap question, and worth asking before an overnight run
and again the morning after one that produced nothing: whether the app is
installed and has been opened, whether it is exempt from battery optimisation,
what standby bucket it is in, whether an alarm is pending and how far out, and
when a poll last completed. It changes nothing and takes seconds, where the two
harnesses below each take the phone out of use for as long as they run. It is
`tools/bfu-check.sh --check-only`, so the checks are the harness's own rather
than a second copy that can drift from them.

`bfu-check.sh` tests the before-first-unlock path. It gathers its evidence after
the fact rather than over a live connection, because adb is generally
unreachable while the device is locked - GrapheneOS defaults the USB-C port to
charging-only in that state. Run it with `--resume` in the morning to read back a
real overnight run.

`./go verdicts` reads back the other half of an overnight run: what the two
buttons on each alert were answered, and what the sky was doing at the time.
That record is what a season of real nights exists to produce - it is what the
alert thresholds get calibrated against - and it is otherwise invisible, because
`verdicts.json` lives in device-encrypted storage where no file manager shows it
and `adb pull` cannot reach it. The app shows the same record in its settings,
under the automatic-thresholds switch it is the evidence for, which is the
version that matters to somebody with no laptop to hand.

`doze-check.sh` reports the measured interval between polls, which Doze rewrites:
the app's battery-optimisation exemption and its app standby bucket both change
the answer, so the number worth trusting is the one from the device. Like
`bfu-check.sh` it reports PASS and FAIL per check and exits non-zero on any
failure, so a release can gate on it. The spacing is the one thing it will not
judge for you unless asked: `--expect-within N` makes an average gap wider than
N minutes a failure, and without it the gap is measured and reported only. That
gap is the mean interval between consecutive polls, so it takes two polls to
have one at all - a single poll in the window is reported as exactly that rather
than as a gap the width of the whole watch.

`--expect-within` is a claim about Doze and nothing else, and the harness holds
it to that. `dumpsys alarm` prints what the app asked for beside what each
policy did with it, and when the app's own request is already wider than the
expectation the check skips rather than fails: the interval is `PollingPolicy`'s
decision and Doze has not touched it.

It is that often. The poll interval in the settings is only consulted when the
app has no position; with one it plans from the sky instead, sleeping until dark
while the sun is up and backing off to `QUIET_MINUTES` on a clear quiet night.
So seeding a two-minute interval does *not* produce a two-minute schedule on a
phone that knows where it is, and a thirty-minute window in daylight sees at
most the poll a reboot happened to trigger. That is the app working, not the
harness failing, and it is reported as untested rather than as a failure - both
for a window the pending alarm reaches past, and for a gap the app chose. To
measure rather than skip, run it after dark against a sky the policy polls fast
for, or read the pending alarm out of `./go status` and pick a window longer
than it.

Both can provoke a genuine alert rather than waiting for the sky to oblige.
Point the observer at the busiest cell in the live grid and set a threshold just
below its value: the fetch, the grid lookup, the decision and the notification
are all the real ones, and only the location is chosen.

Two gates still stand in the way, and a cell chosen on probability alone will
usually fail one of them. The place has to be dark *now* - the darkness gate
cannot be switched off - and its cloud cover has to be within the tolerance,
because the seeded settings leave the cloud gate on. So pick the busiest cell on
the night side. Longitude goes in the way the app takes it, -180 to 180, not the
0-359 the grid is indexed by.

```sh
curl -s https://sky.arek.sh/v1/oval.bin | gzip -dc | od -An -tu1 -v   # 65160 bytes,
# row-major, 181 rows of latitude -90..90, each 360 columns of longitude 0..359
# Column 183 is 183 degrees east, which the app is given as -177.
tools/bfu-check.sh --prepare --seed --latitude -63 --longitude -177 \
    --threshold 10 --expect-alert
```

Three things silently stop alerting altogether, and all three are checked before
a run rather than discovered at three in the morning: an app in the **stopped**
state receives no broadcasts at all, which is where it sits from installation
until it is first opened; an app that has never been opened is in the **NEVER**
standby bucket, where alarms are deferred by a year; and without the
**battery-optimisation exemption** app standby defers them regardless. A
deferred alarm still lists as pending and looks entirely healthy.

**Battery saver** is a fourth, and behaves differently from the other three: it
cannot engage while the phone is charging, so it will not reproduce on a device
plugged into a test rig.

Measured on a Pixel 8a in forced deep Doze, 22-35 minutes per run, with the
exemption granted and a 5-minute interval configured:

- Polls landed every **9-10 minutes**, the same with battery saver on as off.
- That gap is **not** Doze. `device_idle` was recorded as not applying on every
  sample; the alarm counts down past its requested time and fires at
  `maxWhenElapsed`, the far end of the roughly 4-minute batching window the
  system attaches to an inexact alarm. Expect a request of *n* minutes to land
  around *n* + 4.
- The network was never blocked, even in deep Doze with the saver on:
  `dumpsys netpolicy` gave `blocked=BATTERY_SAVER|DOZE|APP_BACKGROUND,
  allowed=POWER_SAVE_ALLOWLIST, effective=NONE`.
- The **restricted standby bucket is unreachable** while the exemption is
  granted - `am set-standby-bucket restricted` reports success and the readback
  stays at 5 (ACTIVE). The worst-case bucket cannot happen to an exempt app.

So on this hardware the exemption does its job and battery saver costs nothing
measurable. That is one AOSP-derived device; the exemption is not honoured on
every OEM build, which is what dontkillmyapp.com exists to catalogue. The
**watch now** switch is the escape hatch when it is not: a foreground service
runs its own clock, so no alarm policy or batching window reaches it.

Beware of measuring this through `logcat` alone - the ring buffer here holds
about twelve minutes, and anything that reads back further silently sees
nothing. That bug lived in `tools/doze-check.sh` and invented a sevenfold
deferral that was not happening.

The app keeps its own log for exactly that reason, and it is the record worth
reading after a night rather than the system's. Seven days by default, bounded
at 512 KB so a fault logging in a loop cannot fill the phone, and written to
device-protected storage so a poll before first unlock can reach it. The screen
exports it through the share sheet and clears it. It records what the app
decided and why, and never where the user is.

```sh
./go log          # the app's own log, off the phone
./go log --live   # logcat instead, followed, filtered to the app's uid and tags
```

The uid filter is not fussiness. Tag names are not the app's alone - the system
logs under `BootReceiver` too, and on a Pixel it is the loudest thing on the
screen - and a uid holds across the short-lived processes each poll wakes up in,
which a pid would not.

### Elements fetcher

Fetches CelesTrak orbital elements and publishes `sats.json` to R2. It runs on
a host outside Cloudflare because Cloudflare's egress cannot reach CelesTrak
(ADR-0008); `fetcher/README.md` covers the build and the whole server setup.

```sh
cd fetcher
npm install
npm test && npm run typecheck
npm run build     # one self-contained file for the host to run
```

### Data Worker

The `worker/` directory holds the Cloudflare cron Worker that fetches upstream
space-weather data and publishes the small derived files the app reads. It needs
Node and no Cloudflare account to develop or test against.

```sh
cd worker
npm install
npm test        # unit tests, run inside the Workers runtime
npm run typecheck

npx wrangler dev --test-scheduled                       # run it locally
curl "http://localhost:8787/__scheduled?cron=*/5+*+*+*+*"  # fire the SWPC refresh
```

Deploying needs a Cloudflare account, an R2 bucket and `wrangler login`; see
[`docs/prerequisites.md`](docs/prerequisites.md).

## Documentation

| Document | What is in it |
|---|---|
| [`docs/sourcing and constraints.md`](docs/sourcing%20and%20constraints.md) | Upstream sources, their terms, and the constraints that shape everything else |
| [`docs/architecture.md`](docs/architecture.md) | Module layout, the hosted-file data contract, the cron Worker, testing |
| [`docs/prerequisites.md`](docs/prerequisites.md) | Provisioning to work through before development |
| [`docs/adr/`](docs/adr/) | Architecture decision records |

## Publishing

There is no CI. The gate is the pre-push hook and `./go release`, and both run
on the machine that holds the signing key - which never leaves it.

```sh
./go push                 # push main, and republish the development release
./go push --no-release    # push and stop there
./go release 1.0.0-rc2    # cut a version: the whole gate, then publish
```

`./go push` publishes the tip of `main` as the one development release: a
signed release APK under a rolling `dev` tag that moves with every push, its
notes listing every commit since the last version tag. Exactly one exists at any
time and it replaces itself, so the link never goes stale and nothing
accumulates. It is signed with the project's release key like any other build,
so it installs over a release and a release installs over it - keeping settings,
the battery-optimisation exemption, and the record of what your alerts were
worth, which an uninstall would take with it.

A development build carries the version name of the *last* release, because that
name identifies a release and does not move between them. The commit is what
identifies the build: it is in the APK's filename, in the release notes, and on
the settings screen of the phone it is installed on.

`./go release <version>` cuts a real one, and runs everything first:

| | | |
|---|---|---|
| 1 | `./go check` | the unit tests and both Node projects |
| 2 | the instrumented suite | on the phone, the catalogue of nights with it |
| 3 | the suite again | against what R8 actually ships |
| 4 | reinstall | the debuggable build, which the harnesses below need |
| 5 | `bfu-check.sh` | reboot, hold at the lock screen, does an alert arrive |
| 6 | `doze-check.sh` | force deep Doze, is the poll interval what was asked |

About an hour with the phone plugged in throughout. It is unattended until step
5 reboots the phone, where it waits with no time limit for you to unlock it.
Then it bumps the version, cuts the changelog, builds and signs, and asks once
before anything leaves the machine.

Steps 5 and 6 need a short poll interval to measure anything, and the fixture
that seeds one writes the whole settings object rather than editing a field. So
the phone's own position and threshold are read first and passed back in, and
the interval is put back afterwards. What cannot be put back is that the harness
switches alerting on: it has no way to know whether it was on before, and
`./go status` is how you check.

Publishing needs a Codeberg access token with write access to the repository, in
`secrets/codeberg-token.txt` or `CODEBERG_TOKEN`, and the signing material in
`keystore.properties` - copy `keystore.properties.example` and fill in the
passphrase. Both are covered in
[`docs/prerequisites.md`](docs/prerequisites.md).

## Verifying a release

Every release is signed with the project's own key, development builds included.
Check the APK's signing certificate against this fingerprint before installing -
particularly if you got the file from anywhere other than this repository:

```
SHA-256: 1B:14:A3:3F:AE:BB:E1:A4:AC:73:D1:6E:88:71:73:8B:
         4D:AD:EF:05:97:3D:77:94:7D:0D:D4:DA:8E:A5:D2:FF
```

```sh
apksigner verify --print-certs gloaming-release.apk
```

Obtainium checks this for you once the first release has pinned it. A build
signed with any other key is not from this project, whatever it claims.

The fingerprint above is the one `./go release` reads: it compares what it has
just built against this file and says so either way. That is the check worth
having in the build rather than in a habit, because signing a release with the
wrong key cannot be undone once anyone has installed it.

## Licence

GPL-3.0. See [`LICENSE`](LICENSE).

Third-party source vendored into this repository keeps its own licence, and
those licences are GPL-compatible. See
[`app/src/main/vendor/README.md`](app/src/main/vendor/README.md) for what is
vendored, where it came from and under what terms.

### A note on the data endpoints

Gloaming fetches its data from endpoints run by this project, which exist under
permissions granted to this project specifically by AuroraWatch UK, Open-Meteo
and CelesTrak. Those permissions are conditional on the app remaining
non-commercial: no advertising, no subscriptions, and no feature unlocked by a
donation.

The licence lets you fork this app, and that is deliberate. But a fork that adds
advertising while still calling this project's endpoints spends someone else's
goodwill, and the likely outcome is the permission being withdrawn for everyone.
If you fork, please point the manifest at your own infrastructure and seek your
own permission from the upstream providers.

## Credits

Aurora forecasts from [NOAA SWPC](https://www.swpc.noaa.gov/); UK aurora data
from [AuroraWatch UK](https://aurorawatch.lancs.ac.uk/) at Lancaster University;
weather from [Open-Meteo](https://open-meteo.com/); orbital elements from
[CelesTrak](https://celestrak.org/); comet elements from the
[Minor Planet Center](https://www.minorplanetcenter.net/). The SGP4/SDP4
propagator behind every pass prediction is vendored from
[Look4Sat](https://github.com/rt-bishop/Look4Sat) under the GPL-3.0. Full attribution
requirements are recorded in the sourcing document and are surfaced in-app next
to the data they cover.
