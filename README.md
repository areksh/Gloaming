# Gloaming

A free, ad-free, Play-Services-free Android app that tells you when to look up:
aurora, satellite passes, comets, eclipses, meteor showers and conjunctions.
Global by design, UK-enhanced.

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
Venus at its brightest, new and full moon, and the equinoxes and solstices - all
worked out on the phone for the year ahead and judged against where you are
standing rather than listed as though everything is visible from everywhere. An
eclipse that reaches maximum after the sun has set where you are says so. You
can filter out the kinds you do not care about, and star the ones you do.
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
between May and August. The planets are there too, with how high each gets and
which way to look.

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
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:testDebugUnitTest      # unit tests, no device needed
./gradlew :app:connectedDebugAndroidTest  # instrumented tests, needs a device

# The same, without wiping the app afterwards - see below
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

The instrumented suite runs against the app's **real** preference stores, and
puts them back when it finishes. It did not always: clearing them took the
location off whatever phone the suite ran on, silently, because the app stayed
installed and the exemption survived - so the next overnight run sat at
"no location" until morning and tested nothing. If you add a test that writes
to a real store, save and restore rather than clearing.

**The plain form uninstalls the app when it finishes**, which takes the app's
settings and its battery-optimisation exemption with it. That is easy to miss
and expensive when it happens: a phone set up for an overnight run and then used
to run the suite is quietly back to a fresh install, and the night tests
nothing. Pass `leaveApksInstalledAfterRun` on any device that is also being used
to watch something.

The instrumented tests cover the seams a JVM test cannot reach: that the
credential- and device-encrypted preference stores really are separate, that the
manifest declares every permission the code uses and nothing more, and that
notifications post without throwing. They need a device connected over adb with
USB debugging enabled, and **awake and unlocked**: an activity launched behind
the keyguard reaches resumed and pauses again without ever drawing, so the
Compose harness finds no semantics tree and every assertion fails with "No
compose hierarchies found in the app" - which reads like a broken test setup and
is not. `adb shell input keyevent KEYCODE_WAKEUP` before a run, and
`settings put global stay_on_while_plugged_in 7` to keep it that way.

The APK lands in `app/build/outputs/apk/debug/`.

### Looking at a night that has not happened

Most of what this app is for happens a few times a decade. Waiting for a total
lunar eclipse to find out whether the eclipse card lays out properly is not a
plan, so `app/src/scenarios/` holds a catalogue of nights, and both suites read
it:

```sh
# The nights are still real - the ephemeris is asked, not a fixture
./gradlew :app:testDebugUnitTest --tests '*ScenariosTest'

# Drawn on the device. connectedDebugAndroidTest takes no --tests; the
# instrumentation runner does the filtering, and the class name must be whole.
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
./gradlew :app:installDebug :app:installDebugAndroidTest
tools/bfu-check.sh --seed     # reboot, hold at the lock screen, report back
tools/doze-check.sh           # force deep Doze, measure the real poll spacing
```

`bfu-check.sh` tests the before-first-unlock path. It gathers its evidence after
the fact rather than over a live connection, because adb is generally
unreachable while the device is locked - GrapheneOS defaults the USB-C port to
charging-only in that state. Run it with `--resume` in the morning to read back a
real overnight run.

`doze-check.sh` reports the measured interval between polls, which Doze rewrites:
the app's battery-optimisation exemption and its app standby bucket both change
the answer, so the number worth trusting is the one from the device.

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

## Verifying a release

Every release is signed with the project's own key. Nothing is published yet,
but when it is, check the APK's signing certificate against this fingerprint
before installing - particularly if you got the file from anywhere other than
this repository:

```
SHA-256: 1B:14:A3:3F:AE:BB:E1:A4:AC:73:D1:6E:88:71:73:8B:
         4D:AD:EF:05:97:3D:77:94:7D:0D:D4:DA:8E:A5:D2:FF
```

```sh
apksigner verify --print-certs gloaming-release.apk
```

Obtainium checks this for you once the first release has pinned it. A build
signed with any other key is not from this project, whatever it claims.

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
