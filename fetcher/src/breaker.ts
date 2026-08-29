import { notifyMaintainer, type Alerting } from "./health";
import { fetchOrExplain } from "./network";
import type { ObjectStore } from "./store";

/**
 * The CelesTrak circuit breaker, moved to wherever the fetching now happens.
 *
 * CelesTrak's usage policy makes stopping on any non-200 its single most
 * important requirement, and Kelso states it is what keeps an IP off the
 * firewall. The state stays in R2 at the same key the Worker used, so the
 * documented manual reset is unchanged and the flag is visible from both
 * sides.
 *
 * **Reset is manual.** Automatic recovery defeats the purpose: a human is
 * meant to find out why the response was not a 200 before another request
 * leaves. `sats.json` keeps serving its last good copy meanwhile.
 */
export interface BreakerState {
  source: string;
  status: number;
  url: string;
  note: string;
  tripped_at: string;
}

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

export async function readBreaker(
  store: ObjectStore,
  source: string,
): Promise<BreakerState | null> {
  const stored = await store.get(breakerKey(source));
  return stored === null ? null : (JSON.parse(stored) as BreakerState);
}

/**
 * Trips the breaker and pushes one alert.
 *
 * A trip on an already-tripped breaker keeps the original: the first non-200 is
 * the one that explains what happened, and re-alerting would train the
 * maintainer to ignore the channel.
 */
export async function trip(
  store: ObjectStore,
  alerting: Alerting,
  source: string,
  failure: { status: number; url: string },
  now: Date,
): Promise<BreakerState> {
  const existing = await readBreaker(store, source);
  if (existing !== null) {
    return existing;
  }

  const state: BreakerState = {
    source,
    status: failure.status,
    url: failure.url,
    note: explain(failure.status),
    tripped_at: now.toISOString(),
  };
  await store.put(breakerKey(source), JSON.stringify(state), {
    contentType: "application/json",
  });

  await notifyMaintainer(alerting, {
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
 * from wherever it pointed, and 301 is the one code that means "this URL is
 * legacy and will stop working".
 *
 * A connection that fails outright is left alone: no response arrived, so it is
 * not the signal the policy is about, and a transient network fault must not
 * demand a human before elements can refresh again. It still fails the run,
 * which is what the health record and its alert exist for - carrying the URL
 * and the reason, because `fetch failed` on its own does not say even which
 * host was being asked.
 */
export async function fetchThroughBreaker(
  store: ObjectStore,
  alerting: Alerting,
  source: string,
  query: BreakerQuery,
  now: Date,
): Promise<Response> {
  const tripped = await readBreaker(store, source);
  if (tripped !== null) {
    throw new BreakerTrippedError(tripped);
  }

  const response = await fetchOrExplain(alerting.fetch, query.url, {
    headers: { "User-Agent": query.userAgent },
    redirect: "manual",
  });

  if (!response.ok) {
    throw new BreakerTrippedError(
      await trip(store, alerting, source, { status: response.status, url: query.url }, now),
    );
  }
  return response;
}
