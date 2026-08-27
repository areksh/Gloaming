# 11. A derived-settings layer rather than writing calibrated values into the store

Date: 2026-08-27

## Status

Accepted.

## Context

`docs/sourcing and constraints.md` Sec. 15 has said since the first version that
the default alert thresholds are the one open question that cannot be settled by
reading a policy page or writing code, and that they need a season of real
nights to calibrate against. Every default in `UserSettings` carries a comment
saying as much - `DEFAULT_THRESHOLD` at 30%, `DEFAULT_CLOUD_TOLERANCE` at 80%,
`DEFAULT_ALERT_INTERVAL_MINUTES` at six hours - and all three are guesses.

The verdict record now exists: every alert asks whether it was worth going
outside for, and the answer is kept with the sky that prompted it. So the app
can, for the first time, work out what these settings should be for the person
holding the phone rather than for an imagined average of everybody.

That raises a question the app has not had to answer before: where does a value
the app decided live? Everything in `UserSettings` up to now has one source -
the user typed it or pressed it - and every consumer reads it from one store.

Two obvious answers, both wrong:

**Write the calibrated values into the store.** Simplest to build, and the
consumers do not change at all. But it destroys the user's own choices the first
time it runs, and there is then nothing to restore when they switch the feature
off. A switch you cannot safely try is a switch nobody tries.

**Give every consumer both and let it decide.** No overwriting, but the rule for
composing them is then written out in the three pollers, the storm service and
the pass service, and it will not stay the same rule in all five. That is how a
poll before first unlock ends up using different thresholds from a poll after
it - which is exactly the class of bug the before-unlock mirror already made
once and had a test written for.

## Decision

A third answer: a `SettingsStore` that wraps another `SettingsStore`.
`Graph.effectiveSettings(context, source)` returns a store whose `read()`
composes the calibrated values over the stored ones when
`UserSettings.automaticSettings` is on, and returns the stored ones untouched
when it is off. Its `write()` passes straight through, so what is written is
always what the user chose and never what was derived from it.

The three pollers construct their settings through it. The composition rule
lives once, in `Calibration.Recommendations.applyTo`, which is a pure function
over a data class and is unit-tested without a device or a clock.

Nothing is calibrated that the record cannot speak to. The aurora threshold, the
cloud tolerance, the pass elevation bar and the four alert intervals are derived;
the comet settings, the calendar lead times and everything else are left exactly
as the user set them, because inventing a number is worse than keeping a
guessed one that at least the user chose.

## Consequences

Switching automatic settings off restores precisely what was there, because
nothing was overwritten. That is what makes the feature safe to offer at all,
and it is the property the whole layer exists to buy.

The settings screen now has two jobs where it had one: showing a value, and
saying who chose it and why. Every calibrated control carries the reasoning
underneath it - "Of 8 alerts at 30% or above, 6 were worth going out for" -
because a setting the app changed by itself, for reasons it will not explain, is
one the user can neither trust nor argue with.

The recommendations are shown even with the switch off. That was not in the
original shape and is most of the value at none of the cost: the app makes its
case and the user decides. It also means somebody can watch the recommendation
settle over a few weeks before handing anything over.

The cost is one indirection between the store and every decision, and a rule
that has to stay explainable in a sentence. A more sophisticated fit - a
logistic regression over the recorded conditions, say - would very likely be
more accurate and could not be stated back to the user, which under Sec. 9.5's
argument about attention makes it the wrong trade rather than a better one.

Deliberately **not** covered: `PollingPolicy.SOUTHWARD_BZ`, `ELEVATED_KP` and
`StormMode`'s enter and leave thresholds. The verdict record now carries the Bz
and Kp at the moment of every alert, so these could be derived - but they are
internal constants rather than settings, and threading a derived value into them
means every poller resolving one on every wake for a benefit that is a battery
optimisation rather than anything the user would see. The Sec. 15 questions
about them can now be answered by reading the record, which is what was actually
blocked. Revisit if the recorded data says the current constants are wrong.
