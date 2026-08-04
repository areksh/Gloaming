import { describe, expect, it } from "vitest";
import { parseAuroraWatchStatus } from "../src/sources/aurorawatch";
import { parseKp, parseOvation, parseSolarWindMagField, parseSolarWindSpeed } from "../src/sources/swpc";

describe("parseOvation", () => {
  // SWPC spells the timestamp keys with spaces and capitals; the rest of the
  // Worker should never see that.
  it("maps SWPC's spaced key names onto the internal grid shape", () => {
    const grid = parseOvation({
      "Observation Time": "2026-08-04T21:26:00Z",
      "Forecast Time": "2026-08-04T22:32:00Z",
      "Data Format": "[Longitude, Latitude, Aurora]",
      coordinates: [[0, -90, 2]],
    });

    expect(grid.observationTime).toBe("2026-08-04T21:26:00Z");
    expect(grid.forecastTime).toBe("2026-08-04T22:32:00Z");
    expect(grid.coordinates).toEqual([[0, -90, 2]]);
  });

  it("rejects a response with no coordinates rather than packing nothing", () => {
    expect(() => parseOvation({ "Observation Time": "t", "Forecast Time": "t" })).toThrow(
      /coordinates/i,
    );
  });
});

describe("parseKp", () => {
  it("takes the most recent reading from the series", () => {
    const kp = parseKp([
      { time_tag: "2026-08-04T21:35:00", kp_index: 0, estimated_kp: 0, kp: "0Z" },
      { time_tag: "2026-08-04T21:36:00", kp_index: 4.33, estimated_kp: 4.33, kp: "4P" },
    ]);

    expect(kp).toEqual({ value: 4.33, time: "2026-08-04T21:36:00" });
  });

  it("returns null for an empty series rather than inventing a quiet reading", () => {
    expect(parseKp([])).toBeNull();
  });
});

describe("parseSolarWindMagField", () => {
  // Bz is the leading indicator the whole adaptive-polling design rests on: a
  // southward turn at L1 buys 30-60 minutes of warning.
  it("reads Bz and Bt from the summary product", () => {
    expect(
      parseSolarWindMagField([{ bt: 10.85, bz_gsm: -8.36, time_tag: "2026-08-04T21:33:00Z" }]),
    ).toEqual({ bz: -8.36, bt: 10.85, time: "2026-08-04T21:33:00Z" });
  });

  it("returns null when the product is empty", () => {
    expect(parseSolarWindMagField([])).toBeNull();
  });
});

describe("parseSolarWindSpeed", () => {
  it("reads proton speed from the summary product", () => {
    expect(parseSolarWindSpeed([{ proton_speed: 406, time_tag: "2026-08-04T21:35:00Z" }])).toEqual({
      speed: 406,
      time: "2026-08-04T21:35:00Z",
    });
  });
});

describe("parseAuroraWatchStatus", () => {
  const xml = `<?xml version='1.0' encoding='UTF-8' standalone='yes'?>
<!DOCTYPE current_status PUBLIC "-//AuroraWatch-API//DTD REST 0.2.5//EN" "http://aurorawatch-api.lancs.ac.uk/0.2.5/aurorawatch-api.dtd">
<current_status api_version="0.2.5"><updated><datetime>2026-08-04T21:39:32+0000</datetime></updated><site_status project_id="project:SAMNET" site_id="site:SAMNET:CRK2" site_url="http://aurorawatch-api.lancs.ac.uk/0.2.5/project/samnet/crk2.xml" status_id="green"/></current_status>`;

  it("reads the status level and update time from the v0.2 document", () => {
    expect(parseAuroraWatchStatus(xml)).toEqual({
      status: "green",
      updated: "2026-08-04T21:39:32+0000",
      siteId: "site:SAMNET:CRK2",
    });
  });

  it("accepts each documented alert level", () => {
    for (const level of ["green", "yellow", "amber", "red"]) {
      const doc = xml.replace('status_id="green"', `status_id="${level}"`);
      expect(parseAuroraWatchStatus(doc).status).toBe(level);
    }
  });

  // Their thresholds must not be second-guessed, so an unrecognised level is an
  // error rather than something quietly coerced to the nearest known one.
  it("rejects an unrecognised status level", () => {
    expect(() => parseAuroraWatchStatus(xml.replace('status_id="green"', 'status_id="chartreuse"'))).toThrow(
      /status/i,
    );
  });

  it("rejects a document with no site status", () => {
    expect(() => parseAuroraWatchStatus("<current_status/>")).toThrow(/status/i);
  });
});
