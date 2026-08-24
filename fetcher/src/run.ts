import { buildSatellites, parseOmmCsv, type ParsedSatellite } from "./celestrak";
import { detectTrains, type StarlinkTrain } from "./trains";
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
  /**
   * The Starlink list, from the **supplemental** endpoint rather than GP.
   *
   * The satellites that make a train are by definition the ones GP serves
   * worst: freshly deployed, actively raising orbit, elements going stale in
   * hours. SupGP is built from the operator's own ephemerides for exactly that
   * case. It is the same OMM CSV, so the parser is reused, and the two-hour cap
   * and the breaker apply to it identically.
   */
  starlinkUrl: string;
  userAgent: string;
}

export interface RunResult {
  published: boolean;
  satellites: number;
  trains: number;
  error?: string;
}

/**
 * The trains in a supplemental document, or none if it cannot be read.
 *
 * Deliberately softer than the core groups, which throw on a bad row so a
 * moved format alerts a human rather than quietly losing the ISS. Trains are an
 * addition; the elements the app needs to work are not. A supplemental document
 * that cannot be parsed should cost this cycle its trains and nothing else,
 * where throwing would take the station and the bright catalogue down with it.
 *
 * A non-200 is a different matter entirely and never reaches here: the breaker
 * stops the run, as CelesTrak's policy requires.
 */
function readTrains(
  csv: string,
  atMillis: number,
): { trains: StarlinkTrain[]; members: ParsedSatellite[] } {
  try {
    const members = parseOmmCsv(csv);
    return { trains: detectTrains(atMillis, members), members };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.warn(`no trains this run: ${message}`);
    return { trains: [], members: [] };
  }
}

/**
 * One run: fetch the groups, publish the elements, record what happened.
 *
 * The groups are fetched **sequentially on purpose**. In parallel, the second
 * request would be on the wire before the first response could forbid it, which
 * is precisely the behaviour the breaker exists to prevent. That holds for the
 * supplemental list too - it goes last, so a breaker already tripped by one of
 * the core groups stops it before it is asked for.
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
    const parsed: ParsedSatellite[] = [];

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

    const starlink = await fetchThroughBreaker(
      store,
      alerting,
      CELESTRAK_SOURCE,
      { url: config.starlinkUrl, userAgent: config.userAgent },
      now(),
    );
    // Measured at the moment the document is being written, because each
    // satellite's elements are stated at an epoch of its own.
    const trains = readTrains(await starlink.text(), now().getTime());

    // Only the two ends of each train, not the launch. Everything the app says
    // about a train comes from when the front rises and when the back is clear,
    // and thousands of Starlink element sets on every device would cost two
    // orders of magnitude more than the document does today.
    const ends = new Set(trains.trains.flatMap((train) => [train.lead, train.tail]));
    parsed.push(...trains.members.filter((member) => ends.has(member.catalogue)));

    const document = buildSatellites(parsed, trains.trains, now());
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

    return {
      published: true,
      satellites: document.satellites.length,
      trains: document.trains.length,
    };
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
        trains: 0,
        error: `${message} (the health record could not be written either: ${also})`,
      };
    }

    return { published: false, satellites: 0, trains: 0, error: message };
  }
}
