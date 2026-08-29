import { describe, expect, it } from "vitest";
import { explainFailure, fetchOrExplain } from "../src/network";

/**
 * What a failed connection is allowed to look like in the journal.
 *
 * The shapes tested here are the ones Node actually produces: every network
 * failure arrives as `TypeError: fetch failed` with the real reason nested
 * underneath, and reporting only the outer message is how a run ends up saying
 * nothing at all about what went wrong.
 */
const refused = () =>
  new TypeError("fetch failed", {
    cause: Object.assign(new Error("connect ECONNREFUSED 104.168.149.178:443"), {
      code: "ECONNREFUSED",
    }),
  });

/** A fetch that never gets a response, as undici's does when nothing answers. */
const failing = (error: unknown): typeof fetch =>
  (async () => {
    throw error;
  }) as unknown as typeof fetch;

describe("explainFailure", () => {
  it("reports the cause rather than the wrapper Node puts around it", () => {
    expect(explainFailure(refused())).toBe("connect ECONNREFUSED 104.168.149.178:443");
  });

  /**
   * A connect timeout is the one that matters most here: it is what a host
   * dropping packets looks like, and undici's message does not name its own
   * code.
   */
  it("adds the code when the message does not already carry it", () => {
    const error = new TypeError("fetch failed", {
      cause: Object.assign(
        new Error("Connect Timeout Error (attempted address: celestrak.org:443, timeout: 10000ms)"),
        { code: "UND_ERR_CONNECT_TIMEOUT" },
      ),
    });

    expect(explainFailure(error)).toBe(
      "Connect Timeout Error (attempted address: celestrak.org:443, timeout: 10000ms) " +
        "(UND_ERR_CONNECT_TIMEOUT)",
    );
  });

  it("follows a chain of causes to the bottom", () => {
    const error = new TypeError("fetch failed", {
      cause: new Error("write EPROTO", {
        cause: Object.assign(new Error("certificate has expired"), {
          code: "CERT_HAS_EXPIRED",
        }),
      }),
    });

    expect(explainFailure(error)).toBe(
      "write EPROTO: certificate has expired (CERT_HAS_EXPIRED)",
    );
  });

  /**
   * A host with both an A and an AAAA record fails on both, and undici reports
   * that as an AggregateError - whose own message is the empty string. Reading
   * only the message there would say literally nothing.
   */
  it("reports the members of an AggregateError, which has no message of its own", () => {
    const error = new TypeError("fetch failed", {
      cause: new AggregateError([
        new Error("connect ECONNREFUSED 104.168.149.178:443"),
        new Error("connect ENETUNREACH 2606:4700::1:443"),
      ]),
    });

    expect(explainFailure(error)).toBe(
      "connect ECONNREFUSED 104.168.149.178:443; connect ENETUNREACH 2606:4700::1:443",
    );
  });

  /**
   * The reason is written into the message at the point the failure is
   * wrapped, and read again wherever that error is finally recorded. Without
   * this the health record would carry it twice, once inside the sentence and
   * once after it.
   */
  it("does not repeat a reason the wrapper has already stated", () => {
    const inner = refused().cause;
    const wrapped = new Error(`could not reach https://celestrak.org/: ${explainFailure(inner)}`, {
      cause: inner,
    });

    expect(explainFailure(wrapped)).toBe(
      "could not reach https://celestrak.org/: connect ECONNREFUSED 104.168.149.178:443",
    );
  });

  it("keeps the only message there is when nothing is underneath", () => {
    expect(explainFailure(new Error("fetch failed"))).toBe("fetch failed");
  });

  it("says something about a value that is not an Error at all", () => {
    expect(explainFailure("the pool is closed")).toBe("the pool is closed");
  });

  /**
   * A cause that points back at itself would otherwise walk forever, which
   * would turn a bad night for the upstream into a job that never exits.
   */
  it("stops rather than looping when the causes form a cycle", () => {
    const outer: Error & { cause?: unknown } = new Error("outer");
    const inner = new Error("inner", { cause: outer });
    outer.cause = inner;

    const explained = explainFailure(outer);

    expect(explained).toContain("outer");
    expect(explained.length).toBeLessThan(200);
  });
});

describe("fetchOrExplain", () => {
  /**
   * Three URLs are fetched in a run and any of them can be the one that fails,
   * so the message has to say which. Without it the journal cannot even
   * distinguish CelesTrak from R2.
   */
  it("names the URL as well as the reason", async () => {
    await expect(
      fetchOrExplain(failing(refused()), "https://celestrak.org/NORAD/elements/gp.php"),
    ).rejects.toThrow(
      "could not reach https://celestrak.org/NORAD/elements/gp.php: " +
        "connect ECONNREFUSED 104.168.149.178:443",
    );
  });

  it("hands back whatever answered, including a refusal, untouched", async () => {
    const fetcher = (async () => new Response("nope", { status: 503 })) as unknown as typeof fetch;

    const response = await fetchOrExplain(fetcher, "https://celestrak.org/");

    expect(response.status).toBe(503);
    expect(await response.text()).toBe("nope");
  });

  it("passes the request options through", async () => {
    const seen: RequestInit[] = [];
    const fetcher = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      seen.push(init ?? {});
      return new Response("ok");
    }) as unknown as typeof fetch;

    await fetchOrExplain(fetcher, "https://celestrak.org/", {
      headers: { "User-Agent": "Gloaming/0.1" },
      redirect: "manual",
    });

    expect(new Headers(seen[0]!.headers).get("User-Agent")).toBe("Gloaming/0.1");
    expect(seen[0]!.redirect).toBe("manual");
  });
});
