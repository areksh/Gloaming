# 12. Constellations as the one exception to the star exclusion

Date: 2026-08-30

## Status

Accepted. Amended by [0017](0017-constellation-figures-draw-stars-without-listing-them.md).

## Context

`docs/sourcing and constraints.md` has excluded stars from the app since the
first version, in two places and for two different reasons.

Sec. 8, "Deep-sky objects: excluded", gives a mechanical reason:
astronomy-engine's user-defined stars are exactly eight slots, `Body.Star1`
through `Body.Star8`, backed by a global mutable `starTable` that is neither
configurable nor thread-safe. Walking a Messier catalogue through them means
mutating global state every iteration. It closes with "Stellarium does this
better", which is true and is the stronger half of the argument.

Sec. 13, "What counts as an event at all", gives the governing rule for scope:
if it cannot be seen with the naked eye or minimal equipment, it is out. Its
list of exclusions ends with deep-sky objects, deferring to Sec. 8 rather than
making an argument of its own.

Neither reason reaches a constellation, and it is worth being precise about why,
because the two exclusions have until now been read as one.

- **The eight slots are irrelevant.** A constellation is not a star. What the
  app needs to place one is a right ascension and a declination, which
  `horizon` takes directly - the same call `Planets` already makes after
  resolving a body's position. No slot is claimed, no global table is touched,
  and nothing has to be thread-safe.
- **The catalogue argument is irrelevant.** There are 88 constellations and
  fewer than 30 anybody would name. That is a table, not a catalogue, and it
  does not grow.
- **The naked-eye rule admits them outright.** A constellation is the most
  naked-eye thing in the sky. It is what somebody sees before they own
  anything.

What does apply, and is the real constraint, is a third rule that has nothing to
do with stars: the split between the calendar and the Tonight tab is by *shape*,
not by feature. Dated and sparse goes in the calendar; nightly and dense goes on
Tonight. Sec. 13 states it and `Tonight.kt` repeats it, and `EventKind.Noctilucent`
is the precedent - a condition that holds for three months, kept out of the
calendar for exactly that reason.

"Orion is up from October to March" is a condition of that shape. Filed as
calendar entries for when each pattern starts and stops being visible, 88
constellations would be 176 rows a year against the roughly 40 the whole
calendar currently holds, on soft thresholds, repeating on the same dates every
year. That is an almanac, and the calendar is not one.

## Decision

Constellations are in scope, as the single exception to the star exclusion, and
are split across the two surfaces by the shape rule rather than given to either
whole.

- **A curated table of 22 patterns** in `astronomy/Constellations.kt`: name,
  J2000 right ascension and declination, brightest star and its magnitude, an
  approximate extent, and one hand-written sentence saying how to pick it out.
- **The season goes on Tonight.** A card leading with the handful worth going
  out for, with where to look and how to recognise it. Ranked by height alone
  the patterns that never set fill it on every night of the year, so those are
  named in a closing line instead and the places go to the seasonal ones;
  whichever is at its best tonight leads however low it is, the rule the space
  station already gets among the passes. `showEveryConstellation` turns the
  full list back on.
- **The one dated fact goes in the calendar.** `EventKind.Constellation`, one
  entry per pattern per year, for the night it stands highest at midnight. The
  window is that one night, not the season.
- **It never notifies.** `EventChannel.of` returns null, as it does for
  `Season`, `MoonPhase` and `MoonQuarter`.
- **Individual stars and deep-sky objects remain excluded**, on Sec. 8's
  original reasoning, which this record leaves standing.

## Consequences

**The line is drawn at the pattern, not at the object.** This is the part that
will be tested by the next question, which will be about the Pleiades, or
Andromeda, or Albireo. The rule is that a constellation is admitted because it
is a region of sky with a name and a shape, needing no equipment and no
catalogue entry to use; the Pleiades and the Andromeda galaxy are objects, they
are found by pointing at a position, and they are the first two rows of exactly
the catalogue Sec. 8 refuses. Naming one inside a star-hop sentence is fine,
because that is a landmark. Giving it a row of its own is the catalogue, and the
answer is no.

**The shape rule survives intact and is now load-bearing twice.** Noctilucent
clouds established that a condition stays off the calendar; this establishes
that a condition with one dated moment inside it contributes that moment and
nothing else. Both are cheaper than the alternative, which was a calendar that
gradually became a list of everything true about the sky.

**The one-night window is what keeps the rest of the machinery still.** Because
the entry is dated to a single night rather than a season, it stays inside
`SkyCalendar.LONGEST_WINDOW_MILLIS`, so `EventPlanner.REMEMBER_FOR_MILLIS` needs
no change and nothing re-announces; `SkyEvent.isUnderWay` marks it correctly;
`CalendarScreen.dash()` prints a sensible range; and `EventPlanner.starKey`
keys on a date that does not drift between recomputes, so no comet-style prune
bypass is needed. A season-long window would have moved all four. This is the
main reason the feature is small.

**The dating is solved rather than searched, and that is why it is affordable.**
A pattern is best placed when the sun sits twelve hours of right ascension from
it, so one daily sweep of the sun's position dates the whole table at once. A
year of the full calendar takes 162ms against its 4000ms budget. Searching per
constellation would have multiplied a year's ephemeris work by the size of the
table.

**The table is judgement, and is the part that will need defending.** There is
no rule behind the 22 rows. A magnitude threshold was considered and rejected:
at magnitude 2.5 it admits Puppis, Carina and Ara, which have bright stars and
no shape anybody could point to, and it is indifferent to Ursa Minor, which
every beginner is told to find first. The criterion is whether somebody could
pick it out and would use its name. That cannot be checked by a test, so the
suite checks the things that can be - that each row falls inside the
constellation it claims, that the coordinates are in range, that the strings are
ASCII - and the judgement is left visible in one list.

**Precession is ignored, deliberately.** The table is J2000 and is never
precessed. Precession moves these about 50 arcseconds a year, so by 2050 it is
out by roughly a third of a degree, against bars measured in tens of degrees and
patterns tens of degrees across. Correcting it would be arithmetic that changes
no answer the app gives.

**One frame trap is now live in two places.** The vendored `constellation`
lookup documents J2000 as its input and converts internally to the B1875
boundaries the IAU drew, while `Planets` and `SkyCalendar` work in the of-date
frame. Handing it of-date coordinates is right almost everywhere and quietly
wrong near a boundary, which is the same class of failure Sec. 6 describes for
satellite magnitudes: two conventions that look like one number. `Planets.seen`
therefore takes a second position in J2000 purely for the lookup, and
`CometSighting` already carried J2000 coordinates, so the comet path was free.
