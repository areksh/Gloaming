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

import type { Deps } from "./pipeline";
import { CACHE_CONTROL, publish } from "./storage";

export const HEALTH_SCHEMA = 1;

export type Transition = "none" | "failed" | "recovered";

export interface SourceHealth {
  source: string;
  state: "ok" | "failing";
  last_success: string | null;
  last_error: string | null;
  last_error_at: string | null;
  consecutive_failures: number;
}

export interface Health {
  schema: number;
  generated: string;
  sources: Record<string, SourceHealth>;
}

export type Outcome = { ok: true } | { ok: false; error: string };

const healthKey = (source: string) => `internal/health/${source}.json`;

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
    if (state !== null) {
      health.sources[source] = state;
    }
  }
  return health;
}

/** Publishes the composed health document for anyone debugging from outside. */
export async function publishHealth(env: Env, sources: string[], now: Date): Promise<void> {
  const health = await composeHealth(env, sources, now);
  await publish(env.DATA, "v1/health.json", JSON.stringify(health), {
    cacheControl: CACHE_CONTROL["health.json"],
    contentType: "application/json",
  });
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
