# Gloaming

A free, ad-free, Play-Services-free Android app that tells you when to look up:
aurora, satellite passes, comets, eclipses, meteor showers and conjunctions.
Global by design, UK-enhanced.

Your location is never sent to us. All the astronomy is computed on-device, all
alert decisions are made on-device, and nothing can contact the app - it reaches
out on its own schedule and nothing reaches in.

The one thing that does leave the phone is a rounded position, at most once an
hour, to Open-Meteo for the cloud forecast - a question about a place cannot be
asked without naming one. It is rounded to about a kilometre, which is the
resolution of the finest model they run, and it travels with no account, key or
identifier of any kind. Nothing this project runs ever learns where you are.

**Status: pre-release.** The application scaffold builds; no features are
implemented yet. The v1.0 release gate is aurora, cloud cover and darkness
windows delivered reliably through Doze.

## Quick start

### Android app

Requires JDK 21 and the Android SDK with platform `android-37.0`.

```sh
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:testDebugUnitTest      # unit tests, no device needed
./gradlew :app:connectedDebugAndroidTest  # instrumented tests, needs a device
```

The instrumented tests cover the seams a JVM test cannot reach: that the
credential- and device-encrypted preference stores really are separate, that the
manifest declares every permission the code uses and nothing more, and that
notifications post without throwing. They need a device connected over adb with
USB debugging enabled.

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

```sh
curl -s https://sky.arek.sh/v1/oval.bin | gzip -dc | od -An -tu1 -v   # 65160 bytes,
# row-major, 181 rows of latitude -90..90, each 360 columns of longitude 0..359
tools/bfu-check.sh --prepare --seed --latitude -63 --longitude 183 \
    --threshold 10 --expect-alert
```

Three things silently stop alerting altogether, and all three are checked before
a run rather than discovered at three in the morning: an app in the **stopped**
state receives no broadcasts at all, which is where it sits from installation
until it is first opened; an app that has never been opened is in the **NEVER**
standby bucket, where alarms are deferred by a year; and without the
**battery-optimisation exemption** app standby defers them regardless. A
deferred alarm still lists as pending and looks entirely healthy.

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
[Minor Planet Center](https://www.minorplanetcenter.net/). Full attribution
requirements are recorded in the sourcing document and are surfaced in-app next
to the data they cover.
