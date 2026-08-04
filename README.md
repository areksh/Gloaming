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

Requires JDK 21 and the Android SDK with platform `android-37.0`.

```sh
./gradlew :app:assembleDebug     # build the debug APK
./gradlew :app:testDebugUnitTest # run unit tests
```

The APK lands in `app/build/outputs/apk/debug/`.

## Documentation

| Document | What is in it |
|---|---|
| [`docs/sourcing and constraints.md`](docs/sourcing%20and%20constraints.md) | Upstream sources, their terms, and the constraints that shape everything else |
| [`docs/architecture.md`](docs/architecture.md) | Module layout, the hosted-file data contract, the cron Worker, testing |
| [`docs/prerequisites.md`](docs/prerequisites.md) | Provisioning to work through before development |
| [`docs/adr/`](docs/adr/) | Architecture decision records |

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
