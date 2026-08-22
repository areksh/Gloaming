# 8. Fetch CelesTrak from outside Cloudflare

Date: 2026-08-22

## Status

Accepted

## Context

CelesTrak's usage policy forbids per-device fetching outright: a thousand
devices pulling elements would produce one success, 999 failures and a firewall
entry. The policy explicitly endorses the alternative - one server fetch fanned
out to many clients - and Sec. 3 of the sourcing document builds on that, which
is why the cron Worker was the natural home for it.

**Cloudflare's network cannot reach CelesTrak.** Three attempts on 2026-08-22,
each returning `HTTP 522` after roughly 24 seconds: two from `wrangler dev
--remote` previews at 12:06:33 and 12:09:15, and one from the deployed Worker
on its own cron at 18:01:09. In the same minute as the third, the same URL with
the same User-Agent answered an ordinary domestic connection with `HTTP 200` in
1.96 seconds.

522 is Cloudflare-generated and means its egress could not open a connection to
the origin. CelesTrak is not itself behind Cloudflare - responses come from
`Microsoft-IIS/10.0` with no Cloudflare headers - so this is Cloudflare's own
network failing to reach them. Production and preview fail identically, so it is
not an artefact of preview egress; the origin was demonstrably healthy at that
instant, so it is not an outage. A 24-second timeout rather than a refusal means
packets dropped rather than rejected.

Whether CelesTrak firewalls Cloudflare Workers' shared egress ranges - their
policy is explicit that shared-egress users get blocked by each other's
behaviour, and Workers share egress across every customer on the platform - or
Cloudflare simply cannot route to their host, cannot be told apart from this
side. The practical consequence is the same, and **a Worker's egress address
cannot be chosen on any plan**, so there is nothing to configure. Testing
stopped at three attempts: repeatedly probing a source that may already have
blocked the range is precisely what the policy warns against.

## Decision

Move the CelesTrak fetch to a host with an address of its own, and leave
everything else exactly where it is.

- A small Node programme, `fetcher/`, bundled by esbuild into one self-contained
  file. The host needs Node and nothing else - no package manager, no
  `node_modules`, no source tree to keep patched.
- It fetches both groups through the same circuit breaker, parses the same OMM
  CSV, and publishes the same `sats.json` with the same `Cache-Control`.
- **R2 remains the fan-out.** Nothing about the read path changes: the CDN
  serves `sats.json`, the app reaches it through the signed manifest, and no
  device has ever known or cared which machine wrote it.
- **It holds no local state.** The breaker flag, the health record and the
  published document all live in R2, at the same keys as before, so the manual
  breaker reset is the same documented command and the host is disposable.
- It writes `internal/health/celestrak.json` itself. The Worker already lists
  `celestrak` among its sources and rebuilds `health.json` from the per-source
  objects every few minutes, so a source that runs somewhere else entirely keeps
  appearing there with no change to the Worker at all.

The Worker keeps the manifest, SWPC and AuroraWatch. Its CelesTrak cron, its
`refreshSatellites` and its copy of the breaker are deleted rather than left
dormant - dead code that once worked is worse than none, because the next person
cannot tell which path is live.

## Consequences

- **The system has two writers.** ADR-0002 chose a cron Worker as the write side
  precisely to avoid running a machine; this puts one back for a single source.
  That is the cost, and it is worth paying only because there is no alternative
  that keeps the app's pass prediction working at all.
- **The host is a dependency with a person attached.** A Worker needs no
  patching; a seedbox does. If it goes away, `sats.json` goes stale and the pass
  planner treats old elements as no passes known, which is a graceful failure
  but a real one. `fetcher/README.md` documents the whole lifecycle so the
  answer to "what runs this and how" is written down rather than remembered.
- **One copy of the parser.** It moved to `fetcher/src/celestrak.ts` with its
  tests, because that is where it runs now. The health record's shape is shared
  as types from `worker/src/contracts.ts`, which has no imports and no runtime
  dependencies, so neither side drags the other's globals into its build.
- **Reversible cheaply.** If Kelso unblocks the range, or the routing fault is
  fixed, moving back is a cron entry and a pipeline function - both are in the
  history at this commit's parent.

## What was not done

**Asking Kelso first.** He is reachable, the policy invites contact, and he is
the only person who can say whether the block is deliberate and undo it. That
should still happen; this decision does not depend on the answer, and the app
should not wait on correspondence to work.

**Space-Track instead of CelesTrak.** It is the authoritative upstream and
identifies callers by account rather than by IP, which would sidestep the
question entirely. Its user agreement restricts redistribution, and republishing
derived elements at a public URL is the whole model here - Sec. 3 notes
specifically that CelesTrak's policy contains no such clause and that this is
what makes the architecture legitimate. Changing that needs the terms read
properly, not a hurried swap.
