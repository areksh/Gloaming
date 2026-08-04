# 4. GPL-3.0 from day one

Date: 2026-07-22

## Status

Accepted

## Context

Full scope (Phase 3 onward) needs SGP4 satellite propagation. The chosen route is
to vendor Look4Sat's Kotlin `predict` package, which is GPL-3.0, making the app
GPL-3.0 (Sec. 1, Sec. 7 of `docs/sourcing and constraints.md`).

The v1.0 release gate is the end of Phase 2 - aurora only - which does not yet
vendor any GPL code, so a permissive licence (MIT) would be available at v1.0.
There is also a genuine alternative: predict4java was relicensed to MIT in 2026,
so full scope could in principle stay permissive by depending on predict4java
instead of vendoring Look4Sat. That was rejected on technical grounds
(predict4java pulls in `commons-lang3`, `slf4j-api` and `joda-time` for what is
pure maths, and is TLE-oriented rather than OMM-oriented), but it means GPL-3.0 is
a preference rather than a hard requirement forced by SGP4 itself.

So the licence choice could be deferred: ship v1.0 as MIT and relicense the
project's own code to GPL-3.0 when Look4Sat is vendored in Phase 3.

## Decision

**Adopt GPL-3.0 from day one**, rather than shipping MIT and relicensing at
Phase 3.

## Consequences

- No relicensing step later; licence headers are consistent from the first
  commit, and there is no window in which some releases are MIT and later ones
  GPL.
- GPL-3.0 permits anyone to fork the app, add advertising, and redistribute it;
  such a fork would reach the project's own endpoints using the project's own
  AuroraWatch permission. The licence cannot forbid this without becoming
  non-free. Mitigations are social, not legal: an endpoint-use note in the
  README, and the disclosure already made to AuroraWatch when permission was
  sought. The prior disclosure is what makes a troublesome fork their problem to
  explain rather than the project's. This risk is accepted.
- Vendoring Look4Sat (GPL-3.0 into GPL-3.0) needs no permission, only compliance:
  preserve copyright notices and licence headers in the vendored files and credit
  the project (Sec. 13).
- The sourcing document's statement that "an MIT licence was available only in the
  aurora-only scope" should be corrected: an MIT path existed for full scope too
  via predict4java. It was not taken; this ADR records why.
