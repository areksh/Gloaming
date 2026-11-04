# 17. Constellation figures draw stars without listing them

Date: 2026-11-04

## Status

Accepted. Amends [0012](0012-constellations-as-the-exception-to-the-star-exclusion.md).

## Context

ADR 0012 let constellations into the app as the one exception to the star
exclusion, and kept individual stars out. A pattern was a name, a centre, a
size and one sentence saying how to pick it out. That is enough to say where
to look, and not enough to show what to look for: "a W of five stars" is
easier to find when you have seen the W.

Drawing a stick figure means storing stars. Each figure is two to fifteen
of them, with positions and magnitudes - 154 in all for the 22 patterns.
Read literally, that is the catalogue 0012 refused. The question is
whether it is.

0012 already drew the line in a way that answers it. It allowed a star to
be *named inside a star-hop sentence*, as a landmark, and refused a star
*given a row of its own*. What made the Pleiades a catalogue entry was not
that the app knew where they were; it was that they would become a thing
you could look up, pick and be pointed at.

## Decision

- **Every watched pattern gets a stick figure**, in
  `astronomy/ConstellationFigures.kt`: its stars, by Bayer letter, with
  J2000 positions and visual magnitudes, and the lines through them.
- **The line moves from "no stars" to "no star is ever a subject".** A star
  in a figure is a point of that pattern. No sheet, chip, Find target,
  calendar entry, search result or notification is ever about one star.
  The pattern's brightest star is labelled in its sheet because the
  pattern's own row already names it.
- **Copied from the Yale Bright Star Catalogue**, 5th revised edition, once,
  by a one-off script, into plain source; nothing reads the catalogue at build
  time.
  Positions are facts. The lines are this app's own, so no third-party
  figure set (Stellarium's, or anybody else's) has to be licensed or
  credited.
- **Drawn the way the pattern stands, never north-up.** The sheet turns
  the figure as it is at its highest tonight, from where the user is; Find
  lays it over the live view. The star-hop sentences already avoid "above"
  and "left of" because the sky turns over between hemispheres, and a
  north-up chart would bring that problem back in pictures.
- **The Pleiades and the Andromeda galaxy stay out**, on 0012's reasoning,
  which this record leaves standing. Neither is part of any figure.

## Consequences

**The table is checked against the library, not against itself.** Every star
must fall inside the constellation it is drawn in, by the vendored IAU
boundary lookup - except Elnath in Auriga and Alpheratz in Pegasus, the two
stars traditionally shared with a neighbour, which are marked as borrowed.
Every star must also lie within twice its pattern's extent of the centre,
and no line may run longer than twenty degrees (the longest, Pegasus's
square, is 16.5).
Between them these catch the mistakes a later edit by hand makes: hours read as
degrees, a lost minus sign, the wrong star looked up.

**Doubles are entered as the eye sees them.** Acrux, Rigil Kentaurus and
epsilon Lyrae are each two catalogue entries and one naked-eye star. Each
is one star here, at the brighter component, with the combined magnitude.

**`Constellation` does not change.** It is serialized into snapshots.
Figures are a separate table keyed by the pattern's name, so a figure can be
corrected without a snapshot migration, and a snapshot written before this
change still loads.

**Which stars make the shape is judgement**, like the 22 rows themselves,
and no test can check it. It is left visible in one table in one file.
