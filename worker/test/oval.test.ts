import { describe, expect, it } from "vitest";
import {
  GRID_LATITUDES,
  GRID_LONGITUDES,
  OVAL_GRID_BYTES,
  type OvationGrid,
  ovalIndex,
  packOvalGrid,
  readOvalCell,
} from "../src/oval";

/**
 * Builds a complete OVATION grid in the longitude-major order SWPC actually
 * serves: all 181 latitudes for longitude 0, then longitude 1, and so on.
 */
function buildGrid(value: (lon: number, lat: number) => number): OvationGrid {
  const coordinates: [number, number, number][] = [];
  for (let lon = 0; lon < GRID_LONGITUDES; lon++) {
    for (let lat = -90; lat <= 90; lat++) {
      coordinates.push([lon, lat, value(lon, lat)]);
    }
  }
  return {
    observationTime: "2026-08-04T21:26:00Z",
    forecastTime: "2026-08-04T22:32:00Z",
    coordinates,
  };
}

describe("ovalIndex", () => {
  it("lays the grid out row-major, one row per latitude from -90", () => {
    expect(ovalIndex(0, -90)).toBe(0);
    expect(ovalIndex(359, -90)).toBe(359);
    expect(ovalIndex(0, -89)).toBe(360);
    expect(ovalIndex(10, 55)).toBe((55 + 90) * 360 + 10);
    expect(ovalIndex(359, 90)).toBe(OVAL_GRID_BYTES - 1);
  });
});

describe("packOvalGrid", () => {
  it("packs one byte per cell for the whole globe", () => {
    const packed = packOvalGrid(buildGrid(() => 0));

    expect(packed).toBeInstanceOf(Uint8Array);
    expect(packed.byteLength).toBe(OVAL_GRID_BYTES);
    expect(OVAL_GRID_BYTES).toBe(GRID_LONGITUDES * GRID_LATITUDES);
  });

  it("reorders longitude-major input into latitude-major output", () => {
    // A value that is unique per cell, so a transposed or shifted write is
    // caught rather than coincidentally matching.
    const packed = packOvalGrid(buildGrid((lon, lat) => (lon + lat + 90) % 101));

    for (const [lon, lat] of [
      [0, -90],
      [10, 55],
      [200, -30],
      [359, 90],
    ] as const) {
      expect(readOvalCell(packed, lon, lat)).toBe((lon + lat + 90) % 101);
    }
  });

  it("round-trips every cell of a fully populated grid", () => {
    // Offset the latitude before multiplying: a negative intermediate would
    // stay negative through JavaScript's remainder operator and assert that an
    // impossible probability survives packing.
    const value = (lon: number, lat: number) => (lon * 7 + (lat + 90) * 13) % 101;
    const packed = packOvalGrid(buildGrid(value));

    for (let lon = 0; lon < GRID_LONGITUDES; lon++) {
      for (let lat = -90; lat <= 90; lat++) {
        if (readOvalCell(packed, lon, lat) !== value(lon, lat)) {
          throw new Error(`cell ${lon},${lat} did not round-trip`);
        }
      }
    }
  });

  it("clamps probabilities into the 0-100 a byte has to hold", () => {
    const packed = packOvalGrid(
      buildGrid((lon, lat) => (lon === 0 && lat === 0 ? 250 : lon === 1 && lat === 0 ? -5 : 0)),
    );

    expect(readOvalCell(packed, 0, 0)).toBe(100);
    expect(readOvalCell(packed, 1, 0)).toBe(0);
  });

  it("rounds fractional probabilities to the nearest whole percent", () => {
    const packed = packOvalGrid(
      buildGrid((lon, lat) => (lon === 0 && lat === 0 ? 12.6 : 0)),
    );

    expect(readOvalCell(packed, 0, 0)).toBe(13);
  });

  // A short read that still parsed as JSON would otherwise publish a grid that
  // is mostly zeros, which reads as "no aurora anywhere" and silently
  // suppresses every alert. Failing loudly is the only safe behaviour.
  it("rejects a grid that does not cover the globe", () => {
    const short = buildGrid(() => 0);
    short.coordinates.pop();

    expect(() => packOvalGrid(short)).toThrow(/65160/);
  });

  it("rejects a grid that repeats a cell instead of covering every one", () => {
    const duplicated = buildGrid(() => 0);
    duplicated.coordinates[0] = [5, 5, 1];
    duplicated.coordinates[1] = [5, 5, 1];

    expect(() => packOvalGrid(duplicated)).toThrow(/twice|duplicate/i);
  });

  it("rejects coordinates outside the grid", () => {
    const badLon = buildGrid(() => 0);
    badLon.coordinates[0] = [360, 0, 0];
    expect(() => packOvalGrid(badLon)).toThrow(/longitude/i);

    const badLat = buildGrid(() => 0);
    badLat.coordinates[0] = [0, 91, 0];
    expect(() => packOvalGrid(badLat)).toThrow(/latitude/i);
  });

  it("rejects a non-finite probability rather than writing a junk byte", () => {
    const nan = buildGrid(() => 0);
    nan.coordinates[0] = [0, -90, Number.NaN];

    expect(() => packOvalGrid(nan)).toThrow(/probability/i);
  });
});
