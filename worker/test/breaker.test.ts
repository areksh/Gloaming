import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import {
  BreakerTrippedError,
  breakerKey,
  fetchThroughBreaker,
  readBreaker,
  trip,
} from "../src/breaker";

const now = () => new Date("2026-08-22T12:00:00Z");
const later = () => new Date("2026-08-22T14:00:00Z");

/** Each test uses its own source name: storage is shared within a file. */
let counter = 0;
const uniqueSource = () => `brk${counter++}`;

/** Collects the ntfy pushes a trip sends, and answers upstream with `status`. */
function recorder(status: number) {
  const posts: { url: string; body: string }[] = [];
  const requests: { url: string; redirect?: RequestInit["redirect"] }[] = [];

  const fetcher = (async (url: RequestInfo | URL, init?: RequestInit) => {
    if (String(url).includes("ntfy")) {
      posts.push({ url: String(url), body: String(init?.body ?? "") });
      return new Response("ok");
    }
    requests.push({ url: String(url), redirect: init?.redirect });
    return new Response("body", { status });
  }) as unknown as typeof fetch;

  return { posts, requests, deps: { fetch: fetcher, now } };
}

const alerting = {
  ...env,
  NTFY_BASE_URL: "https://ntfy.example",
  NTFY_TOPIC: "topic",
} as unknown as Env;

describe("readBreaker", () => {
  it("reports a closed breaker when nothing has tripped it", async () => {
    expect(await readBreaker(env, uniqueSource())).toBeNull();
  });
});

describe("trip", () => {
  it("records the response code, the URL and the time", async () => {
    const source = uniqueSource();
    const { deps } = recorder(500);

    await trip(alerting, deps, source, { status: 500, url: "https://celestrak.org/x" });

    const state = await readBreaker(env, source);
    expect(state?.status).toBe(500);
    expect(state?.url).toBe("https://celestrak.org/x");
    expect(state?.tripped_at).toBe("2026-08-22T12:00:00.000Z");
  });

  it("pushes one alert naming the code and the manual reset", async () => {
    const source = uniqueSource();
    const { posts, deps } = recorder(403);

    await trip(alerting, deps, source, { status: 403, url: "https://celestrak.org/x" });

    expect(posts).toHaveLength(1);
    expect(posts[0]!.body).toContain("403");
    // 403 means "not updated since your last download", which is the one code a
    // human is likely to shrug at. Say so, or the alert reads as an outage.
    expect(posts[0]!.body).toContain("not updated");
    expect(posts[0]!.body).toContain(breakerKey(source));
  });

  // The breaker's whole job is to stop querying until a human looks. A second
  // trip must not overwrite what the first one recorded, nor alert again.
  it("keeps the first trip and stays quiet on a later one", async () => {
    const source = uniqueSource();
    const first = recorder(500);
    await trip(alerting, first.deps, source, { status: 500, url: "https://celestrak.org/x" });

    const second = recorder(404);
    await trip(alerting, { ...second.deps, now: later }, source, {
      status: 404,
      url: "https://celestrak.org/y",
    });

    const state = await readBreaker(env, source);
    expect(state?.status).toBe(500);
    expect(state?.tripped_at).toBe("2026-08-22T12:00:00.000Z");
    expect(second.posts).toHaveLength(0);
  });
});

describe("fetchThroughBreaker", () => {
  it("returns the response when the breaker is closed and upstream is 200", async () => {
    const source = uniqueSource();
    const { requests, deps } = recorder(200);

    const response = await fetchThroughBreaker(env, deps, source, {
      url: "https://celestrak.org/gp.php",
      userAgent: "Gloaming/0.1",
    });

    expect(response.status).toBe(200);
    expect(requests).toHaveLength(1);
    expect(await readBreaker(env, source)).toBeNull();
  });

  // Every code CelesTrak documents means stop, not retry: 301 a legacy URL
  // still being redirected, 403 no new data, 404 a decade-old URL, 50x a
  // struggling server.
  for (const status of [301, 403, 404, 500, 503]) {
    it(`trips on HTTP ${status} and refuses to query again`, async () => {
      const source = uniqueSource();
      const first = recorder(status);

      await expect(
        fetchThroughBreaker(alerting, first.deps, source, {
          url: "https://celestrak.org/gp.php",
          userAgent: "Gloaming/0.1",
        }),
      ).rejects.toThrow(BreakerTrippedError);
      expect((await readBreaker(env, source))?.status).toBe(status);

      const second = recorder(200);
      await expect(
        fetchThroughBreaker(alerting, second.deps, source, {
          url: "https://celestrak.org/gp.php",
          userAgent: "Gloaming/0.1",
        }),
      ).rejects.toThrow(BreakerTrippedError);
      // The point of the whole exercise: no second request left the Worker.
      expect(second.requests).toHaveLength(0);
      expect(second.posts).toHaveLength(0);
    });
  }

  // A followed redirect would arrive as a 200 from wherever it pointed, and the
  // one code that says "your URL is legacy and will stop working" would never
  // be seen.
  it("does not follow redirects", async () => {
    const source = uniqueSource();
    const { requests, deps } = recorder(200);

    await fetchThroughBreaker(env, deps, source, {
      url: "https://celestrak.org/gp.php",
      userAgent: "Gloaming/0.1",
    });

    expect(requests[0]!.redirect).toBe("manual");
  });

  it("names the app in the User-Agent", async () => {
    const source = uniqueSource();
    const seen: string[] = [];
    const fetcher = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      seen.push(new Headers(init?.headers).get("User-Agent") ?? "");
      return new Response("body");
    }) as unknown as typeof fetch;

    await fetchThroughBreaker(env, { fetch: fetcher, now }, source, {
      url: "https://celestrak.org/gp.php",
      userAgent: "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)",
    });

    expect(seen[0]).toBe("Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)");
  });

  // Manual reset, exactly as documented: delete the object.
  it("queries again once the state object is deleted", async () => {
    const source = uniqueSource();
    const tripped = recorder(500);
    await expect(
      fetchThroughBreaker(alerting, tripped.deps, source, {
        url: "https://celestrak.org/gp.php",
        userAgent: "Gloaming/0.1",
      }),
    ).rejects.toThrow(BreakerTrippedError);

    await env.DATA.delete(breakerKey(source));

    const reset = recorder(200);
    const response = await fetchThroughBreaker(alerting, reset.deps, source, {
      url: "https://celestrak.org/gp.php",
      userAgent: "Gloaming/0.1",
    });
    expect(response.status).toBe(200);
    expect(reset.requests).toHaveLength(1);
  });

  /**
   * A refused connection or a timeout is not a response, so it is not the
   * signal the policy is about, and a transient one must not demand a human
   * before elements can refresh again. It still fails the source, which is
   * what `health.json` and its alert are for.
   */
  it("leaves the breaker closed when the connection fails outright", async () => {
    const source = uniqueSource();
    const fetcher = (async () => {
      throw new Error("connection refused");
    }) as unknown as typeof fetch;

    await expect(
      fetchThroughBreaker(env, { fetch: fetcher, now }, source, {
        url: "https://celestrak.org/gp.php",
        userAgent: "Gloaming/0.1",
      }),
    ).rejects.toThrow("connection refused");

    expect(await readBreaker(env, source)).toBeNull();
  });
});
