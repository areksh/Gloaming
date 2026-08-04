import { describe, expect, it } from "vitest";
import { activityFor, composeStatus, maxProbability, STATUS_SCHEMA } from "../src/status";
import { OVAL_GRID_BYTES, ovalIndex } from "../src/oval";

function gridWithPeak(peak: number): Uint8Array {
  const packed = new Uint8Array(OVAL_GRID_BYTES);
  packed[ovalIndex(10, 60)] = peak;
  return packed;
}

const space = {
  updated: "2026-08-04T21:30:00Z",
  observationTime: "2026-08-04T21:26:00Z",
  forecastTime: "2026-08-04T22:32:00Z",
  ovalEtag: '"abc123"',
  maxProbability: 42,
  kp: { value: 4.33, time: "2026-08-04T21:36:00Z" },
  solarWind: { bz: -8.36, bt: 10.85, speed: 406, time: "2026-08-04T21:33:00Z" },
};

const uk = {
  updated: "2026-08-04T21:39:32+0000",
  status: "amber",
  siteId: "site:SAMNET:CRK2",
};

describe("maxProbability", () => {
  it("finds the strongest cell anywhere on the globe", () => {
    expect(maxProbability(gridWithPeak(73))).toBe(73);
  });

  it("is zero for an entirely quiet grid", () => {
    expect(maxProbability(new Uint8Array(OVAL_GRID_BYTES))).toBe(0);
  });
});

describe("activityFor", () => {
  it("buckets the peak probability so a device can skip fetching the grid", () => {
    expect(activityFor(0)).toBe("none");
    expect(activityFor(5)).toBe("none");
    expect(activityFor(10)).toBe("low");
    expect(activityFor(30)).toBe("moderate");
    expect(activityFor(60)).toBe("high");
    expect(activityFor(100)).toBe("high");
  });
});

describe("composeStatus", () => {
  // The 3-minute AuroraWatch cron and the 5-minute SWPC cron both contribute to
  // status.json. Composing from stored partials is what stops the faster one
  // republishing a stale copy of the other's fields.
  it("combines both sources into one document", () => {
    const status = composeStatus({ space, uk, now: "2026-08-04T21:40:00Z" });

    expect(status.schema).toBe(STATUS_SCHEMA);
    expect(status.generated).toBe("2026-08-04T21:40:00Z");
    expect(status.oval_etag).toBe('"abc123"');
    expect(status.global_activity).toBe("moderate");
    expect(status.max_probability).toBe(42);
    expect(status.kp).toEqual({ value: 4.33, time: "2026-08-04T21:36:00Z" });
    expect(status.solar_wind).toEqual({
      bz: -8.36,
      bt: 10.85,
      speed: 406,
      time: "2026-08-04T21:33:00Z",
    });
    expect(status.aurorawatch).toEqual({
      status: "amber",
      updated: "2026-08-04T21:39:32+0000",
      site_id: "site:SAMNET:CRK2",
    });
  });

  it("keeps the space-weather fields when the UK partial is missing", () => {
    const status = composeStatus({ space, uk: null, now: "2026-08-04T21:40:00Z" });

    expect(status.aurorawatch).toBeNull();
    expect(status.kp?.value).toBe(4.33);
    expect(status.global_activity).toBe("moderate");
  });

  it("keeps the UK fields when the space-weather partial is missing", () => {
    const status = composeStatus({ space: null, uk, now: "2026-08-04T21:40:00Z" });

    expect(status.aurorawatch?.status).toBe("amber");
    expect(status.kp).toBeNull();
    expect(status.solar_wind).toBeNull();
    expect(status.oval_etag).toBeNull();
    // With no grid there is no basis for claiming any activity level.
    expect(status.global_activity).toBe("unknown");
  });

  it("produces a usable document when neither partial exists yet", () => {
    const status = composeStatus({ space: null, uk: null, now: "2026-08-04T21:40:00Z" });

    expect(status.schema).toBe(STATUS_SCHEMA);
    expect(status.global_activity).toBe("unknown");
    expect(status.aurorawatch).toBeNull();
  });

  // The app displays data age prominently, so every component carries the time
  // it was actually observed rather than the time it was republished.
  it("carries each source's own observation time, not the composition time", () => {
    const status = composeStatus({ space, uk, now: "2026-08-04T21:40:00Z" });

    expect(status.oval_observed).toBe("2026-08-04T21:26:00Z");
    expect(status.oval_forecast).toBe("2026-08-04T22:32:00Z");
    expect(status.space_updated).toBe("2026-08-04T21:30:00Z");
  });
});
