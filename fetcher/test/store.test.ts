import { afterEach, describe, expect, it, vi } from "vitest";
import { R2Store } from "../src/store";

/**
 * The seam that replaced the Worker's R2 binding.
 *
 * Nothing here checks that SigV4 is *correct* - that is aws4fetch's job and R2
 * would reject it loudly - but it does check the two things this code gets
 * wrong on its own: where the request is addressed, and whether it was signed
 * at all.
 */
describe("R2Store", () => {
  const credentials = {
    accountId: "abc123",
    accessKeyId: "key",
    secretAccessKey: "secret",
    bucket: "gloaming-data",
    // Off, so the failure cases assert on the first response rather than
    // waiting out the backoff. Production keeps the default.
    retries: 0,
  };

  const calls: { url: string; method: string; headers: Headers }[] = [];

  function stubFetch(response: () => Response) {
    vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(String(input), init);
      calls.push({ url: request.url, method: request.method, headers: request.headers });
      return response();
    });
  }

  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  /**
   * Object keys contain slashes and they are path separators, not data.
   * Percent-encoding them would create an object literally named
   * "internal%2Fbreaker%2Fcelestrak.json", which nothing else would ever find.
   */
  it("addresses a nested key without encoding its slashes", async () => {
    stubFetch(() => new Response("{}"));

    await new R2Store(credentials).get("internal/breaker/celestrak.json");

    expect(calls[0]!.url).toBe(
      "https://abc123.r2.cloudflarestorage.com/gloaming-data/internal/breaker/celestrak.json",
    );
  });

  it("signs every request", async () => {
    stubFetch(() => new Response("{}"));

    await new R2Store(credentials).get("v1/sats.json");

    expect(calls[0]!.headers.get("authorization")).toContain("AWS4-HMAC-SHA256");
  });

  // A missing object is the ordinary state of the breaker flag, not an error.
  it("reads a missing object as absent", async () => {
    stubFetch(() => new Response("not found", { status: 404 }));

    expect(await new R2Store(credentials).get("internal/breaker/celestrak.json")).toBeNull();
  });

  it("raises anything else, rather than treating it as absent", async () => {
    stubFetch(() => new Response("denied", { status: 403 }));

    await expect(new R2Store(credentials).get("v1/sats.json")).rejects.toThrow(/403/);
  });

  it("carries the caching contract and content type on a write", async () => {
    stubFetch(() => new Response("", { status: 200 }));

    await new R2Store(credentials).put("v1/sats.json", "{}", {
      contentType: "application/json",
      cacheControl: "public, max-age=1800",
    });

    expect(calls[0]!.method).toBe("PUT");
    expect(calls[0]!.headers.get("content-type")).toBe("application/json");
    expect(calls[0]!.headers.get("cache-control")).toBe("public, max-age=1800");
  });

  it("raises when a write is refused", async () => {
    stubFetch(() => new Response("nope", { status: 500 }));

    await expect(new R2Store(credentials).put("v1/sats.json", "{}")).rejects.toThrow(/500/);
  });

  /**
   * R2 being unreachable and an upstream being unreachable are reported
   * identically by Node - `fetch failed`, with the reason nested underneath -
   * and they are fixed in completely different places. The key names which of
   * the two a reader is looking at, and the cause says what to do about it.
   */
  it("says which object it was reaching for when nothing answers", async () => {
    vi.stubGlobal("fetch", async () => {
      throw new TypeError("fetch failed", {
        cause: Object.assign(new Error("getaddrinfo EAI_AGAIN abc123.r2.cloudflarestorage.com"), {
          code: "EAI_AGAIN",
        }),
      });
    });

    await expect(new R2Store(credentials).get("v1/sats.json")).rejects.toThrow(
      "R2 GET v1/sats.json could not be sent: getaddrinfo EAI_AGAIN abc123.r2.cloudflarestorage.com",
    );
    await expect(new R2Store(credentials).put("v1/sats.json", "{}")).rejects.toThrow(
      "R2 PUT v1/sats.json could not be sent: getaddrinfo EAI_AGAIN abc123.r2.cloudflarestorage.com",
    );
  });
});
