import { describe, expect, it } from "vitest";
import {
  OBSERVATION_WINDOW_DAYS,
  OBSERVATION_WINDOW_LADDER,
  brightestPossible,
  buildComets,
  cobsKey,
  parseCobsObservations,
  julianDayTt,
  mpcKey,
  parseCometEls,
  reduceObservations,
  type CobsObservation,
} from "../src/comets";

/**
 * Rows taken verbatim from `CometEls.txt` on 2026-08-25, not hand-aligned.
 * The format is fixed-width Fortran and a field counted one column out does not
 * fail - it yields a plausible orbit for the wrong comet - so the fixtures are
 * real lines and the column positions come from the MPC's own format document
 * rather than from counting a sample.
 */
const LEMMON = "    CK25A060  2025 11  8.5287  0.529829  0.995627  132.9564  108.0974  143.6635  20260825  13.2  4.0  C/2025 A6 (Lemmon)                                       MPEC 2026-P55";
const MCNAUGHT = "0220P         2026 06 14.1174  1.559302  0.500328  180.5616  150.0840    8.1208  20260825  15.0  4.0  220P/McNaught                                            MPEC 2026-Q97";
const HALE_BOPP = "    CJ95O010  1997 03 29.0340  0.924488  0.994899  130.7186  281.7973   89.7396  20260825  -2.0  4.0  C/1995 O1 (Hale-Bopp)                                    MPC194091";
const HARTLEY = "0161P         2026 11 27.1833  1.265135  0.836173   47.0655    1.4746   95.7915  20260825   8.5  6.0  161P/Hartley-IRAS                                        MPEC 2026-Q97";
const TEMPEL = "0010P         2026 08  2.1161  1.417738  0.537447  195.4690  117.7973   12.0272  20260825   5.0 10.0  10P/Tempel                                               MPEC 2026-Q97";
const HOWELL = "0088P         2026 03 18.7454  1.357739  0.563272  235.8553   56.6670    4.3819  20260825  11.0  6.0  88P/Howell                                               MPEC 2026-Q97";

const NOW = Date.parse("2026-08-25T12:00:00Z");

describe("parseCometEls", () => {
  it("reads the orbital elements from their documented columns", () => {
    const [comet] = parseCometEls(`${LEMMON}\n`);

    expect(comet?.q).toBe(0.529829);
    expect(comet?.e).toBe(0.995627);
    expect(comet?.peri).toBe(132.9564);
    expect(comet?.node).toBe(108.0974);
    expect(comet?.i).toBe(143.6635);
    expect(comet?.magnitude_h).toBe(13.2);
    expect(comet?.slope).toBe(4.0);
    expect(comet?.name).toBe("C/2025 A6 (Lemmon)");
  });

  /**
   * The perihelion date is Terrestrial Time and arrives as a calendar date, so
   * it has to become a Julian date before the solver can use it. This is the
   * conversion that has to be right: an hour of error moves a comet near the
   * Earth by an arcminute, which is the whole error budget.
   */
  it("turns the perihelion date into a Julian date in Terrestrial Time", () => {
    const [comet] = parseCometEls(`${LEMMON}\n`);

    expect(comet?.perihelion_jd_tt).toBeCloseTo(julianDayTt(2025, 11, 8.5287), 9);
  });

  it("keys a numbered comet by its number and a provisional one by its packed designation", () => {
    expect(parseCometEls(`${LEMMON}\n`)[0]?.key).toBe("K25A060");
    expect(parseCometEls(`${MCNAUGHT}\n`)[0]?.key).toBe("220P");
  });

  /**
   * A row the parser cannot make sense of costs that comet and nothing else.
   * The file carries 957 orbits from every era of observation, and taking the
   * whole list down over one malformed line would lose the comet somebody is
   * actually outside looking at.
   */
  it("skips a malformed row and keeps the rest", () => {
    const parsed = parseCometEls(`${LEMMON}\nnot a comet at all\n\n${MCNAUGHT}\n`);

    expect(parsed.map((comet) => comet.key)).toEqual(["K25A060", "220P"]);
  });

  /**
   * Orbit type A is an object that turned out to be an asteroid and D is one
   * lost or seen to disintegrate. Neither will be in anybody's sky, and both
   * would otherwise sit in the list indefinitely.
   */
  it("drops asteroids and defunct comets", () => {
    const asteroid = `0002A         ${LEMMON.slice(14)}`;
    const defunct = `0003D         ${LEMMON.slice(14)}`;

    expect(parseCometEls(`${asteroid}\n${defunct}\n`)).toHaveLength(0);
  });

  it("rejects elements that are not physically possible", () => {
    const negative = LEMMON.replace("  0.529829  0.995627", " -0.529829  0.995627");
    const sideways = LEMMON.replace("  143.6635", "  343.6635");

    expect(parseCometEls(`${negative}\n${sideways}\n`)).toHaveLength(0);
  });

  it("reads every row of the real file", () => {
    const file = [LEMMON, MCNAUGHT, HOWELL].join("\n");

    expect(parseCometEls(file)).toHaveLength(3);
  });
});

describe("julianDayTt", () => {
  it("puts J2000.0 where every ephemeris puts it", () => {
    expect(julianDayTt(2000, 1, 1.5)).toBe(2451545.0);
  });

  /**
   * Halley's 1986 perihelion as JPL Horizons states it - an independent number
   * rather than one this code produced.
   */
  it("agrees with Horizons on Halley's 1986 perihelion", () => {
    expect(julianDayTt(1986, 2, 9.459040325)).toBeCloseTo(2446470.959040325, 6);
  });

  /** January and February belong to the previous year in the standard formula. */
  it("handles the January and February shift", () => {
    expect(julianDayTt(2026, 1, 1.0) - julianDayTt(2025, 12, 31.0)).toBe(1);
    expect(julianDayTt(2026, 3, 1.0) - julianDayTt(2026, 2, 28.0)).toBe(1);
    expect(julianDayTt(2024, 3, 1.0) - julianDayTt(2024, 2, 29.0)).toBe(1);
  });
});

describe("mpcKey", () => {
  it("matches the identifier COBS reports", () => {
    expect(mpcKey("0220", "P", "")).toBe("220P");
    expect(mpcKey("0003", "I", "")).toBe("3I");
    expect(mpcKey("", "C", "K25A060")).toBe("K25A060");
  });

  /**
   * 141P has split into at least five pieces, each its own row, and the
   * fragment letter lives in the packed-designation columns that are otherwise
   * blank for a numbered comet. Ignoring it is not a cosmetic duplicate: every
   * fragment would share one key, so each would inherit whichever fragment's
   * observations were fetched, and the app would report one object's brightness
   * under another's name.
   */
  it("keeps the fragments of a split comet apart", () => {
    expect(mpcKey("0141", "P", "a")).toBe("141P-A");
    expect(mpcKey("0141", "P", "d")).toBe("141P-D");
    expect(mpcKey("0073", "P", "bt")).toBe("73P-BT");
    expect(mpcKey("0141", "P", "")).toBe("141P");
  });
});

describe("cobsKey", () => {
  /**
   * COBS puts a fragment letter in one of two places depending on the
   * designation: inside the packed name for a provisional comet, which already
   * matches what the MPC file carries, and only in `component` for a numbered
   * one. Appending in the first case would produce `K23R01b-B`, which matches
   * no MPC row at all.
   */
  it("agrees with mpcKey on both ways COBS names a fragment", () => {
    expect(cobsKey("73P", "BT")).toBe("73P-BT");
    expect(cobsKey("K23R01b", "B")).toBe("K23R01b");
    expect(cobsKey("220P", null)).toBe("220P");
    expect(cobsKey("K25A060", null)).toBe("K25A060");
  });
});

describe("parseCobsObservations", () => {
  const record = (over: Record<string, unknown> = {}) => ({
    magnitude: "9.4",
    obs_date: "2026-08-24 18:43:12",
    comet: { mpc_name: "220P", component: null },
    observer: { icq_name: "MIY01" },
    ...over,
  });

  it("groups reports by the comet they belong to", () => {
    const grouped = parseCobsObservations(
      JSON.stringify({
        objects: [
          record(),
          record({ comet: { mpc_name: "10P", component: null }, magnitude: "8.3" }),
          record({ observer: { icq_name: "SCH04" }, magnitude: "9.1" }),
        ],
      }),
    );

    expect(grouped.get("220P")).toHaveLength(2);
    expect(grouped.get("10P")).toHaveLength(1);
  });

  it("reads the observation time as UT", () => {
    const grouped = parseCobsObservations(JSON.stringify({ objects: [record()] }));

    expect(grouped.get("220P")?.[0]?.atMillis).toBe(Date.parse("2026-08-24T18:43:12Z"));
  });

  /**
   * The response covers every comet anybody reported in the window, so one bad
   * record should cost that record and not the fortnight.
   */
  it("skips records it cannot read and keeps the rest", () => {
    const grouped = parseCobsObservations(
      JSON.stringify({
        objects: [
          record({ magnitude: null }),
          record({ obs_date: "not a date" }),
          record({ comet: { mpc_name: "", component: null } }),
          record({ observer: {} }),
          record(),
        ],
      }),
    );

    expect(grouped.get("220P")).toHaveLength(1);
  });

  it("has nothing to say about a response it cannot parse at all", () => {
    expect(parseCobsObservations("not json").size).toBe(0);
    expect(parseCobsObservations(JSON.stringify({ objects: "no" })).size).toBe(0);
    expect(parseCobsObservations(JSON.stringify({})).size).toBe(0);
  });
});

/**
 * Reducing a pile of amateur reports to one magnitude.
 *
 * This decides what the app claims a comet looks like, and the live feed is the
 * reason it cannot be a maximum or a latest-wins. On 2026-08-25 a single COBS
 * observer was reporting C/2020 F3 (NEOWISE) at magnitude 1.2, six years after
 * it faded, and Bernardinelli-Bernstein - which is out beyond Saturn - at 0.5.
 * Either would have gone straight to the top of the calendar as the brightest
 * comet of the decade.
 *
 * Two rules answer it: the median ignores a wild value among many, and a
 * minimum number of independent observers ignores a wild observer.
 */
describe("reduceObservations", () => {
  const at = (iso: string, magnitude: number, observer: string): CobsObservation => ({
    magnitude,
    atMillis: Date.parse(iso),
    observer,
  });

  it("takes the median rather than the brightest", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-24T00:00:00Z", 10.4, "SCH04"),
        at("2026-08-24T01:00:00Z", 10.1, "YUS"),
        at("2026-08-24T02:00:00Z", 2.0, "SHIaf"),
        at("2026-08-24T03:00:00Z", 10.7, "MEY"),
        at("2026-08-24T04:00:00Z", 11.0, "DEK01"),
      ],
      NOW,
    );

    expect(reduced?.magnitude).toBe(10.4);
    expect(reduced?.reports).toBe(5);
    expect(reduced?.observers).toBe(5);
  });

  /**
   * The NEOWISE case exactly: one person, several reports, all wrong. Numbers
   * alone cannot tell that from a real comet only one person has looked at, so
   * the rule is that one person is never enough.
   */
  it("refuses a magnitude that rests on a single observer", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-22T04:00:00Z", 1.2, "SHIaf"),
        at("2026-08-22T03:00:00Z", 1.2, "SHIaf"),
        at("2026-08-22T02:00:00Z", 1.3, "SHIaf"),
        at("2026-08-22T01:00:00Z", 1.5, "SHIaf"),
        at("2026-08-22T00:00:00Z", 2.9, "SHIaf"),
        at("2026-08-21T23:00:00Z", 5.2, "SHIaf"),
        at("2026-08-21T22:00:00Z", 9.5, "SHIaf"),
      ],
      NOW,
    );

    expect(reduced).toBeNull();
  });

  /**
   * The NEOWISE case one step on: the wild observer keeps filing and a single
   * genuine report arrives beside it. A median over reports is dragged to the
   * wild value - seven of the eight numbers are one person's - so each observer
   * gets one voice, the median of their own reports, and the answer sits
   * between the two voices instead of being whichever of them filed more.
   */
  it("keeps a prolific observer from outvoting the field", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-25T06:00:00Z", 1.2, "SHIaf"),
        at("2026-08-25T05:00:00Z", 1.2, "SHIaf"),
        at("2026-08-25T04:00:00Z", 1.3, "SHIaf"),
        at("2026-08-25T03:00:00Z", 1.5, "SHIaf"),
        at("2026-08-25T02:00:00Z", 2.9, "SHIaf"),
        at("2026-08-25T01:00:00Z", 5.2, "SHIaf"),
        at("2026-08-25T00:00:00Z", 9.5, "SHIaf"),
        at("2026-08-25T07:00:00Z", 12.0, "MEY"),
      ],
      NOW,
    );

    // SHIaf's own median is 1.5 and MEY's is 12.0; the published figure is the
    // median of those two voices, not of the eight reports (which would be 2.2).
    expect(reduced?.magnitude).toBe(6.8);
    expect(reduced?.reports).toBe(8);
    expect(reduced?.observers).toBe(2);
  });

  it("reports when the most recent observation was made", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-20T00:00:00Z", 9.0, "AAA"),
        at("2026-08-24T18:00:00Z", 9.2, "BBB"),
        at("2026-08-22T00:00:00Z", 9.1, "CCC"),
      ],
      NOW,
    );

    expect(reduced?.atMillis).toBe(Date.parse("2026-08-24T18:00:00Z"));
  });

  it("has nothing to say when nobody has looked", () => {
    expect(reduceObservations([], NOW)).toBeNull();
  });

  /**
   * Reports older than the window are dropped before the median is taken.
   * Brightness is the one thing about a comet that changes fast, so a month-old
   * report of one in outburst is not evidence about tonight.
   */
  it("ignores observations from outside the window", () => {
    const reduced = reduceObservations(
      [
        at("2026-06-01T00:00:00Z", 2.0, "AAA"),
        at("2026-06-02T00:00:00Z", 2.0, "BBB"),
        at("2026-08-24T00:00:00Z", 9.0, "CCC"),
        at("2026-08-24T00:00:00Z", 9.2, "DDD"),
      ],
      NOW,
    );

    expect(reduced?.magnitude).toBe(9.1);
    expect(reduced?.reports).toBe(2);
  });

  it("takes the mean of the middle two when the count is even", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-24T00:00:00Z", 8.0, "AAA"),
        at("2026-08-24T00:00:00Z", 9.0, "BBB"),
        at("2026-08-24T00:00:00Z", 10.0, "CCC"),
        at("2026-08-24T00:00:00Z", 11.0, "DDD"),
      ],
      NOW,
    );

    expect(reduced?.magnitude).toBe(9.5);
  });

  it("ignores a report with no magnitude in it", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-24T00:00:00Z", Number.NaN, "AAA"),
        at("2026-08-24T00:00:00Z", 9.0, "BBB"),
        at("2026-08-24T00:00:00Z", 9.0, "CCC"),
      ],
      NOW,
    );

    expect(reduced?.magnitude).toBe(9.0);
    expect(reduced?.reports).toBe(2);
  });
});

/**
 * The brightest a comet could possibly look, used as the fetcher's coarse bar.
 *
 * An upper bound rather than an estimate, so the filter can never drop a comet
 * that turns out to be worth seeing: it puts the comet at perihelion and the
 * Earth at the closest the two orbits allow. The real magnitude on any given
 * night is fainter than this, and the device works that out for itself.
 */
/**
 * The window is a ladder, shortest rung first.
 *
 * The two jobs of this reduction pull against each other: rejecting a wild
 * observer wants many independent people and so a long window, while tracking a
 * comet that is genuinely changing wants a short one. A fixed fortnight chose
 * the first and lost the second - measured on 180 days of real COBS data, it
 * flattened a 3.8-magnitude outburst of 29P/Schwassmann-Wachmann into a
 * 0.1-magnitude wobble.
 */
describe("the observation window ladder", () => {
  const at = (iso: string, magnitude: number, observer: string): CobsObservation => ({
    magnitude,
    atMillis: Date.parse(iso),
    observer,
  });

  it("uses the shortest window four separate people looked in", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-25T01:00:00Z", 7.0, "AAA"),
        at("2026-08-25T02:00:00Z", 7.0, "BBB"),
        at("2026-08-25T03:00:00Z", 7.2, "CCC"),
        at("2026-08-25T04:00:00Z", 7.2, "DDD"),
        at("2026-08-14T00:00:00Z", 12.0, "EEE"),
        at("2026-08-14T01:00:00Z", 12.0, "FFF"),
      ],
      NOW,
    );

    expect(reduced?.windowDays).toBe(2);
    expect(reduced?.magnitude).toBe(7.1);
    expect(reduced?.reports).toBe(4);
  });

  /**
   * Two people are enough to believe a magnitude but not enough to justify
   * ignoring a fortnight of other reports. Individual estimates are far noisier
   * than they look - six observers put 10P/Tempel between 7.3 and 12.0 on one
   * night - so a pair falls back to a longer window rather than winning on
   * recency alone.
   */
  it("will not let a mere pair outvote a longer window", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-25T02:00:00Z", 7.0, "AAA"),
        at("2026-08-25T03:00:00Z", 7.2, "BBB"),
        at("2026-08-20T00:00:00Z", 12.0, "CCC"),
        at("2026-08-20T01:00:00Z", 12.0, "DDD"),
      ],
      NOW,
    );

    expect(reduced?.windowDays).toBe(7);
    expect(reduced?.observers).toBe(4);
  });

  /**
   * The whole point: a comet that has just brightened reports its new
   * brightness rather than a fortnight's average of the old one.
   */
  it("follows an outburst instead of averaging it away", () => {
    const quiet = Array.from({ length: 20 }, (_, i) =>
      at(`2026-08-${String(11 + Math.floor(i / 2)).padStart(2, "0")}T0${i % 2}:00:00Z`, 16.0, `OBS${i % 6}`));
    const outburst = [
      at("2026-08-25T01:00:00Z", 12.3, "AAA"),
      at("2026-08-25T02:00:00Z", 12.3, "BBB"),
      at("2026-08-25T03:00:00Z", 12.5, "CCC"),
      at("2026-08-25T04:00:00Z", 12.5, "DDD"),
    ];

    const reduced = reduceObservations([...quiet, ...outburst], NOW);

    expect(reduced?.magnitude).toBe(12.4);
    expect(reduced?.windowDays).toBe(2);
  });

  /**
   * A faint comet nobody looked at over a cloudy weekend still gets an answer,
   * from a longer rung. That is what the longer rungs are for.
   */
  it("reaches further back for a comet only two people have seen all fortnight", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-16T00:00:00Z", 14.0, "AAA"),
        at("2026-08-19T00:00:00Z", 14.4, "BBB"),
      ],
      NOW,
    );

    expect(reduced?.windowDays).toBe(14);
    expect(reduced?.magnitude).toBe(14.2);
  });

  /**
   * A short window must not become a back door around the observer rule. One
   * person reporting hourly still fails, however recent the reports are - which
   * is the NEOWISE case, and the reason the rule exists at all.
   */
  it("still refuses a single observer however fresh the reports", () => {
    const reduced = reduceObservations(
      [
        at("2026-08-25T04:00:00Z", 1.2, "SHIaf"),
        at("2026-08-25T03:00:00Z", 1.2, "SHIaf"),
        at("2026-08-25T02:00:00Z", 1.3, "SHIaf"),
        at("2026-08-25T01:00:00Z", 1.5, "SHIaf"),
      ],
      NOW,
    );

    expect(reduced).toBeNull();
  });

  it("climbs the ladder in order and never past the fetched window", () => {
    expect([...OBSERVATION_WINDOW_LADDER]).toEqual([2, 3, 5, 7, 14]);
    expect(OBSERVATION_WINDOW_DAYS).toBe(14);
    expect(Math.max(...OBSERVATION_WINDOW_LADDER)).toBe(OBSERVATION_WINDOW_DAYS);
  });
});

describe("brightestPossible", () => {
  it("uses the standard comet magnitude law", () => {
    const [lemmon] = parseCometEls(`${LEMMON}\n`);

    // H = 13.2, G = 4, q = 0.529829, so the closest the Earth can be is
    // 1 - q = 0.470171, and the law gives
    // 13.2 + 5 log10(0.470171) + 10 log10(0.529829) = 8.80.
    expect(brightestPossible(lemmon!)).toBeCloseTo(8.803, 3);
  });

  it("has no answer for a comet with no absolute magnitude", () => {
    const [lemmon] = parseCometEls(`${LEMMON}\n`);

    expect(brightestPossible({ ...lemmon!, magnitude_h: null })).toBeNull();
  });
});

describe("buildComets", () => {
  it("attaches observations to the comet they belong to", () => {
    const document = buildComets(
      parseCometEls(`${LEMMON}\n`),
      new Map([
        [
          "K25A060",
          [
            { magnitude: 5.8, atMillis: NOW - 86_400_000, observer: "AAA" },
            { magnitude: 6.0, atMillis: NOW - 86_400_000, observer: "BBB" },
          ],
        ],
      ]),
      NOW,
    );

    expect(document.comets).toHaveLength(1);
    expect(document.comets[0]?.observed?.magnitude).toBe(5.9);
    expect(document.comets[0]?.observed?.observers).toBe(2);
  });

  /**
   * A comet nobody has measured is still published, with its observation field
   * null. The app then falls back to the H and slope prediction and says that
   * is what it is doing, which is the honest version of a number routinely a
   * magnitude out.
   */
  it("publishes a comet with no observations at all", () => {
    const document = buildComets(parseCometEls(`${LEMMON}\n`), new Map(), NOW);

    expect(document.comets).toHaveLength(1);
    expect(document.comets[0]?.observed).toBeNull();
  });

  /**
   * The fetcher's bar sits far below any setting the app offers, so the device
   * does the filtering the user can see and change. All this cuts is the long
   * tail no telescope in a garden will reach.
   */
  it("leaves out comets far below anything a person could see", () => {
    const document = buildComets(parseCometEls(`${TEMPEL}\n${MCNAUGHT}\n`), new Map(), NOW);

    expect(document.comets.map((comet) => comet.key)).toEqual(["10P"]);
  });

  /**
   * Hale-Bopp has an absolute magnitude of -2, the brightest in the file, and
   * so clears any bar evaluated at perihelion. That perihelion was in March
   * 1997 and it is now somewhere past Neptune. Without the window it is
   * published every week forever, at the top of the list.
   */
  it("drops a comet whose perihelion was decades ago", () => {
    const document = buildComets(parseCometEls(`${HALE_BOPP}\n`), new Map(), NOW);

    expect(document.comets).toHaveLength(0);
  });

  /**
   * The case the whole COBS argument rests on. 220P/McNaught's elements predict
   * magnitude 15.7 at best; on 2026-08-25 thirteen observers had it at 9.3.
   * Filtering on the prediction drops the second-brightest comet in the sky.
   */
  it("keeps a comet the prediction calls faint and observers call bright", () => {
    const document = buildComets(
      parseCometEls(`${MCNAUGHT}\n`),
      new Map([["220P", [
        { magnitude: 9.2, atMillis: NOW - 3_600_000, observer: "AAA" },
        { magnitude: 9.4, atMillis: NOW - 3_600_000, observer: "BBB" },
      ]]]),
      NOW,
    );

    expect(document.comets[0]?.observed?.magnitude).toBe(9.3);
  });

  /**
   * And the other way round, which matters just as much. 161P/Hartley-IRAS is
   * predicted at 7.1 and was measured at 15.0 by nine people on the same day.
   * A prediction must not be able to overrule a measurement in either
   * direction, or the list fills with comets nobody can see.
   */
  it("drops a comet the prediction calls bright and observers call faint", () => {
    const document = buildComets(
      parseCometEls(`${HARTLEY}\n`),
      new Map([["161P", [
        { magnitude: 14.9, atMillis: NOW - 3_600_000, observer: "AAA" },
        { magnitude: 15.1, atMillis: NOW - 3_600_000, observer: "BBB" },
      ]]]),
      NOW,
    );

    expect(document.comets).toHaveLength(0);
  });

  /**
   * A measurement is evidence about now, so it stands on its own without the
   * perihelion window the prediction needs.
   */
  it("keeps a measured comet regardless of when its perihelion was", () => {
    const document = buildComets(
      parseCometEls(`${HALE_BOPP}\n`),
      new Map([["J95O010", [
        { magnitude: 9.0, atMillis: NOW - 3_600_000, observer: "AAA" },
        { magnitude: 9.0, atMillis: NOW - 3_600_000, observer: "BBB" },
      ]]]),
      NOW,
    );

    expect(document.comets).toHaveLength(1);
  });

  /**
   * A measured magnitude overrules the prediction in both directions. 220P went
   * from 18 to 8 in a week in May 2026, and a fetcher filtering on the predicted
   * number alone would have dropped it in exactly the week it mattered - which
   * is the entire argument for reading COBS at all.
   */
  it("keeps a comet whose measurements beat its prediction", () => {
    const document = buildComets(
      parseCometEls(`${MCNAUGHT}\n`),
      new Map([
        [
          "220P",
          [
            { magnitude: 8.2, atMillis: NOW - 3_600_000, observer: "AAA" },
            { magnitude: 8.4, atMillis: NOW - 3_600_000, observer: "BBB" },
          ],
        ],
      ]),
      NOW,
    );

    expect(document.comets).toHaveLength(1);
    expect(document.comets[0]?.observed?.magnitude).toBe(8.3);
  });

  it("stamps the document with its schema and the time it was built", () => {
    const document = buildComets(parseCometEls(`${LEMMON}\n`), new Map(), NOW);

    expect(document.schema).toBe(1);
    expect(document.updated).toBe(Math.floor(NOW / 1000));
  });

  it("puts the brightest comet first", () => {
    const document = buildComets(
      parseCometEls(`${TEMPEL}\n${MCNAUGHT}\n${HOWELL}\n`),
      new Map([
        ["220P", [
          { magnitude: 4.0, atMillis: NOW, observer: "AAA" },
          { magnitude: 4.0, atMillis: NOW, observer: "BBB" },
        ]],
        ["88P", [
          { magnitude: 6.0, atMillis: NOW, observer: "AAA" },
          { magnitude: 6.0, atMillis: NOW, observer: "BBB" },
        ]],
      ]),
      NOW,
    );

    expect(document.comets.map((comet) => comet.key)).toEqual(["220P", "88P", "10P"]);
  });

  it("ignores observations for a comet it has no elements for", () => {
    const document = buildComets(
      parseCometEls(`${LEMMON}\n`),
      new Map([["K20F030", [
        { magnitude: 1.2, atMillis: NOW, observer: "AAA" },
        { magnitude: 1.2, atMillis: NOW, observer: "BBB" },
      ]]]),
      NOW,
    );

    expect(document.comets.map((comet) => comet.key)).toEqual(["K25A060"]);
  });
});
