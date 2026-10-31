# 16. Background location on a chosen cadence

Date: 2026-10-31

## Status

Accepted. Supersedes the "foreground only, no `ACCESS_BACKGROUND_LOCATION`,
ever" limit in Sec. 7 of the sourcing document, and the "never in the
background" wording `LocationMode.Automatic` carried with it.

## Context

Detecting the position was built on 2026-08-25 with three limits: coarse only,
foreground only, and last-known fixes only. The second was stated as the
difference between a convenience and tracking. "Detect it for me" meant when
the app was open; the position was then stored, and every scheduled wake used
that stored copy exactly as it would a typed-in one.

That holds up for somebody who opens the app before going out. It does not for
the case the alerting exists for. The alarms run overnight on a phone nobody
touches, and a phone carried to a cottage for the weekend goes on judging the
darkness gate, the cloud forecast and every pass at home until the app is
opened again - which, for somebody relying on the alert to tell them to look,
may be never. The stored position was quietly wrong for exactly the nights it
mattered.

## Decision

The user may choose to have the approximate position refreshed in the
background, on a cadence they pick: every hour, every six, once a day, or a
whole number of hours from one to forty-eight. It is off unless chosen. The
default stays "each time you open Gloaming", and typing the position in stays
first-class.

- **Coarse only, still.** `ACCESS_BACKGROUND_LOCATION` extends the coarse grant
  in time, not in precision. `ACCESS_FINE_LOCATION` remains undeclared, and
  `ManifestPermissionsTest` says so.
- **One fix per wake.** `LocationAlarm` arms an inexact alarm and
  `LocationReceiver` asks for a single approximate fix with a twenty-second
  timeout: from the network provider, or from the platform's own fused
  provider where the network provider is off (API 31 and later). No updates
  are subscribed to and GPS is never asked.
- **Never logged, never sent.** The journal records that a refresh happened or
  why it did not, and never where the phone was. The position goes where a
  typed one does: credential-encrypted storage, and the whole-degree mirror if
  before-unlock alerting is on. Nothing about it leaves the device beyond the
  rounded position Open-Meteo was already given.
- **Unlocked only.** The receiver is not Direct Boot aware. The settings it
  reads and writes are credential-encrypted, and the precise position must not
  be written anywhere weaker. The reboot that locks the phone also clears the
  alarm, and the boot receiver arms it again after unlock.
- **A withdrawn grant ends it plainly.** If the background permission has been
  taken away in the system settings, the next wake puts the cadence back to
  "each time you open Gloaming" and records that it did, so the screen can say
  why rather than leave the change to be noticed.

## Consequences

- Choosing a background cadence brings up Android's own prompt, and from
  Android 11 sends the user to the system page where they must pick "Allow all
  the time". That is a real ask of this audience, and many will decline. They
  lose nothing they had before.
- The alarm is inexact, so the system attaches a window of up to an hour to
  it. "Every hour" means a refresh somewhere within about an hour and three
  quarters, and longer intervals arrive up to an hour late.
- The next refresh is one interval after the last, but never sooner than a
  quarter of an hour away. A position that has never been refreshed counts as
  overdue, so the first refresh after a cadence is chosen comes after that
  quarter of an hour, and so does the retry after a refresh that finds no
  fix. A position detected on the screen counts as a refresh too.
- A refresh that falls due before first unlock waits for the unlock. The
  overnight polls go on using the last stored position meanwhile, which is
  what they did before this decision.
- The app now declares a permission the manifest once promised it never would.
  The promise is replaced by a narrower one - coarse, opt-in, on a schedule
  the user chose - and the permission test pins that shape.
