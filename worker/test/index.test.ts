import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import type { Health } from "../src/health";
import worker from "../src/index";

/**
 * The scheduled handler, as the runtime actually calls it.
 *
 * The pipelines are covered in their own file; what is only reachable from here
 * is the failure wiring - whether a source that breaks is recorded and pushed
 * to a human, and whether one broken thing takes the others down with it. That
 * is exactly the class of bug `health.json` exists to catch, so it must not be
 * possible for the catcher itself to fail silently.
 */
describe("scheduled", () => {
  const SWPC_CRON = "*/5 * * * *";

  function controller(cron: string): ScheduledController {
    return { cron, scheduledTime: Date.now(), noRetry: () => {} } as ScheduledController;
  }

  const context = {
    waitUntil: () => {},
    passThroughOnException: () => {},
  } as unknown as ExecutionContext;

  /**
   * Upstreams pointed at a dead local port, and no ntfy topic: this exercises
   * the failure wiring, and it must not depend on - or reach - a network.
   */
  const offline = {
    SWPC_OVATION_URL: "http://127.0.0.1:1/ovation",
    SWPC_KP_URL: "http://127.0.0.1:1/kp",
    SWPC_SOLAR_WIND_MAG_URL: "http://127.0.0.1:1/mag",
    SWPC_SOLAR_WIND_SPEED_URL: "http://127.0.0.1:1/speed",
    AURORAWATCH_STATUS_URL: "http://127.0.0.1:1/status.xml",
    NTFY_TOPIC: "",
  };

  async function readHealthDocument(e: Env): Promise<Health> {
    const stored = await e.DATA.get("v1/health.json");
    if (stored === null) {
      throw new Error("health.json was not published");
    }
    return JSON.parse(await stored.text()) as Health;
  }

  /**
   * Signing is the one step that runs before any source and outside all of
   * them, so a bad key used to abort the whole invocation before anything was
   * recorded - the single failure most likely to go unnoticed, because the
   * channel built to report it never ran.
   */
  it("records a manifest signing failure instead of failing silently", async () => {
    const broken = { ...env, ...offline, MANIFEST_SIGNING_KEY: "not a pem" } as unknown as Env;

    await expect(worker.scheduled(controller(SWPC_CRON), broken, context)).rejects.toThrow();

    const health = await readHealthDocument(broken);
    expect(health.sources.manifest?.state).toBe("failing");
    expect(health.sources.manifest?.last_error).toContain("PKCS8");
  });

  /**
   * One broken thing must not take the others with it. The grid is what
   * answers "is there aurora here"; a manifest that cannot be signed is
   * survivable for a cycle, because the app keeps polling the endpoints it
   * already has.
   */
  it("still refreshes the data when the manifest cannot be signed", async () => {
    const broken = { ...env, ...offline, MANIFEST_SIGNING_KEY: "not a pem" } as unknown as Env;

    await expect(worker.scheduled(controller(SWPC_CRON), broken, context)).rejects.toThrow();

    const health = await readHealthDocument(broken);
    expect(health.sources.swpc).toBeDefined();
  });
});

/**
 * Each cron drives one source, and the mapping lives in a switch that the
 * configuration cannot see. A schedule added to `wrangler.jsonc` without a
 * branch here would fire on time and do nothing at all - worse, it would throw
 * before any source ran and leave no trace in `health.json`.
 */
describe("cron routing", () => {
  const context = {
    waitUntil: () => {},
    passThroughOnException: () => {},
  } as unknown as ExecutionContext;

  it("refuses a schedule it has no handler for", async () => {
    const controller = {
      cron: "* * * * *",
      scheduledTime: Date.now(),
      noRetry: () => {},
    } as ScheduledController;

    await expect(worker.scheduled(controller, env, context)).rejects.toThrow(/No handler/);
  });
});
