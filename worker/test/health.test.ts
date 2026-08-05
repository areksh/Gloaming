import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import {
  composeHealth,
  HEALTH_SCHEMA,
  notifyMaintainer,
  readHealth,
  recordOutcome,
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
    ).resolves.toBeUndefined();
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
