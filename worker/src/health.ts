/**
 * Health tracking and developer alerting.
 *
 * A cron Worker that fails silently is how an IP ends up firewalled, and both
 * SWPC and AuroraWatch can fail quietly from day one. The CelesTrak circuit
 * breaker in Phase 3 is required to "report to a human", which presumes a
 * channel to one, so that channel is built here rather than alongside the
 * breaker itself.
 *
 * Two complementary halves:
 *
 * - `health.json` in R2, a pull channel that is useful for debugging whenever
 *   anyone thinks to look.
 * - A maintainer-only ntfy topic, a push channel for the times nobody is
 *   looking. It reaches exactly one phone, the maintainer's, and is unrelated
 *   to how users are notified - the app has no push of any kind.
 *
 * Alerts fire on the *edge* into failure and back out of it. A source down for
 * an hour would otherwise produce twenty identical alerts and train the
 * maintainer to ignore them.
 */

import {
  HEALTH_SCHEMA,
  type Health,
  type SourceHealth,
  type Transition,
} from "./contracts";
import type { Deps } from "./pipeline";
import { CACHE_CONTROL, publish } from "./storage";

// Re-exported so existing callers are unaffected by the shapes moving out.
export { HEALTH_SCHEMA };
export type { Health, SourceHealth, Transition } from "./contracts";

export type Outcome = { ok: true } | { ok: false; error: string };

const healthKey = (source: string) => `internal/health/${source}.json`;

/**
 * How long a source may go without a success before its record stops being
 * worth believing.
 *
 * Generously above each source's real cadence, because the point is to catch a
 * source that has *stopped*, not to complain about one that ran late. SWPC and
 * AuroraWatch are minutes apart, so an hour is many missed cycles; CelesTrak is
 * fetched two-hourly from another host entirely, so it gets far longer.
 *
 * A source with no entry here is never called stale. That is the safe default:
 * a new source should not start alarming before anyone has decided what its
 * cadence even is.
 */
const STALE_AFTER_MILLIS: Record<string, number> = {
  manifest: 60 * 60 * 1000,
  swpc: 60 * 60 * 1000,
  aurorawatch: 60 * 60 * 1000,
  celestrak: 6 * 60 * 60 * 1000,
  // Twice daily, and the run skips without recording anything while the
  // published document is fresh - so this entry is the only thing that can
  // tell a comet source that stopped from one in perfect order. Three missed
  // cycles, not one, so a single skipped run cannot cry wolf.
  comets: 36 * 60 * 60 * 1000,
};

/**
 * Whether [state] is too old to believe at [now].
 *
 * Only ever applied to a source whose last attempt succeeded. One that is
 * already failing keeps that label, which says strictly more: "failing" names
 * a cause, and "stale" is what you say when there is no cause because nothing
 * has run.
 */
function isStale(state: SourceHealth, now: Date): boolean {
  if (state.state !== "ok" || state.last_success === null) {
    return false;
  }
  const limit = STALE_AFTER_MILLIS[state.source];
  if (limit === undefined) {
    return false;
  }
  const age = now.getTime() - Date.parse(state.last_success);
  return Number.isFinite(age) && age > limit;
}

export async function readHealth(env: Env, source: string): Promise<SourceHealth | null> {
  const stored = await env.DATA.get(healthKey(source));
  if (stored === null) {
    return null;
  }
  return JSON.parse(await stored.text()) as SourceHealth;
}

/**
 * Records one run's outcome and reports whether the source changed state.
 *
 * A source failing on its first ever run counts as a transition into failure:
 * it has no prior success to fall back on, which is exactly the case worth
 * hearing about.
 */
export async function recordOutcome(
  env: Env,
  source: string,
  outcome: Outcome,
  now: Date,
): Promise<Transition> {
  const previous = await readHealth(env, source);
  const wasFailing = previous?.state === "failing";

  const next: SourceHealth = outcome.ok
    ? {
        source,
        state: "ok",
        last_success: now.toISOString(),
        // Kept rather than wiped: the last failure is what someone debugging a
        // flapping source needs to see.
        last_error: previous?.last_error ?? null,
        last_error_at: previous?.last_error_at ?? null,
        consecutive_failures: 0,
      }
    : {
        source,
        state: "failing",
        last_success: previous?.last_success ?? null,
        last_error: outcome.error,
        last_error_at: now.toISOString(),
        consecutive_failures: (previous?.consecutive_failures ?? 0) + 1,
      };

  await env.DATA.put(healthKey(source), JSON.stringify(next));

  if (outcome.ok) {
    return wasFailing ? "recovered" : "none";
  }
  return wasFailing ? "none" : "failed";
}

export async function composeHealth(env: Env, sources: string[], now: Date): Promise<Health> {
  const entries = await Promise.all(
    sources.map(async (source) => [source, await readHealth(env, source)] as const),
  );

  const health: Health = { schema: HEALTH_SCHEMA, generated: now.toISOString(), sources: {} };
  for (const [source, state] of entries) {
    if (state === null) {
      continue;
    }
    // Overlaid on the composed document, never written back to the partial:
    // that record is the log of attempts, and the last attempt did succeed.
    health.sources[source] = isStale(state, now) ? { ...state, state: "stale" } : state;
  }
  return health;
}

/** Which sources a staleness alert has already gone out for. */
const STALE_LATCH_KEY = "internal/health/stale.json";

async function readStaleLatch(env: Env): Promise<string[]> {
  const stored = await env.DATA.get(STALE_LATCH_KEY);
  if (stored === null) {
    return [];
  }
  try {
    const parsed = JSON.parse(await stored.text()) as { sources?: unknown };
    return Array.isArray(parsed.sources) ? parsed.sources.filter((s) => typeof s === "string") : [];
  } catch {
    // A corrupt latch means at worst one repeated alert, which is far better
    // than throwing here and taking the whole invocation down with it.
    return [];
  }
}

/**
 * Alerts on a source that has stopped reporting, and on its return.
 *
 * Separate from [recordOutcome]'s transitions because it catches the opposite
 * failure. That one fires when a source is *attempted* and fails; this one
 * fires when a source is not attempted at all, which is what happened when the
 * SWPC cron stopped on 2026-08-25 and the record went on saying `ok` for hours.
 *
 * Recovery has to come from here too. A source that merely stopped was never
 * marked failing, so it never transitions back through [recordOutcome] and
 * nothing else would ever say it had returned.
 *
 * Only sources present in [health] are considered, so the latch is never
 * disturbed for a source this invocation knows nothing about.
 */
export async function reportStaleness(env: Env, deps: Deps, health: Health): Promise<void> {
  const known = Object.keys(health.sources);
  const nowStale = known.filter((source) => health.sources[source]?.state === "stale");

  const latch = await readStaleLatch(env);
  const previously = latch.filter((source) => known.includes(source));

  const started = nowStale.filter((source) => !previously.includes(source));
  const ended = previously.filter((source) => !nowStale.includes(source));

  for (const source of started) {
    const last = health.sources[source]?.last_success ?? "never";
    await notifyMaintainer(env, deps, {
      title: `Gloaming: ${source} has stopped reporting`,
      body:
        `No successful run since ${last}. Nothing is failing - nothing is being ` +
        `attempted, so check that its schedule is still firing.`,
    });
  }
  for (const source of ended) {
    await notifyMaintainer(env, deps, {
      title: `Gloaming: ${source} recovered`,
      body: `${source} is reporting again.`,
    });
  }

  if (started.length > 0 || ended.length > 0) {
    const kept = latch.filter((source) => !known.includes(source));
    await env.DATA.put(STALE_LATCH_KEY, JSON.stringify({ sources: [...kept, ...nowStale] }));
  }
}

/** Publishes the composed health document for anyone debugging from outside. */
export async function publishHealth(env: Env, sources: string[], now: Date): Promise<Health> {
  const health = await composeHealth(env, sources, now);
  await publish(env.DATA, "v1/health.json", JSON.stringify(health), {
    cacheControl: CACHE_CONTROL["health.json"],
    contentType: "application/json",
  });
  return health;
}

/**
 * Posts one alert to the maintainer's ntfy topic.
 *
 * Never throws. The alert exists so a human finds out about a problem; if the
 * alerting channel is itself unreachable, that must not fail the refresh that
 * prompted it. `health.json` still records the truth for anyone who looks.
 */
export async function notifyMaintainer(
  env: Env,
  deps: Deps,
  note: { title: string; body: string },
): Promise<void> {
  if (env.NTFY_TOPIC === "") {
    return;
  }

  try {
    await deps.fetch(`${env.NTFY_BASE_URL}/${env.NTFY_TOPIC}`, {
      method: "POST",
      headers: { Title: note.title },
      body: note.body,
    });
  } catch {
    // Deliberately swallowed; see the note above.
  }
}
