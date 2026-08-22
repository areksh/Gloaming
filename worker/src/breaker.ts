/**
 * The CelesTrak circuit breaker.
 *
 * CelesTrak's usage policy is emphatic that machine-to-machine software must
 * stop querying on *any* non-200 response and report to a human, and Kelso
 * states that this one step is what keeps an IP off the firewall even as the
 * limits tighten. Not per-code handling, not backoff: stop.
 *
 * A cron Worker is stateless per invocation, so the trip has to be persisted or
 * the next run would cheerfully retry - the exact behaviour the policy warns
 * about. State lives in R2 beside the health objects.
 *
 * **Reset is manual.** Automatic recovery would defeat the purpose: a human is
 * supposed to find out why the response was not a 200 before another request
 * leaves. `sats.json` keeps serving its last good copy meanwhile, and elements
 * stay usable for days, so a tripped breaker degrades pass prediction rather
 * than breaking it.
 */

import { notifyMaintainer } from "./health";
import type { Deps } from "./pipeline";

export interface BreakerState {
  source: string;
  /** The non-200 that tripped it. */
  status: number;
  url: string;
  /** What CelesTrak documents that code as meaning. */
  note: string;
  tripped_at: string;
}

/** Raised instead of querying, and by the query that trips the breaker. */
export class BreakerTrippedError extends Error {
  constructor(readonly state: BreakerState) {
    super(
      `${state.source} breaker tripped at ${state.tripped_at}: HTTP ${state.status} from ` +
        `${state.url} (${state.note}). Reset with: wrangler r2 object delete ` +
        `gloaming-data/${breakerKey(state.source)} --remote`,
    );
    this.name = "BreakerTrippedError";
  }
}

export const breakerKey = (source: string) => `internal/breaker/${source}.json`;

/** What each documented code means, so an alert is actionable at 3am. */
function explain(status: number): string {
  if (status === 301) {
    return "a wrong domain or a legacy query still being redirected; the redirects will stop being honoured";
  }
  if (status === 403) {
    return "the data has not updated since the last successful download";
  }
  if (status === 404) {
    return "an old URL that no longer exists";
  }
  if (status >= 500) {
    return "the server is struggling and queries must cease immediately";
  }
  return "an undocumented response code";
}

/** The stored trip, or null while the breaker is closed. */
export async function readBreaker(env: Env, source: string): Promise<BreakerState | null> {
  const stored = await env.DATA.get(breakerKey(source));
  if (stored === null) {
    return null;
  }
  return JSON.parse(await stored.text()) as BreakerState;
}

/**
 * Trips the breaker and pushes one alert.
 *
 * A trip on an already-tripped breaker keeps the original: the first non-200 is
 * the one that explains what happened, and re-alerting would train the
 * maintainer to ignore the channel. Nothing should reach here in that state
 * anyway, since the check comes before the query.
 */
export async function trip(
  env: Env,
  deps: Deps,
  source: string,
  failure: { status: number; url: string },
): Promise<BreakerState> {
  const existing = await readBreaker(env, source);
  if (existing !== null) {
    return existing;
  }

  const state: BreakerState = {
    source,
    status: failure.status,
    url: failure.url,
    note: explain(failure.status),
    tripped_at: deps.now().toISOString(),
  };
  await env.DATA.put(breakerKey(source), JSON.stringify(state));

  await notifyMaintainer(env, deps, {
    title: `Gloaming: ${source} breaker tripped`,
    body:
      `HTTP ${state.status} from ${state.url} - ${state.note}.\n\n` +
      `No further queries until this is cleared by hand:\n` +
      `wrangler r2 object delete gloaming-data/${breakerKey(source)} --remote`,
  });

  return state;
}

export interface BreakerQuery {
  url: string;
  userAgent: string;
}

/**
 * Fetches through the breaker: refuses to query while it is tripped, and trips
 * it on any non-200.
 *
 * Redirects are deliberately not followed. A followed 301 would arrive as a 200
 * from wherever it pointed, and the one code that means "this URL is legacy and
 * will stop working" would never be seen.
 *
 * A connection that fails outright is left alone: no response arrived, so it is
 * not the signal the policy is about, and a transient network fault must not
 * demand a human before elements can refresh again. It still fails the source,
 * which is what `health.json` and its alert exist for.
 */
export async function fetchThroughBreaker(
  env: Env,
  deps: Deps,
  source: string,
  query: BreakerQuery,
): Promise<Response> {
  const tripped = await readBreaker(env, source);
  if (tripped !== null) {
    throw new BreakerTrippedError(tripped);
  }

  const response = await deps.fetch(query.url, {
    headers: { "User-Agent": query.userAgent },
    redirect: "manual",
  });

  if (!response.ok) {
    throw new BreakerTrippedError(
      await trip(env, deps, source, { status: response.status, url: query.url }),
    );
  }
  return response;
}
