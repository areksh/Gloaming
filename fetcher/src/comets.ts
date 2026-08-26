/**
 * Comet orbits from the Minor Planet Center, with observed magnitudes from
 * COBS.
 *
 * Two upstreams on different cadences reduced into one small document. The
 * elements say where a comet will be, which is knowable years ahead and changes
 * only as the orbit is refined; the observations say how bright it actually is,
 * which is knowable only by looking and changes week to week.
 *
 * **The prediction is not good enough on its own.** The absolute magnitude and
 * slope in the MPC file feed a two-hundred-year-old law that assumes a comet
 * brightens smoothly as it approaches the sun. Comets outburst, fragment and
 * disappoint: 220P/McNaught went from about magnitude 18 to 8.2 in a week in
 * May 2026, and the law had it at 18 throughout. Thousands of people were
 * looking, and COBS had it within days.
 *
 * Normalised here rather than on the device for the same reasons as CelesTrak:
 * the fixed-width column mapping and the Julian date conversion are the parts
 * most likely to be wrong and are cheap to test here, `minSdk 24` predates
 * `java.time`, and the answer is identical on every install.
 */

/** `comets.json`'s shape. Bump if a field's meaning changes. */
export const COMETS_SCHEMA = 1;

/**
 * The credit COBS's data usage guidelines ask for, carried in the document
 * itself.
 *
 * Their licence is CC BY-NC-SA 4.0, which asks three things of anybody who
 * redistributes: credit with a link to the licence, no commercial use, and that
 * derivatives go out under the same terms. This file *is* a derivative - the
 * magnitudes in it are medians over a fortnight of reports rather than
 * observations as filed - and it is served from a public CDN, so the obligation
 * attaches to the object and not only to the app that reads it. Somebody who
 * finds this URL on its own should be able to see whose work it is.
 *
 * The app credits them on its own screen as well; this is the copy that travels
 * with the data.
 */
export const COMETS_ATTRIBUTION = {
  observations: "Credit: COBS Comet Observation Database - CC BY-NC-SA 4.0",
  observations_licence: "https://creativecommons.org/licenses/by-nc-sa/4.0/",
  observations_source: "https://cobs.si/",
  elements: "Orbital elements: IAU Minor Planet Center",
  elements_source: "https://www.minorplanetcenter.net/",
  changes:
    "Observed magnitudes are the median of reports from at least two independent " +
    "observers within a 14-day window, not observations as filed.",
} as const;

/**
 * How faint a comet may be and still be published.
 *
 * Deliberately far below anything the app offers as a setting - the faintest
 * the user can ask for is 8 - so the device does the filtering the user can see
 * and change, and this only keeps the document small. What it cuts is the long
 * tail of comets that no telescope in a garden will ever reach, which is most
 * of the 957 orbits in the file.
 */
export const PUBLISH_MAGNITUDE = 10;

/**
 * How far back observations are gathered.
 *
 * Brightness is the one thing about a comet that changes fast, so a long window
 * would average an outburst away against the fortnight before it. Two weeks is
 * long enough that a comet nobody looked at on a cloudy weekend still has a
 * figure, and short enough to follow a comet that is changing.
 */
export const OBSERVATION_WINDOW_DAYS = 14;

/**
 * How many people must have looked before a measured magnitude is believed.
 *
 * The rule exists because of a specific failure. On 2026-08-25 one COBS
 * observer was reporting C/2020 F3 (NEOWISE) at magnitude 1.2 - six years after
 * it faded - and C/2014 UN271, which is out beyond Saturn, at 0.5. Seven
 * reports, one person, all nonsense. A median over that observer's own numbers
 * still says 1.5, because a median only defends against a wild value among many
 * and this was a wild observer.
 *
 * Two is the smallest number that makes independent agreement mean anything.
 * The cost is that a genuine comet only one person has caught waits a day for a
 * second look, which is the right way round: the app would rather be late than
 * announce the comet of the decade because one person mistyped.
 */
export const LEAST_OBSERVERS = 2;

/**
 * How long before and after perihelion a comet is worth carrying, in days.
 *
 * Needed because the magnitude bound below is evaluated at perihelion, which
 * says nothing about where the comet is now. Without a window the file happily
 * publishes Hale-Bopp - absolute magnitude -2, and so brighter at perihelion
 * than anything else in the catalogue - despite that perihelion having been in
 * March 1997 and the comet now being somewhere past Neptune.
 *
 * Two years ahead covers everything the calendar's own year-long horizon can
 * show, with room for a comet to be worth mentioning before it arrives. One
 * year behind covers the long fade afterwards, which for a bright comet is the
 * half people go on watching.
 */
export const PERIHELION_BEFORE_DAYS = 365;
export const PERIHELION_AFTER_DAYS = 730;

/** One magnitude report, as COBS gives it. */
export interface CobsObservation {
  magnitude: number;
  atMillis: number;
  /** The observer's COBS code, used only to count how many people agree. */
  observer: string;
}

/** What a fortnight of reports comes to. */
export interface ObservedMagnitude {
  /** The median, in magnitudes. */
  magnitude: number;
  /** When the most recent report used was made, so the app can age it. */
  atMillis: number;
  reports: number;
  observers: number;
}

/** One comet's orbit, as read from the MPC file. */
export interface ParsedComet {
  /**
   * The identifier COBS reports, so the two sources can be joined: the
   * catalogue number and type for a numbered comet (`220P`), and the packed
   * provisional designation otherwise (`K25A060`).
   */
  key: string;
  /** The MPC's own combined designation and name, for display. */
  name: string;
  /** Perihelion distance, AU. */
  q: number;
  /** 0 for a circle, below 1 for an ellipse, 1 for a parabola, above for a hyperbola. */
  e: number;
  /** Inclination to the ecliptic, J2000, degrees. */
  i: number;
  /** Longitude of the ascending node, J2000, degrees. */
  node: number;
  /** Argument of perihelion, J2000, degrees. */
  peri: number;
  /**
   * Perihelion passage as a Julian date in **Terrestrial Time**, not UTC and
   * not a unix timestamp.
   *
   * Published in the scale the MPC states it in, rather than converted to unix
   * seconds and back. The two scales differ by about a minute at present, and a
   * comet passing near the Earth moves an arcminute in that - so a round trip
   * through UTC would introduce exactly the error the app has no budget for,
   * twice. The device converts its own clock instead, which it must do anyway.
   */
  perihelion_jd_tt: number;
  /** Absolute magnitude, or null where the file gives none. */
  magnitude_h: number | null;
  /** Slope parameter, conventionally 4. */
  slope: number | null;
}

/** A comet as published, with whatever anybody has measured attached. */
export interface PublishedComet extends ParsedComet {
  observed: ObservedMagnitude | null;
}

export interface CometsDocument {
  schema: number;
  /** Unix seconds, matching `sats.json`. */
  updated: number;
  /** Who the data belongs to and on what terms - see [COMETS_ATTRIBUTION]. */
  attribution: typeof COMETS_ATTRIBUTION;
  comets: PublishedComet[];
}

/**
 * Column positions from the MPC's own format document at
 * `iau/info/CometOrbitFormat.html`, one-indexed and inclusive as it states
 * them. Named rather than inlined because a fixed-width field read one place
 * out does not fail - it yields a plausible orbit for the wrong comet.
 */
const COLUMNS = {
  number: [1, 4],
  orbitType: [5, 5],
  packed: [6, 12],
  year: [15, 18],
  month: [20, 21],
  day: [23, 29],
  q: [31, 39],
  eccentricity: [42, 49],
  peri: [52, 59],
  node: [62, 69],
  inclination: [72, 79],
  magnitudeH: [92, 95],
  slope: [97, 100],
  name: [103, 158],
} as const;

/**
 * Orbit types that are not a comet anybody will see.
 *
 * `A` is an object that turned out to be an asteroid and `D` is one lost or
 * seen to disintegrate. Both stay in the file and neither will be in the sky.
 */
const NOT_A_COMET = new Set(["A", "D"]);

function field(line: string, [start, end]: readonly [number, number]): string {
  return line.slice(start - 1, end).trim();
}

/**
 * The Julian date of a calendar date in the same scale it was given in.
 *
 * The standard Gregorian formula. It carries no time-scale of its own: given a
 * date in Terrestrial Time it returns a Julian date in Terrestrial Time, which
 * is exactly what is wanted for a perihelion passage.
 */
export function julianDayTt(year: number, month: number, day: number): number {
  let y = year;
  let m = month;
  // January and February are counted as months 13 and 14 of the year before,
  // which is what puts the leap day at the end where the formula needs it.
  if (m <= 2) {
    y -= 1;
    m += 12;
  }
  const century = Math.floor(y / 100);
  const gregorian = 2 - century + Math.floor(century / 4);
  return (
    Math.floor(365.25 * (y + 4716)) +
    Math.floor(30.6001 * (m + 1)) +
    day +
    gregorian -
    1524.5
  );
}

/**
 * The identifier both sources can be joined on.
 *
 * A numbered comet is keyed by its number and type (`220P`) and an unnumbered
 * one by its packed provisional designation (`K25A060`).
 *
 * **Fragments need care.** A comet that has split is published as several rows,
 * and for a numbered comet the fragment letter sits in the *packed designation*
 * columns that are otherwise blank - `0141P      a`. Ignoring it collapses
 * 141P and its four fragments onto one key, which is not merely a duplicate in
 * the list: it makes every fragment inherit whichever fragment's observations
 * happened to be fetched, so the app would report one object's brightness under
 * another's name.
 */
export function mpcKey(number: string, orbitType: string, packed: string): string {
  const numbered = number.trim();
  const rest = packed.trim();
  if (numbered === "") return rest;

  const comet = `${Number.parseInt(numbered, 10)}${orbitType.trim()}`;
  return rest === "" ? comet : `${comet}-${rest.toUpperCase()}`;
}

/**
 * The same key, from the shape COBS reports.
 *
 * COBS encodes a fragment in one of two places depending on the designation.
 * For a provisional one the letter is already inside the packed name it gives
 * (`K23R01b`, with `component` also set to `B`); for a numbered one the name is
 * plain (`73P`) and only `component` carries it. Appending blindly would turn
 * the first into `K23R01b-B`, which matches nothing.
 */
export function cobsKey(mpcName: string, component: string | null): string {
  const name = mpcName.trim();
  const fragment = (component ?? "").trim();
  if (fragment === "") return name;
  if (name.toUpperCase().endsWith(fragment.toUpperCase())) return name;
  return `${name}-${fragment.toUpperCase()}`;
}

/**
 * Every orbit in the file that this app could have anything to say about.
 *
 * A row that cannot be read costs that comet and nothing else. The file spans
 * every era of observation and taking the list down over one malformed line
 * would lose the comet somebody is outside looking at.
 */
export function parseCometEls(text: string): ParsedComet[] {
  const comets: ParsedComet[] = [];

  for (const line of text.split("\n")) {
    if (line.trim().length < 50) continue;

    const orbitType = field(line, COLUMNS.orbitType);
    if (NOT_A_COMET.has(orbitType)) continue;

    const q = Number(field(line, COLUMNS.q));
    const e = Number(field(line, COLUMNS.eccentricity));
    const i = Number(field(line, COLUMNS.inclination));
    const node = Number(field(line, COLUMNS.node));
    const peri = Number(field(line, COLUMNS.peri));
    const year = Number(field(line, COLUMNS.year));
    const month = Number(field(line, COLUMNS.month));
    const day = Number(field(line, COLUMNS.day));

    // Bounds rather than a format check: a field read from the wrong columns
    // usually still parses as a number, and only the physics catches it.
    if (!(q > 0) || !(q < 1000)) continue;
    if (!(e >= 0) || !(e < 20)) continue;
    if (!(i >= 0) || !(i <= 180)) continue;
    if (!(node >= 0) || !(node <= 360)) continue;
    if (!(peri >= 0) || !(peri <= 360)) continue;
    if (!(year > 1500) || !(year < 2500)) continue;
    if (!(month >= 1) || !(month <= 12)) continue;
    if (!(day > 0) || !(day < 32)) continue;

    const key = mpcKey(
      field(line, COLUMNS.number),
      orbitType,
      field(line, COLUMNS.packed),
    );
    if (key === "") continue;

    comets.push({
      key,
      name: field(line, COLUMNS.name),
      q,
      e,
      i,
      node,
      peri,
      perihelion_jd_tt: julianDayTt(year, month, day),
      magnitude_h: numberOrNull(field(line, COLUMNS.magnitudeH)),
      slope: numberOrNull(field(line, COLUMNS.slope)),
    });
  }

  return comets;
}

/**
 * The observations in a COBS response, grouped by the comet they belong to.
 *
 * Reads only the five fields this needs from a record with forty. Anything
 * unparseable is skipped rather than thrown on: the response covers every comet
 * anybody reported in the window, so one bad record should cost that record and
 * not the fortnight.
 *
 * Observation times are UT, as the whole of variable-star and comet photometry
 * has always recorded them, and are read as such.
 */
export function parseCobsObservations(body: string): Map<string, CobsObservation[]> {
  const grouped = new Map<string, CobsObservation[]>();

  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return grouped;
  }

  const records = (parsed as { objects?: unknown }).objects;
  if (!Array.isArray(records)) return grouped;

  for (const record of records) {
    const row = record as {
      magnitude?: unknown;
      obs_date?: unknown;
      comet?: { mpc_name?: unknown; component?: unknown };
      observer?: { icq_name?: unknown };
    };

    // Checked for type before conversion, because `Number(null)` and
    // `Number("")` are both 0 - and a record with no magnitude would otherwise
    // become a comet at magnitude zero, brighter than any that has ever been
    // seen. COBS does carry such records: an observer can report a coma
    // diameter or a tail without estimating a brightness.
    const reported = row.magnitude;
    if (typeof reported !== "string" && typeof reported !== "number") continue;
    if (typeof reported === "string" && reported.trim() === "") continue;
    const magnitude = Number(reported);
    if (!Number.isFinite(magnitude)) continue;

    if (typeof row.obs_date !== "string") continue;
    const atMillis = Date.parse(`${row.obs_date.replace(" ", "T")}Z`);
    if (!Number.isFinite(atMillis)) continue;

    const name = row.comet?.mpc_name;
    if (typeof name !== "string" || name.trim() === "") continue;
    const component = typeof row.comet?.component === "string" ? row.comet.component : null;

    const observer = row.observer?.icq_name;
    if (typeof observer !== "string" || observer.trim() === "") continue;

    const key = cobsKey(name, component);
    const list = grouped.get(key) ?? [];
    list.push({ magnitude, atMillis, observer: observer.trim() });
    grouped.set(key, list);
  }

  return grouped;
}

/**
 * How many pages the response says it spans.
 *
 * COBS pages its results, and a window that fits on one page today may not
 * during a bright apparition, when thousands of people report the same comet.
 * Reading only the first page would not fail - it would quietly reduce a
 * fortnight to a fraction of it, on exactly the comet everybody was watching.
 */
export function cobsPageCount(body: string): number {
  try {
    const parsed = JSON.parse(body) as { info?: { pages?: unknown } };
    const pages = Number(parsed.info?.pages);
    return Number.isFinite(pages) && pages > 0 ? Math.floor(pages) : 1;
  } catch {
    return 1;
  }
}

function numberOrNull(text: string): number | null {
  if (text === "") return null;
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
}

/**
 * What a fortnight of amateur reports comes to, or null if it comes to nothing
 * that can be stood behind.
 *
 * The median rather than the mean, and independent observers rather than
 * reports - see [LEAST_OBSERVERS] for the failure that shaped both.
 */
export function reduceObservations(
  observations: CobsObservation[],
  nowMillis: number,
): ObservedMagnitude | null {
  const window = nowMillis - OBSERVATION_WINDOW_DAYS * 24 * 60 * 60 * 1000;
  const recent = observations.filter(
    (observation) =>
      observation.atMillis >= window &&
      observation.atMillis <= nowMillis &&
      Number.isFinite(observation.magnitude),
  );
  if (recent.length === 0) return null;

  const observers = new Set(recent.map((observation) => observation.observer));
  if (observers.size < LEAST_OBSERVERS) return null;

  const sorted = recent.map((observation) => observation.magnitude).sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  const median =
    sorted.length % 2 === 1
      ? sorted[middle]!
      : (sorted[middle - 1]! + sorted[middle]!) / 2;

  return {
    // Rounded to a tenth, which is the precision magnitudes are reported to.
    // It also keeps a median of two from publishing 9.100000000000001.
    magnitude: Math.round(median * 10) / 10,
    atMillis: Math.max(...recent.map((observation) => observation.atMillis)),
    reports: recent.length,
    observers: observers.size,
  };
}

/**
 * The brightest a comet could ever look, by the standard magnitude law.
 *
 * `m = H + 5 log10(delta) + 2.5 G log10(r)`, evaluated with the comet at
 * perihelion and the Earth as close to it as the two orbits could ever put
 * them. That makes it an upper bound rather than an estimate, which is the
 * property a coarse filter needs: it can hold a comet that turns out to be dull
 * but can never drop one that turns out to be worth seeing. What a comet
 * actually looks like on a given night is fainter, and the device works that
 * out for itself.
 */
export function brightestPossible(comet: ParsedComet): number | null {
  if (comet.magnitude_h === null) return null;
  const slope = comet.slope ?? 4;
  // The closest the Earth can come to a comet at perihelion distance q. Floored
  // so a comet crossing the Earth's own distance does not take log10 of zero.
  const closest = Math.max(Math.abs(comet.q - 1), 0.01);
  return (
    comet.magnitude_h +
    5 * Math.log10(closest) +
    2.5 * slope * Math.log10(comet.q)
  );
}

/** The Julian date of a unix instant, near enough for a window of years. */
export function julianDayFromMillis(millis: number): number {
  return millis / 86_400_000 + 2440587.5;
}

/**
 * The published document: the comets worth carrying, brightest first.
 *
 * **A measurement decides, and the prediction is only the fallback.** That
 * ordering matters in both directions, and the live data shows both. On
 * 2026-08-25 the law put 220P/McNaught at magnitude 15.7 while thirteen
 * observers had it at 9.3, so filtering on the prediction would have dropped
 * the second-brightest comet in the sky. The same day it put 161P/Hartley-IRAS
 * at 7.1 while nine observers had it at 15.0, so trusting the prediction would
 * have published a comet nobody could see. A measurement can promote and it can
 * demote; what it cannot do is be overruled by an estimate.
 *
 * Where nobody has looked, the prediction stands - but only for a comet near
 * enough to perihelion for it to mean anything.
 */
export function buildComets(
  comets: ParsedComet[],
  observations: Map<string, CobsObservation[]>,
  nowMillis: number,
): CometsDocument {
  const published: PublishedComet[] = [];

  const today = julianDayFromMillis(nowMillis);

  for (const comet of comets) {
    const observed = reduceObservations(observations.get(comet.key) ?? [], nowMillis);

    if (observed !== null) {
      // Somebody has looked. That settles it, whichever way it goes, and the
      // comet's distance from perihelion is beside the point - a measurement
      // is evidence about now in a way no element set is.
      if (observed.magnitude <= PUBLISH_MAGNITUDE) published.push({ ...comet, observed });
      continue;
    }

    const sincePerihelion = today - comet.perihelion_jd_tt;
    if (sincePerihelion > PERIHELION_BEFORE_DAYS) continue;
    if (sincePerihelion < -PERIHELION_AFTER_DAYS) continue;

    // A comet with neither a measurement nor an absolute magnitude cannot be
    // placed on the scale at all, and is dropped rather than guessed at.
    const predicted = brightestPossible(comet);
    if (predicted === null || predicted > PUBLISH_MAGNITUDE) continue;

    published.push({ ...comet, observed: null });
  }

  published.sort((a, b) => effective(a) - effective(b));

  return {
    schema: COMETS_SCHEMA,
    updated: Math.floor(nowMillis / 1000),
    attribution: COMETS_ATTRIBUTION,
    comets: published,
  };
}

function effective(comet: PublishedComet): number {
  return comet.observed?.magnitude ?? brightestPossible(comet) ?? Number.POSITIVE_INFINITY;
}
