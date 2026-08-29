import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import {
  composeHealth,
  HEALTH_SCHEMA,
  type Health,
  notifyMaintainer,
  readHealth,
  recordOutcome,
  reportStaleness,
} from "../src/health";

const now = () => new Date("2026-08-05T01:00:00Z");
const later = () => new Date("2026-08-05T01:05:00Z");

/** Each test uses its own source name: storage is shared within a file. */
let counter = 0;
const uniqueSource = () => `src${counter++}`;

describe("recordOutcome", () => {
  it("records a first success without announcing a recovery", async () => {
    const source = uniqueSource();

    const transition = await recordOutcome(env, source, { ok: true }, now());

    expect(transition).toBe("none");
    const health = await readHealth(env, source);
    expect(health?.state).toBe("ok");
    expect(health?.last_success).toBe("2026-08-05T01:00:00.000Z");
    expect(health?.consecutive_failures).toBe(0);
  });

  it("reports the transition into failure", async () => {
    const source = uniqueSource();
    await recordOutcome(env, source, { ok: true }, now());

    const transition = await recordOutcome(env, source, { ok: false, error: "503" }, later());

    expect(transition).toBe("failed");
    const health = await readHealth(env, source);
    expect(health?.state).toBe("failing");
    expect(health?.last_error).toBe("503");
    expect(health?.last_error_at).toBe("2026-08-05T01:05:00.000Z");
    expect(health?.consecutive_failures).toBe(1);
  });

  // A source down for an hour must not produce twenty alerts. Only the edge
  // into failure is worth waking someone for.
  it("stays quiet while a known failure continues", async () => {
    const source = uniqueSource();
    await recordOutcome(env, source, { ok: true }, now());
    await recordOutcome(env, source, { ok: false, error: "503" }, later());

    const second = await recordOutcome(env, source, { ok: false, error: "503" }, later());
    const third = await recordOutcome(env, source, { ok: false, error: "500" }, later());

    expect(second).toBe("none");
    expect(third).toBe("none");
    expect((await readHealth(env, source))?.consecutive_failures).toBe(3);
  });

  it("reports the transition back to health", async () => {
    const source = uniqueSource();
    await recordOutcome(env, source, { ok: true }, now());
    await recordOutcome(env, source, { ok: false, error: "503" }, later());

    const transition = await recordOutcome(env, source, { ok: true }, later());

    expect(transition).toBe("recovered");
    const health = await readHealth(env, source);
    expect(health?.state).toBe("ok");
    expect(health?.consecutive_failures).toBe(0);
    // The last error is kept for debugging rather than wiped on recovery.
    expect(health?.last_error).toBe("503");
  });

  // A source failing on its very first run has no prior success to fall back
  // on, and is exactly the case worth hearing about.
  it("reports a failure on a source that has never succeeded", async () => {
    const source = uniqueSource();

    const transition = await recordOutcome(env, source, { ok: false, error: "boom" }, now());

    expect(transition).toBe("failed");
    expect((await readHealth(env, source))?.last_success).toBeNull();
  });
});

describe("composeHealth", () => {
  it("publishes every source's state in one document", async () => {
    const a = uniqueSource();
    const b = uniqueSource();
    await recordOutcome(env, a, { ok: true }, now());
    await recordOutcome(env, b, { ok: false, error: "timeout" }, now());

    const health = await composeHealth(env, [a, b], later());

    expect(health.schema).toBe(HEALTH_SCHEMA);
    expect(health.generated).toBe("2026-08-05T01:05:00.000Z");
    expect(health.sources[a]?.state).toBe("ok");
    expect(health.sources[b]?.state).toBe("failing");
    expect(health.sources[b]?.last_error).toBe("timeout");
  });

  it("omits a source that has never reported", async () => {
    const health = await composeHealth(env, ["never-run"], now());

    expect(health.sources["never-run"]).toBeUndefined();
  });

  /**
   * The failure this was written for, observed in production 2026-08-25. The
   * SWPC cron stopped firing and the health record went on saying `ok` with
   * zero failures for hours, because a source that is never *attempted* never
   * records anything. Meanwhile `status.json` served a reading two and a half
   * hours stale and nothing anywhere said so.
   *
   * Health recorded attempts and nothing else; it now also judges whether the
   * last success is recent enough to believe. A source nobody is asking is
   * exactly the silent failure this channel exists to catch.
   */
  it("calls a source stale when its last success is too old to believe", async () => {
    const source = "swpc";
    await recordOutcome(env, source, { ok: true }, now());

    const health = await composeHealth(env, [source], new Date("2026-08-05T02:30:00Z"));

    expect(health.sources[source]?.state).toBe("stale");
    // The stored record is untouched: it is the log of attempts, and the last
    // attempt really did succeed.
    expect((await readHealth(env, source))?.state).toBe("ok");
  });

  it("leaves a source alone while its last success is recent", async () => {
    const source = "swpc2";
    await recordOutcome(env, source, { ok: true }, now());

    const health = await composeHealth(env, [source], later());

    expect(health.sources[source]?.state).toBe("ok");
  });

  /** A source already known to be failing keeps that, which says more. */
  it("does not relabel a failing source as merely stale", async () => {
    const source = "swpc3";
    await recordOutcome(env, source, { ok: false, error: "503" }, now());

    const health = await composeHealth(env, [source], new Date("2026-08-05T02:30:00Z"));

    expect(health.sources[source]?.state).toBe("failing");
  });

  /**
   * Sources refresh at their own rates, so one threshold cannot serve all of
   * them. CelesTrak is fetched two-hourly by a job on another host; calling it
   * stale after ten minutes would cry wolf every cycle.
   */
  it("judges each source against its own expected rate", async () => {
    await recordOutcome(env, "celestrak", { ok: true }, now());

    const withinItsRate = await composeHealth(env, ["celestrak"], new Date("2026-08-05T02:00:00Z"));
    const wellPastIt = await composeHealth(env, ["celestrak"], new Date("2026-08-05T09:00:00Z"));

    expect(withinItsRate.sources["celestrak"]?.state).toBe("ok");
    expect(wellPastIt.sources["celestrak"]?.state).toBe("stale");
  });

  /**
   * The comet source publishes twice a day and its run skips without recording
   * anything while the published document is fresh, so if it stops being
   * attempted its record freezes at `ok` - exactly the silent failure this
   * label exists to catch, and until now the one source it could not catch it
   * for. Judged against three missed twice-daily cycles, so one skipped run or
   * a slow day cannot cry wolf.
   */
  it("notices when the comet source has stopped being attempted", async () => {
    await recordOutcome(env, "comets", { ok: true }, now());

    const withinItsRate = await composeHealth(env, ["comets"], new Date("2026-08-06T07:00:00Z"));
    const wellPastIt = await composeHealth(env, ["comets"], new Date("2026-08-06T14:00:00Z"));

    expect(withinItsRate.sources["comets"]?.state).toBe("ok");
    expect(wellPastIt.sources["comets"]?.state).toBe("stale");
  });
});

describe("reportStaleness", () => {
  const pushes = () => {
    const sent: { title: string; body: string }[] = [];
    const fetcher = (async (url: RequestInfo | URL, init?: RequestInit) => {
      sent.push({
        title: new Headers(init?.headers).get("Title") ?? "",
        body: String(init?.body ?? ""),
      });
      return new Response("ok");
    }) as unknown as typeof fetch;
    return { sent, deps: { fetch: fetcher, now: () => now() } };
  };

  /**
   * ntfy answering but refusing the message - a rate limit against the shared
   * egress address, say. The push is lost exactly as if the network had gone,
   * and the caller is told nothing unless it looks at the status.
   */
  const refuses = () => ({
    deps: {
      fetch: (async () => new Response("rate limited", { status: 429 })) as unknown as typeof fetch,
      now: () => now(),
    },
  });

  const stale = (source: string): Health => ({
    schema: HEALTH_SCHEMA,
    generated: now().toISOString(),
    sources: {
      [source]: {
        source,
        state: "stale",
        last_success: "2026-08-05T00:00:00.000Z",
        last_error: null,
        last_error_at: null,
        consecutive_failures: 0,
      },
    },
  });

  const healthy = (source: string): Health => {
    const document = stale(source);
    document.sources[source] = { ...document.sources[source]!, state: "ok" };
    return document;
  };

  /**
   * A pull channel nobody thinks to check is not an alert. The staleness that
   * prompted all this went unnoticed for two and a half hours precisely because
   * nothing pushed.
   */
  it("pushes once when a source goes stale", async () => {
    const source = "stale-a";
    const first = pushes();

    await reportStaleness(env, first.deps, stale(source));

    expect(first.sent).toHaveLength(1);
    expect(first.sent[0]?.title).toContain(source);
  });

  /** And not again on every compose, which is every few minutes. */
  it("does not push again while it stays stale", async () => {
    const source = "stale-b";
    await reportStaleness(env, pushes().deps, stale(source));

    const second = pushes();
    await reportStaleness(env, second.deps, stale(source));

    expect(second.sent).toHaveLength(0);
  });

  /**
   * Recovery has to come from here too. A source that merely stopped was never
   * marked failing, so it never transitions back through recordOutcome and
   * nothing else would ever say it had returned.
   */
  it("says so when a stale source starts reporting again", async () => {
    const source = "stale-c";
    await reportStaleness(env, pushes().deps, stale(source));

    const back = pushes();
    await reportStaleness(env, back.deps, healthy(source));

    expect(back.sent).toHaveLength(1);
    expect(back.sent[0]?.title).toContain("recovered");
  });

  /**
   * The latch records what the maintainer has been told, not what the Worker
   * noticed. On 2026-08-29 the recovery push was lost and the latch cleared
   * anyway, so the last word on swpc stayed "stopped reporting" for the six
   * hours after it had come back.
   */
  it("says it again when the stale alert did not get through", async () => {
    const source = "stale-e";
    await reportStaleness(env, refuses().deps, stale(source));

    const retry = pushes();
    await reportStaleness(env, retry.deps, stale(source));

    expect(retry.sent).toHaveLength(1);
    expect(retry.sent[0]?.title).toContain("stopped reporting");
  });

  it("says it again when the recovery alert did not get through", async () => {
    const source = "stale-f";
    await reportStaleness(env, pushes().deps, stale(source));
    await reportStaleness(env, refuses().deps, healthy(source));

    const retry = pushes();
    await reportStaleness(env, retry.deps, healthy(source));

    expect(retry.sent).toHaveLength(1);
    expect(retry.sent[0]?.title).toContain("recovered");
  });

  it("stays quiet about a source that was never stale", async () => {
    const quiet = pushes();

    await reportStaleness(env, quiet.deps, healthy("stale-d"));

    expect(quiet.sent).toHaveLength(0);
  });
});

describe("notifyMaintainer", () => {
  it("posts the alert to the configured ntfy topic", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const fetcher = (async (url: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(url), init });
      return new Response("ok");
    }) as unknown as typeof fetch;

    await notifyMaintainer(
      { ...env, NTFY_BASE_URL: "https://ntfy.example", NTFY_TOPIC: "secret-topic" } as unknown as Env,
      { fetch: fetcher, now },
      { title: "SWPC failing", body: "503 from services.swpc.noaa.gov" },
    );

    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toBe("https://ntfy.example/secret-topic");
    expect(calls[0]!.init?.method).toBe("POST");
    expect(calls[0]!.init?.body).toBe("503 from services.swpc.noaa.gov");
    expect(new Headers(calls[0]!.init?.headers).get("Title")).toBe("SWPC failing");
  });

  // The alert exists so a human finds out. If ntfy itself is unreachable that
  // must not take down the refresh that was otherwise fine - health.json still
  // records the truth for anyone who looks.
  it("does not throw when ntfy is unreachable", async () => {
    const fetcher = (async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;

    await expect(
      notifyMaintainer(env, { fetch: fetcher, now }, { title: "t", body: "b" }),
    ).resolves.toBe(false);
  });

  // Whether the alert landed is the caller's business: a latch that records an
  // alert nobody received strands the source in its last announced state.
  it("reports an alert ntfy accepted", async () => {
    const fetcher = (async () => new Response("ok")) as unknown as typeof fetch;

    await expect(
      notifyMaintainer(env, { fetch: fetcher, now }, { title: "t", body: "b" }),
    ).resolves.toBe(true);
  });

  it("reports an alert ntfy refused", async () => {
    const fetcher = (async () =>
      new Response("rate limited", { status: 429 })) as unknown as typeof fetch;

    await expect(
      notifyMaintainer(env, { fetch: fetcher, now }, { title: "t", body: "b" }),
    ).resolves.toBe(false);
  });

  it("does nothing when no topic is configured", async () => {
    let called = false;
    const fetcher = (async () => {
      called = true;
      return new Response("ok");
    }) as unknown as typeof fetch;

    await notifyMaintainer({ ...env, NTFY_TOPIC: "" } as unknown as Env, { fetch: fetcher, now }, {
      title: "t",
      body: "b",
    });

    expect(called).toBe(false);
  });
});
