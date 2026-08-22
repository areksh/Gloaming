/**
 * CelesTrak GP elements, as OMM in CSV.
 *
 * **The app never parses this.** On 2026-07-11 the public catalogue ran out of
 * 5-digit numbers, and GP data is not published in TLE format for the 6-digit
 * objects that follow - which includes the brightest new satellites going up.
 * So elements arrive as OMM, and are normalised here into `sats.json`, whose
 * fields map one-for-one onto the propagator's element type.
 *
 * Normalising Worker-side rather than on the device is deliberate:
 *
 * - The column mapping and the epoch conversion are the parts most likely to be
 *   wrong, and a pass 40 seconds out is the classic symptom. Here they are
 *   cheap to test.
 * - `minSdk 24` predates `java.time`, so ISO-8601 arithmetic on the device
 *   would mean either desugaring or hand-rolled date maths, for a value that is
 *   identical on every install.
 * - CelesTrak's usage policy forbids per-device fetching outright (Sec. 3), so
 *   there is exactly one fetcher and it may as well do the work once.
 *
 * Columns are looked up by name from the header row rather than by position.
 * The order is stable in practice, but nothing about the format guarantees it,
 * and reading a reordered document by index would not fail - it would publish
 * plausible elements for the wrong orbit.
 */

/** `sats.json`'s shape. Bump if a field's meaning changes. */
export const SATELLITES_SCHEMA = 1;

/** One satellite's mean elements, in the units the propagator expects. */
export interface SatelliteElements {
  name: string;
  /** NORAD catalogue number. Six digits for anything catalogued since 2026-07. */
  catalogue: number;
  /**
   * Epoch as the packed `YYDDD.dddddd` the TLE format used and every SGP4
   * implementation still takes: 2-digit year, day of year from 1, and the
   * fraction of that day.
   */
  epoch: number;
  /** The same instant in unix milliseconds, so the app can age the elements. */
  epoch_millis: number;
  /** Revolutions per day. */
  mean_motion: number;
  eccentricity: number;
  /** Degrees. */
  inclination: number;
  /** Right ascension of the ascending node, degrees. */
  raan: number;
  /** Argument of pericentre, degrees. */
  arg_perigee: number;
  /** Degrees. */
  mean_anomaly: number;
  /** Drag term, inverse Earth radii. */
  bstar: number;
  /** First derivative of mean motion, revolutions per day squared. */
  mean_motion_dot: number;
  /**
   * Standard magnitude where one can be stood behind, otherwise null. Never
   * guessed: an app that says "magnitude 2.5" and delivers 4.5 half the time is
   * worse than one that never claimed a number.
   */
  magnitude: number | null;
}

export interface SatellitesDocument {
  schema: number;
  updated: string;
  satellites: SatelliteElements[];
}

/**
 * The handful of standard magnitudes worth publishing.
 *
 * Deliberately tiny. `qs.mag` has no Starlink coverage and mixes two
 * incompatible conventions, and SCORE holds observations rather than standard
 * magnitudes, so v1 stays qualitative and describes passes by geometry
 * instead (Sec. 6). CSS/Tiangong is named alongside the ISS in that section but
 * has no figure here: none was found that a source could be cited for, and a
 * number nobody has stood behind is exactly what this table exists to avoid.
 */
export const STANDARD_MAGNITUDES: Record<number, number> = {
  25544: -1.8, // ISS (ZARYA)
};

/** Every column the propagator needs. A document missing one is not usable. */
const COLUMNS = [
  "OBJECT_NAME",
  "EPOCH",
  "MEAN_MOTION",
  "ECCENTRICITY",
  "INCLINATION",
  "RA_OF_ASC_NODE",
  "ARG_OF_PERICENTER",
  "MEAN_ANOMALY",
  "NORAD_CAT_ID",
  "BSTAR",
  "MEAN_MOTION_DOT",
] as const;

/** Splits one CSV line, honouring the quoting a name with a comma would need. */
function splitRow(line: string): string[] {
  const fields: string[] = [];
  let field = "";
  let quoted = false;

  for (let i = 0; i < line.length; i++) {
    const character = line[i];
    if (quoted) {
      if (character === '"') {
        if (line[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        field += character;
      }
    } else if (character === '"') {
      quoted = true;
    } else if (character === ",") {
      fields.push(field);
      field = "";
    } else {
      field += character;
    }
  }
  fields.push(field);
  return fields;
}

/**
 * Converts an OMM epoch to the packed `YYDDD.dddddd` form, and to unix millis.
 *
 * The fraction is computed from the time components rather than from a Date,
 * because CelesTrak publishes microseconds and a Date would round them away
 * before the division.
 */
export function packEpoch(iso: string): { epoch: number; millis: number } {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2}(?:\.\d+)?)Z?$/.exec(iso.trim());
  if (match === null) {
    throw new Error(`CelesTrak EPOCH is not an ISO-8601 timestamp: "${iso}"`);
  }

  const [year, month, day, hour, minute] = match.slice(1, 6).map(Number) as [
    number,
    number,
    number,
    number,
    number,
  ];
  const seconds = Number(match[6]);

  const midnight = Date.UTC(year, month - 1, day);
  const dayOfYear = (midnight - Date.UTC(year, 0, 1)) / 86_400_000 + 1;
  const secondsOfDay = hour * 3600 + minute * 60 + seconds;

  return {
    epoch: (year % 100) * 1000 + dayOfYear + secondsOfDay / 86_400,
    millis: midnight + Math.round(secondsOfDay * 1000),
  };
}

/**
 * Parses one group's OMM CSV.
 *
 * Throws rather than skipping a bad row. The document is machine-generated and
 * homogeneous, so a row that does not parse means the format has moved, and
 * dropping it quietly is how the ISS goes missing for a night with nobody able
 * to say why. Failing loudly alerts a human while `sats.json` keeps serving its
 * last good copy.
 */
export function parseOmmCsv(csv: string): SatelliteElements[] {
  const lines = csv.split("\n").map((line) => line.replace(/\r$/, "")).filter((line) => line !== "");
  const header = lines.shift();
  if (header === undefined) {
    throw new Error("CelesTrak returned an empty document");
  }

  const names = splitRow(header).map((name) => name.trim().toUpperCase());
  const index: Record<string, number> = {};
  for (const column of COLUMNS) {
    const position = names.indexOf(column);
    if (position === -1) {
      throw new Error(`CelesTrak document has no ${column} column`);
    }
    index[column] = position;
  }

  const satellites = lines.map((line, row) => {
    const fields = splitRow(line);

    const value = (column: (typeof COLUMNS)[number]): string => {
      const raw = fields[index[column] as number];
      if (raw === undefined) {
        throw new Error(`CelesTrak row ${row + 1} has no ${column} field`);
      }
      return raw.trim();
    };
    const number = (column: (typeof COLUMNS)[number]): number => {
      const raw = value(column);
      const parsed = Number(raw);
      if (raw === "" || !Number.isFinite(parsed)) {
        throw new Error(`CelesTrak row ${row + 1} has an unreadable ${column}: "${raw}"`);
      }
      return parsed;
    };

    const { epoch, millis } = packEpoch(value("EPOCH"));
    return {
      name: value("OBJECT_NAME"),
      catalogue: number("NORAD_CAT_ID"),
      epoch,
      epoch_millis: millis,
      mean_motion: number("MEAN_MOTION"),
      eccentricity: number("ECCENTRICITY"),
      inclination: number("INCLINATION"),
      raan: number("RA_OF_ASC_NODE"),
      arg_perigee: number("ARG_OF_PERICENTER"),
      mean_anomaly: number("MEAN_ANOMALY"),
      bstar: number("BSTAR"),
      mean_motion_dot: number("MEAN_MOTION_DOT"),
      magnitude: null,
    } satisfies SatelliteElements;
  });

  if (satellites.length === 0) {
    throw new Error("CelesTrak document listed no satellites");
  }
  return satellites;
}

/**
 * Composes `sats.json` from the parsed groups.
 *
 * `stations` and `visual` overlap - the ISS is in both - so entries are
 * deduplicated by catalogue number. The two groups are read from the same GP
 * database in the same run, so the duplicates are identical and the first is
 * kept. Sorting by catalogue number keeps the published document stable.
 */
export function buildSatellites(
  parsed: SatelliteElements[],
  now: Date,
): SatellitesDocument {
  const unique = new Map<number, SatelliteElements>();
  for (const satellite of parsed) {
    if (!unique.has(satellite.catalogue)) {
      unique.set(satellite.catalogue, {
        ...satellite,
        magnitude: STANDARD_MAGNITUDES[satellite.catalogue] ?? null,
      });
    }
  }

  return {
    schema: SATELLITES_SCHEMA,
    updated: now.toISOString(),
    satellites: [...unique.values()].sort((a, b) => a.catalogue - b.catalogue),
  };
}
