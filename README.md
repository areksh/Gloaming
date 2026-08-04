# Gloaming

A free, ad-free, Play-Services-free Android app that tells you when to look up:
aurora, satellite passes, comets, eclipses, meteor showers and conjunctions.
Global by design, UK-enhanced.

Location never leaves your phone. All the astronomy is computed on-device, all
alert decisions are made on-device, and nothing can contact the app - it reaches
out on its own schedule and nothing reaches in.

**Status: pre-release.** The application scaffold builds; no features are
implemented yet. The v1.0 release gate is aurora, cloud cover and darkness
windows delivered reliably through Doze.

## Quick start

### Android app

Requires JDK 21 and the Android SDK with platform `android-37.0`.

```sh
./gradlew :app:assembleDebug     # build the debug APK
./gradlew :app:testDebugUnitTest # run unit tests
```

The APK lands in `app/build/outputs/apk/debug/`.

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
