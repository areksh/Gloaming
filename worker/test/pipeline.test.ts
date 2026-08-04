import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { GRID_LONGITUDES } from "../src/oval";
import { refreshSpaceWeather, refreshUk } from "../src/pipeline";
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
    expect(oval?.httpMetadata?.contentEncoding).toBe("gzip");
    // The packed grid is 65,160 bytes and almost all zeros, so it must
    // compress to a small fraction of that.
    expect(oval!.size).toBeLessThan(5000);

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
