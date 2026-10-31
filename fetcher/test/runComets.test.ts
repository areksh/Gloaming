import { describe, expect, it } from "vitest";
import { COMETS_SOURCE, runComets, type CometConfig } from "../src/run";
import type { ObjectStore, PutOptions } from "../src/store";

/**
 * The comet run, against an in-memory bucket.
 *
 * Deliberately separate from the CelesTrak run's tests, because the point of
 * the code under test is that the two are separate: a failure at the Minor
 * Planet Center must not cost the station its elements, and no breaker is
 * shared between them.
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

const LEMMON =
  "    CK25A060  2025 11  8.5287  0.529829  0.995627  132.9564  108.0974  143.6635" +
  "  20260825  13.2  4.0  C/2025 A6 (Lemmon)                                       MPEC 2026-P55";

const config: CometConfig = {
  elementsUrl: "https://data.minorplanetcenter.net/iau/MPCORB/CometEls.txt",
  observationsUrl: "https://cobs.si/api/obs_list.api",
  userAgent: "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)",
};

const now = () => new Date("2026-08-25T18:00:00Z");

const observation = (magnitude: number, observer: string) => ({
  magnitude: String(magnitude),
  obs_date: "2026-08-24 18:43:12",
  comet: { mpc_name: "K25A060", component: null },
  observer: { icq_name: observer },
});

/** Answers each host with whatever the test wants, and records the requests. */
function network(answers: {
  elements?: { body?: string; status?: number };
  observations?: { body?: string; status?: number; pages?: number };
}) {
  const requests: { url: string; agent?: string; redirect?: RequestInit["redirect"] }[] = [];
  const pushes: { title: string; body: string }[] = [];

  const fetcher = (async (url: RequestInfo | URL, init?: RequestInit) => {
    const target = String(url);
    if (target.includes("ntfy")) {
      pushes.push({
        title: new Headers(init?.headers).get("Title") ?? "",
        body: String(init?.body ?? ""),
      });
      return new Response("ok");
    }
    requests.push({
      url: target,
      agent: new Headers(init?.headers).get("User-Agent") ?? undefined,
      redirect: init?.redirect,
    });

    if (target.includes("minorplanetcenter")) {
      const { body = `${LEMMON}\n`, status = 200 } = answers.elements ?? {};
      return new Response(body, { status });
    }
    const { body, status = 200, pages = 1 } = answers.observations ?? {};
    const page = Number(new URL(target).searchParams.get("page") ?? "1");
    return new Response(
      body ??
        JSON.stringify({
          info: { pages, recordsTotal: 2 },
          objects: [observation(5.8, `AAA${page}`), observation(6.0, `BBB${page}`)],
        }),
      { status },
    );
  }) as unknown as typeof fetch;

  return { requests, pushes, alerting: { baseUrl: "https://ntfy.sh", topic: "t", fetch: fetcher } };
}

describe("runComets", () => {
  it("publishes the comets it can read", async () => {
    const store = new MemoryStore();
    const { alerting } = network({});

    const result = await runComets(store, alerting, config, now);

    expect(result.published).toBe(true);
    expect(result.comets).toBe(1);
    expect(result.measured).toBe(1);

    const published = JSON.parse(store.objects.get("v1/comets.json")!.body);
    expect(published.comets[0].key).toBe("K25A060");
    expect(published.comets[0].observed.magnitude).toBe(5.9);
  });

  it("names itself to both hosts and refuses to follow a redirect", async () => {
    const store = new MemoryStore();
    const { requests, alerting } = network({});

    await runComets(store, alerting, config, now);

    expect(requests).toHaveLength(2);
    for (const request of requests) {
      expect(request.agent).toBe(config.userAgent);
      expect(request.redirect).toBe("manual");
    }
  });

  it("asks COBS only for the window it will actually use", async () => {
    const store = new MemoryStore();
    const { requests, alerting } = network({});

    await runComets(store, alerting, config, now);

    const cobs = new URL(requests.find((r) => r.url.includes("cobs"))!.url);
    expect(cobs.searchParams.get("format")).toBe("json");
    expect(cobs.searchParams.get("from_date")).toBe("2026-08-11");
  });

  /**
   * COBS pages its results, and a window that fits on one page today will not
   * during a bright apparition when thousands of people report one comet.
   * Reading only the first page would not fail - it would quietly reduce a
   * fortnight to a fraction of it, on exactly the comet everybody was watching.
   */
  it("follows COBS through every page it says there are", async () => {
    const store = new MemoryStore();
    const { requests, alerting } = network({ observations: { pages: 3 } });

    const result = await runComets(store, alerting, config, now);

    const pages = requests
      .filter((r) => r.url.includes("cobs"))
      .map((r) => new URL(r.url).searchParams.get("page"));
    expect(pages).toEqual(["1", "2", "3"]);

    const published = JSON.parse(store.objects.get("v1/comets.json")!.body);
    expect(published.comets[0].observed.reports).toBe(6);
    expect(published.comets[0].observed.observers).toBe(6);
    expect(result.measured).toBe(1);
  });

  /** A ceiling, so a misread response cannot loop against somebody's observatory. */
  it("stops paging rather than looping forever", async () => {
    const store = new MemoryStore();
    const { requests, alerting } = network({ observations: { pages: 5000 } });

    await runComets(store, alerting, config, now);

    expect(requests.filter((r) => r.url.includes("cobs")).length).toBeLessThanOrEqual(10);
  });

  /**
   * Without elements there are no comets at all; without observations there
   * are still comets, and the app falls back to the predicted magnitude and
   * says that is what it is doing. So losing COBS costs this run its
   * measurements and not its comets - the same split `readTrains` makes.
   */
  it("still publishes when COBS cannot be read", async () => {
    const store = new MemoryStore();
    const { alerting } = network({ observations: { status: 503 } });

    const result = await runComets(store, alerting, config, now);

    expect(result.published).toBe(true);
    expect(result.comets).toBe(1);
    expect(result.measured).toBe(0);
    expect(JSON.parse(store.objects.get("v1/comets.json")!.body).comets[0].observed).toBeNull();
  });

  it("publishes nothing when the elements cannot be read", async () => {
    const store = new MemoryStore();
    const { alerting } = network({ elements: { status: 403 } });

    const result = await runComets(store, alerting, config, now);

    expect(result.published).toBe(false);
    expect(result.error).toContain("403");
    expect(store.objects.has("v1/comets.json")).toBe(false);
  });

  /**
   * The same account a non-200 gets, for the failure that carries no status at
   * all. Two hosts are asked in a comet run and R2 is a third, so a message
   * that says only `fetch failed` does not even narrow it down to one of them.
   */
  it("says which host could not be reached when nothing answers", async () => {
    const store = new MemoryStore();
    const fetcher = (async (url: RequestInfo | URL) => {
      if (String(url).includes("ntfy")) return new Response("ok");
      throw new TypeError("fetch failed", {
        cause: Object.assign(new Error("getaddrinfo ENOTFOUND data.minorplanetcenter.net"), {
          code: "ENOTFOUND",
        }),
      });
    }) as unknown as typeof fetch;

    const result = await runComets(
      store,
      { baseUrl: "https://ntfy.example", topic: "t", fetch: fetcher },
      config,
      now,
    );

    expect(result.error).toBe(
      `could not reach ${config.elementsUrl}: getaddrinfo ENOTFOUND data.minorplanetcenter.net`,
    );
  });

  /**
   * A document that parses to nothing is a moved format, not an empty sky:
   * the file has carried hundreds of comets continuously for decades. Treating
   * it as success would replace a good document with an empty one and take
   * every comet off every device.
   */
  it("refuses to publish an empty document", async () => {
    const store = new MemoryStore();
    const { alerting } = network({ elements: { body: "nothing like a comet\n" } });

    const result = await runComets(store, alerting, config, now);

    expect(result.published).toBe(false);
    expect(store.objects.has("v1/comets.json")).toBe(false);
  });

  it("records its own health rather than CelesTrak's", async () => {
    const store = new MemoryStore();
    const { alerting } = network({});

    await runComets(store, alerting, config, now);

    expect(store.objects.has(`internal/health/${COMETS_SOURCE}.json`)).toBe(true);
    expect(store.objects.has("internal/health/celestrak.json")).toBe(false);
  });

  /**
   * No breaker, deliberately. CelesTrak's exists because its usage policy
   * demands stopping on any non-200; neither the MPC nor COBS asks for
   * anything of the kind, and a manual-reset breaker on them would mean one
   * transient 500 halts comet updates until a human notices.
   */
  it("does not trip a breaker on a bad response", async () => {
    const store = new MemoryStore();
    const { alerting } = network({ elements: { status: 500 } });

    await runComets(store, alerting, config, now);

    expect([...store.objects.keys()].filter((key) => key.includes("breaker"))).toEqual([]);
  });

  it("alerts once on the edge into failure and not again", async () => {
    const store = new MemoryStore();
    const { pushes, alerting } = network({ elements: { status: 500 } });

    await runComets(store, alerting, config, now);
    await runComets(store, alerting, config, now);

    expect(pushes).toHaveLength(1);
    expect(pushes[0]?.title).toContain("comets is failing");
  });

  it("says so when it starts working again", async () => {
    const store = new MemoryStore();
    const failing = network({ elements: { status: 500 } });
    await runComets(store, failing.alerting, config, now);

    const working = network({});
    await runComets(store, working.alerting, config, now);

    expect(working.pushes.map((push) => push.title)).toEqual([
      "Gloaming: comets recovered",
    ]);
  });

  /**
   * Set at PUT time because the Worker is never in the read path. Six hours
   * against `sats.json`'s thirty minutes: a satellite's elements go stale in
   * hours and a comet's in months.
   */
  it("publishes with a cache lifetime suited to how often it changes", async () => {
    const store = new MemoryStore();
    const { alerting } = network({});

    await runComets(store, alerting, config, now);

    expect(store.objects.get("v1/comets.json")!.options).toEqual({
      contentType: "application/json",
      cacheControl: "public, max-age=21600",
    });
  });

  /**
   * The fetcher shares a two-hourly timer with the satellite elements, because
   * CelesTrak's policy demands that cadence. Comets need nothing like it -
   * elements are republished weekly and magnitudes arrive at about 1.65 an hour
   * across every comet in the database - so without a guard the same document
   * is rebuilt twelve times a day and both hosts asked for a megabyte each
   * time, for an answer that has not moved.
   */
  it("leaves a recent document alone rather than asking again", async () => {
    const store = new MemoryStore();
    const { requests, alerting } = network({});
    await runComets(store, alerting, config, now);
    requests.length = 0;

    const twoHoursLater = () => new Date(now().getTime() + 2 * 60 * 60 * 1000);
    const result = await runComets(store, alerting, config, twoHoursLater);

    expect(result.skipped).toBe(true);
    expect(result.published).toBe(false);
    expect(requests).toEqual([]);
  });

  it("refreshes once the document is half a day old", async () => {
    const store = new MemoryStore();
    const { alerting } = network({});
    await runComets(store, alerting, config, now);

    const halfADayLater = () => new Date(now().getTime() + 12 * 60 * 60 * 1000);
    const result = await runComets(store, alerting, config, halfADayLater);

    expect(result.published).toBe(true);
    expect(result.skipped).toBeUndefined();
  });

  /**
   * An unreadable object is treated as absent, so a corrupt document is
   * replaced on the next run rather than freezing the source for good.
   */
  it("republishes over a document it cannot read", async () => {
    const store = new MemoryStore();
    await store.put("v1/comets.json", "{ truncated");
    const { alerting } = network({});

    const result = await runComets(store, alerting, config, now);

    expect(result.published).toBe(true);
  });

  /** A clock that has gone backwards must not wedge the source shut. */
  it("republishes when the document appears to be from the future", async () => {
    const store = new MemoryStore();
    const { alerting } = network({});
    await runComets(store, alerting, config, now);

    const earlier = () => new Date(now().getTime() - 48 * 60 * 60 * 1000);
    const result = await runComets(store, alerting, config, earlier);

    expect(result.published).toBe(true);
  });
});
