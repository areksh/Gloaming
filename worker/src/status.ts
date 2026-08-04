/**
 * Composition of `status.json`, the small latency-critical document every
 * device polls.
 *
 * Two crons contribute on different cadences - AuroraWatch every 3 minutes,
 * SWPC every 5 - so neither may write the whole document. Each stores its own
 * partial and this module composes them, which is what stops the faster cron
 * republishing a stale copy of the other's fields.
 */

export const STATUS_SCHEMA = 1;

export type Activity = "unknown" | "none" | "low" | "moderate" | "high";

/**
 * Peak-probability boundaries for the coarse activity flag.
 *
 * Provisional. These decide only whether a device bothers to fetch the grid,
 * not whether anyone is alerted, so being approximately right is enough for
 * now; the alerting thresholds are a separate calibration exercise against real
 * observations. `max_probability` is published alongside so a device can apply
 * its own cut without a schema change.
 */
const ACTIVITY_THRESHOLDS: ReadonlyArray<readonly [number, Activity]> = [
  [60, "high"],
  [30, "moderate"],
  [10, "low"],
];

export interface SpacePartial {
  updated: string;
  observationTime: string;
  forecastTime: string;
  ovalEtag: string | null;
  maxProbability: number;
  kp: { value: number; time: string } | null;
  solarWind: { bz: number; bt: number; speed: number | null; time: string } | null;
}

export interface UkPartial {
  updated: string;
  status: string;
  siteId: string;
}

export interface Status {
  schema: number;
  generated: string;
  global_activity: Activity;
  max_probability: number | null;
  oval_etag: string | null;
  oval_observed: string | null;
  oval_forecast: string | null;
  space_updated: string | null;
  kp: { value: number; time: string } | null;
  solar_wind: { bz: number; bt: number; speed: number | null; time: string } | null;
  aurorawatch: { status: string; updated: string; site_id: string } | null;
}

/** The strongest cell anywhere on the globe, which drives the activity flag. */
export function maxProbability(packed: Uint8Array): number {
  let peak = 0;
  for (const cell of packed) {
    if (cell > peak) {
      peak = cell;
    }
  }
  return peak;
}

export function activityFor(peak: number): Activity {
  for (const [floor, activity] of ACTIVITY_THRESHOLDS) {
    if (peak >= floor) {
      return activity;
    }
  }
  return "none";
}

export function composeStatus(input: {
  space: SpacePartial | null;
  uk: UkPartial | null;
  now: string;
}): Status {
  const { space, uk, now } = input;

  return {
    schema: STATUS_SCHEMA,
    generated: now,
    // Without a grid there is no basis for claiming any activity level, and
    // "none" would wrongly tell devices there is nothing worth fetching.
    global_activity: space === null ? "unknown" : activityFor(space.maxProbability),
    max_probability: space?.maxProbability ?? null,
    oval_etag: space?.ovalEtag ?? null,
    oval_observed: space?.observationTime ?? null,
    oval_forecast: space?.forecastTime ?? null,
    space_updated: space?.updated ?? null,
    kp: space?.kp ?? null,
    solar_wind: space?.solarWind ?? null,
    aurorawatch:
      uk === null ? null : { status: uk.status, updated: uk.updated, site_id: uk.siteId },
  };
}
