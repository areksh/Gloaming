# 9. Three specific pollers, not one event engine

Date: 2026-08-25

## Status

Accepted.

## Context

Phase 4 adds a fourth kind of thing the app watches. Before it there were two:
aurora, driven by an upstream feed and a poll cadence, and satellite passes,
driven by a local prediction over a twelve-hour horizon. Phase 4's sky calendar
is a third - eclipses, meteor showers, conjunctions, greatest elongations and
noctilucent clouds - and it is the third that makes the pressure to generalise
real, because two of anything is a coincidence and three is a pattern.

All three do the same four things in the same order: work out what is coming,
judge it against gates, decide whether to speak, and say when to come back. The
temptation is an `EventSource` interface with a `Decision` type, one scheduler,
one gate pipeline, and each of the three as a plug-in.

The sourcing document names this temptation and rejects it in advance, in
Sec. 9.4: "Do not build a generic framework for this. 'An adaptive attention
system for all event types' is how a two-week feature becomes six months. Build
two small specific things that happen to share a table." That was written when
there were two. This record exists because the decision has now been taken a
second time, with a third case in hand and the argument for unifying stronger
than it was.

## Decision

Three specific pollers, sharing nothing but the gates' vocabulary and the
channel taxonomy. `SkyPoller` with `AlertDecision`, `PassPoller` with
`PassPlanner`, `EventPoller` with `EventPlanner`. No common interface, no shared
scheduler, no extracted gate pipeline.

## Consequences

The shape looks duplicated and mostly is not. What the three would share is a
sequence of verbs; what differs is every decision inside them, and building
Phase 4 turned up four places where the general answer is wrong for at least one
of the three:

- **The darkness gate cannot be common.** Sec. 9.2 says every outdoor event is
  gated on darkness. A solar eclipse happens in daylight by definition, so a
  shared gate would suppress every one of them. The calendar answers "can this
  be seen from here" per kind instead, with the right rule for each.
- **Rate limiting cannot be common.** Passes carry a per-channel interval
  because a clear night over Edinburgh has upwards of forty worth looking at.
  The whole calendar produces about forty events a year. The same apparatus
  would have nothing to do.
- **The cadence cannot be common.** Aurora polls adaptively against a live feed;
  passes recompute a twelve-hour horizon; the calendar's events were knowable a
  century ago and need no cadence at all, only moments to keep.
- **"Already told" cannot be common.** A pass is matched approximately, because
  freshened elements move its peak by minutes and an exact comparison would
  re-announce it. A calendar event never moves, so it is matched exactly, and
  several can be pending at once - which needs a set where a pass needs one slot.

A framework absorbing all four differences would be configuration, and
configuration that is only ever set one way per caller is worse than three
readable files.

The cost is real and accepted: a fifth kind of watched thing means a fourth
poller, and a change to how gates work must be made in three places. That is the
trade - three files that each read straight through, against one that reads as a
matrix of special cases.

There is one place the three genuinely do share, and it is deliberately not a
framework: they run on the same wake. `PollReceiver` calls all three and
schedules the next alarm for the soonest of the three answers. The calendar has
no alarm of its own for the same reason passes do not - a third alarm would be a
third thing to get wrong after a reboot.
