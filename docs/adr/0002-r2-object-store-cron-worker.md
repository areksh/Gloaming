# 2. R2 object store with a cron Worker out of the read path

Date: 2026-07-22

## Status

Accepted

## Context

The app needs a handful of small derived data objects (aurora status, packed
OVATION grid, orbital and comet elements) refreshed on the order of minutes and
served to potentially thousands of installs, on free hosting, without a scaling
cliff. The reasoning is set out in Sec. 2 of `docs/sourcing and constraints.md`;
this ADR records the decision.

Constraints that rule out the obvious alternatives:

- **Cloudflare Pages** is a deployment target, not a mutable store. It has no
  "write a file" primitive, and the free plan allows 500 deploys/month against a
  3-minute refresh needing roughly 14,400. A cron Worker cannot write to Pages in
  any supported way.
- **A Worker in the read path** is capped at 100,000 requests/day on the free
  plan, which puts a hard ceiling in the low thousands of installs - an
  unacceptable cliff for an app whose premise is staying free.

## Decision

- The **cron Worker writes; it is never in the read path.** It fetches upstreams
  on Cron Triggers and PUTs derived objects to R2.
- **R2 with a public custom domain serves reads directly** through the CDN, with
  `Cache-Control` set on object metadata at PUT and ETag revalidation. Only
  edge-cache misses reach R2, billed as cheap Class B operations with zero
  egress.
- Objects live under a versioned `/v1/` path prefix.

## Consequences

- The read path scales with the CDN, not with Worker request budget. At correct
  `Cache-Control`, R2 sees roughly one revalidation per PoP per interval
  regardless of user count.
- Cost scales linearly and cheaply past the free tier (Class B at $0.36/million);
  there is no forced plan upgrade. The upgrade order is: correct `Cache-Control`
  (free) -> accept R2 Class B overage (cents) -> Workers Paid only if the cron
  outgrows the free plan.
- The Worker being stateless per invocation means any cross-invocation state (the
  CelesTrak circuit breaker, the `status.json` composition partials) must be
  persisted in R2 or KV. See `docs/architecture.md` Sec. 3 for the split (R2 for
  partials, KV for the breaker flag).
- `Cache-Control` correctness is load-bearing, not cosmetic: without `max-age`
  the edge does not absorb the fan-out.
