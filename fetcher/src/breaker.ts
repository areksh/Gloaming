import { notifyMaintainer, type Alerting } from "./health";
import { explainFailure, fetchOrExplain } from "../../worker/src/network";
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
 * let through: a 200 clears the flag, and anything else - a refusal, or no
 * answer at all - holds it for another two days. Never sooner, and never more
 * than one request per two days, which is still nothing like the retrying the
 * policy warns about. `sats.json` keeps serving its last good copy meanwhile,
 * and elements stay usable for days.
 *
 * Every step of that is pushed to the maintainer: the trip, the attempt going
 * out, what it got, and a reset by hand when the next run finds one. A request
 * nobody approved should never leave unannounced.
 */
export interface BreakerState {
  source: string;
  status: number;
  url: string;
  note: string;
  tripped_at: string;
  /**
   * When an automatic attempt last went out and got no answer. That waits two
   * days like a refusal, but no response arrived to replace the trip, so the
   * trip stands and this restarts the clock.
   */
  attempted_at?: string;
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
  new Date(Date.parse(state.attempted_at ?? state.tripped_at) + CLEARS_AFTER_MILLIS);

const holds = (state: BreakerState, now: Date): boolean =>
  now.getTime() < clearsAt(state).getTime();

/**
 * The fetcher's own copy of the trip it last knew to be in force.
 *
 * A manual reset is a `wrangler` delete of the flag, which the fetcher never
 * sees happen. The flag gone while this remains is how the next run tells a
 * reset by hand from a breaker that was never tripped. Under a prefix of its
 * own, so that deleting the flag never takes this with it.
 */
const knownKey = (source: string) => `internal/breaker-known/${source}.json`;

async function record(store: ObjectStore, state: BreakerState): Promise<void> {
  const body = JSON.stringify(state);
  await store.put(breakerKey(state.source), body, { contentType: "application/json" });
  await store.put(knownKey(state.source), body, { contentType: "application/json" });
}

const resetCommand = (source: string) =>
  `wrangler r2 object delete gloaming-data/${breakerKey(source)} --remote`;

const waitsUntil = (state: BreakerState) =>
  `No further queries until ${clearsAt(state).toISOString()}, when one is tried ` +
  `again, unless this is cleared by hand first:\n${resetCommand(state.source)}`;

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
  await record(store, state);

  await notifyMaintainer(alerting, {
    title: `Gloaming: ${source} breaker tripped${existing === null ? "" : " again"}`,
    body: `HTTP ${state.status} from ${state.url} - ${state.note}.\n\n${waitsUntil(state)}`,
  });

  return state;
}

/**
 * Announces a reset by hand, the first time a run finds one, and forgets the
 * trip so the next run does not announce it again.
 */
async function noticeResetByHand(
  store: ObjectStore,
  alerting: Alerting,
  source: string,
): Promise<void> {
  const stored = await store.get(knownKey(source));
  if (stored === null) {
    return;
  }
  const known = JSON.parse(stored) as BreakerState;
  await store.delete(knownKey(source));
  await notifyMaintainer(alerting, {
    title: `Gloaming: ${source} breaker cleared by hand`,
    body:
      `It had tripped at ${known.tripped_at} on HTTP ${known.status} from ${known.url}. ` +
      `Querying as normal again.`,
  });
}

export interface BreakerQuery {
  url: string;
  userAgent: string;
}

/**
 * Fetches through the breaker: refuses to query while a trip holds, and trips
 * it on any non-200. Once a trip has held for two days the request goes out,
 * announced first; a 200 clears the flag, and no answer at all holds it for
 * another two days.
 *
 * Redirects are deliberately not followed. A followed 301 would arrive as a 200
 * from wherever it pointed, and 301 is the one code that means "this URL is
 * legacy and will stop working".
 *
 * A connection that fails outright leaves a closed breaker alone: no response
 * arrived, so it is not the signal the policy is about, and a transient network
 * fault must not demand a human before elements can refresh again. It still
 * fails the run, which is what the health record and its alert exist for -
 * carrying the URL and the reason, because `fetch failed` on its own does not
 * say even which host was being asked. On an attempt two days on it is a
 * failed attempt like any other, and waits another two days.
 */
export async function fetchThroughBreaker(
  store: ObjectStore,
  alerting: Alerting,
  source: string,
  query: BreakerQuery,
  now: Date,
): Promise<Response> {
  const tripped = await readBreaker(store, source);
  if (tripped === null) {
    await noticeResetByHand(store, alerting, source);
  } else if (holds(tripped, now)) {
    throw new BreakerTrippedError(tripped);
  } else {
    await notifyMaintainer(alerting, {
      title: `Gloaming: ${source} breaker trying again`,
      body:
        `Tripped at ${tripped.tripped_at} on HTTP ${tripped.status} from ${tripped.url}, ` +
        `and not cleared by hand. One request is going to ${query.url} now.`,
    });
  }

  let response: Response;
  try {
    response = await fetchOrExplain(alerting.fetch, query.url, {
      headers: { "User-Agent": query.userAgent },
      redirect: "manual",
    });
  } catch (error) {
    if (tripped !== null) {
      const waiting: BreakerState = { ...tripped, attempted_at: now.toISOString() };
      await record(store, waiting);
      await notifyMaintainer(alerting, {
        title: `Gloaming: ${source} breaker got no answer`,
        body: `${explainFailure(error)}.\n\nThe trip stands. ${waitsUntil(waiting)}`,
      });
    }
    throw error;
  }

  if (!response.ok) {
    throw new BreakerTrippedError(
      await trip(store, alerting, source, { status: response.status, url: query.url }, now),
    );
  }
  if (tripped !== null) {
    await store.delete(breakerKey(source));
    await store.delete(knownKey(source));
    await notifyMaintainer(alerting, {
      title: `Gloaming: ${source} breaker cleared itself`,
      body: `HTTP ${response.status} from ${query.url}. Querying as normal again.`,
    });
  }
  return response;
}
