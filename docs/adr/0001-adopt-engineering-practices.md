# 1. Adopt engineering practices and the Gloaming name

Date: 2026-07-22

## Status

Accepted

## Context

Gloaming is a new, greenfield project: a free, ad-free, Play-Services-free
Android app with a small Cloudflare backend, intended to be maintained for years
and distributed to end users off-Play. Its external constraints are recorded in
`docs/sourcing and constraints.md`; its architecture in `docs/architecture.md`.

Two things need settling before code lands. First, the going-forward engineering
bar. Second, the app's name: the sourcing document was written under the working
title "Sky Alerts", while the repository, project and package are "Gloaming".

## Decision

- **Adopt the full engineering standard as the going-forward bar** for new work:
  test-driven development with meaningful tests and branch coverage as a
  diagnostic; small, atomic, conventional commits; a developer manual under
  `docs/`; ADRs for architecturally significant decisions; and a `CHANGELOG.md`
  kept under an `Unreleased` section. Proportionality still applies - apparatus
  is added as the project's drivers warrant, not all at once - but new code is
  written test-first from the start.
- **The canonical name is Gloaming.** "Sky Alerts" is a stale working title. The
  package id and display name follow from Gloaming (for example
  `cc.<domain>.gloaming`, confirmed in `docs/prerequisites.md`). The sourcing
  document should be updated or annotated so the two names do not drift.

## Consequences

- New code is held to the TDD bar; the test runner and coverage tooling are set
  up as part of recreating the app scaffold.
- This ADR is the first entry in the log and records the adoption itself.
- Existing untouched material is not retrofitted; the standard governs new work.
- Any remaining "Sky Alerts" references are corrected as the files are next
  touched, not in a separate churn commit.
