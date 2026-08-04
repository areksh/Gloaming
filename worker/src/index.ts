/**
 * The Gloaming data Worker.
 *
 * Cron only: it fetches upstream sources on a schedule and writes derived
 * objects to R2. It is deliberately absent from the read path, where the free
 * plan's 100,000 requests/day would put a hard ceiling on the install base.
 * Devices read from the R2 custom domain through the CDN instead.
 */

import { refreshSpaceWeather, refreshUk } from "./pipeline";

/** Which cron expression drives which source. Matches `triggers.crons`. */
const AURORAWATCH_CRON = "*/3 * * * *";
const SWPC_CRON = "*/5 * * * *";

export default {
  async scheduled(controller, env, _ctx) {
    // fetch must stay bound to the global scope. Passing the bare reference
    // through loses `this` and fails at runtime with "Illegal invocation".
    const deps = { fetch: globalThis.fetch.bind(globalThis), now: () => new Date() };

    switch (controller.cron) {
      case AURORAWATCH_CRON:
        await refreshUk(env, deps);
        break;
      case SWPC_CRON:
        await refreshSpaceWeather(env, deps);
        break;
      default:
        // An unrecognised schedule means the config and this switch have
        // drifted apart. Say so rather than silently doing nothing.
        throw new Error(`No handler for cron schedule "${controller.cron}"`);
    }
  },
} satisfies ExportedHandler<Env>;
