# 10. Fetch the comet sources outside Cloudflare, and without a breaker

Date: 2026-08-26

## Status

Accepted.

## Context

Phase 5 adds two upstreams: the Minor Planet Center, for comet orbital elements,
and COBS, for the magnitudes observers have actually measured. Two decisions had
to be taken about them, and the existing architecture pulled in opposite
directions on the second.

**Where they are fetched from.** `docs/architecture.md` planned a Worker cron,
`0 3 * * 1`, producing `comets.json`. That entry predates ADR-0008, which moved
CelesTrak out of Cloudflare because Cloudflare's egress could not reach it at
all - a discovery that cost a phase and was made only after the code was
written. The MPC is a similarly old-school academic host and COBS is a small
observatory in Slovenia. Betting a second phase on Cloudflare reaching either is
the same bet, taken again, with the same information.

Checking first bore this out immediately, though not in the way expected.
`www.minorplanetcenter.net` times out entirely from some networks and answers
403 to others, and its `/data` path redirects somewhere that refuses common
clients. `data.minorplanetcenter.net` serves the same `CometEls.txt` over HTTP/2
with an ETag, a `Last-Modified` and a permissive CORS header. So the host
mattered as much as the platform, and neither would have been discovered by
writing the fetcher first.

**Whether they get a circuit breaker.** The fetcher already has one, and it is
load-bearing: CelesTrak's usage policy makes stopping on any non-200 its single
most important requirement, and Kelso states it is what keeps an IP off the
firewall. It trips on any non-200, refuses to query while tripped, and **resets
only by hand**, because the point is that a human finds out why the response was
not a 200 before another request leaves.

The pull towards reusing it is obvious - it exists, it is tested, it is the
house style for talking to an upstream. The pull against is that a manual-reset
breaker is a very strong instrument, and neither the MPC nor COBS asks for it.

## Decision

Comets are fetched from the same machine as CelesTrak, as a second source with
its own health record, its own failure path and its own alerting edge. A failure
at the MPC cannot cost the station its elements, and the reverse holds too.

They get **no circuit breaker**. What they get instead is what any polite client
owes: a named User-Agent, redirects not followed, one request a day, and a
failure recorded and alerted on rather than retried.

The cadence is enforced rather than assumed, and that took a second look. The
fetcher runs on a two-hourly timer because CelesTrak's policy demands that
cadence of the satellite elements, and comets were simply added to the same run
- which would have asked both hosts for about a megabyte twelve times a day for
an answer that changes once. The comet half now skips unless the published
`comets.json` is at least eleven hours old, reading that age from the object
itself so the machine stays disposable.

**Eleven hours makes it twice a day, and that number comes from the consumer
rather than from the upstreams.** COBS states no rate limit anywhere, so the
question was never what they permit. It is what could reach anybody: the app's
calendar poller sleeps up to twelve hours between wakes and the object is cached
at the edge for six, so data fetched more often than twice a day cannot arrive
at a phone any sooner. Measured before choosing it - across every comet COBS
carries, reports arrive at about 1.65 an hour, and for the two comets currently
bright enough to matter, removing an entire report moves the fortnight median by
0.000 magnitudes. Polling hourly would transfer a megabyte to change nothing.

## Consequences

A transient 500 from either host costs one day's refresh and nothing else. Under
a breaker it would halt comet updates until a human noticed and deleted an
object - which for a source with no policy demanding it is a cost with no
corresponding benefit, and a fine way to discover in October that comets stopped
updating in August.

The two upstreams are also treated differently from each other, which is
deliberate. Without elements there are no comets at all, so a failure there fails
the run. Without observations there are still comets - the app falls back to the
predicted magnitude and says so - so a failure there costs the run its
measurements and not its comets. That is the same split `readTrains` already
makes for Starlink, and for the same reason: an addition should not be able to
take down the thing it is added to.

Redirects are still not followed, which is inherited reasoning rather than
policy compliance. A followed 301 arrives as a 200 from wherever it pointed, and
the whole value of a 301 is that it says the URL is legacy and will stop
working. Given that `www.minorplanetcenter.net/data` already redirects, this is
not hypothetical here.

`comets` joins `celestrak` in the Worker's source list without a cron, so its
health record appears in `health.json` alongside the rest. The Worker composes
it without knowing where it came from.

The risk accepted is that a genuinely hostile response - an outright block
rather than a transient failure - is retried once a day rather than stopping
dead. At one request a day with a named agent, that is a difference the far end
is unlikely to notice; if either host ever states a policy like CelesTrak's, the
breaker is already built and this decision should be revisited.
