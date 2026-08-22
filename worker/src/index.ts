/**
 * The Gloaming data Worker.
 *
 * Cron only: it fetches upstream sources on a schedule and writes derived
 * objects to R2. It is deliberately absent from the read path, where the free
 * plan's 100,000 requests/day would put a hard ceiling on the install base.
 * Devices read from the R2 custom domain through the CDN instead.
 */

import { notifyMaintainer, publishHealth, recordOutcome, type Outcome } from "./health";
import { ensureManifest, refreshSpaceWeather, refreshUk, type Deps } from "./pipeline";

/** Which cron expression drives which source. Matches `triggers.crons`. */
const AURORAWATCH_CRON = "*/3 * * * *";
const SWPC_CRON = "*/5 * * * *";

/**
 * Every source that appears in `health.json`.
 *
 * Signing the manifest is one of them. It is not an upstream, but it fails the
 * same way and matters more: it used to run outside all of this, so a bad key
 * aborted the invocation before anything was recorded and the one channel built
 * to report a silent failure was itself silent.
 *
 * `celestrak` is listed but has no cron here: it is fetched from outside
 * Cloudflare entirely (ADR-0008) and writes its own record into the same
 * bucket, which this composes without needing to know where it came from.
 */
const SOURCES = ["manifest", "swpc", "aurorawatch", "celestrak"];

/**
 * Runs one source's refresh, records the outcome, and alerts on a change of
 * state.
 *
 * Alerting only on the edge into and out of failure is deliberate: a source
 * down for an hour would otherwise produce twenty identical alerts and train
 * the maintainer to ignore them.
 *
 * The failure is returned rather than thrown, so one broken source cannot stop
 * the others from running. The caller rethrows at the end, which keeps a failed
 * run showing as a failed invocation in Cloudflare's own metrics rather than
 * being quietly absorbed.
 */
async function runSource(
  env: Env,
  deps: Deps,
  source: string,
  refresh: () => Promise<void>,
): Promise<unknown | undefined> {
  let outcome: Outcome;
  let failure: unknown;

  try {
    await refresh();
    outcome = { ok: true };
  } catch (error) {
    failure = error;
    outcome = { ok: false, error: error instanceof Error ? error.message : String(error) };
  }

  const transition = await recordOutcome(env, source, outcome, deps.now());

  if (transition === "failed" && !outcome.ok) {
    await notifyMaintainer(env, deps, {
      title: `Gloaming: ${source} is failing`,
      body: outcome.error,
    });
  } else if (transition === "recovered") {
    await notifyMaintainer(env, deps, {
      title: `Gloaming: ${source} recovered`,
      body: `${source} is publishing again.`,
    });
  }

  await publishHealth(env, SOURCES, deps.now());

  return failure;
}

export default {
  async scheduled(controller, env, _ctx) {
    // fetch must stay bound to the global scope. Passing the bare reference
    // through loses `this` and fails at runtime with "Illegal invocation".
    const deps: Deps = { fetch: globalThis.fetch.bind(globalThis), now: () => new Date() };

    // An unrecognised schedule means the config and this switch have drifted
    // apart. Say so before doing any work rather than silently doing nothing.
    const source =
      controller.cron === AURORAWATCH_CRON
        ? { name: "aurorawatch", refresh: () => refreshUk(env, deps) }
        : controller.cron === SWPC_CRON
          ? { name: "swpc", refresh: () => refreshSpaceWeather(env, deps) }
          : null;
    if (source === null) {
      throw new Error(`No handler for cron schedule "${controller.cron}"`);
    }

    // Cheap no-op once published, and it means the manifest repairs itself
    // within one cycle rather than needing a deploy. Tracked like a source and
    // allowed to fail on its own: the app keeps polling the endpoints it
    // already holds, so an unsigned manifest is survivable for a cycle while a
    // grid nobody refreshed is not.
    const failures = [
      await runSource(env, deps, "manifest", () => ensureManifest(env, deps.now)),
      await runSource(env, deps, source.name, source.refresh),
    ].filter((failure) => failure !== undefined);

    if (failures.length > 0) {
      throw failures[0];
    }
  },
} satisfies ExportedHandler<Env>;
