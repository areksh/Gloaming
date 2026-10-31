import { beforeEach, describe, expect, it } from "vitest";
import { breakerKey, readBreaker } from "../src/breaker";
import type { SourceHealth } from "../src/health";
import { CELESTRAK_SOURCE, run } from "../src/run";
import type { ObjectStore, PutOptions } from "../src/store";

/**
 * The run, against an in-memory bucket.
 *
 * This is the same contract the Worker's pipeline had, verified again because
 * the storage underneath it changed from a binding to signed HTTP. The parser
 * itself is shared source and is tested once, in the Worker's suite.
 */
class MemoryStore implements ObjectStore {
  readonly objects = new Map<string, { body: string; options: PutOptions }>();

  async get(key: string): Promise<string | null> {
    return this.objects.get(key)?.body ?? null;
  }

  async put(key: string, body: string, options: PutOptions = {}): Promise<void> {
    this.objects.set(key, { body, options });
  }

  async delete(key: string): Promise<void> {
    this.objects.delete(key);
  }
}

const HEADER =
  "OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE," +
  "ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID," +
  "ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT";

const row = (name: string, catalogue: number) =>
  `${name},1998-067A,2026-08-22T01:10:41.579040,15.49562116,.00076901,51.6330,334.1166,` +
  `71.1509,289.0313,0,U,${catalogue},999,58196,.16914294E-3,.9068E-4,0`;

const config = {
  stationsUrl: "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=CSV",
  visualUrl: "https://celestrak.org/NORAD/elements/gp.php?GROUP=visual&FORMAT=CSV",
  starlinkUrl:
    "https://celestrak.org/NORAD/elements/supplemental/sup-gp.php?FILE=starlink&FORMAT=csv",
  userAgent: "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)",
};

const now = () => new Date("2026-08-22T18:00:00Z");

/** Answers CelesTrak with `status`, and records everything asked for. */
function network(status: number, bodies?: Record<string, string>) {
  const requests: { url: string; redirect?: RequestInit["redirect"]; agent?: string }[] = [];
  const pushes: { url: string; title: string; body: string }[] = [];

  const fetcher = (async (url: RequestInfo | URL, init?: RequestInit) => {
    const target = String(url);
    if (target.includes("ntfy")) {
      pushes.push({
        url: target,
        title: new Headers(init?.headers).get("Title") ?? "",
        body: String(init?.body ?? ""),
      });
      return new Response("ok");
    }
    requests.push({
      url: target,
      redirect: init?.redirect,
      agent: new Headers(init?.headers).get("User-Agent") ?? undefined,
    });
    const group = target.includes("supplemental")
      ? "starlink"
      : target.includes("stations")
        ? "stations"
        : "visual";
    return new Response(bodies?.[group] ?? "", { status });
  }) as unknown as typeof fetch;

  return {
    requests,
    pushes,
    alerting: { baseUrl: "https://ntfy.example", topic: "secret-topic", fetch: fetcher },
  };
}

/** One Starlink of a launch: low, and at a stated place along the orbit. */
const starlinkRow = (catalogue: number, launch: string, meanAnomaly: number, meanMotion = 15.9) =>
  `STARLINK-${catalogue},${launch},2026-08-22T01:10:41.579040,${meanMotion},.00010000,53.0000,` +
  `120.0000,90.0000,${meanAnomaly.toFixed(4)},0,U,${catalogue},999,58196,.16914294E-3,.9068E-4,0`;

/** Eight satellites of one launch, bunched along nine degrees of the orbit. */
const train = Array.from({ length: 8 }, (_, i) =>
  starlinkRow(101000 + i, `2026-123${String.fromCharCode(65 + i)}`, 100 + i * 1.3),
).join("\n");

const groups = {
  stations: `${HEADER}\n${row("ISS (ZARYA)", 25544)}\n`,
  visual: `${HEADER}\n${row("ISS (ZARYA)", 25544)}\n${row("COSMOS 1220", 12345)}\n`,
  starlink: `${HEADER}\n${train}\n`,
};

const health = async (store: MemoryStore): Promise<SourceHealth> =>
  JSON.parse((await store.get(`internal/health/${CELESTRAK_SOURCE}.json`))!) as SourceHealth;

describe("run", () => {
  let store: MemoryStore;

  beforeEach(() => {
    store = new MemoryStore();
  });

  it("publishes both groups as one deduplicated document", async () => {
    const net = network(200, groups);

    const result = await run(store, net.alerting, config, now);

    expect(result).toEqual({ published: true, satellites: 4, trains: 1 });
    const published = JSON.parse(store.objects.get("v1/sats.json")!.body);
    // The two groups, plus the two ends of the train and nothing between them.
    expect(published.satellites.map((s: { catalogue: number }) => s.catalogue)).toEqual([
      12345, 25544, 101000, 101007,
    ]);
    expect(published.updated).toBe("2026-08-22T18:00:00.000Z");
  });

  /**
   * The Worker is never in the read path, so the CDN only caches what was set
   * at PUT time. Losing this header would put every device's poll back on R2.
   */
  it("sets the published caching contract at PUT time", async () => {
    const net = network(200, groups);

    await run(store, net.alerting, config, now);

    expect(store.objects.get("v1/sats.json")!.options).toEqual({
      contentType: "application/json",
      cacheControl: "public, max-age=1800",
    });
  });

  it("names the app in the User-Agent and does not follow redirects", async () => {
    const net = network(200, groups);

    await run(store, net.alerting, config, now);

    expect(net.requests).toHaveLength(3);
    expect(net.requests.every((r) => r.agent === config.userAgent)).toBe(true);
    expect(net.requests.every((r) => r.redirect === "manual")).toBe(true);
  });

  it("records a success where the Worker composes health from", async () => {
    const net = network(200, groups);

    await run(store, net.alerting, config, now);

    const recorded = await health(store);
    expect(recorded.state).toBe("ok");
    expect(recorded.last_success).toBe("2026-08-22T18:00:00.000Z");
    expect(recorded.consecutive_failures).toBe(0);
  });

  /**
   * The policy's one hard requirement. Requesting the second group in parallel
   * would put a request on the wire that the first response had already
   * forbidden.
   */
  it("does not fetch the second group once the first trips the breaker", async () => {
    const net = network(522);

    const result = await run(store, net.alerting, config, now);

    expect(result.published).toBe(false);
    expect(result.error).toContain("522");
    expect(net.requests).toHaveLength(1);
    expect((await readBreaker(store, CELESTRAK_SOURCE))?.status).toBe(522);
  });

  it("makes no request at all while the breaker is tripped", async () => {
    await store.put(
      breakerKey(CELESTRAK_SOURCE),
      JSON.stringify({
        source: CELESTRAK_SOURCE,
        status: 403,
        url: config.stationsUrl,
        note: "the data has not updated since the last successful download",
        tripped_at: "2026-08-22T12:00:00.000Z",
      }),
    );
    const net = network(200, groups);

    const result = await run(store, net.alerting, config, now);

    expect(result.published).toBe(false);
    expect(net.requests).toHaveLength(0);
    expect(net.pushes).toHaveLength(1); // the source failing, not a second trip
  });

  // Elements stay usable for days, so a tripped breaker degrades pass
  // prediction rather than breaking it.
  it("leaves the last good document in place when a run fails", async () => {
    await run(store, network(200, groups).alerting, config, now);
    const before = store.objects.get("v1/sats.json")!.body;

    await run(store, network(500).alerting, config, now);

    expect(store.objects.get("v1/sats.json")!.body).toBe(before);
  });

  /**
   * Two pushes at the moment of a trip, saying different things: what CelesTrak
   * returned, and that the source has gone down. Nothing after that, however
   * long it stays down.
   */
  it("alerts on the edge into failure and not again", async () => {
    const first = network(500);
    await run(store, first.alerting, config, now);
    expect(first.pushes.map((p) => p.title)).toEqual([
      "Gloaming: celestrak breaker tripped",
      "Gloaming: celestrak is failing",
    ]);

    const second = network(500);
    await run(store, second.alerting, config, now);

    expect(second.pushes).toHaveLength(0);
    expect((await health(store)).consecutive_failures).toBe(2);
  });

  /**
   * A trip clears itself two days on if nobody has cleared it by hand. One
   * request is let through; a 200 clears the flag, and anything else trips it
   * afresh for another two days. Elements stay usable for days, so two days is
   * the gap a human gets to look before another request leaves.
   */
  describe("two days after a trip", () => {
    const at = (iso: string) => () => new Date(iso);
    const trippedAt = at("2026-08-20T18:00:00.000Z"); // exactly two days before now()

    it("still refuses a moment before two days have passed", async () => {
      await run(store, network(503).alerting, config, at("2026-08-20T18:00:00.001Z"));
      const net = network(200, groups);

      const result = await run(store, net.alerting, config, now);

      expect(result.published).toBe(false);
      expect(net.requests).toHaveLength(0);
    });

    it("tries again, and clears itself on a 200", async () => {
      await run(store, network(503).alerting, config, trippedAt);
      const net = network(200, groups);

      const result = await run(store, net.alerting, config, now);

      expect(result.published).toBe(true);
      expect(net.requests).toHaveLength(3);
      expect(await readBreaker(store, CELESTRAK_SOURCE)).toBeNull();
      expect(net.pushes.map((p) => p.title)).toEqual(["Gloaming: celestrak recovered"]);
    });

    it("trips for another two days when the retry is refused", async () => {
      await run(store, network(503).alerting, config, trippedAt);

      const retry = network(404, groups);
      await run(store, retry.alerting, config, now);

      expect(retry.requests).toHaveLength(1);
      expect(await readBreaker(store, CELESTRAK_SOURCE)).toMatchObject({
        status: 404,
        tripped_at: "2026-08-22T18:00:00.000Z",
      });

      const tooSoon = network(200, groups);
      await run(store, tooSoon.alerting, config, at("2026-08-24T17:59:59.999Z"));
      expect(tooSoon.requests).toHaveLength(0);

      const nextRetry = network(200, groups);
      await run(store, nextRetry.alerting, config, at("2026-08-24T18:00:00.000Z"));
      expect(nextRetry.requests).toHaveLength(3);
    });

    /**
     * One push per refused retry: what came back this time, and when the next
     * attempt is. The source is already failing, so that alert stays quiet.
     */
    it("says the retry was refused, and when the next one is", async () => {
      await run(store, network(503).alerting, config, trippedAt);

      const retry = network(404, groups);
      await run(store, retry.alerting, config, now);

      expect(retry.pushes.map((p) => p.title)).toEqual([
        "Gloaming: celestrak breaker tripped again",
      ]);
      expect(retry.pushes[0]!.body).toContain("HTTP 404");
      expect(retry.pushes[0]!.body).toContain("2026-08-24T18:00:00.000Z");
    });
  });

  it("says in the trip alert when the breaker will clear itself", async () => {
    const net = network(503);

    await run(store, net.alerting, config, now);

    expect(net.pushes[0]!.body).toContain("2026-08-24T18:00:00.000Z");
  });

  it("says so when a failing source starts publishing again", async () => {
    await run(store, network(500).alerting, config, now);
    store.objects.delete(breakerKey(CELESTRAK_SOURCE)); // the manual reset

    const net = network(200, groups);
    await run(store, net.alerting, config, now);

    expect(net.pushes.map((p) => p.title)).toEqual(["Gloaming: celestrak recovered"]);
    expect((await health(store)).state).toBe("ok");
  });

  /**
   * The last point at which a nested reason can reach a human. An error that
   * carries its detail in `cause` - which is how Node reports every network
   * fault, and how anything wrapping one passes it on - would otherwise be
   * recorded as whatever bare sentence sat on top of it.
   */
  it("records the reason under an error, not just the top of it", async () => {
    const broken: ObjectStore = {
      get: async () => null,
      put: async () => {
        throw new Error("R2 PUT failed", { cause: new Error("the signing key was rejected") });
      },
      delete: async () => {},
    };
    const net = network(200, groups);

    const result = await run(broken, net.alerting, config, now);

    expect(result.error).toContain("the signing key was rejected");
  });

  /**
   * A run can fail *because* R2 is unreachable, in which case writing the
   * health record fails too. The original problem is the one a human needs to
   * read; losing it behind a complaint about bookkeeping would send them
   * looking in the wrong place.
   */
  it("still reports the original failure when it cannot record it", async () => {
    const broken: ObjectStore = {
      get: async () => {
        throw new Error("R2 GET returned 400");
      },
      put: async () => {
        throw new Error("R2 PUT returned 400");
      },
      delete: async () => {
        throw new Error("R2 DELETE returned 400");
      },
    };
    const net = network(200, groups);

    const result = await run(broken, net.alerting, config, now);

    expect(result.published).toBe(false);
    expect(result.error).toContain("R2 GET returned 400");
    expect(result.error).toContain("could not be written either");
    // And it got nowhere near CelesTrak. The breaker flag is read before
    // anything leaves for them, so a run that cannot reach R2 - or cannot even
    // sign the request - has spent none of the two-hourly allowance and can be
    // retried straight away. That property is the difference between "fix it
    // and run again" and "fix it and wait two hours".
    expect(net.requests).toHaveLength(0);
  });

  /**
   * A refused connection is not a response, so it is not the signal the policy
   * is about. A transient network fault must not need a human before elements
   * can refresh again.
   *
   * The failure is thrown in the shape Node really throws it: the reason is
   * nested in `cause` and the message on top is the same `fetch failed` for
   * every network fault there is. Reported as it arrives it says nothing - not
   * which host, not what happened - and on the night CelesTrak went down that
   * was the whole of the health record.
   */
  it("says which URL could not be reached and why", async () => {
    const fetcher = (async (url: RequestInfo | URL) => {
      if (String(url).includes("ntfy")) return new Response("ok");
      throw new TypeError("fetch failed", {
        cause: Object.assign(new Error("connect ECONNREFUSED 104.168.149.178:443"), {
          code: "ECONNREFUSED",
        }),
      });
    }) as unknown as typeof fetch;

    const result = await run(
      store,
      { baseUrl: "https://ntfy.example", topic: "t", fetch: fetcher },
      config,
      now,
    );

    expect(result.error).toBe(
      `could not reach ${config.stationsUrl}: connect ECONNREFUSED 104.168.149.178:443`,
    );
    expect(await readBreaker(store, CELESTRAK_SOURCE)).toBeNull();
    expect((await health(store)).state).toBe("failing");
    // And the maintainer is told the same thing, rather than "fetch failed".
    expect((await health(store)).last_error).toContain("ECONNREFUSED");
  });

  /**
   * The supplemental list is asked for last, so a breaker already tripped by
   * one of the core groups stops it before it goes on the wire.
   */
  it("does not reach the supplemental list once the breaker has tripped", async () => {
    const net = network(404, groups);

    await run(store, net.alerting, config, now);

    expect(net.requests.some((r) => r.url.includes("supplemental"))).toBe(false);
  });

  /**
   * Trains are an addition; the elements the app needs to work are not. A
   * supplemental document whose format has moved should cost this cycle its
   * trains and nothing else - throwing would take the station down with it.
   */
  it("still publishes the elements when the supplemental list cannot be read", async () => {
    const net = network(200, { ...groups, starlink: "nothing like a CSV" });

    const result = await run(store, net.alerting, config, now);

    expect(result).toEqual({ published: true, satellites: 2, trains: 0 });
    expect((await health(store)).state).toBe("ok");
  });

  /**
   * The reason detection happens here at all. The Starlink group runs to
   * thousands of objects, and publishing it whole would put megabytes on every
   * device on every fetch; only the two ends of each train are needed to say
   * when the front rises and the back is clear.
   */
  it("publishes the two ends of a train and nothing in between", async () => {
    const net = network(200, groups);

    await run(store, net.alerting, config, now);

    const published = JSON.parse(store.objects.get("v1/sats.json")!.body);
    expect(published.trains).toEqual([
      { launch: "2026-123", members: 8, lead: 101007, tail: 101000 },
    ]);
    // The six between the ends are not published, and neither is the rest of
    // the group. Well inside a few kilobytes for the whole document.
    expect(store.objects.get("v1/sats.json")!.body.length).toBeLessThan(4096);
  });
});
