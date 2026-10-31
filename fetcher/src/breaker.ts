import { notifyMaintainer, type Alerting } from "./health";
import { fetchOrExplain } from "../../worker/src/network";
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
 * **A human gets two days to reset it by hand.** They are meant to find out
 * why the response was not a 200 before another request leaves, but a trip
 * that nobody gets to - a 503 on an evening nobody is watching - should not
 * leave elements frozen indefinitely. So two days after a trip one request is
 * let through: a 200 clears the flag, and anything else trips it afresh for
 * another two days. Never sooner, and never more than one request per two
 * days, which is still nothing like the retrying the policy warns about.
 * `sats.json` keeps serving its last good copy meanwhile, and elements stay
 * usable for days.
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
        `${state.url} (${state.note}). Tries again at ${clearsAt(state).toISOString()}, ` +
        `or reset now with: wrangler r2 object delete ` +
        `gloaming-data/${breakerKey(state.source)} --remote`,
    );
    this.name = "BreakerTrippedError";
  }
}

export const breakerKey = (source: string) => `internal/breaker/${source}.json`;

/** How long a trip holds before one request is let through again. */
const CLEARS_AFTER_MILLIS = 2 * 24 * 60 * 60 * 1000;

/** When a trip stops holding, if nobody has cleared it by hand first. */
const clearsAt = (state: BreakerState): Date =>
  new Date(Date.parse(state.tripped_at) + CLEARS_AFTER_MILLIS);

const holds = (state: BreakerState, now: Date): boolean =>
  now.getTime() < clearsAt(state).getTime();

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
 * A trip on a breaker that still holds keeps the original: the first non-200 is
 * the one that explains what happened, and re-alerting would train the
 * maintainer to ignore the channel. One that has stopped holding is replaced -
 * that is the retry two days on being refused, and what came back this time is
 * the news.
 */
export async function trip(
  store: ObjectStore,
  alerting: Alerting,
  source: string,
  failure: { status: number; url: string },
  now: Date,
): Promise<BreakerState> {
  const existing = await readBreaker(store, source);
  if (existing !== null && holds(existing, now)) {
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
    title: `Gloaming: ${source} breaker tripped${existing === null ? "" : " again"}`,
    body:
      `HTTP ${state.status} from ${state.url} - ${state.note}.\n\n` +
      `No further queries until ${clearsAt(state).toISOString()}, when one is tried ` +
      `again, unless this is cleared by hand first:\n` +
      `wrangler r2 object delete gloaming-data/${breakerKey(source)} --remote`,
  });

  return state;
}

export interface BreakerQuery {
  url: string;
  userAgent: string;
}

/**
 * Fetches through the breaker: refuses to query while a trip holds, and trips
 * it on any non-200. Once a trip has held for two days the request goes out,
 * and a 200 clears the flag.
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
  if (tripped !== null && holds(tripped, now)) {
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
  if (tripped !== null) {
    await store.delete(breakerKey(source));
  }
  return response;
}
