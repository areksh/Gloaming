# Gloaming - Sourcing and Constraints

A free, ad-free, Play-Services-free Android app that tells you when to look up:
aurora, satellite passes, comets, eclipses, meteor showers and conjunctions.

> **On the name:** this document was drafted under the working title "Sky
> Alerts". The app is called **Gloaming**; see `docs/adr/0001-adopt-engineering-practices.md`.
> Remaining references to the old title mean this app.

Global by design, UK-enhanced. Code and releases on Codeberg; data on Cloudflare behind
a project-owned domain.

**Status:** design notes, revised 22 July 2026. Version numbers, licences, endpoints and
platform limits were checked against the source or the provider's own documentation at the
time of writing. Re-verify before committing; Sec. 3 is moving.

## What this document covers

This document records the project's external constraints and the decisions that follow
from them: which upstream sources are used, under what terms, what is computed where, and
what the hosting topology has to look like as a result.

It is not an application architecture document. Module boundaries, the Room schema, DI
wiring and the Compose structure are out of scope and belong in a separate document that
does not yet exist. The exception is Sec. 9, which specifies the attention and alerting
pipeline, because that pipeline is shaped almost entirely by the constraints recorded here.

## Scope and release gate

**The release gate is the end of Phase 2 (Sec. 14): aurora, cloud cover, darkness windows,
delivered reliably through Doze.** That combination is the underserved niche and is
sufficient to ship v1.0.

Phases 3-5 - satellites, the sky calendar, comets - are post-release updates. They are
what makes people stay, not what makes the app worth releasing. Nothing in Phases 3-5 is a
prerequisite for Phase 1 or 2, and the phasing is designed to keep it that way.

Explicit non-goals for v1: deep-sky objects (Sec. 8), rocket launches (Sec. 14), satellite
magnitude prediction beyond a hardcoded handful (Sec. 6), Google Play distribution (Sec. 12), and
inbound push of any kind (Sec. 9.3).

---

## 1. The load-bearing constraint: the app stays non-commercial

This is a technical constraint rather than a business decision. Two of the best available
sources forbid commercial use, and that propagates through the whole design.

- **AuroraWatch UK** - "Use of the API is restricted to non-commercial purposes. Use in
  paid-for or ad-supported applications is not permitted at this time."
- **Open-Meteo** free tier - non-commercial only. Their worked examples define "operating
  websites or apps that have subscriptions or advertisements" as commercial, and "private
  or non-profit websites or apps that do not have subscriptions or advertising" as
  non-commercial.

A Ko-fi or Codeberg donation link falls between the two definitions. Creative Commons'
NC test is "primarily intended for or directed towards commercial advantage or private
monetary compensation"; voluntary donations are a grey case CC has never definitively
resolved.

**All three permissions were sought and granted in July 2026.** Nothing on the critical
path is now waiting on a third party. The conditions attached are recorded below and are
binding; the correspondence should be kept, since the people who gave these answers will
not necessarily be the ones fielding a question about them in three years.

### Resolved: Open-Meteo, 2026-07

Open-Meteo have confirmed the app may use the non-commercial API, on one condition:

> **Donations must be fully voluntary and must not unlock any feature.**

This is a binding design constraint, not a courtesy. It forecloses, permanently:

- Supporter tiers, "pro" modes, or any cosmetic unlock for donors
- Early or beta access for backers
- Donor-only alert channels, higher polling rates, or extra data sources
- Removing a donation prompt by donating

The constraint is recorded here rather than left in an inbox because it is exactly the kind
of thing violated eighteen months later by a well-meaning "supporters get the beta channel"
decision.

### Resolved: CelesTrak, 2026-07

Reviewed by Dr Kelso, who confirmed the approach and directed attention to the usage
policy. The policy contains no redistribution clause; see Sec. 3 and Sec. 15.

### Resolved: AuroraWatch UK, 2026-07

Approved. The donation link, the public redistribution of proxied data, and the GPL-3.0
licence were all put to them explicitly, and all are acceptable.

**On the dedicated URL:** their docs offer one to API consumers ("In case of difficulties
we can arrange for an alternative URL which is used just by your app"). They have said it
is not needed at present, and that they will provide one if problems arise later.

Two things follow:

- **The shared public endpoint is the supported path for now.** No special-casing in the
  Worker, and the 3-minute cadence stands as documented.
- **A URL change is a live possibility, not a hypothetical.** If usage grows or their load
  changes, the AuroraWatch base URL moves. This is precisely the scenario the manifest
  (Sec. 10) exists for, except that the URL in question is server-side rather than
  in the APK. Keep the AuroraWatch endpoint in Worker configuration rather than hardcoded
  in the fetch, so a swap is a config change and not a redeploy. Cheap now, tedious later.

Contact them again before making any change that materially increases load: a large
install-base jump, a shorter poll interval, or adding `all-site-status.xml` polling for the
Shetland fix (Sec. 4). "They said yes once" is not a standing permission for a different
traffic profile.

**Fallback if permission is ever withdrawn:** OVATION/SWPC-only aurora (Sec. 4). This is a
smaller loss than it appears, because SWPC is the foundation of the aurora feature and
AuroraWatch is a UK-only quality layer on top of it. No architectural change is required to
drop the layer; the bounding-box switch simply always evaluates false.

### App licence: GPL-3.0

Full scope requires SGP4, which requires vendoring Look4Sat's predict package (Sec. 7). That
makes the app GPL-3.0. An MIT licence was available only in the aurora-only scope.

One consequence was disclosed to AuroraWatch when permission was sought, and is recorded
here because it does not go away: **GPL-3.0 permits anyone to fork the app, add
advertising, and redistribute it.** The licence cannot forbid this without becoming
non-free. Such a fork would reach the project's own endpoints using the project's own
AuroraWatch permission. Mitigations are social rather than legal: an endpoint-use note in
the README, and the disclosure already made. If a fork does appear and causes trouble, the
prior disclosure is what makes it their problem to complain about rather than the project's
to explain.

---

## 2. Shape of the system

```
  +----------------------------------------------------+
  | CLOUDFLARE WORKER (cron only, not in read path)    |
  |   5 min   -> SWPC OVATION grid + Kp + solar wind   |
  |   3 min   -> AuroraWatch v0.2 (UK layer only)      |
  |   2 h     -> CelesTrak OMM (not faster, Sec. 3)    |
  |   weekly  -> MPC comet elements                    |
  +-------------------------+--------------------------+
                            | PUT (Class A ops)
  +-------------------------v--------------------------+
  | CLOUDFLARE R2 + public custom domain               |
  |   sky.example.tld/v1/...                           |
  |   index, status, oval, sats, comets                |
  |   Cache-Control set at PUT; CDN absorbs fan-out    |
  +-------------------------+--------------------------+
                            | HTTPS conditional GET, mostly 304 at edge
                            | OUTBOUND ONLY: nothing contacts the app
  +-------------------------v--------------------------+
  | ANDROID APP                                        |
  |   Adaptive polling on the device's own schedule    |
  |   ALL location maths on-device:                    |
  |     oval grid lookup    -> aurora probability      |
  |     astronomy-engine    -> sun/moon/eclipses       |
  |     vendored SGP4       -> satellite passes        |
  |     project Kepler code -> comets                  |
  |   ALL attention and alert decisions on-device      |
  +-------------------------+--------------------------+
                            | direct, per-device
  +-------------------------v--------------------------+
  | OPEN-METEO (cloud cover, deliberately unproxied)   |
  +----------------------------------------------------+
```

### Why R2 and not Pages

Cloudflare Pages is a deployment target, not a mutable store. It has no "write a file"
primitive, and the free plan allows **500 deploys per month**. A 3-minute `status.json`
refresh is roughly 14,400 deploys per month; the Pro plan's 5,000 does not fix it either.
A cron Worker cannot write to Pages in any supported way.

R2 with a public custom domain is the correct primitive. The cron Worker `PUT`s objects;
the CDN serves them directly with ETags and no Worker invocation per request.

### Why the Worker stays out of the read path

Workers on the free plan allow **100,000 requests/day**. Even under adaptive polling (Sec. 9.3)
that puts a hard ceiling somewhere in the low thousands of installs - an unacceptable
scaling cliff for an app whose premise is that it stays free.

R2 read traffic is billed as Class B operations, and only requests that miss the edge
cache reach R2 at all.

### Scaling and cost envelope

R2 free tier, recurring monthly, not a trial: **10 GB-month storage, 1 million Class A
operations, 10 million Class B operations, zero egress.**

| | Volume | Free-tier fraction |
|---|---|---|
| Class A (writes) | ~480/day status + ~288/day oval + 12/day sats ~ **24,000/month** | ~2% |
| Storage | ~40 KB | negligible |
| Class B (reads) | <=96/device/day worst case, typically far lower under adaptive polling, and **only on edge-cache misses** | see below |

At a pathological zero cache-hit rate and worst-case polling, 10M Class B/month supports
roughly **3,000 devices**. With `Cache-Control` set correctly at PUT time, R2 sees
approximately one revalidation per Cloudflare PoP per refresh interval regardless of user
count, so the real ceiling is one to two orders of magnitude higher.

Overage is linear and cheap: Class B is $0.36/million. Ten thousand devices with no caching
at all is roughly $8/month. There is no cliff and no forced plan upgrade - R2 overage does
not require the Workers Paid plan.

**Upgrade path, in order:** correct `Cache-Control` (free) -> accept R2 Class B overage
(cents) -> Workers Paid at $5/month if the cron ever needs more than free Workers allows.

### Caching contract

Set on the object metadata at PUT time; the CDN honours it:

| Object | `Cache-Control` |
|---|---|
| `index.json` | `public, max-age=3600` |
| `status.json` | `public, max-age=60, stale-while-revalidate=120` |
| `oval.bin` | `public, max-age=120, stale-while-revalidate=180` |
| `sats.json` | `public, max-age=1800` |
| `comets.json` | `public, max-age=21600` |

Clients use `If-None-Match` against the ETag R2 sets on upload. "ETagged" alone is not a
caching strategy; without `max-age` the edge does not absorb the fan-out and every device
revalidates against R2.

**Clients jitter every scheduled wake.** A fixed period with no randomisation aligns every
install on wall-clock boundaries. A uniform random offset across the period is a few lines
at the start and painful to retrofit. The OS batches jobs across apps to save radio
wake-ups, which supplies some dispersion for free, but that is a side effect and is not
relied upon.

### Three deliberate asymmetries

- **Aurora, satellites and comets are proxied.** Those providers want one server fetch
  fanned out to many clients. For CelesTrak this is mandatory rather than polite (Sec. 3).
- **Open-Meteo is called direct from each device.** Its limits (10,000/day, 5,000/hour,
  600/minute) are per-IP. Proxying concentrates every user onto a small number of Worker
  egress IPs and trips the 600/minute limit, taking all users down simultaneously. Direct
  calls spread across many client IPs.

  Caveat on that reasoning: mobile carriers use CGNAT heavily, so "thousands of distinct
  IPs" overstates the dispersion, and other applications' Open-Meteo traffic shares the
  same carrier egress budget. With a 1-hour on-disk cache and realistic install counts
  this is not a problem, but the argument is weaker than it first appears and the failure
  mode - a whole carrier's users rate-limited at once - should be handled gracefully.

  The Open-Meteo base URL therefore lives in the manifest (Sec. 10), not in the APK. Hardcoding
  it reproduces exactly the trap the manifest exists to avoid.
- **The aurora grid is shipped whole and looked up on-device**, never queried by
  coordinate. This is a privacy decision (Sec. 4, Sec. 11), not a performance one.

---

## 3. CelesTrak: the TLE format is ending

**On 2026-07-11, with the cataloguing of Saramago, the public catalogue ran out of 5-digit
numbers.** The USSF SATCAT is now past 100000. Per CelesTrak:

> All newly cataloged objects will have 6-digit catalog numbers of 100000+ and **GP data
> will not be available for them using the TLE format.**

**The exhaustion point was 69999, not 99999.** The range 70000-99999 is 18 SDS working
space and was never available for public catalog numbers, so the legacy TLE format
continues only for objects below 70000. ISS is 25544, so it and today's bright satellites
are unaffected, but anything newly launched and bright is permanently invisible to a
TLE-based application.

**The app uses OMM in CSV from day one and never parses TLE.** CelesTrak has provided OMM
since May 2020 using CCSDS 502.0-B-3 Table 4-1 keywords; CSV is roughly 10% smaller than
TLE and simpler to parse. Their usage policy is explicit: "Users need to update their
software to use the CSV formats."

This was recorded as a direct cost in Sec. 7, on the understanding that Look4Sat's
`OrbitalData` parses TLEs. **Checked against v4.4.6, 2026-08-22: it does not.** It is a
plain data class of mean elements; the TLE and CSV parsers live in `utility/DataParser.kt`,
which is not vendored. The real work was a field mapping plus one format conversion, since
`OrbitalData.epoch` is the TLE's packed `YYDDD.dddddd` while OMM gives ISO-8601 - and even
that happens in the Worker, so the app never parses a date. **As built, the app deserialises
numbers and nothing else.**

### Which objects are affected, concretely

Confirmed directly by Dr Kelso, 2026-07:

- **The `visual` group is not yet using 6-digit catalog numbers.** Phase 3 pass prediction
  for the classic bright objects is not in immediate jeopardy.
- **Starlink is**, once 18 SDS releases data for the launches since the threshold was
  crossed. Four launches and nearly 100 satellites went up in the first weeks alone.
- **AST SpaceMobile is.** This is the one that matters most for this app.

AST SpaceMobile's BlueBird satellites are the brightest new objects going up: mean apparent
magnitude 3.44, and near zenith they reach magnitude 0.5. The BlueWalker 3 prototype peaked
at magnitude 0.4, making it briefly one of the brightest objects in the night sky, brighter
than nine of the ten brightest stars. AST is targeting roughly 45 satellites by the end of
2026.

So the position is not hypothetical: **the brightest class of new satellite is exactly the
class a TLE-parsing application cannot see.** An app whose entire premise is telling people
when to look up would be structurally blind to the most conspicuous thing in the sky.

Open question for Kelso: does anything block 6-digit objects from entering the `visual`
group? If the group is curated by legacy tooling, it may lag reality precisely when it
matters most. See Sec. 15.

### CelesTrak usage policy compliance

The governing document is the CelesTrak Usage Policy (2026-05-15, updated 2026-05-22), plus
`celestrak.org/NORAD/documentation/gp-data-formats.php`, which is the normative spec for
the queries made here and must be read before the parser is written.

**The policy contains no redistribution or mirroring clause.** It is entirely concerned
with load: query cadence, correct URLs, correct formats, and error handling. Republishing
derived elements at a public URL is not addressed and is not prohibited. See Sec. 15 for why
this question is closed.

The policy in fact endorses this architecture directly. On organisations whose users share
an egress IP, it says it is up to them to set up a proxy to cache the queries in a way that
does not get all their devices blocked. One server fetch fanned out to many clients is the
behaviour being asked for.

Hard requirements:

- **Data updates once every two hours.** Never poll faster. This applies to SupGP too, even
  though its constellations update on varying schedules.
- **Download only the data needed, when it is going to be used, and only once per update.**
- **Circuit-break on any non-200 response.** The policy is explicit and this is the single
  most important requirement in it: machine-to-machine software should immediately stop
  querying on any non-200 response and report to a human for investigation. Kelso states
  that this step alone prevents an IP being firewalled even as limits tighten further.
  - Not per-code handling. Not exponential backoff. **Stop.**
  - HTTP 301 means a wrong domain or a legacy query still being redirected, and the
    redirects will stop being honoured.
  - HTTP 404 generally means old URLs from decade-old software.
  - HTTP 403 means the data has not updated since the last successful download.
  - HTTP 50x means the server is struggling and queries must cease immediately.
- Use `celestrak.org`, not `.com` or `.net`. HTTPS.
- Enforcement currently covers the Active list for GP and the Starlink list for GP and
  SupGP. SATCAT, SOCRATES, EOP and space weather are next. The posture is tightening, not
  stable.

CelesTrak supports hundreds of thousands of unique IP addresses daily and millions monthly,
as a 501(c)(3) non-profit, with no paid tier. The stated alternative to voluntary
compliance is charging for access.

Per-device fetching is therefore impossible. A thousand devices pulling elements would
produce one success, 999 failures, and a firewall entry.

### Implementing the circuit breaker

A cron Worker is stateless per invocation, so a tripped breaker has to be persisted or the
next invocation will cheerfully retry, which is the exact behaviour the policy warns will
get the IP firewalled.

- Any non-200 from CelesTrak writes a tripped flag to R2 or KV alongside the response code
  and timestamp.
- Every subsequent invocation checks the flag first and does not query CelesTrak while it
  is set.
- **Reset is manual.** A human investigates and clears it. Automatic recovery defeats the
  purpose.
- `sats.json` keeps serving its last good copy with its age surfaced (Sec. 10). Elements stay
  usable for days, so a tripped breaker degrades pass prediction gracefully rather than
  breaking it.
- Tripping the breaker notifies a human. This requires an alerting path the project does
  not currently have; see below.

### Developer alerting

"Report the results to a human" presumes a channel to a human. There isn't one. A cron
Worker that fails silently is precisely how an IP ends up firewalled, and the same silent
failure mode applies to AuroraWatch and SWPC without the firewall consequence.

- **`health.json` in R2** with last-success timestamps and last-error codes per source.
  Needed for debugging regardless, but it is pull rather than push, so it only helps if
  someone remembers to look.
- **One developer-only ntfy topic.** One POST when a breaker trips. The Sec. 9.3 reasoning
  against ntfy does not apply here: no fan-out, no user privacy surface, no persistent
  connection on anyone's phone but the maintainer's.
- Check whether Cloudflare's own Workers alerting covers cron failure on the free plan
  before building anything.

Both, together, are roughly half a day of work.

---

## 4. Aurora: global foundation, UK enhancement layer

**The app is not UK-only.** SWPC's `ovation_aurora_latest.json` is a **global 360 x 181
grid** - longitude 0-359, latitude -90 to +90 - of aurora probability in percent, covering
**both hemispheres**, updated every 5 minutes with a 30-minute forecast. Indexing the cell
for the observer's position gives the answer directly. Aurora australis works without
additional code: Tasmania, New Zealand's South Island, Patagonia, the Antarctic bases.

### The grid, not a Kp threshold

**Geomagnetic latitude is not geographic latitude.** The auroral oval is centred on the
geomagnetic pole, which sits well over towards North America. Aurora is seen from Colorado
and Virginia during storms while the same geographic latitude in Siberia sees nothing. A
"Kp >= 5 and latitude >= X" rule is wrong by thousands of kilometres depending on longitude.

OVATION has the geomagnetic field model built in. Using the grid makes the app correct
everywhere with no IGRF code in the project. The quiet oval sits around 67-68°
**geomagnetic** latitude, and aurora can be visible up to ~1,000 km equatorward of the oval
low on the horizon, so the grid cell value is treated as a floor rather than a cutoff.

**Calibration caveat:** OVATION is known to overpredict, particularly at low activity. The
cell value is an input to the likelihood model, not a probability to display verbatim as
truth. The UI wording should not imply more precision than the model has.

### Ship the grid, look it up on-device

The raw file is ~65,000 entries and a couple of megabytes of JSON, which cannot be pushed
to devices every few minutes.

The Worker packs the grid as **one byte per cell** (probability 0-100 fits in a byte),
row-major. 65 KB raw; gzip reduces it to single-digit KB because it is overwhelmingly
zeros. The device decodes and indexes locally.

A `/aurora?lat=&lon=` endpoint is rejected. It is easier, and it would log approximate user
locations on every poll, which destroys the Sec. 11 posture. The privacy story depends on the
grid being shipped whole.

### Two-tier fetching

`oval.bin` at 5-10 KB gzipped is roughly ten times the size of `status.json`, for data that
is usually all zeros.

`status.json` therefore carries `oval_etag` and a coarse `global_activity` flag. The device
fetches `oval.bin` only when activity is non-nil or the ETag has changed and the device
needs a local answer. One field of schema, an order of magnitude of traffic.

### Solar wind is a leading indicator, not decoration

`status.json` carries solar wind because DSCOVR sits at L1, roughly an hour upstream of
Earth. **A southward turn in Bz gives 30-60 minutes of warning before a substorm.** This is
what makes adaptive polling (Sec. 9.3) better than a fixed cadence rather than merely cheaper:
the app escalates its own attention *before* the event, using data that says it is coming.

### Where AuroraWatch fits

**AuroraWatch UK is an enhancement layer, not the foundation.** A bounding-box check on the
observer's position selects it inside the UK and OVATION outside. Both feed one internal
likelihood model, so the UI never branches on which is in play.

It is worth the complexity because AuroraWatch runs on real UK magnetometer data rather
than a planetary index, and their own position is that Kp is a poor indicator for the UK
specifically. It is a genuine quality difference, for one country. Losing the AuroraWatch
layer (Sec. 1) would cost the UK quality bump, not the feature: the bounding-box switch
simply always evaluates false and every user falls through to OVATION.

**Shetland:** AuroraWatch alerts come from a single "alerting site" that switches
automatically on data loss and defaults to the Lancaster magnetometer, making alerts
increasingly pessimistic further north. `all-site-status.xml` gives per-region values.

---

## 5. Geographic coverage

| Feature | Coverage |
|---|---|
| **Aurora** | **Global, both hemispheres.** OVATION grid. UK gets an AuroraWatch quality bump. Effectively useless from the tropics, which is accurate rather than a defect. |
| **Comets, eclipses, conjunctions, planets** | **Fully global.** Computed per-observer. The southern hemisphere often gets the better comets. |
| **Dark-sky windows** | Global, subject to the polar caveat below. |
| **Cloud cover** | Global, at varying quality - Open-Meteo runs 1-2 km regional models over Europe and North America against 9-11 km global elsewhere. |
| **ISS** | **Capped at roughly +/-60° latitude.** 51.6° inclination means the ground track never exceeds +/-51.6° and visibility extends only a few degrees past it. Iceland, northern Norway and Alaska are marginal to nothing. |
| **Other bright satellites** | Fine at high latitude - many are sun-synchronous at ~98° inclination, so polar users still get passes. |
| **Meteor showers** | Northern-biased in reality. The Perseids' radiant sits at Dec ~ +58, essentially a non-event south of the equator; the Eta Aquariids favour the south. The radiant-altitude calculation handles this automatically, which is why it is computed rather than tabulated. |

### The latitude irony

**Aurora and ISS are anticorrelated in latitude.** The best aurora is where the ISS is not
visible. The UK sits in the narrow band that is good at both: London at 51.5°N is almost
directly beneath the ISS ground track, and the aurora reaches Britain several times a year
in a decent solar cycle. Cloud is the binding constraint at those latitudes, which makes
cloud integration a feature rather than a nicety, and a world-class free UK-specific aurora
service exists. The UK is a good home for this app on the merits.

### Polar and high-latitude handling

**Above roughly 60°N there is no astronomical darkness at all from May to August**  -
Shetland's "simmer dim". `searchAltitude` for the sun at -18° returns **null** rather than
a time, and the app reports "no astronomical darkness tonight" rather than crashing or
computing nonsense. High-latitude winter gives 18+ hours of darkness and a correspondingly
long event list.

This feeds directly into Sec. 9.3: at high latitude in summer the app goes dormant for the
season, because there is nothing it could usefully tell anyone.

astronomy-engine overhauled its rise/set logic for polar cases and added USNO polar test
data; it will find, for example, a Moon that rises, stays partially visible for 20 minutes
at 85°N, and sets again. The library handles the maths. The app handles the nulls.

---

## 6. Data sources

| Source | Endpoint | Auth | Refresh | Licence / terms |
|---|---|---|---|---|
| **NOAA SWPC** | `services.swpc.noaa.gov/json/ovation_aurora_latest.json` (global grid), `/json/planetary_k_index_1m.json`, `/products/summary/solar-wind-mag-field.json` (Bz, Bt), `/products/summary/solar-wind-speed.json` | None | 5 min | US Gov, **public domain**. The unrestricted foundation. |
| **AuroraWatch UK** | `aurorawatch-api.lancs.ac.uk/0.2/status/current-status.xml` (small), `.../alerting-site-activity.xml` (24 h values), `.../all-site-status.xml` (per-site) | None | **>= 3 min** | Non-commercial only. User-Agent must name the app. Thresholds must not be adjusted. Must not be called "official". Attribution required. Contact first. XML only. **UK layer only.** |
| **CelesTrak** | `celestrak.org/NORAD/elements/gp.php?GROUP=...&FORMAT=CSV` - groups `stations`, `visual` | None | **2 h, once per update** | Non-profit. 403 on repeat. Firewall on abuse. **OMM CSV, not TLE.** |
| **MPC** | Comet orbital elements | None | Weekly | Public. Includes H (absolute magnitude) and slope. |
| **JPL SBDB / Horizons** | Cross-check elements | None | As needed | Public. |
| **Open-Meteo** | `api.open-meteo.com/v1/forecast` - `cloud_cover`, `cloud_cover_low/mid/high` | None | 1 h max | Non-commercial. **CC BY 4.0 - attribution link next to displayed data.** |
| **IMO** | Meteor shower calendar | - | Annually | No API exists. Hand-built table (Sec. 8). |
| **IAU CPS SCORE** | `score.cps.iau.org` - REST API + full CSV dump | None for reads | Monthly at most | **CC-BY**, acknowledgement text mandatory. Satellite brightness *observations*, not standard magnitudes. **Not used in v1** - see Sec. 6. |

**Aurora is the only feature with a live upstream.** Everything else is deterministic or
near-deterministic: passes are computed, eclipses and conjunctions are known centuries
ahead, showers are annual, comet elements change weekly at most. This is why the adaptive
polling in Sec. 9.3 applies to aurora and to nothing else - elsewhere there is nothing to poll
for.

### Satellite brightness

**v1 does not use a magnitude dataset at all.** The reasoning below matters because the
obvious move - bundle `qs.mag` and print numbers - is worse than doing nothing.

#### The classic source: Mike McCants

`qsmag.zip` (`mmccants.org/programs/qsmag.zip`) holds intrinsic magnitudes for ~1,500
objects, derived from McCants' own visual observations over several decades. It is the
classic source because for a long time it was the only one of its breadth.

Its status is more specific than "abandoned":

- **The magnitude file is dormant, not the author.** `qsmag.zip` is dated **14 September
  2020**, but Quicksat itself is at **v2.15Q, 7 January 2025**. The site is live. McCants
  is active and reachable; he has simply stopped updating the data.
- `mcnames` is no longer updated and has been removed from the site.
- Stellarium bundles `qs.mag` plus RCS data and, where no standard magnitude exists,
  estimates visual magnitude from RCS. Stellarium obtained **explicit permission** from
  McCants.

**Staleness is not the same as inaccuracy.** Standard magnitudes for stable objects do not
rot - an Atlas Centaur upper stage tumbles the same way in 2026 as in 2020, and the classic
bright catalogue is stable. The file is stale in the sense of "no new entries", not
"wrong".

**Incompleteness is the fatal part: zero Starlink coverage.** Starlink is most of what a
casual observer now sees. A source that covers the 1970s well and the last six years not at
all is answering a question users are not asking.

#### Two incompatible conventions

Quicksat magnitudes assume 1000 km and **100% illumination**, and predict **maximum**
brightness. Molczan magnitudes assume 1000 km and **50% illumination**, and predict **mean**
brightness. The illumination assumption alone is 0.8 mag; mean-versus-max adds roughly 0.7
for a tumbling cylinder. McCants stated the distinction himself in 1996: his value predicts
maximum brightness, Molczan's predicts mean, and the latter is about a magnitude fainter.

Used alone, either source is internally consistent. The hazard is blending them into one
table, which silently produces predictions a magnitude and a half out.

#### The deeper problem

These are **relative comparison values, not absolute predictions.** The satobs community's
own guidance is that whichever system is used, an object will most likely not display the
tabulated brightness at any point in a pass except possibly one instant. McCants' values
are additionally the *optimistic* case, being maxima: an object tumbling through two
magnitudes will spend most of the pass fainter than its tabulated value.

An app that says "magnitude 2.5" and delivers 4.5 half the time is worse than one that
never claimed a number.

#### What the app does instead

**The `visual` group is already the curation.** CelesTrak's `visual` group is ~150 objects
pre-selected as naked-eye visible. Restricting pass prediction to that group means "is this
worth telling someone about?" has already been answered upstream. Magnitudes would only
help *rank* within the group, and qualitative buckets rank adequately.

Everything needed comes from SGP4 plus astronomy-engine, with no external data:

| Input | Source |
|---|---|
| Satellite sunlit? | Satellite position against Earth's shadow. Geometry. |
| Observer dark enough? | Sun between -6° and -18°. astronomy-engine, offline. |
| Range at closest approach | SGP4 |
| Maximum elevation | SGP4 |
| Phase angle (sun-satellite-observer) | Geometry |

Bucket those into descriptions: high maximum elevation, favourable phase and short range ->
"bright pass, high in the south"; low, grazing, entering shadow early -> "low and brief,
probably faint".

Magnitudes are hardcoded only for the handful where a number can be stood behind
(ISS ~ -1.8, CSS/Tiangong).

Iridium flares are gone; the constellation deorbited. Not built. A one-line FAQ entry,
because the question still gets asked.

Starlink trains are pure geometry from the elements - "fresh launch, sunlit, low" is the
whole prediction, with no magnitude data required.

#### The modern alternative: SCORE (IAU CPS)

The claim that no current open brightness dataset exists is no longer true. **SCORE**, the
Satellite Constellation Observation Repository, is run by the IAU Centre for the Protection
of the Dark and Quiet Sky (`score.cps.iau.org`):

- A centralised repository of satellite brightness observations, **free and open for
  download, CC-BY**.
- A REST API (JSON, rate-limited to 10 req/s, searchable by date range or NORAD ID; an API
  key is needed only for uploads, not reads), plus a **full CSV dump** of the dataset.
- Live and taking data. Recent observations as of **July 2026** include Starlink, BeiDou,
  Molniya and SL-12 rocket bodies.
- NSF-funded (grant AST 2332736), run by CPS SatHub - not a single-maintainer project.

**It holds observations, not standard magnitudes.** Each row is "at time T, object N was
magnitude X, at range R and phase angle P". Deriving usable standard magnitudes means
reducing them: normalise to 1000 km, pick one phase convention and hold it, aggregate per
object with a statistic robust to tumblers and outliers. That is Worker-side work - a
monthly CSV pull, offline, no device impact - of a few hundred lines plus real judgement.

Expect coverage skew. CPS exists because constellations interfere with observatories, so
the data is constellation-heavy and may be thin on exactly the classic bright objects
McCants covered well. SCORE may prove complementary to `qs.mag` rather than a replacement,
which is an argument for taking neither and staying qualitative.

**That skew is less of a drawback than it first appears.** The BlueBird photometry cited in
Sec. 3 was produced by Cole, Mallama, Harrington and Respler under IAU CPS, the same
organisation that runs SCORE. So SCORE covers precisely the objects `qs.mag` never will and
that matter most to this app: the newest, brightest, 6-digit-catalogued constellation
satellites. If magnitudes are ever wanted, those are the objects worth having them for. The
test in Sec. 15 is therefore coverage of the objects users will actually see, not coverage of
the catalogue in general.

Deferred to Phase 3+ and only if users ask for numbers. See Sec. 15.

#### Rejected: physical modelling and RCS

SatChecker estimates brightness with Lumos-Sat (Fankhauser et al. 2023) - proper BRDF
modelling, integrating over scattering surfaces and including light reflected from Earth.
Scientifically correct and entirely disproportionate here: it needs per-satellite geometry,
material properties and orientation models.

RCS-derived estimates (Stellarium's fallback) correlate loosely with optical brightness at
best; radar cross-section is not albedo. Stellarium does it because it needs *something* for
twenty thousand objects. This app has ~150.

---

## 7. What's computed where

**On-device, offline, no network:**

| Feature | How |
|---|---|
| Aurora probability here | Local lookup into the packed OVATION grid |
| Rise/set, twilight windows | `searchRiseSet`, `searchAltitude` (arbitrary sun angle) |
| Lunar eclipses | `searchLunarEclipse` |
| Solar eclipses, global + local circumstances | `searchGlobalSolarEclipse`, `searchLocalSolarEclipse` |
| Moon phase, illumination | `searchMoonPhase`, `illumination` |
| Planetary conjunctions | `searchRelativeLongitude`, `pairLongitude` |
| Greatest elongation, oppositions, apsides | `searchMaxElongation`, `searchPlanetApsis` |
| Transits | `searchTransit` |
| Seasons, constellation lookup | `seasons`, `constellation` |
| Satellite passes | vendored SGP4/SDP4 + OMM elements |
| Comet positions | project Kepler solver (Sec. 8) |
| Dark-sky windows | `searchAltitude` at -18°, **null-safe** (Sec. 5) |
| **Attention state and alert decisions** | **All of them. See Sec. 9.** |

**Hosted, because it cannot be computed:** aurora grid and status, orbital elements, comet
elements, shower table.

### Location input

**Manual location entry is a first-class input, not a fallback.** The target audience runs
GrapheneOS and denies location by default; the whole privacy posture is weakened by asking
for a permission the app does not need; and manual entry enables trip planning ("will
Shetland be clear on Tuesday?"), which device GPS cannot.

`LocationManager` (never the fused provider - Play Services) is offered as a convenience
for users who want it. The app must be fully functional with location permission
permanently denied.

### Android app: SDK levels

```kotlin
minSdk = 24        // Android 7.0
targetSdk = 37     // Android 17, stable since 16 June 2026
compileSdk = 37
jdkVersion = 21
```

**Look4Sat** - a FOSS Android satellite tracker with no Play Services, ~1,200 stars,
actively developed - ships exactly `compileSdk 37 / minSdk 24 / jdkVersion 21`, AGP 9.2.1,
Kotlin 2.4.0, Compose BOM 2026.06.00, and nothing in this dependency set forces higher.
This audience keeps hardware alive for a decade; Android 7 support costs one
`if (SDK_INT >= 26)` branch for notification channels.

`targetSdk 37`: off-Play there is no Google deadline, but a stale target makes the OS apply
legacy compatibility behaviours silently, which is the opposite of what this audience
wants.

### Libraries

| Library | Coordinates | Licence | minSdk impact |
|---|---|---|---|
| **astronomy-engine** | **vendored** at `app/src/main/vendor/`, from tag `v2.1.19` | MIT | **None.** The 10,674-line Kotlin source imports `kotlin.math.*` and nothing else - zero `java.*` imports, no `java.time`, no `Calendar`. `Time.fromMillisecondsSince1970(Long)`. No desugaring. |
| **SGP4/SDP4** | vendored at `app/src/main/vendor/com/rtbishop/look4sat/`, from tag `v4.4.6` | **GPL-3** | None - pure `kotlin.math`. Eleven files: the ten in `predict/` plus `utility/GeoConverter.kt`, which they import two extension functions from. |
| OkHttp, Room, Compose, coroutines, kotlinx-serialization | | | Standard. |

No push, messaging or distributor library is required. See Sec. 9.3.

**Not used:** Orekit (Apache-2.0, actively developed, but a full space-dynamics library
needing external data files - disproportionate). Fused location provider (Play Services).
`EncryptedSharedPreferences` (Jetpack Security is effectively unmaintained; plain
`SharedPreferences` is adequate for a user's own key on their own device).

**The vendored package contains no pass search.** Checked 2026-08-22: it offers
`getPosition`, `getElevation`, `willBeSeen` and the eclipse state for a single instant, and
Look4Sat's own AOS/LOS hunt lives in its app layer rather than in the code vendored here.
Finding when a pass begins, ends and peaks is therefore this project's code -
`sh.arek.gloaming.satellites.PassFinder` - and is the one substantial piece of new astronomy
in Phase 3. See ADR-0007 for where the vendored files sit and why no `:core:predict` module
was built.

**predict4java** (`uk.me.g4dpz:predict4java:1.2.2`) is the alternative to vendoring:
maintained (1.2.2, Feb 2026) and **relicensed to MIT** in 2026 - older sources saying GPL
are out of date. It pulls in `commons-lang3`, `slf4j-api` and **`joda-time`** for what is
pure maths, and it is TLE-oriented, so adopting it means adding OMM support to someone
else's library rather than to a local fork. Vendoring Look4Sat's Kotlin port is cleaner;
the cost is GPL-3.

**Validation:** the vendored propagator plus a new element mapping is exactly the change
that produces passes 40 seconds out. Vallado's SGP4 verification test vectors run against
the vendored code as a JVM unit test (there is no CI; see Sec. 12). Scoped to where the app
predicts - near-Earth, perigee above 156 km - because this port descends from the original
Spacetrack Report #3 and Vallado's 2006 revision differs from it precisely in the deep-space
Lyddane fix and the sub-156 km drag modification. In scope, measured 2026-08-22: 20 m for
objects in normal flight, 320 m for those in decay, against a 500 m bound. 320 m is 40
milliseconds of pass time.

### astronomy-engine: dormancy, and why it is vendored

Last release **v2.1.19, December 2023**; last repo push **January 2025**. Roughly 18 months
quiet despite the author's stated long-term commitment. For an ephemeris library this is
more defensible than most - VSOP87 does not rot, and it is validated against NOVAS and JPL
Horizons to +/-1 arcminute - but it is an unmaintained dependency.

**It is also not on Maven Central.** The coordinate recorded in earlier drafts of this
document (`io.github.cosinekitty:astronomy:2.1.19`) does not exist anywhere. The only
distribution of the Kotlin/JVM edition is JitPack, as `com.github.cosinekitty:astronomy:v2.1.19`,
and JitPack builds the artefact on demand rather than serving an immutable release - a
third-party build service in the supply chain, and friction for both F-Droid inclusion and
reproducible builds (Sec. 12).

Since it is MIT, one file and dependency-free, it is **vendored** instead, byte-identical,
under `app/src/main/vendor/`. See ADR-0006. Dormancy stops being a risk once the source is
in the tree.

---

## 8. The two components written from scratch

### Comets

astronomy-engine **has no Keplerian element input.** Nothing accepts MPC or JPL elements.
`defineStar` handles only fixed objects beyond one light-year. `GravitySimulator` requires
state vectors.

1. Parse MPC elements (q, e, i, RAAN, argp, T, H, slope).
2. Solve Kepler's equation - Newton-Raphson for elliptical, **Barker's equation for
   near-parabolic**, which most bright comets are. `e ~ 1` breaks the standard solver.
3. -> heliocentric ecliptic coordinates.
4. Subtract `helioVector(Body.Earth)` -> geocentric.
5. -> RA/Dec -> `horizon()` for alt/az.
6. Magnitude: `m = H + 5*log10(delta) + 2.5*K*log10(r)`, delta = Earth distance, r = Sun distance,
   K = activity slope from the elements.

Two-body propagation is accurate to arcminutes over months, which is well beyond the
requirement ("is it up, is it dark, is it brighter than 6"). Roughly 150-200 lines
including Barker's.

The H/slope formula is unreliable - comets outburst, fragment and disappoint. Predicted
magnitude is presented as a hint and labelled as one. This is why `comets.json` is curated
rather than scraped: "which comets are worth telling someone about" is a weekly judgement
call.

### Meteor showers

No API exists. A hand-built table is required: name, radiant RA/Dec, nightly radiant drift,
activity window, peak date/time, ZHR, parent body. Roughly 15 rows covers the showers
anyone cares about. Shipped in the APK with a remote override.

Given the radiant, the app computes radiant altitude at the observer's location combined
with moon phase and moonrise - which most incumbents skip. A ZHR of 100 with a full moon
and a radiant at 10° is not worth getting up for, and the app says so. This also makes the
feature correct in the southern hemisphere for free (Sec. 5).

### Deep-sky objects: excluded

astronomy-engine's user-defined stars are **exactly eight slots** - `Body.Star1` through
`Body.Star8`, backed by a global mutable `starTable`. Not a soft limit, not configurable,
not thread-safe.

Slots can be redefined in a loop to walk a Messier catalogue, at the cost of mutating
global state every iteration. If DSOs ever become a feature, alt/az is computed directly
from catalogue RA/Dec instead. For v1 they are out of scope; Stellarium does this better.

---

## 9. Attention, decision and delivery

The "notifications don't work" reviews on the existing AuroraWatch Android app are a Doze
problem, not a data problem. But delivery is necessary and not sufficient: an app that
delivers reliably and decides badly gets uninstalled at 2 a.m.

### 9.1 Everything happens on the device

The notification is local. The decision is local. The schedule is local. **Nothing ever
contacts the app**; the app reaches out on its own timetable, and nothing else can reach
in.

This follows from the privacy posture rather than the reverse: the server does not know
where any user is (Sec. 4, Sec. 11), so it could not make a useful per-user decision even if it
wanted to.

The pipeline:

```
  scheduled wake (Sec. 9.3)
    -> fetch status.json (1 KB, conditional)
    -> global_activity nil?  -> stop. No notification. Back to sleep.
    -> fetch oval.bin if oval_etag changed
    -> local grid lookup at the observer's position
    -> local likelihood model (OVATION cell, or AuroraWatch inside the UK)
    -> below user threshold?  -> stop.
    -> sun above -6°?  -> stop.  (astronomy-engine, offline)
    -> fetch cloud cover (Open-Meteo, direct)
    -> cloud above user tolerance?  -> stop, unless user opted out of the cloud gate
    -> hysteresis / quiet-hours / rate-limit checks
    -> notify
```

### 9.2 Decision rules

- **Hysteresis.** Fire on the rising edge across `T_on`. Do not re-arm until the value
  falls below `T_off`, where `T_off < T_on`. Kp oscillating around a single threshold
  during a storm is how an app notifies eleven times before dawn.
- **Minimum re-alert interval.** Per channel, default 6 hours. A storm is one event, not
  forty.
- **Quiet hours.** User-set, defaulting to off. An exceptional-event override (e.g. local
  probability > 80%) is offered as a separate opt-in toggle, defaulting off. The user
  decides what is worth being woken for; the app does not decide on their behalf.
- **Per-user threshold.** Exposed in settings, conservative by default. High-latitude users
  want a much higher bar than users in Kent.
- **Darkness gate.** Offline, free, and eliminates most false alerts. Non-optional.
- **Cloud gate.** Requires network at decision time. If Open-Meteo is unreachable, notify
  anyway and state that cloud cover is unknown rather than suppressing silently.

**The gates apply to scheduled events too, re-checked close to the event.** A pass
notification fired at 19:00 for a 22:14 pass using 19:00's cloud data is worse than
useless. Every outdoor event - passes, showers, eclipses, comets, NLC - is gated on
darkness (free, offline) and on cloud re-checked at roughly T-30m. This is the one rule
that generalises across the whole app, and it costs a call the app is already making.

### 9.3 Delivery: polling only

**There is no push. There is no inbound connection of any kind.** Earlier drafts carried an
ntfy topic-subscription layer; it is removed.

Aurora is the only reactive feature (Sec. 6), so push affected exactly one thing: latency, by
roughly 0-15 minutes, against data that already lags the sky by 3-5. Against that it cost
an external service dependency, a second code path firing a few times a month and therefore
rotting undetected, and a persistent connection for every user not already running a
distributor. Push is never guaranteed, so the poll path has to exist and work regardless;
having only that path means the one that matters is the one exercised constantly.

The residual argument for push - a user *already running* ntfy for other apps gets it at
near-zero marginal battery, since the connection is shared - is real, but applies only to
that subset. It is an optimisation for some users, not architecture. Out of the release
path; revisit after v1 if anyone asks.

#### Adaptive polling

A flat 15-minute poll is both slower than wanted when it matters and more expensive than
needed the rest of the time. Every input below is already available, most of it offline and
free:

| Condition | Poll rate |
|---|---|
| Sun above -6° locally | **Do not poll at all.** Night hours are known days ahead - astronomy-engine, no network. |
| No astronomical darkness at all (simmer dim, Sec. 5) | Dormant for the season |
| Dark, but heavily overcast | Hourly |
| Dark, clear, geomagnetically quiet | 30 min |
| Dark, clear, Bz southward or Kp elevated | **5 min** |

The last row is the point. Because solar wind is an L1 measurement (Sec. 4), the app escalates
its own attention 30-60 minutes *ahead* of a substorm. That is better latency than a flat
15-minute poll precisely when it matters, and far less battery than one the rest of the
time.

Battery, honestly: 96 conditional GETs a day is trivial as data, but each costs a radio
wake-up - roughly 5-10 seconds of high-power state plus RRC tail on cellular, much less on
WiFi. Low single-digit percent per day at worst case. Adaptive polling cuts that
substantially, because most of the day fails the darkness gate before any network is
touched.

#### Storm mode

When local probability crosses a high threshold **and** it is dark **and** it is clear,
escalate to a `specialUse` foreground service polling every couple of minutes until the
event subsides. This is a persistent notification, but at that point the user is standing
in a field looking north, which is the situation the app exists to produce. Off-Play,
`specialUse` can be declared without a reviewer deciding an astronomy app does not qualify.
It costs nothing the rest of the time because it does not run.

#### Implementation notes

- **Battery-optimisation exemption is still the whole game.** Request
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and explain why in-app. It is what lifts Doze's
  network restrictions; without it, polls are deferred into maintenance windows hours
  apart, precisely overnight, when the aurora happens and the phone is on a bedside table.
  GrapheneOS users will grant it given a reason.
  - **The exemption is a requirement, not a refinement**, and running without it is out of
    scope: the app says so plainly rather than degrading quietly, and the non-exempt
    schedule is deliberately neither measured nor accommodated. Decided 2026-08-22. The
    watch-now switch is the answer for a night that matters, since a foreground service
    runs its own clock and no alarm policy reaches it.
- **OEM caveat:** the exemption is sufficient on AOSP-derived and GrapheneOS builds. It is
  not sufficient on several major OEM skins, which apply their own process killers. Link
  dontkillmyapp.com from the FAQ and detect the common cases where possible.
- **`WorkManager`'s minimum periodic interval is 15 minutes**, which the table above
  violates in both directions. Use chained `OneTimeWorkRequest`s with computed delays, or
  `AlarmManager.setWindow()`. Both are viable once the exemption is granted.
- Jitter every scheduled wake (Sec. 2).

#### Not built

- **A persistent websocket.** Molly runs one because Signal needs sub-second delivery of
  arbitrary per-user messages. This payload is one global status that changes a few times a
  month. Android 15 caps `dataSync` foreground services at ~6 h per 24 in any case; storm
  mode uses `specialUse` and runs for the duration of an event, not permanently.
- **Exact alarms.** `USE_EXACT_ALARM` is reserved for alarm-clock and calendar apps;
  `SCHEDULE_EXACT_ALARM` needs a manual grant in Settings. The design needs neither: see
  live pass mode below, which owns its own clock rather than asking the OS for one.

### 9.4 The attention model

Three tiers, one vocabulary, two implementations. **Aurora's tiers are driven by an
upstream feed; passes' tiers are driven by a local prediction.** They share the Sec. 9.2 gates
and the channel taxonomy, and essentially nothing else. This table exists to make that
legible, not to imply a shared mechanism.

| Tier | Aurora | Passes |
|---|---|---|
| **Dormant** | Sun up, or no darkness this season | No pass within 12 h |
| **Armed** | Dark; polling at 5-30 min by Bz/Kp | Pass known; freshen elements at T-3h; re-check cloud at T-30m |
| **Live** | Probability high, dark, clear -> FGS, ~2 min polls | T-10m -> FGS, local clock, live az/el, no network needed |

**Do not build a generic framework for this.** "An adaptive attention system for all event
types" is how a two-week feature becomes six months. Build two small specific things that
happen to share a table.

#### Live pass mode (Phase 3)

The strongest case for a live UI in the app, and it dissolves a problem flagged as
unsolvable elsewhere: **a foreground service started ten minutes before a pass does not
need the OS to wake it precisely.** Once running it owns its own clock, sidestepping the
exact-alarm permission by never needing it at the critical moment.

The payload is what a person outside actually wants: countdown, azimuth and elevation
updating live - "rises SW 22:14, peak 68° south 22:17, enters shadow 22:19".

**Frequency asymmetry is the risk.** Storm mode is cheap because storms are rare - a few
times a month, and the user is pleased each time. **ISS passes are daily, often several per
night.** A foreground service per pass is a persistent-notification generator and a battery
complaint. It is **opt-in per pass** - the user stars a pass, or enables it for the ISS
channel specifically - and never automatic across the `visual` group.

#### Where live mode does not generalise

- **Solar eclipse totality** - contact-time countdown, same code, seconds genuinely matter.
  A Phase 4 freebie once live pass mode exists, not a design driver.
- **Lunar eclipses** - hours long, nothing to point at. No.
- **Meteor showers** - a multi-hour window, not a moment. The right shape is a scheduled
  notification ("radiant up at 23:30, moon sets 01:00"), optionally with a tally screen. Not
  a foreground service.
- **Comets, conjunctions, NLC** - no.

#### Where adaptive polling does not generalise

Nowhere, essentially - see Sec. 6. There is one live upstream. The one worthwhile relative:
**freshen deterministic inputs before use rather than on a fixed cadence.** ISS reboosts
and debris-avoidance manoeuvres invalidate elements and shift pass times by tens of seconds
to minutes. Having told someone "ISS at 22:14, look southwest", re-fetch `sats.json` a few
hours beforehand rather than serving a prediction from a 2-hourly cron that landed badly.

### 9.5 Channels

One Doze budget, many event types. Separate **notification channels** per category so users
can silence comets and keep aurora:

`aurora`, `iss-passes`, `bright-satellites`, `comets`, `eclipses`,
`meteor-showers`, `conjunctions`

---

## 10. Hosted files

| File | Size | Refresh | Notes |
|---|---|---|---|
| `index.json` | 200 B | on change | **Manifest.** The only URL baked into the APK. Signed. |
| `index.json.sig` | 64 B | on change | Ed25519 detached signature over `index.json`. |
| `status.json` | ~1 KB | 3 min | AuroraWatch level (UK) + Kp + solar wind + `global_activity` + `oval_etag`. Latency-critical. |
| `oval.bin` | ~5-10 KB gz | 5 min | **Packed OVATION grid**: 1 byte/cell, row-major, 360x181. Global aurora probability. Fetched conditionally (Sec. 4). |
| `sats.json` | ~20 KB | 2 h | OMM elements: `stations` + `visual`, plus hardcoded standard magnitudes. |
| `comets.json` | ~2 KB | weekly | Curated MPC elements + H/slope. |
| `showers.json` | ~2 KB | annually | **Shipped in the APK**, remote override optional. |

~40 KB total. The backend is a cron job and an object store.

### The manifest

APKs live forever. F-Droid and Obtainium users do not reliably update. A `status.json` URL
baked into v1.0 and moved a year later leaves those installs polling a dead URL
indefinitely, with no way to stop them.

Only the manifest URL ships in the APK:

```json
{
  "schema": 1,
  "min_supported_version": 3,
  "issued_at": 1755853200,
  "message": null,
  "endpoints": {
    "status":  "https://sky.example.tld/v1/status.json",
    "oval":    "https://sky.example.tld/v1/oval.bin",
    "sats":    "https://sky.example.tld/v1/sats.json",
    "comets":  "https://sky.example.tld/v1/comets.json",
    "weather": "https://api.open-meteo.com/v1/forecast"
  }
}
```

`min_supported_version` is the kill switch; `message` is the broadcast channel. One request
per day.

`issued_at` is unix seconds at which those contents were first published, and the app
refuses any manifest older than the one it already holds. A signature does not expire, so
verification alone leaves a replay open: whoever could answer for the domain could serve a
correctly signed older copy to move the endpoints back or restore a withdrawn `message`. A
wall clock rather than a counter, so deleting the object or rebuilding the bucket does not
restart the sequence and lock every existing install out of every future manifest.

Note that `weather` is in here. Hardcoding it into the APK would reproduce exactly the
problem the manifest exists to solve.

### The manifest is signed

`message` is a broadcast channel into a notification on every install. If the domain lapses
or is hijacked, that is a phishing primitive with no user recourse.

The Worker signs `index.json` with Ed25519; the public key is compiled into the APK; the app
refuses an unsigned or badly-signed manifest and falls back to its last known-good copy.
`message` renders as plain text with no clickable links regardless. Cost: an afternoon.

**Buy the domain before shipping v1.0.** ~£10/year. The difference between "migration is a
DNS change" and "migration is impossible".

### Staleness is displayed

The app shows the age of the data it is displaying, prominently, whenever it exceeds one
refresh interval. "Kp 6" from three days ago is worse than showing nothing. If the manifest
has been unreachable for longer than a threshold, say so rather than silently serving stale
state as current.

Adaptive polling makes this more important, not less: a device dormant through a fortnight
of overcast summer nights is *correctly* holding old data, and the UI must distinguish
"quiet" from "not looked recently".

---

## 11. Privacy posture

All location maths is on-device. The aurora grid is shipped whole and indexed locally (Sec. 4),
never queried by coordinate. All attention and alert decisions are made on-device (Sec. 9.1).
Location permission is optional; manual entry is first-class (Sec. 7).

**All network traffic is outbound, initiated by the device, on a schedule the device
chooses.** No push, no distributor, no persistent connection, no per-device address stored
anywhere, and nothing that could contact the app if it wanted to.

The Data Safety story is **"no data collected"**. No accounts, no keys, no analytics, no
ads, no Play Services. Nothing this project runs ever learns where a user is.

The data is all public, so this is the app's actual differentiator, and belongs on the
listing.

Two things would break this claim and are therefore excluded by design: a coordinate-keyed
aurora endpoint (Sec. 4), and UnifiedPush-generic endpoint storage (Sec. 9.3).

### The one exception: cloud cover

**Open-Meteo is asked about a position, and that is unavoidable.** A cloud forecast is a
question about a place; there is no grid small enough to ship, and proxying it through the
Worker trades the disclosure for a rate limit that takes every user down at once (Sec. 2).
So one request, to a third party, carries where the user is.

Three things keep it as small as it can be, and the claim above should be stated with this
caveat rather than without it:

- **The position is rounded to two decimal places** - about 1.1 km, the resolution of the
  finest model Open-Meteo runs, so the forecast loses nothing. The request carries a
  neighbourhood, not an address.
- **At most one request an hour**, which is also the cap Open-Meteo's terms set. A cached
  reading answers the rest.
- **No identifier travels with it.** There is no account, no key and no device token, so
  the requests are not linkable into a track by anything except the IP address - which for
  most users is CGNAT shared with a whole carrier.

The honest phrasing for the listing is therefore "no data collected; your location is
never sent to us" - not "location never leaves your phone", which the cloud gate makes
untrue. If a user would rather not make that trade, the cloud gate is the thing to make
optional; the darkness gate is offline and stays non-optional (Sec. 9.2).

---

## 12. Distribution

Google Play is out of scope. **Play policy is what makes background execution painful, not
Android.** Off-Play the app can request battery exemption and declare `specialUse`
foreground services without a reviewer deciding an astronomy app does not qualify.

- **Own F-Droid repo on Codeberg Pages**, consumed via F-Droid or Obtainium. Ships on the
  project's schedule from day one.
- **F-Droid main repo.** Broadest reach, but inclusion is slow and the build must work
  unattended on their build server. A separate, later milestone rather than a release
  blocker.
- **Accrescent.** GrapheneOS-endorsed and precisely the target audience. Separate
  submission process; also post-v1.
- **Direct APK from Codeberg releases** via Obtainium.

Signing keys are generated offline and backed up before v1.0. Losing them means every
install has to be manually reinstalled.

Codeberg is right for code, issues, releases, F-Droid repo metadata and generator scripts.
Codeberg is **not a CDN** - a German non-profit with roughly two part-time staff and
expenses around EUR 1,050/month (2022), whose FAQ asks users not to be wasteful because the
infrastructure is a common good. It is not a polling endpoint, particularly for a project
soliciting donations on it.

---

## 13. Mandatory attribution

Next to the displayed data, not buried under the donation link:

- **AuroraWatch UK:** "AuroraWatch UK alerts data is provided by Lancaster University, UK
  using data from the SAMNET and/or AuroraWatchNet magnetometer networks." Plus links to
  AuroraWatch UK, Lancaster University, SAMNET and AuroraWatchNet. Prefer the attribution
  strings the v0.2 API serves per-site over hardcoding, so they track the source actually in
  use. Not "official". No implied endorsement. **Shown only when the UK layer is active.**
  - As built, the sentence is hardcoded and only AuroraWatch UK and Lancaster University are
    linked. The per-site attribution would have to be carried through `status.json`, which is
    a schema change and a Worker deploy, and the pages the API advertises for the two
    magnetometer networks (`/project-info/samnet/`, `/project-info/aurorawatchnet/`) both 404
    - so both networks are named in the required sentence but not linked, because a link to a
    404 credits nobody. Checked 2026-08-22.
  - The per-site documents also declare a licence the table above does not record:
    **CC BY-NC-SA 3.0**. Non-commercial is already the project's posture, but ShareAlike on
    the data is a term worth a conscious decision rather than an assumption, and it should be
    confirmed with Lancaster rather than inferred from an XML element.
  - Their conditions of use additionally require a robot to publish a way to report problems.
    The User-Agent carries the repository URL, which satisfies it.
- **Open-Meteo (CC BY 4.0):** a link next to displayed weather data, e.g.
  `<a href="https://open-meteo.com/">Weather data by Open-Meteo.com</a>`.
- **NOAA SWPC:** public domain, no obligation - credited anyway; OVATION is the backbone of
  the aurora feature.
- **CelesTrak:** credit; follow their usage policy.
- **Mike McCants:** not applicable unless `qs.mag` is bundled, which v1 does not do (Sec. 6).
  If that changes, permission first (Stellarium asked and received it) and credit. He is
  active and reachable; do not ask until the decision to use the data has actually been
  taken.
- **IAU CPS SatHub / SCORE:** not applicable unless SCORE data is used (Sec. 6). If that
  changes, the acknowledgement text in the SCORE Use Policy is mandatory, including the NSF
  grant number, alongside CC-BY attribution.
- **Look4Sat:** GPL-3.0 to GPL-3.0 vendoring needs no permission, only compliance  -
  preserve copyright notices and licence headers in the vendored files, and credit the
  project.

---

## 14. Build order

Each slice stands alone.

**Phase 1 - foundations**
1. Buy the domain. All three data-source permissions are granted (Sec. 1), so nothing is
   blocked on correspondence. Keep the AuroraWatch endpoint in Worker config rather than
   hardcoded, against a future URL change.
2. Cron Worker -> `status.json` + packed `oval.bin` -> R2, with `Cache-Control` set at PUT.
   Verify ETag/304 behaviour through the custom domain and that the byte packing
   round-trips.
3. `health.json` and the developer alerting path (Sec. 3). Built here rather than in Phase 3
   because SWPC and AuroraWatch can fail silently from day one, and because the CelesTrak
   circuit breaker in Phase 3 depends on it already existing.
4. Manifest + Ed25519 signing, both ends.
5. App: poll `index.json` -> `status.json`/`oval.bin`, grid lookup, manual location entry,
   notification channel, battery exemption flow, staleness display. Fixed-interval polling
   at this stage; adaptive arrives in Phase 2 once its inputs exist.
6. **Test on a real phone, overnight, in Doze.** This is the milestone that matters.
   Everything else is decoration on top of it.

**Phase 2 - make aurora useful**
7. Open-Meteo cloud cover, direct from device, 1 h disk cache.
8. astronomy-engine: dark-sky windows (null-safe) + moon phase.
9. The Sec. 9.2 decision rules - hysteresis, quiet hours, thresholds, rate limiting, gates. Not
   optional and not deferrable; this is the difference between an app people keep and one
   they mute.
10. Adaptive polling (Sec. 9.3). Depends on 7 and 8 for its inputs, which is why it is here
   rather than in Phase 1.
11. Storm mode.
12. AuroraWatch UK enhancement layer + bounding-box switch.
13. Home-screen widget. Highest value per line of code in the project, and it sidesteps the
    notification problem entirely for the "is it worth checking?" case.
14. Red night-vision mode.

**-> Ship v1.0 here.**

Phase 2 also produces the first real battery and Doze data. Nothing in Phase 3's live mode
should be committed to before reading it.

**Phase 3 - satellites (the largest single lump)**
15. Worker: CelesTrak **OMM CSV** -> `sats.json`, 2-hourly, respecting one-per-update.
    **Circuit breaker on any non-200 first, before the parser** (Sec. 3). It is the requirement
    Kelso singles out as the one that keeps an IP off the firewall, and it is easier to
    build before there is working code to be protective of. Read
    `gp-data-formats.php` before writing the parser.
16. Vendor Look4Sat predict package; add the OMM->elements constructor; run Vallado's SGP4
    verification vectors in CI (Sec. 7).
17. Pass prediction with sunlit/twilight visibility, and the qualitative descriptions in
    Sec. 6. ISS first, `visual` group after. Handle the +/-60° latitude ceiling explicitly  -
    high-latitude users get no ISS and are told why rather than shown an empty list.
    Note that "bright pass, high in the south, 22:14" needs no magnitude *data*, but it
    does need all of 14-16: the qualitative approach saves a data file and a permission,
    not the work.
18. Element freshening at T-3h and cloud re-check at T-30m for notified passes (Sec. 9.4).
19. Live pass mode - opt-in per pass, never automatic (Sec. 9.4).
20. Starlink trains - pure geometry, no magnitude data needed.

**Phase 4 - the sky calendar**
21. astronomy-engine events: eclipses, moon phases, conjunctions, elongations. Mostly
    wiring.
22. Meteor showers: hand-built table + radiant altitude + moon interference. Scheduled
    notification, no live mode.
23. Solar eclipse totality countdown - reuses live pass mode.
24. Noctilucent clouds (see below).

**Phase 5 - comets**
25. Kepler solver including Barker's equation, magnitude estimate, `comets.json` curation
    workflow.

### Candidate: noctilucent clouds

Worth considering for Phase 4. NLCs peak June-August at roughly 50-60°N - exactly the
window when Sec. 5 says the app has no astronomical darkness and therefore nothing else to
offer, at exactly the latitudes it covers best. The trigger is close to pure geometry: sun
between 6° and 16° below the horizon, look north. No incumbent alerts on this well.

The data problem is real - there is no clean live source since AIM ended in 2023 - so the
feature would be a geometry-and-season prompt ("conditions are right, go look north")
rather than a prediction, and must be labelled as such. It fills the dead season using code
the app already has.

### Excluded: rocket launches

Visible rocket launches are effectively a Florida, California and Texas phenomenon. From
the UK there is essentially nothing to see (SaxaVord and Sutherland notwithstanding;
neither is flying meaningfully). "Visible from here" is also a real calculation involving
pad location, azimuth, trajectory and time of day that Launch Library 2 does not provide,
so the feature is significant work to tell core users about things they cannot see. If
revisited, Launch Library 2 (`ll.thespacedevs.com`, free, CC-BY, attribute The Space Devs)
is the source.

---

## 15. Open questions

**No permission questions remain open.** Everything below is a technical or product
decision the project can settle on its own.

**Closed, 2026-07:**

- ~~AuroraWatch UK: donation link, redistribution, GPL-3.0~~ - all approved. A dedicated
  URL was offered but declined as unnecessary for now; they will provide one if problems
  arise. Keep the endpoint in Worker config so a later swap is trivial. Sec. 1.
- ~~Open-Meteo donation link~~ - approved, conditional on donations being fully voluntary
  and unlocking nothing. Recorded as a constraint in Sec. 1.
- ~~CelesTrak redistribution permission~~ - the usage policy contains no redistribution or
  mirroring clause, and Kelso reviewed the approach without raising one. The policy
  explicitly endorses proxy-and-cache for shared-egress users. Closed.
  - Noted so it is not re-litigated: adjacent CelesTrak and Space-Track documents refer to
    restrictions on redistributing Space-Track data, which raised the question of whether
    the permission was Kelso's to give at all. The governing policy does not raise it and
    neither did he. No Orbital Data Request is required. Revisit only if the policy changes.
- ~~Is the `visual` group already carrying 6-digit objects?~~ - no, confirmed by Kelso.
  Starlink and AST SpaceMobile are. Sec. 3.

**New:**

- **Does anything block 6-digit objects from entering the `visual` group?** If the group is
  curated by legacy tooling it may lag reality exactly when it matters, leaving the app
  blind to the brightest new objects despite parsing OMM correctly. Ask Kelso directly; he
  answers factual questions readily. Sec. 3.
- **Developer alerting path.** The CelesTrak circuit breaker is required to report to a
  human and there is currently no channel to one. `health.json` plus a maintainer-only ntfy
  topic. Roughly half a day, and it covers silent AuroraWatch and SWPC failures too. Sec. 3.
  - ~~Check whether Cloudflare's own alerting covers cron failure first~~ - checked
    2026-08-05: **it does not.** Cloudflare's notification catalogue carries no Workers or
    Cron Trigger entry at any tier, and webhook delivery needs a paid account regardless.
    Nothing to reuse, so both halves get built. The topic is generated and unused so far.
- ~~**OVATION grid packing** - confirm the real-world gzipped size during a large storm~~ -
  measured 2026-08-04 against live SWPC data. The packed grid is 65,160 bytes; at 26% of
  cells non-zero it gzips to **1.6 KB**, and a simulated severe storm at 61% non-zero reaches
  only **2.5 KB**. Both are well inside the 5-10 KB budgeted, so neither clipping to a
  probability floor nor splitting the hemispheres is needed. Revisit only if a real storm
  contradicts the simulation.
- **Default alert thresholds** - `T_on`/`T_off` need real values, ideally validated against
  a season of data rather than guessed. This is the only item here that cannot be settled by
  reading a policy page or writing code. Log aurora activity against a subjective "was it
  worth it?" judgement from Phase 1 onwards, so there is something to calibrate against by
  the time Phase 2 needs it. Sec. 9.2.
- **Adaptive polling thresholds** - which Bz and Kp values trigger escalation to 5 min?
  Same calibration problem, same answer. Sec. 9.3.
- **Storm mode exit condition** - probability falling below a floor, a fixed timeout, or
  the user dismissing it? Getting this wrong leaves a foreground service running until
  dawn. Sec. 9.3.
- **Satellite magnitudes: does anyone actually want a number?** Settled for v1 - no
  magnitude dataset, qualitative descriptions only (Sec. 6). Not blocking, and no email to
  McCants unless and until that reverses. The live question is whether users ask for
  numbers at all; "bright pass, high in the south, 22:14" may be more useful to someone
  standing outside than "mag 2.1".
- **If they do: is SCORE worth reducing?** Phase 3+ at the earliest. Pull the CSV, check
  coverage of the `visual` group specifically rather than of the catalogue in general, and
  see whether the reduction to standard magnitudes produces anything defensible for the
  classic bright objects or only for Starlink. If only Starlink, the answer is no. Sec. 6.
- **`comets.json` curation** - magnitude cutoff or manual? A recurring weekly chore.
  Automate the cutoff, keep a manual override.
- **Shetland alerting** - per-region AuroraWatch site, or is Lancaster-default adequate for
  v1? Sec. 4.
- **BYO API keys** (Astrospheric, Met Office DataHub): define the `WeatherSource` interface
  now to avoid painting into a corner; ship Open-Meteo only. Add others on request.
  Realistically low uptake.
