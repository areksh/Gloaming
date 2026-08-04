import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { CACHE_CONTROL, publish } from "../src/storage";

describe("CACHE_CONTROL", () => {
  // Without max-age the edge does not absorb the fan-out and every device
  // revalidates against R2 - the whole read-path cost model depends on these
  // exact values, so they are pinned rather than left to drift.
  it("matches the documented caching contract", () => {
    expect(CACHE_CONTROL).toEqual({
      "index.json": "public, max-age=3600",
      "status.json": "public, max-age=60, stale-while-revalidate=120",
      "oval.bin": "public, max-age=120, stale-while-revalidate=180",
      "sats.json": "public, max-age=1800",
      "comets.json": "public, max-age=21600",
    });
  });
});

describe("publish", () => {
  it("stores Cache-Control on the object so the CDN serves it", async () => {
    await publish(env.DATA, "v1/status.json", '{"schema":1}', {
      cacheControl: CACHE_CONTROL["status.json"],
      contentType: "application/json",
    });

    const stored = await env.DATA.get("v1/status.json");
    expect(stored).not.toBeNull();
    expect(stored?.httpMetadata?.cacheControl).toBe("public, max-age=60, stale-while-revalidate=120");
    expect(stored?.httpMetadata?.contentType).toBe("application/json");
    expect(await stored?.text()).toBe('{"schema":1}');
  });

  it("records a content encoding when the body is already compressed", async () => {
    await publish(env.DATA, "v1/oval.bin", new Uint8Array([1, 2, 3]), {
      cacheControl: CACHE_CONTROL["oval.bin"],
      contentType: "application/octet-stream",
      contentEncoding: "gzip",
    });

    const stored = await env.DATA.get("v1/oval.bin");
    expect(stored?.httpMetadata?.contentEncoding).toBe("gzip");
    expect(stored?.httpMetadata?.cacheControl).toBe("public, max-age=120, stale-while-revalidate=180");
  });

  it("returns the stored object so its ETag can be published in status.json", async () => {
    const written = await publish(env.DATA, "v1/oval.bin", new Uint8Array([9]), {
      cacheControl: CACHE_CONTROL["oval.bin"],
      contentType: "application/octet-stream",
    });

    expect(written.etag).toBeTruthy();
    const stored = await env.DATA.get("v1/oval.bin");
    expect(stored?.etag).toBe(written.etag);
  });
});
