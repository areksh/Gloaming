# 15. The CelesTrak breaker clears itself after two days

Date: 2026-10-31

## Status

Accepted. Supersedes the manual-only reset in Sec. 3 of the sourcing document,
which ADR-0008 and ADR-0010 both describe as part of their context.

## Context

The CelesTrak circuit breaker trips on any non-200, refuses to query while
tripped, and until now reset **only by hand**: Sec. 3 says a human investigates
and clears it, and that automatic recovery defeats the purpose. The purpose is
real. CelesTrak's usage policy makes stopping on any non-200 its single most
important requirement, and Kelso states it is what keeps an IP off the firewall.

What a manual-only reset costs is that element freshness depends, with no upper
bound, on a person reading one push notification and acting on it. On
2026-08-25 the breaker tripped on a 503 at 14:28, the alert fired as designed,
and it was missed: elements were seventeen hours old before anybody noticed
(architecture item 30). That was harmless because elements stay usable for
days. They do not stay usable indefinitely - SGP4 is a near-epoch model - and a
trip on an evening nobody is watching, followed by a week away, would leave pass
prediction degrading with nothing in the system able to end it.

## Decision

A trip holds for **two days**. If nobody has cleared it by hand by then, the
next run lets one request through:

- A **200 clears the flag**, by deleting the same object the manual reset
  deletes, and the run carries on as normal.
- **A refusal trips it afresh** - a new `tripped_at`, a new two days - with what
  came back this time.
- **No answer at all holds it for another two days too.** No response arrived to
  replace the trip, so the trip stands and `attempted_at` restarts the clock.
  Retrying on every run instead would mean a request and two pushes every two
  hours for as long as CelesTrak is unreachable - which is also what being
  firewalled looks like.

Every step is pushed to the maintainer, because a request nobody approved should
never leave unannounced:

| Event | Push |
|---|---|
| A trip | `breaker tripped`, with when the next attempt will be |
| An attempt going out, before the request | `breaker trying again` |
| The attempt got a 200 | `breaker cleared itself` |
| The attempt was refused | `breaker tripped again`, with the next attempt |
| The attempt got no answer | `breaker got no answer`, with the reason and the next attempt |
| A reset by hand | `breaker cleared by hand`, from the next run |

A reset by hand is a `wrangler` delete the fetcher never sees happen. So every
trip is also written to `internal/breaker-known/celestrak.json`, under a prefix
of its own so deleting the flag never takes it too, and a run that finds the
flag gone and that record still present announces the reset and deletes the
record. It arrives with the next timer run, not at the moment of the delete.

A manual reset still works exactly as before and takes effect at once. The
period is fixed rather than growing: a source down for a fortnight costs one
request every two days, against the thirty-six a day a healthy two-hourly run
makes.

The breaker's other rules are unchanged. A connection that fails outright still
leaves a closed breaker closed, redirects are still not followed, and the groups
are still fetched sequentially, so a failed attempt on the first group stops the
other two in the same run.

## Consequences

- Elements can no longer stay frozen indefinitely because an alert went unread.
  The longest a single trip can hold is two days plus however long the timer
  takes to come round.
- The fetcher now sends requests to CelesTrak that no human approved, at most
  one per two days while a fault persists. That is a deliberate relaxation of
  "stop and wait for a human", accepted because one request in two days is not
  the hammering the policy exists to prevent.
- A site coming back from an outage can answer 5xx for a while before it is
  really back, and a retry that lands in that window costs another two days. So
  does a network blip at the moment of a retry. Clearing the flag by hand once
  the URL answers 200 is still the faster path.
- A successful attempt sends three pushes - trying, cleared, and the source's
  own `recovered` - and a reset by hand two. Each says something different, and
  they come at most once per two days.
- The fetcher's R2 token now deletes objects as well as reading and writing
  them. R2's object read and write permission already covers that.
- A trip written by a fetcher from before this decision has no
  `internal/breaker-known/` record, so a reset by hand of that one trip goes
  unannounced. Every trip since has one.
- ADR-0010's reasoning for giving the comet sources no breaker still holds: a
  breaker on them would halt comet updates for two days on one transient 500,
  and neither the MPC nor COBS asks for anything of the kind.
