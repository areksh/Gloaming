/**
 * Parsers for the NOAA SWPC products.
 *
 * SWPC data is US Government public domain and is the unrestricted foundation
 * of the aurora feature: the OVATION grid works globally in both hemispheres,
 * with the geomagnetic field model already built in.
 *
 * Each parser is total: it either returns a value it can stand behind or
 * returns null / throws. Nothing here invents a quiet reading to paper over a
 * missing one, because "quiet" is indistinguishable from "working" downstream.
 */

import type { OvationGrid } from "../oval";

export interface KpReading {
  value: number;
  time: string;
}

export interface MagFieldReading {
  bz: number;
  bt: number;
  time: string;
}

export interface SolarWindSpeedReading {
  speed: number;
  time: string;
}

function asRecord(raw: unknown, what: string): Record<string, unknown> {
  if (typeof raw !== "object" || raw === null) {
    throw new Error(`${what} response was not an object`);
  }
  return raw as Record<string, unknown>;
}

/**
 * Latest-first is not guaranteed, and SWPC appends, so the last entry is the
 * newest. Products that are empty return null rather than throwing: a gap in a
 * supporting reading should degrade the status document, not fail the run.
 */
function lastEntry(raw: unknown, what: string): Record<string, unknown> | null {
  if (!Array.isArray(raw)) {
    throw new Error(`${what} response was not an array`);
  }
  const last = raw.at(-1);
  if (last === undefined) {
    return null;
  }
  return asRecord(last, what);
}

function numberOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/** Maps SWPC's spaced, capitalised key names onto the internal grid shape. */
export function parseOvation(raw: unknown): OvationGrid {
  const record = asRecord(raw, "OVATION");
  const coordinates = record["coordinates"];
  if (!Array.isArray(coordinates)) {
    throw new Error("OVATION response had no coordinates array");
  }

  return {
    observationTime: String(record["Observation Time"] ?? ""),
    forecastTime: String(record["Forecast Time"] ?? ""),
    coordinates: coordinates as [number, number, number][],
  };
}

export function parseKp(raw: unknown): KpReading | null {
  const entry = lastEntry(raw, "Kp");
  if (entry === null) {
    return null;
  }

  const value = numberOrNull(entry["kp_index"]);
  const time = entry["time_tag"];
  if (value === null || typeof time !== "string") {
    return null;
  }
  return { value, time };
}

export function parseSolarWindMagField(raw: unknown): MagFieldReading | null {
  const entry = lastEntry(raw, "Solar wind magnetic field");
  if (entry === null) {
    return null;
  }

  const bz = numberOrNull(entry["bz_gsm"]);
  const bt = numberOrNull(entry["bt"]);
  const time = entry["time_tag"];
  if (bz === null || bt === null || typeof time !== "string") {
    return null;
  }
  return { bz, bt, time };
}

export function parseSolarWindSpeed(raw: unknown): SolarWindSpeedReading | null {
  const entry = lastEntry(raw, "Solar wind speed");
  if (entry === null) {
    return null;
  }

  const speed = numberOrNull(entry["proton_speed"]);
  const time = entry["time_tag"];
  if (speed === null || typeof time !== "string") {
    return null;
  }
  return { speed, time };
}
