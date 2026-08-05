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

/** Every source that appears in `health.json`. */
const SOURCES = ["swpc", "aurorawatch"];

/**
 * Runs one source's refresh, records the outcome, and alerts on a change of
 * state.
 *
 * Alerting only on the edge into and out of failure is deliberate: a source
 * down for an hour would otherwise produce twenty identical alerts and train
 * the maintainer to ignore them.
 *
 * The original error is rethrown once health has been recorded, so a failed run
 * also shows as a failed invocation in Cloudflare's own metrics rather than
 * being quietly absorbed.
 */
async function runSource(
  env: Env,
  deps: Deps,
  source: string,
  refresh: () => Promise<void>,
): Promise<void> {
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

  if (failure !== undefined) {
    throw failure;
  }
}

export default {
  async scheduled(controller, env, _ctx) {
    // fetch must stay bound to the global scope. Passing the bare reference
    // through loses `this` and fails at runtime with "Illegal invocation".
    const deps: Deps = { fetch: globalThis.fetch.bind(globalThis), now: () => new Date() };

    // Cheap no-op once published, and it means the manifest repairs itself
    // within one cycle rather than needing a deploy.
    await ensureManifest(env);

    switch (controller.cron) {
      case AURORAWATCH_CRON:
        await runSource(env, deps, "aurorawatch", () => refreshUk(env, deps));
        break;
      case SWPC_CRON:
        await runSource(env, deps, "swpc", () => refreshSpaceWeather(env, deps));
        break;
      default:
        // An unrecognised schedule means the config and this switch have
        // drifted apart. Say so rather than silently doing nothing.
        throw new Error(`No handler for cron schedule "${controller.cron}"`);
    }
  },
} satisfies ExportedHandler<Env>;
