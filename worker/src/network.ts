/**
 * Why a request never got a response.
 *
 * Shared by the Worker and the elements fetcher, and here for the same reason
 * `contracts.ts` is: it has no imports and touches no globals of its own, so
 * either side can use it without dragging the other's runtime along. Both fetch
 * upstreams, both record what went wrong into the same `health.json`, and both
 * were reporting network failures the same useless way.
 *
 * Node reports every network-level failure the same way: `TypeError: fetch
 * failed`, with the real reason nested in `cause` and sometimes nested again
 * below that. Nothing else about such a failure exists - no status code and no
 * body, because nothing answered - so the cause chain is the whole of what a
 * human has to go on.
 *
 * A run that reports only the outer message leaves the journal, the health
 * record and the maintainer's phone all saying `fetch failed`, which does not
 * distinguish an upstream being down from R2 being unreachable, a DNS miss from
 * a dropped connection, or one of the three CelesTrak URLs from the other two.
 * Those are fixed in entirely different places, and on the night CelesTrak went
 * down this was the whole of what the service had to say about it.
 */

/** How far down a chain to read, so one that points back at itself cannot loop. */
const MAX_DEPTH = 5;

/** Node's wrapper, which says nothing the reason underneath does not say better. */
const GENERIC = "fetch failed";

/** One error in its own words: its message, and its code where that adds anything. */
function describe(error: Error): string {
  const message = error.message.trim();
  const code = (error as { code?: unknown }).code;
  const named = typeof code === "string" && code !== "" ? code : null;

  if (message === "") return named ?? "";
  if (named === null || message.includes(named)) return message;
  return `${message} (${named})`;
}

/** An error and everything underneath it, outermost first. */
function reasons(error: unknown, depth: number): string[] {
  if (depth >= MAX_DEPTH || error === null || error === undefined) return [];

  if (!(error instanceof Error)) {
    const text = String(error).trim();
    return text === "" ? [] : [text];
  }

  const own = describe(error);

  // A host that resolves to both an A and an AAAA record fails on both, and
  // undici reports that as an AggregateError - whose own message is the empty
  // string. Its members are the only account of what happened.
  const beneath =
    error instanceof AggregateError && error.errors.length > 0
      ? [
          error.errors
            .map((member) => reasons(member, depth + 1).join(": "))
            .filter((member) => member !== "")
            .join("; "),
        ].filter((members) => members !== "")
      : reasons(error.cause, depth + 1);

  return own === "" ? beneath : [own, ...beneath];
}

/**
 * A failed request in one line: the reason, and everything under it.
 *
 * Exported for its own sake as well as for [fetchOrExplain], because the R2
 * client fails the same way and phrases itself differently.
 */
export function explainFailure(error: unknown): string {
  const chain = reasons(error, 0);

  // A repeated message is noise: undici restates the same reason at more than
  // one level of the chain.
  const unique = [...new Set(chain)];

  // The wrapper is worth reporting only when it is all there is.
  const specific = unique.filter((reason) => reason !== GENERIC);
  const useful = specific.length === 0 ? unique : specific;

  return useful.length === 0 ? "the request failed for no stated reason" : useful.join(": ");
}

/**
 * Fetches, and says what went wrong when nothing comes back.
 *
 * A response is handed back untouched however unwelcome it is: a non-200 is a
 * different kind of event entirely, and what to do about one is the caller's
 * business - for CelesTrak, the breaker's.
 */
export async function fetchOrExplain(
  fetcher: typeof fetch,
  url: string,
  init?: RequestInit,
): Promise<Response> {
  try {
    return await fetcher(url, init);
  } catch (error) {
    throw new Error(`could not reach ${url}: ${explainFailure(error)}`, { cause: error });
  }
}
