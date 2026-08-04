# 3. All computation on-device; polling only, no push

Date: 2026-07-22

## Status

Accepted

## Context

The app must decide when to notify a user about aurora and, later, passes and sky
events. Those decisions depend on the user's location and on darkness and cloud
at that location. The reasoning is in Sec. 4, Sec. 9 and Sec. 11 of
`docs/sourcing and constraints.md`; this ADR records the resulting posture.

Two upstream sources forbid commercial use and the whole project is
non-commercial as a result (Sec. 1). The target audience runs GrapheneOS and
denies location by default.

## Decision

- **All location maths and all attention and alert decisions happen on the
  device.** The server never learns where any user is. The aurora grid is shipped
  whole and indexed locally, never queried by coordinate; a `/aurora?lat=&lon=`
  endpoint is rejected precisely because it would log approximate locations.
- **Delivery is polling only. There is no push and no inbound connection of any
  kind.** The app reaches out on its own adaptive schedule; nothing can reach in.
  The earlier ntfy topic-subscription layer is removed.
- **Location permission is optional and manual entry is first-class.** The app is
  fully functional with location permission permanently denied, and uses
  `LocationManager` (never the fused provider) only as a convenience.

## Consequences

- The Data Safety story is "no data collected", which is the app's actual
  differentiator and belongs on the listing.
- Because aurora is the only reactive feature, polling costs only a small latency
  penalty (0-15 min) against data that already lags the sky by 3-5, while
  removing an external service dependency, a rarely-exercised second code path,
  and a persistent connection on every user's phone. The one path that matters is
  the one exercised constantly.
- Adaptive polling (Sec. 9.3) is the mechanism: no polling while the sun is up,
  dormant through the polar summer, escalating to 5-minute polls when solar wind
  at L1 signals an incoming substorm. Storm mode uses a `specialUse` foreground
  service for the duration of an event only.
- The manifest is signed (Ed25519) because `message` is a broadcast channel into
  a notification on every install; a lapsed or hijacked domain would otherwise be
  a phishing primitive. The app refuses an unsigned or badly-signed manifest and
  falls back to last known-good.
- Two things are excluded by design to keep the "no data collected" claim true: a
  coordinate-keyed aurora endpoint, and any generic-endpoint (UnifiedPush) storage.
