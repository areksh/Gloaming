/**
 * Shapes that more than one component has to agree on.
 *
 * These live apart from the code that writes them because the CelesTrak source
 * no longer runs in the Worker - it runs on a host outside Cloudflare (see
 * ADR-0008) and writes its own health record into the same bucket, which the
 * Worker then composes into `health.json` without knowing or caring where it
 * came from. Keeping the shapes in a file with no imports and no runtime
 * dependencies means both sides can share them without either dragging the
 * other's globals along.
 */

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
