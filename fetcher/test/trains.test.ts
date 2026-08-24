import { describe, expect, it } from "vitest";
import type { ParsedSatellite } from "../src/celestrak";
import { detectTrains } from "../src/trains";

/**
 * Finding the launches that are still a train.
 *
 * A train is a launch whose satellites have not yet spread out: deployed
 * together, still low, still bunched along one arc of the orbit. All three
 * conditions are needed. Low and numerous but spread out is just a shell seen
 * edge on, and bunched but already raised is a launch that has finished.
 *
 * The bunching test is also what makes the feature expire on its own. Nothing
 * marks a train as over; it simply stops matching as the launch drifts apart,
 * which is exactly when there is no longer a train to see.
 */
describe("detectTrains", () => {
  const member = (
    catalogue: number,
    objectId: string,
    meanAnomaly: number,
    meanMotion = 15.9,
  ): ParsedSatellite => ({
    name: `STARLINK-${catalogue}`,
    catalogue,
    objectId,
    epoch: 26236.5,
    epoch_millis: Date.parse("2026-08-24T12:00:00.000Z"),
    mean_motion: meanMotion,
    eccentricity: 0.0001,
    inclination: 53.0,
    raan: 120.0,
    arg_perigee: 90.0,
    mean_anomaly: meanAnomaly,
    bstar: 0.0001,
    mean_motion_dot: 0.0001,
    magnitude: null,
  });

  /** A launch of `count` satellites strung along `spread` degrees from `first`. */
  const launch = (
    designator: string,
    catalogue: number,
    count: number,
    spread: number,
    meanMotion = 15.9,
    first = 100,
  ): ParsedSatellite[] =>
    Array.from({ length: count }, (_, i) =>
      member(
        catalogue + i,
        `${designator}${String.fromCharCode(65 + (i % 26))}${i >= 26 ? "A" : ""}`,
        (first + (spread * i) / Math.max(count - 1, 1)) % 360,
        meanMotion,
      ),
    );

  it("finds a launch that is still low and still bunched", () => {
    const trains = detectTrains(launch("2026-123", 100200, 22, 12));

    expect(trains).toHaveLength(1);
    expect(trains[0]?.launch).toBe("2026-123");
    expect(trains[0]?.members).toBe(22);
  });

  /**
   * The satellite furthest ahead along the orbit crosses the horizon first, so
   * it is the one the description names as the front of the train.
   */
  it("names the satellite in front as the lead and the one behind as the tail", () => {
    const [train] = detectTrains(launch("2026-123", 100200, 10, 9));

    expect(train?.tail).toBe(100200);
    expect(train?.lead).toBe(100209);
  });

  it("works out front and back across the wrap at 360 degrees", () => {
    const [train] = detectTrains(launch("2026-123", 100200, 10, 18, 15.9, 352));

    expect(train?.tail).toBe(100200);
    expect(train?.lead).toBe(100209);
  });

  it("ignores a launch that has spread out around the orbit", () => {
    expect(detectTrains(launch("2026-123", 100200, 22, 300))).toEqual([]);
  });

  it("ignores a launch already raised to its operational shell", () => {
    expect(detectTrains(launch("2026-123", 100200, 22, 12, 15.06))).toEqual([]);
  });

  it("ignores a handful of objects that happen to be close together", () => {
    expect(detectTrains(launch("2026-123", 100200, 5, 4))).toEqual([]);
  });

  it("keeps the trains apart when two launches are up at once", () => {
    const trains = detectTrains([
      ...launch("2026-123", 100200, 22, 12),
      ...launch("2026-124", 100300, 19, 8, 15.9, 200),
      // Raised, spread out, and the same size as the others.
      ...launch("2026-100", 100100, 20, 280, 15.05),
    ]);

    expect(trains.map((train) => train.launch)).toEqual(["2026-123", "2026-124"]);
    expect(trains[1]?.members).toBe(19);
  });

  /**
   * SupGP is owner-supplied and its columns are not guaranteed to match GP's.
   * A document without international designators yields no trains rather than
   * grouping everything under one nonexistent launch.
   */
  it("finds nothing when the document carries no international designators", () => {
    const anonymous = launch("2026-123", 100200, 22, 12).map((s) => ({ ...s, objectId: null }));

    expect(detectTrains(anonymous)).toEqual([]);
  });

  it("ignores an international designator it cannot read", () => {
    const odd = launch("2026-123", 100200, 22, 12).map((s) => ({ ...s, objectId: "TBA" }));

    expect(detectTrains(odd)).toEqual([]);
  });
});
