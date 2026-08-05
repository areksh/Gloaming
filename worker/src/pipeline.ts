/**
 * The refresh pipelines, one per cron schedule.
 *
 * Both end by recomposing `status.json` from stored partials. Neither writes
 * the document directly: the two crons run on different cadences, so a direct
 * write would republish a stale copy of the other source's fields.
 *
 * The network boundary is injected rather than reached for globally, so the
 * pipelines can be exercised end-to-end against a real R2 binding without any
 * outbound traffic.
 */

import { buildManifest, importSigningKey, serialiseManifest, signManifest } from "./manifest";
import { packOvalGrid } from "./oval";
import { parseAuroraWatchStatus } from "./sources/aurorawatch";
import { parseKp, parseOvation, parseSolarWindMagField, parseSolarWindSpeed } from "./sources/swpc";
import {
  composeStatus,
  maxProbability,
  type SpacePartial,
  type UkPartial,
} from "./status";
import { CACHE_CONTROL, gzip, publish } from "./storage";

export interface Deps {
  fetch: typeof fetch;
  now: () => Date;
}

const SPACE_PARTIAL_KEY = "internal/space.json";
const UK_PARTIAL_KEY = "internal/uk.json";
const STATUS_KEY = "v1/status.json";
const OVAL_KEY = "v1/oval.bin";
const MANIFEST_KEY = "v1/index.json";
const MANIFEST_SIG_KEY = "v1/index.json.sig";

/**
 * Fetches an upstream, naming the app in the User-Agent as AuroraWatch's terms
 * require, and refusing anything that is not a 200.
 *
 * A non-200 must not be smoothed over: a partial or error body that still
 * parsed would publish a grid of zeros, which reads as "no aurora anywhere"
 * and silently suppresses every alert.
 */
async function fetchOk(deps: Deps, url: string, userAgent: string): Promise<Response> {
  const response = await deps.fetch(url, { headers: { "User-Agent": userAgent } });
  if (!response.ok) {
    throw new Error(`Upstream ${url} returned ${response.status}`);
  }
  return response;
}

async function readPartial<T>(bucket: R2Bucket, key: string): Promise<T | null> {
  const stored = await bucket.get(key);
  if (stored === null) {
    return null;
  }
  return JSON.parse(await stored.text()) as T;
}

/** Recomposes and republishes `status.json` from whichever partials exist. */
async function republishStatus(env: Env, now: Date): Promise<void> {
  const [space, uk] = await Promise.all([
    readPartial<SpacePartial>(env.DATA, SPACE_PARTIAL_KEY),
    readPartial<UkPartial>(env.DATA, UK_PARTIAL_KEY),
  ]);

  const status = composeStatus({ space, uk, now: now.toISOString() });

  await publish(env.DATA, STATUS_KEY, JSON.stringify(status), {
    cacheControl: CACHE_CONTROL["status.json"],
    contentType: "application/json",
  });
}

/**
 * Publishes `index.json` and its detached signature, but only when the contents
 * have actually changed.
 *
 * Called on every cron rather than on deploy, so a manifest that is deleted or
 * never written comes back within one cycle without anyone intervening.
 * Rewriting it unchanged would reset the object's cache age for no reason.
 */
export async function ensureManifest(env: Env): Promise<void> {
  const manifest = buildManifest({
    minSupportedVersion: env.MIN_SUPPORTED_VERSION,
    message: env.MANIFEST_MESSAGE === "" ? null : env.MANIFEST_MESSAGE,
    baseUrl: env.PUBLIC_BASE_URL,
    weatherUrl: env.OPEN_METEO_URL,
  });
  const body = serialiseManifest(manifest);

  const current = await env.DATA.get(MANIFEST_KEY);
  if (current !== null && (await current.text()) === body) {
    return;
  }

  const key = await importSigningKey(env.MANIFEST_SIGNING_KEY);
  const signature = await signManifest(key, body);

  // Signature first: a manifest briefly present without its signature would be
  // rejected by every app that fetched in between, whereas a stale signature
  // simply fails closed against the old body.
  await publish(env.DATA, MANIFEST_SIG_KEY, signature, {
    cacheControl: CACHE_CONTROL["index.json"],
    contentType: "application/octet-stream",
  });
  await publish(env.DATA, MANIFEST_KEY, body, {
    cacheControl: CACHE_CONTROL["index.json"],
    contentType: "application/json",
  });
}

/** SWPC: the OVATION grid plus the Kp and solar wind readings. */
export async function refreshSpaceWeather(env: Env, deps: Deps): Promise<void> {
  const agent = env.USER_AGENT;

  const gridResponse = await fetchOk(deps, env.SWPC_OVATION_URL, agent);
  const grid = parseOvation(await gridResponse.json());
  const packed = packOvalGrid(grid);

  // Opaque gzip bytes: the client inflates them. See gzip() for why no
  // Content-Encoding is declared.
  const written = await publish(env.DATA, OVAL_KEY, await gzip(packed), {
    cacheControl: CACHE_CONTROL["oval.bin"],
    contentType: "application/octet-stream",
  });

  // Supporting readings must not fail the run: the grid alone is enough to
  // answer "is there aurora here", and a missing Kp only costs a display field.
  const [kp, magField, windSpeed] = await Promise.all([
    fetchOk(deps, env.SWPC_KP_URL, agent)
      .then(async (r) => parseKp(await r.json()))
      .catch(() => null),
    fetchOk(deps, env.SWPC_SOLAR_WIND_MAG_URL, agent)
      .then(async (r) => parseSolarWindMagField(await r.json()))
      .catch(() => null),
    fetchOk(deps, env.SWPC_SOLAR_WIND_SPEED_URL, agent)
      .then(async (r) => parseSolarWindSpeed(await r.json()))
      .catch(() => null),
  ]);

  const partial: SpacePartial = {
    updated: deps.now().toISOString(),
    observationTime: grid.observationTime,
    forecastTime: grid.forecastTime,
    ovalEtag: written.httpEtag,
    maxProbability: maxProbability(packed),
    kp,
    solarWind:
      magField === null
        ? null
        : { bz: magField.bz, bt: magField.bt, speed: windSpeed?.speed ?? null, time: magField.time },
  };

  await env.DATA.put(SPACE_PARTIAL_KEY, JSON.stringify(partial));
  await republishStatus(env, deps.now());
}

/** AuroraWatch UK: the UK-only enhancement layer over the OVATION foundation. */
export async function refreshUk(env: Env, deps: Deps): Promise<void> {
  const response = await fetchOk(deps, env.AURORAWATCH_STATUS_URL, env.USER_AGENT);
  const status = parseAuroraWatchStatus(await response.text());

  const partial: UkPartial = {
    updated: status.updated,
    status: status.status,
    siteId: status.siteId,
  };

  await env.DATA.put(UK_PARTIAL_KEY, JSON.stringify(partial));
  await republishStatus(env, deps.now());
}
