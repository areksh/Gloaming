import type { SourceHealth, Transition } from "../../worker/src/contracts";
import type { ObjectStore } from "./store";

export type { SourceHealth, Transition };

export type Outcome = { ok: true } | { ok: false; error: string };

const healthKey = (source: string) => `internal/health/${source}.json`;

/**
 * Records this run's outcome where the Worker's health composition will find it.
 *
 * The Worker still owns `health.json`: it lists `celestrak` among its sources
 * and rebuilds the published document from the per-source objects every few
 * minutes. Writing this one object is therefore the whole of what is needed for
 * a source that runs somewhere else entirely to keep appearing there, with no
 * change to the Worker at all.
 *
 * Deliberately the same shape and the same edge semantics as the Worker's own
 * `recordOutcome`. Two implementations of one format is a real cost, and it is
 * paid because the storage underneath is genuinely different - a binding there,
 * a signed HTTP request here - not because the format is in doubt.
 */
export async function recordOutcome(
  store: ObjectStore,
  source: string,
  outcome: Outcome,
  now: Date,
): Promise<Transition> {
  const stored = await store.get(healthKey(source));
  const previous = stored === null ? null : (JSON.parse(stored) as SourceHealth);
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

  await store.put(healthKey(source), JSON.stringify(next), {
    contentType: "application/json",
  });

  if (outcome.ok) {
    return wasFailing ? "recovered" : "none";
  }
  return wasFailing ? "none" : "failed";
}

export interface Alerting {
  baseUrl: string;
  /** Empty disables alerting, which is what a machine with no topic set does. */
  topic: string;
  fetch: typeof fetch;
}

/**
 * Posts one alert to the maintainer's ntfy topic.
 *
 * Never throws. The alert exists so a human finds out about a problem; if the
 * alerting channel is itself unreachable that must not fail the run that
 * prompted it, and the health record still carries the truth.
 */
export async function notifyMaintainer(
  alerting: Alerting,
  note: { title: string; body: string },
): Promise<void> {
  if (alerting.topic === "") {
    return;
  }

  try {
    await alerting.fetch(`${alerting.baseUrl}/${alerting.topic}`, {
      method: "POST",
      headers: { Title: note.title },
      body: note.body,
    });
  } catch {
    // Deliberately swallowed; see the note above.
  }
}
