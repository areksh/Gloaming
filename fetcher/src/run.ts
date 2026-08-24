import { buildSatellites, parseOmmCsv, type SatelliteElements } from "./celestrak";
import { fetchThroughBreaker } from "./breaker";
import { notifyMaintainer, recordOutcome, type Alerting } from "./health";
import type { ObjectStore } from "./store";

export const CELESTRAK_SOURCE = "celestrak";
const SATS_KEY = "v1/sats.json";

/**
 * The published caching contract for `sats.json`.
 *
 * Lives here rather than in the Worker's table because this is what publishes
 * the object. The Worker is never in the read path, so an object's cache
 * headers only exist if they are set at PUT time; without this every device's
 * poll goes back to R2 instead of stopping at the edge.
 */
const SATS_CACHE_CONTROL = "public, max-age=1800";

export interface Config {
  stationsUrl: string;
  visualUrl: string;
  userAgent: string;
}

export interface RunResult {
  published: boolean;
  satellites: number;
  error?: string;
}

/**
 * One run: fetch both groups, publish the elements, record what happened.
 *
 * The groups are fetched **sequentially on purpose**. In parallel, the second
 * request would be on the wire before the first response could forbid it, which
 * is precisely the behaviour the breaker exists to prevent.
 *
 * Nothing here throws. A scheduled job that crashes is worse than one that
 * records a failure and comes back, and the failure needs to reach the health
 * record either way.
 */
export async function run(
  store: ObjectStore,
  alerting: Alerting,
  config: Config,
  now: () => Date = () => new Date(),
): Promise<RunResult> {
  try {
    const parsed: SatelliteElements[] = [];

    for (const url of [config.stationsUrl, config.visualUrl]) {
      const response = await fetchThroughBreaker(
        store,
        alerting,
        CELESTRAK_SOURCE,
        { url, userAgent: config.userAgent },
        now(),
      );
      parsed.push(...parseOmmCsv(await response.text()));
    }

    const document = buildSatellites(parsed, now());
    await store.put(SATS_KEY, JSON.stringify(document), {
      contentType: "application/json",
      cacheControl: SATS_CACHE_CONTROL,
    });

    const transition = await recordOutcome(store, CELESTRAK_SOURCE, { ok: true }, now());
    if (transition === "recovered") {
      await notifyMaintainer(alerting, {
        title: `Gloaming: ${CELESTRAK_SOURCE} recovered`,
        body: `${CELESTRAK_SOURCE} is publishing again.`,
      });
    }

    return { published: true, satellites: document.satellites.length };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);

    // Recording the failure can itself fail - R2 being unreachable is one way
    // for the run to have gone wrong in the first place. If it does, the
    // original problem is still the one worth reporting: it is what a human
    // needs to read, and losing it behind a complaint about the health record
    // would send them looking in the wrong place entirely.
    try {
      const transition = await recordOutcome(
        store,
        CELESTRAK_SOURCE,
        { ok: false, error: message },
        now(),
      );
      // Only on the edge into failure. A source down for a day would otherwise
      // produce twelve identical alerts and train the maintainer to ignore
      // them. The breaker sends its own push when it trips, which says a
      // different thing: what CelesTrak actually returned.
      if (transition === "failed") {
        await notifyMaintainer(alerting, {
          title: `Gloaming: ${CELESTRAK_SOURCE} is failing`,
          body: message,
        });
      }
    } catch (recording) {
      const also = recording instanceof Error ? recording.message : String(recording);
      return {
        published: false,
        satellites: 0,
        error: `${message} (the health record could not be written either: ${also})`,
      };
    }

    return { published: false, satellites: 0, error: message };
  }
}
