import { buildSatellites, parseOmmCsv, type ParsedSatellite } from "./celestrak";
import { detectTrains, type StarlinkTrain } from "./trains";
import {
  OBSERVATION_WINDOW_DAYS,
  buildComets,
  cobsPageCount,
  parseCobsObservations,
  parseCometEls,
  type CobsObservation,
} from "./comets";
import { fetchThroughBreaker } from "./breaker";
import { explainFailure, fetchOrExplain } from "../../worker/src/network";
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
    const message = explainFailure(error);
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
    const message = explainFailure(error);

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
      const also = explainFailure(recording);
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

// --- comets ---

/**
 * A second source, entirely separate from CelesTrak.
 *
 * Separate on purpose, at every level: its own health record, its own failure
 * path, and no shared breaker. A 403 from the Minor Planet Center must not stop
 * the station's elements refreshing, and the reverse holds too.
 *
 * **Deliberately no circuit breaker.** The one on CelesTrak exists because
 * CelesTrak's usage policy demands stopping on any non-200 and Kelso states it
 * is what keeps an IP off the firewall (Sec. 3). Neither the MPC nor COBS asks
 * for anything of the kind, and a manual-reset breaker on them would mean one
 * transient 500 halts comet updates until a human notices. What they get
 * instead is what any polite client owes: a named User-Agent, one request a
 * day, and a failure recorded and alerted on rather than retried.
 */
export const COMETS_SOURCE = "comets";

const COMETS_KEY = "v1/comets.json";

/**
 * Six hours, against `sats.json`'s thirty minutes.
 *
 * Orbital elements for a satellite in low orbit go stale in hours; a comet's
 * go stale in months, and the observed magnitudes underneath change daily at
 * the very most. Nothing is gained by asking more often than the document
 * changes.
 */
const COMETS_CACHE_CONTROL = "public, max-age=21600";

/** COBS's own maximum page size, so a fortnight normally arrives in one request. */
const COBS_PAGE_SIZE = 2500;

/**
 * A ceiling on paging, so a misread response cannot become an unbounded loop
 * against somebody else's observatory. Ten pages is 25,000 observations in a
 * fortnight, some forty times the ordinary volume.
 */
const COBS_MAX_PAGES = 10;

export interface CometConfig {
  /** The MPC's comet element file, fixed-width. */
  elementsUrl: string;
  /** COBS's observation API, without its query - this adds one. */
  observationsUrl: string;
  userAgent: string;
}

export interface CometRunResult {
  published: boolean;
  comets: number;
  /** How many of them carry a magnitude somebody actually measured. */
  measured: number;
  /** True when the published document was recent enough to leave alone. */
  skipped?: boolean;
  error?: string;
}

/**
 * How recent the published document has to be for a run to leave it alone.
 *
 * The timer that drives this fires every two hours, because that is what
 * CelesTrak's policy requires of the satellite elements. Comets need nothing
 * like it, so without this the same document would be rebuilt twelve times a
 * day and both hosts asked for about a megabyte each time.
 *
 * **Eleven hours, which makes it twice a day**, and the number is set by the
 * consumer rather than by the upstreams. COBS states no rate limit at all, so
 * the question is not what they permit but what could reach anybody: the app's
 * calendar poller sleeps up to twelve hours between wakes and this object is
 * cached at the edge for six, so data fetched more often than twice a day
 * cannot reach a phone any sooner. Eleven rather than twelve so the cycle
 * cannot drift later and later and drop to once a day whenever the timer lands
 * a few minutes shy.
 *
 * Measured before choosing it: across every comet COBS carries, reports arrive
 * at about 1.65 an hour, and for the two comets currently bright enough to
 * matter, removing a whole report moves the fortnight median by 0.000
 * magnitudes. Hourly polling would transfer a megabyte to change nothing.
 *
 * The age comes from the published document itself rather than from local
 * state, which keeps the machine disposable - the same reasoning that puts the
 * breaker and the health record in R2.
 */
const COMETS_MIN_AGE_MILLIS = 11 * 60 * 60 * 1000;

/**
 * A plain fetch with a name attached, which says what went wrong when nothing
 * answers.
 *
 * Redirects are not followed, for the same reason the breaker does not follow
 * them: a followed 301 arrives as a 200 from wherever it pointed, and the whole
 * value of a 301 is that it says the URL is legacy. `www.minorplanetcenter.net`
 * answers 403 to some clients and redirects `/data` elsewhere, which is why
 * `data.minorplanetcenter.net` is the host used.
 */
async function fetchText(
  alerting: Alerting,
  url: string,
  userAgent: string,
): Promise<string> {
  const response = await fetchOrExplain(alerting.fetch, url, {
    headers: { "User-Agent": userAgent },
    redirect: "manual",
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} from ${url}`);
  }
  return response.text();
}

/** One page of the observation window, as a URL. */
function observationsUrl(base: string, now: Date, page: number): string {
  const from = new Date(now.getTime() - OBSERVATION_WINDOW_DAYS * 24 * 60 * 60 * 1000);
  const url = new URL(base);
  url.searchParams.set("format", "json");
  url.searchParams.set("from_date", from.toISOString().slice(0, 10));
  url.searchParams.set("length", String(COBS_PAGE_SIZE));
  url.searchParams.set("page", String(page));
  return url.toString();
}

/**
 * The fortnight of observations, or none if COBS cannot be read.
 *
 * Softer than the elements, on the same reasoning `readTrains` uses: without
 * elements there are no comets at all, but without observations there are still
 * comets - the app falls back to the predicted magnitude and says so. Losing
 * the measurements should cost this run its measurements and not its comets.
 */
async function readObservations(
  alerting: Alerting,
  config: CometConfig,
  now: Date,
): Promise<Map<string, CobsObservation[]>> {
  const merged = new Map<string, CobsObservation[]>();

  try {
    let pages = 1;
    for (let page = 1; page <= Math.min(pages, COBS_MAX_PAGES); page += 1) {
      const body = await fetchText(
        alerting,
        observationsUrl(config.observationsUrl, now, page),
        config.userAgent,
      );
      if (page === 1) pages = cobsPageCount(body);

      for (const [key, observations] of parseCobsObservations(body)) {
        merged.set(key, [...(merged.get(key) ?? []), ...observations]);
      }
    }
  } catch (error) {
    const message = explainFailure(error);
    console.warn(`no observed magnitudes this run: ${message}`);
    return new Map();
  }

  return merged;
}

/**
 * How old the published document is, or null if there is none or it cannot be
 * read.
 *
 * A document that cannot be parsed is treated as absent, so a corrupt object
 * gets replaced on the next run rather than freezing the source forever.
 */
async function publishedAgeMillis(store: ObjectStore, now: Date): Promise<number | null> {
  try {
    const stored = await store.get(COMETS_KEY);
    if (stored === null) return null;
    const updated = Number((JSON.parse(stored) as { updated?: unknown }).updated);
    if (!Number.isFinite(updated)) return null;
    return now.getTime() - updated * 1000;
  } catch {
    return null;
  }
}

/**
 * One comet run: elements, observations, published document.
 *
 * Sequential like the CelesTrak run, and for a milder version of the same
 * reason: two academic hosts should not be asked for large documents at the
 * same instant by the same client.
 *
 * Does nothing at all if the published document is recent enough. This shares
 * a two-hourly timer with the satellite elements, which need that cadence and
 * comets do not - see [COMETS_MIN_AGE_MILLIS].
 */
export async function runComets(
  store: ObjectStore,
  alerting: Alerting,
  config: CometConfig,
  now: () => Date = () => new Date(),
): Promise<CometRunResult> {
  try {
    const age = await publishedAgeMillis(store, now());
    if (age !== null && age >= 0 && age < COMETS_MIN_AGE_MILLIS) {
      return { published: false, comets: 0, measured: 0, skipped: true };
    }

    const elements = parseCometEls(
      await fetchText(alerting, config.elementsUrl, config.userAgent),
    );
    if (elements.length === 0) {
      throw new Error(`${config.elementsUrl} parsed to no comets at all`);
    }

    const observations = await readObservations(alerting, config, now());
    const document = buildComets(elements, observations, now().getTime());

    await store.put(COMETS_KEY, JSON.stringify(document), {
      contentType: "application/json",
      cacheControl: COMETS_CACHE_CONTROL,
    });

    const transition = await recordOutcome(store, COMETS_SOURCE, { ok: true }, now());
    if (transition === "recovered") {
      await notifyMaintainer(alerting, {
        title: `Gloaming: ${COMETS_SOURCE} recovered`,
        body: `${COMETS_SOURCE} is publishing again.`,
      });
    }

    return {
      published: true,
      comets: document.comets.length,
      measured: document.comets.filter((comet) => comet.observed !== null).length,
    };
  } catch (error) {
    const message = explainFailure(error);

    try {
      const transition = await recordOutcome(
        store,
        COMETS_SOURCE,
        { ok: false, error: message },
        now(),
      );
      if (transition === "failed") {
        await notifyMaintainer(alerting, {
          title: `Gloaming: ${COMETS_SOURCE} is failing`,
          body: message,
        });
      }
    } catch (recording) {
      const also = explainFailure(recording);
      return {
        published: false,
        comets: 0,
        measured: 0,
        error: `${message} (the health record could not be written either: ${also})`,
      };
    }

    return { published: false, comets: 0, measured: 0, error: message };
  }
}
