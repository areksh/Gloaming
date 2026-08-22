import { describe, expect, it } from "vitest";
import { parseAuroraWatchStatus } from "../src/sources/aurorawatch";
import { buildSatellites, parseOmmCsv } from "../src/sources/celestrak";
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

/**
 * CelesTrak publishes OMM in CSV. The app never sees any of this: the Worker
 * normalises it into `sats.json`, so the column mapping and the epoch
 * conversion are tested here in TypeScript rather than on a phone.
 */
describe("parseOmmCsv", () => {
  const HEADER =
    "OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE," +
    "ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID," +
    "ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT";
  const ISS =
    "ISS (ZARYA),1998-067A,2021-11-16T12:28:09.322176,15.48582035,.0004694,51.6447," +
    "309.4881,203.6966,299.8876,0,U,25544,999,31220,.31985E-4,.1288E-4,0";

  it("maps the named columns onto the element fields", () => {
    const [iss] = parseOmmCsv(`${HEADER}\n${ISS}\n`);

    expect(iss?.name).toBe("ISS (ZARYA)");
    expect(iss?.catalogue).toBe(25544);
    expect(iss?.mean_motion).toBe(15.48582035);
    expect(iss?.eccentricity).toBe(0.0004694);
    expect(iss?.inclination).toBe(51.6447);
    expect(iss?.raan).toBe(309.4881);
    expect(iss?.arg_perigee).toBe(203.6966);
    expect(iss?.mean_anomaly).toBe(299.8876);
    expect(iss?.bstar).toBe(0.000031985);
    expect(iss?.mean_motion_dot).toBe(0.00001288);
  });

  /**
   * SGP4 wants the TLE's packed `YYDDD.dddddd`; OMM gives ISO-8601. Doing the
   * conversion here keeps date arithmetic out of an app whose minSdk predates
   * `java.time`, and a pass 40 seconds out is the classic symptom of getting it
   * wrong.
   */
  it("packs the ISO epoch into the propagator's YYDDD.dddddd form", () => {
    const [iss] = parseOmmCsv(`${HEADER}\n${ISS}\n`);

    // 16 November 2021 is day 320; 12:28:09.322176 is 0.51955234 of a day.
    expect(iss?.epoch).toBeCloseTo(21320.51955234, 8);
    expect(iss?.epoch_millis).toBe(Date.parse("2021-11-16T12:28:09.322Z"));
  });

  it("counts the leap day when packing an epoch", () => {
    const row = ISS.replace("2021-11-16T12:28:09.322176", "2024-03-01T00:00:00.000000");

    const [sat] = parseOmmCsv(`${HEADER}\n${row}\n`);

    // 2024 is a leap year: 31 + 29 + 1 = day 61, not 60.
    expect(sat?.epoch).toBeCloseTo(24061.0, 8);
  });

  it("packs the first and last instants of a year", () => {
    const first = ISS.replace("2021-11-16T12:28:09.322176", "2026-01-01T00:00:00.000000");
    const last = ISS.replace("2021-11-16T12:28:09.322176", "2026-12-31T23:59:59.999999");

    expect(parseOmmCsv(`${HEADER}\n${first}\n`)[0]?.epoch).toBeCloseTo(26001.0, 8);
    expect(parseOmmCsv(`${HEADER}\n${last}\n`)[0]?.epoch).toBeCloseTo(26365.99999999, 7);
  });

  /**
   * The reason the app is on OMM at all: the public catalogue passed 100000 in
   * July 2026 and GP data is not published in TLE format for those objects.
   */
  it("accepts a six-digit catalogue number", () => {
    const row = ISS.replace(",25544,", ",100123,");

    expect(parseOmmCsv(`${HEADER}\n${row}\n`)[0]?.catalogue).toBe(100123);
  });

  it("follows the header rather than a fixed column order", () => {
    const reordered = "NORAD_CAT_ID,OBJECT_NAME,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION," +
      "RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,BSTAR,MEAN_MOTION_DOT";
    const row = "25544,ISS (ZARYA),2021-11-16T12:28:09.322176,15.48582035,.0004694,51.6447," +
      "309.4881,203.6966,299.8876,.31985E-4,.1288E-4";

    const [iss] = parseOmmCsv(`${reordered}\n${row}\n`);

    expect(iss?.catalogue).toBe(25544);
    expect(iss?.name).toBe("ISS (ZARYA)");
  });

  it("rejects a document missing a column the propagator needs", () => {
    const without = HEADER.replace(",BSTAR", ",NOT_BSTAR");

    expect(() => parseOmmCsv(`${without}\n${ISS}\n`)).toThrow(/BSTAR/);
  });

  // Silently dropping a row is how the ISS goes missing for a night with no
  // explanation. A malformed row means the format moved: fail, and let
  // sats.json keep serving its last good copy.
  it("rejects a row whose numbers do not parse", () => {
    const row = ISS.replace(",15.48582035,", ",n/a,");

    expect(() => parseOmmCsv(`${HEADER}\n${row}\n`)).toThrow(/MEAN_MOTION/);
  });

  it("rejects an empty document rather than publishing no satellites", () => {
    expect(() => parseOmmCsv(`${HEADER}\n`)).toThrow(/no satellites/i);
  });

  it("reads a name containing a comma", () => {
    const row = `"OBJECT, RESIDUE",${ISS.split(",").slice(1).join(",")}`;

    expect(parseOmmCsv(`${HEADER}\n${row}\n`)[0]?.name).toBe("OBJECT, RESIDUE");
  });

  it("ignores blank lines and carriage returns", () => {
    const document = `${HEADER}\r\n${ISS}\r\n\r\n`;

    expect(parseOmmCsv(document)).toHaveLength(1);
  });
});

describe("buildSatellites", () => {
  const iss = {
    name: "ISS (ZARYA)",
    catalogue: 25544,
    epoch: 21320.51955234,
    epoch_millis: 1637065689322,
    mean_motion: 15.48582035,
    eccentricity: 0.0004694,
    inclination: 51.6447,
    raan: 309.4881,
    arg_perigee: 203.6966,
    mean_anomaly: 299.8876,
    bstar: 0.000031985,
    mean_motion_dot: 0.00001288,
    magnitude: null,
  };
  const other = { ...iss, name: "COSMOS 1220", catalogue: 12345 };

  // The ISS is in both `stations` and `visual`, so the two groups always
  // overlap.
  it("keeps one entry per catalogue number", () => {
    const document = buildSatellites([iss, other, iss], new Date("2026-08-22T12:00:00Z"));

    expect(document.satellites).toHaveLength(2);
    expect(document.updated).toBe("2026-08-22T12:00:00.000Z");
  });

  it("orders by catalogue number so the document is stable", () => {
    const document = buildSatellites([iss, other], new Date("2026-08-22T12:00:00Z"));

    expect(document.satellites.map((s) => s.catalogue)).toEqual([12345, 25544]);
  });

  // A number nobody has stood behind is worse than no number: the app
  // describes passes qualitatively and never prints a magnitude it guessed.
  it("attaches a standard magnitude only where one is known", () => {
    const document = buildSatellites([iss, other], new Date("2026-08-22T12:00:00Z"));

    expect(document.satellites.find((s) => s.catalogue === 25544)?.magnitude).toBe(-1.8);
    expect(document.satellites.find((s) => s.catalogue === 12345)?.magnitude).toBeNull();
  });
});
