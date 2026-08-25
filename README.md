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

On top of that sits a sky calendar: eclipses, meteor showers, close pairings of
planets and the moon's phases, worked out on the phone for the year ahead and
judged against where you are standing rather than listed as though everything
is visible from everywhere. An eclipse that reaches maximum after the sun has
set where you are says so. Eclipses carry a countdown you can follow contact by
contact, and summer nights carry a prompt for noctilucent clouds - which is a
prompt and not a forecast, because nothing has been able to forecast those
since 2023.

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
