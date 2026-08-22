import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { breakerKey, readBreaker, trip } from "../src/breaker";
import { toPem } from "../src/manifest";
import { GRID_LONGITUDES } from "../src/oval";
import { ensureManifest, refreshSatellites, refreshSpaceWeather, refreshUk } from "../src/pipeline";
import type { SatellitesDocument } from "../src/sources/celestrak";
import type { Status } from "../src/status";

function ovationBody(peak: number) {
  const coordinates: [number, number, number][] = [];
  for (let lon = 0; lon < GRID_LONGITUDES; lon++) {
    for (let lat = -90; lat <= 90; lat++) {
      coordinates.push([lon, lat, lon === 10 && lat === 60 ? peak : 0]);
    }
  }
  return {
    "Observation Time": "2026-08-04T21:26:00Z",
    "Forecast Time": "2026-08-04T22:32:00Z",
    "Data Format": "[Longitude, Latitude, Aurora]",
    coordinates,
  };
}

const AURORAWATCH_XML = `<?xml version='1.0' encoding='UTF-8'?>
<current_status api_version="0.2.5"><updated><datetime>2026-08-04T21:39:32+0000</datetime></updated><site_status project_id="project:SAMNET" site_id="site:SAMNET:CRK2" status_id="amber"/></current_status>`;

/** A fetch stub that answers by URL substring, and records what was asked for. */
function stubFetch(routes: Record<string, () => Response>) {
  const seen: string[] = [];
  const fetcher = async (input: RequestInfo | URL): Promise<Response> => {
    const url = String(input);
    seen.push(url);
    for (const [fragment, respond] of Object.entries(routes)) {
      if (url.includes(fragment)) {
        return respond();
      }
    }
    throw new Error(`unexpected fetch: ${url}`);
  };
  return { fetcher: fetcher as unknown as typeof fetch, seen };
}

function spaceRoutes(peak: number) {
  return {
    ovation_aurora_latest: () => Response.json(ovationBody(peak)),
    planetary_k_index: () =>
      Response.json([{ time_tag: "2026-08-04T21:36:00", kp_index: 4.33, estimated_kp: 4.33, kp: "4P" }]),
    "solar-wind-mag-field": () =>
      Response.json([{ bt: 10.85, bz_gsm: -8.36, time_tag: "2026-08-04T21:33:00Z" }]),
    "solar-wind-speed": () => Response.json([{ proton_speed: 406, time_tag: "2026-08-04T21:35:00Z" }]),
  };
}

async function readStatus(): Promise<Status> {
  const stored = await env.DATA.get("v1/status.json");
  if (stored === null) {
    throw new Error("status.json was not published");
  }
  return JSON.parse(await stored.text()) as Status;
}

const now = () => new Date("2026-08-04T21:40:00Z");

describe("refreshSpaceWeather", () => {
  it("publishes a gzipped grid and a status document referencing its ETag", async () => {
    const { fetcher } = stubFetch(spaceRoutes(73));

    await refreshSpaceWeather(env, { fetch: fetcher, now });

    const oval = await env.DATA.get("v1/oval.bin");
    expect(oval).not.toBeNull();
    // The packed grid is 65,160 bytes and almost all zeros, so it must
    // compress to a small fraction of that.
    expect(oval!.size).toBeLessThan(5000);

    // Compression is part of the payload, not an HTTP transfer encoding.
    // Declaring Content-Encoding lets the CDN inflate the object and serve the
    // full 65 KB, so the body is opaque gzip the client inflates itself and
    // carries no encoding header for anything to act on.
    expect(oval?.httpMetadata?.contentEncoding).toBeUndefined();
    const magic = new Uint8Array(await oval!.arrayBuffer()).slice(0, 2);
    expect([...magic]).toEqual([0x1f, 0x8b]);

    const status = await readStatus();
    // The device compares this against the ETag the CDN serves, which is the
    // quoted form.
    expect(status.oval_etag).toBe(oval?.httpEtag);
    expect(status.global_activity).toBe("high");
    expect(status.max_probability).toBe(73);
    expect(status.kp).toEqual({ value: 4.33, time: "2026-08-04T21:36:00" });
    expect(status.solar_wind?.bz).toBe(-8.36);
    expect(status.solar_wind?.speed).toBe(406);
    expect(status.generated).toBe("2026-08-04T21:40:00.000Z");
  });

  it("sends a User-Agent naming the app on every upstream request", async () => {
    const seenAgents: (string | null)[] = [];
    const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
      seenAgents.push(new Headers(init?.headers).get("User-Agent"));
      const url = String(input);
      for (const [fragment, respond] of Object.entries(spaceRoutes(10))) {
        if (url.includes(fragment)) return respond();
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as unknown as typeof fetch;

    await refreshSpaceWeather(env, { fetch: fetcher, now });

    expect(seenAgents.length).toBeGreaterThan(0);
    for (const agent of seenAgents) {
      expect(agent).toMatch(/Gloaming/);
    }
  });

  it("fails loudly when an upstream is down rather than publishing a quiet grid", async () => {
    const { fetcher } = stubFetch({
      ovation_aurora_latest: () => new Response("upstream down", { status: 503 }),
      planetary_k_index: () => Response.json([]),
      "solar-wind-mag-field": () => Response.json([]),
      "solar-wind-speed": () => Response.json([]),
    });

    await expect(refreshSpaceWeather(env, { fetch: fetcher, now })).rejects.toThrow(/503/);
  });
});

describe("refreshUk", () => {
  it("publishes the AuroraWatch level into status.json", async () => {
    const { fetcher } = stubFetch({ "current-status.xml": () => new Response(AURORAWATCH_XML) });

    await refreshUk(env, { fetch: fetcher, now });

    const status = await readStatus();
    expect(status.aurorawatch).toEqual({
      status: "amber",
      updated: "2026-08-04T21:39:32+0000",
      site_id: "site:SAMNET:CRK2",
    });
  });
});

describe("composition across the two crons", () => {
  // The 3-minute UK cron and the 5-minute space-weather cron both republish
  // status.json. Neither may drop the other's fields, which is the whole reason
  // each stores a partial rather than writing the document directly.
  it("keeps both sources' fields no matter which cron ran last", async () => {
    const space = stubFetch(spaceRoutes(73));
    const ukFetch = stubFetch({ "current-status.xml": () => new Response(AURORAWATCH_XML) });

    await refreshSpaceWeather(env, { fetch: space.fetcher, now });
    await refreshUk(env, { fetch: ukFetch.fetcher, now });

    let status = await readStatus();
    expect(status.aurorawatch?.status).toBe("amber");
    expect(status.kp?.value).toBe(4.33);
    expect(status.max_probability).toBe(73);

    // Now the space-weather cron runs again: the UK fields must survive.
    await refreshSpaceWeather(env, { fetch: stubFetch(spaceRoutes(15)).fetcher, now });

    status = await readStatus();
    expect(status.aurorawatch?.status).toBe("amber");
    expect(status.max_probability).toBe(15);
    expect(status.global_activity).toBe("low");
  });
});

describe("ensureManifest", () => {
  async function envWithKey() {
    const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
      "sign",
      "verify",
    ])) as CryptoKeyPair;
    const pkcs8 = (await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer;
    return {
      env: { ...env, MANIFEST_SIGNING_KEY: toPem(new Uint8Array(pkcs8)) } as unknown as Env,
      publicKey: pair.publicKey,
    };
  }

  it("publishes the manifest with a signature its public key verifies", async () => {
    const { env: e, publicKey } = await envWithKey();

    await ensureManifest(e);

    const manifest = await e.DATA.get("v1/index.json");
    const signature = await e.DATA.get("v1/index.json.sig");
    expect(manifest).not.toBeNull();
    expect(signature).not.toBeNull();

    const body = await manifest!.text();
    const sig = await signature!.arrayBuffer();
    expect(sig.byteLength).toBe(64);

    // Verify over the bytes actually published, which is what the app does.
    const ok = await crypto.subtle.verify(
      { name: "Ed25519" },
      publicKey,
      sig,
      new TextEncoder().encode(body),
    );
    expect(ok).toBe(true);

    const parsed = JSON.parse(body);
    expect(parsed.endpoints.status).toBe(`${e.PUBLIC_BASE_URL}/status.json`);
    expect(parsed.endpoints.weather).toBe(e.OPEN_METEO_URL);
  });

  /**
   * The manifest already deployed has no stamp at all, and it has to acquire
   * one without anybody touching it.
   *
   * This is the case the stamp was introduced into, and the one it silently
   * failed: an absent `issued_at` reads as undefined, `JSON.stringify` drops
   * an undefined value, and the rebuilt document therefore came out
   * byte-identical to the unstamped one already published. It compared equal,
   * returned early, and would have done so for ever - a rollback guard that
   * never armed itself, on the only manifest that exists.
   */
  it("stamps a manifest that was published before stamps existed", async () => {
    const { env: e } = await envWithKey();
    const unstamped = {
      schema: 1,
      min_supported_version: e.MIN_SUPPORTED_VERSION,
      message: null,
      endpoints: {
        status: `${e.PUBLIC_BASE_URL}/status.json`,
        oval: `${e.PUBLIC_BASE_URL}/oval.bin`,
        weather: e.OPEN_METEO_URL,
      },
    };
    await e.DATA.put("v1/index.json", JSON.stringify(unstamped));

    await ensureManifest(e, () => new Date("2026-08-22T09:00:00Z"));

    const parsed = JSON.parse(await (await e.DATA.get("v1/index.json"))!.text());
    expect(parsed.issued_at).toBe(Math.floor(new Date("2026-08-22T09:00:00Z").getTime() / 1000));
  });

  // Ed25519 is deterministic, but republishing on every cron would still churn
  // the object and reset its cache age for no reason.
  it("leaves an unchanged manifest alone", async () => {
    const { env: e } = await envWithKey();

    await ensureManifest(e);
    const first = await e.DATA.get("v1/index.json");
    const firstUploaded = first!.uploaded.getTime();

    await ensureManifest(e);
    const second = await e.DATA.get("v1/index.json");

    expect(second!.uploaded.getTime()).toBe(firstUploaded);
  });

  it("republishes when the manifest contents change", async () => {
    const { env: e } = await envWithKey();

    await ensureManifest(e);
    await ensureManifest({ ...e, MANIFEST_MESSAGE: "Maintenance tonight" } as unknown as Env);

    const manifest = await e.DATA.get("v1/index.json");
    expect(JSON.parse(await manifest!.text()).message).toBe("Maintenance tonight");
  });

  /**
   * The app refuses a manifest older than the one it already holds, so a
   * hijacked domain cannot replay an old signed copy to move the endpoints
   * back. That only works if this stamps each publication.
   */
  it("stamps the manifest with the time its contents last changed", async () => {
    const { env: e } = await envWithKey();
    const at = new Date("2026-08-22T09:00:00Z");

    await ensureManifest(e, () => at);

    const parsed = JSON.parse(await (await e.DATA.get("v1/index.json"))!.text());
    expect(parsed.issued_at).toBe(Math.floor(at.getTime() / 1000));
  });

  it("advances the stamp when the contents change", async () => {
    const { env: e } = await envWithKey();

    await ensureManifest(e, () => new Date("2026-08-22T09:00:00Z"));
    await ensureManifest(
      { ...e, MANIFEST_MESSAGE: "Maintenance tonight" } as unknown as Env,
      () => new Date("2026-08-22T10:00:00Z"),
    );

    const parsed = JSON.parse(await (await e.DATA.get("v1/index.json"))!.text());
    expect(parsed.issued_at).toBe(Math.floor(new Date("2026-08-22T10:00:00Z").getTime() / 1000));
  });

  /**
   * A wall clock rather than a counter, precisely so that this case is safe: a
   * counter restarting from one after the object was deleted would look like a
   * rollback to every install that had already seen a higher number, and they
   * would refuse the replacement for good.
   */
  it("keeps moving forward after the manifest is deleted", async () => {
    const { env: e } = await envWithKey();
    await ensureManifest(e, () => new Date("2026-08-22T09:00:00Z"));
    await e.DATA.delete("v1/index.json");

    await ensureManifest(e, () => new Date("2026-08-22T11:00:00Z"));

    const parsed = JSON.parse(await (await e.DATA.get("v1/index.json"))!.text());
    expect(parsed.issued_at).toBe(Math.floor(new Date("2026-08-22T11:00:00Z").getTime() / 1000));
  });

  it("restores the manifest if it is deleted", async () => {
    const { env: e } = await envWithKey();
    await ensureManifest(e);
    await e.DATA.delete("v1/index.json");

    await ensureManifest(e);

    expect(await e.DATA.get("v1/index.json")).not.toBeNull();
  });
});

describe("refreshSatellites", () => {
  const HEADER =
    "OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE," +
    "ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID," +
    "ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT";

  const row = (name: string, catalogue: number) =>
    `${name},1998-067A,2026-08-04T12:28:09.322176,15.48582035,.0004694,51.6447,309.4881,` +
    `203.6966,299.8876,0,U,${catalogue},999,31220,.31985E-4,.1288E-4,0`;

  const celestrakRoutes = () => ({
    "GROUP=stations": () => new Response(`${HEADER}\n${row("ISS (ZARYA)", 25544)}\n`),
    "GROUP=visual": () =>
      new Response(`${HEADER}\n${row("ISS (ZARYA)", 25544)}\n${row("COSMOS 1220", 12345)}\n`),
  });

  /** The breaker is persistent by design, so each test starts from closed. */
  async function closeBreaker() {
    await env.DATA.delete(breakerKey("celestrak"));
  }

  async function readSatellites(): Promise<SatellitesDocument> {
    const stored = await env.DATA.get("v1/sats.json");
    if (stored === null) {
      throw new Error("sats.json was not published");
    }
    return JSON.parse(await stored.text()) as SatellitesDocument;
  }

  it("publishes both groups as one deduplicated document", async () => {
    await closeBreaker();
    const { fetcher, seen } = stubFetch(celestrakRoutes());

    await refreshSatellites(env, { fetch: fetcher, now });

    const document = await readSatellites();
    expect(document.satellites.map((s) => s.catalogue)).toEqual([12345, 25544]);
    expect(document.updated).toBe("2026-08-04T21:40:00.000Z");
    expect(seen.filter((url) => url.includes("celestrak"))).toHaveLength(2);

    const stored = await env.DATA.get("v1/sats.json");
    expect(stored?.httpMetadata?.cacheControl).toBe("public, max-age=1800");
    expect(stored?.httpMetadata?.contentType).toBe("application/json");
  });

  /**
   * The policy's one hard requirement: stop on a non-200. Requesting the second
   * group in parallel would put a request on the wire that the first response
   * had already forbidden.
   */
  it("does not fetch the second group once the first trips the breaker", async () => {
    await closeBreaker();
    const { fetcher, seen } = stubFetch({
      "GROUP=stations": () => new Response("gone", { status: 404 }),
      "GROUP=visual": () => new Response(`${HEADER}\n${row("ISS (ZARYA)", 25544)}\n`),
    });

    await expect(refreshSatellites(env, { fetch: fetcher, now })).rejects.toThrow(/404/);

    expect(seen.filter((url) => url.includes("celestrak"))).toHaveLength(1);
    expect((await readBreaker(env, "celestrak"))?.status).toBe(404);
  });

  // Elements stay usable for days, so a tripped breaker degrades pass
  // prediction rather than breaking it.
  it("leaves the last good document in place when the breaker trips", async () => {
    await closeBreaker();
    const good = stubFetch(celestrakRoutes());
    await refreshSatellites(env, { fetch: good.fetcher, now });

    const later = stubFetch({ "GROUP=": () => new Response("no", { status: 500 }) });
    await expect(refreshSatellites(env, { fetch: later.fetcher, now })).rejects.toThrow();

    expect((await readSatellites()).satellites).toHaveLength(2);
  });

  it("makes no request at all while the breaker is tripped", async () => {
    await closeBreaker();
    const quiet = { ...env, NTFY_TOPIC: "" } as unknown as Env;
    const { fetcher, seen } = stubFetch(celestrakRoutes());
    await trip(quiet, { fetch: fetcher, now }, "celestrak", {
      status: 403,
      url: "https://celestrak.org/NORAD/elements/gp.php",
    });

    await expect(refreshSatellites(env, { fetch: fetcher, now })).rejects.toThrow(/403/);

    expect(seen).toHaveLength(0);
    await closeBreaker();
  });
});
