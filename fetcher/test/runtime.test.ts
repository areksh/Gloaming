import { afterEach, describe, expect, it, vi } from "vitest";
import { ensureRuntime } from "../src/runtime";

/**
 * The host's Node is not ours to choose.
 *
 * A seedbox shipped Node 18, where `crypto` is not a global, and every signed
 * request died with `crypto is not defined` - a message that names neither the
 * cause nor the fix.
 */
describe("ensureRuntime", () => {
  const real = globalThis.crypto;

  afterEach(() => {
    Object.defineProperty(globalThis, "crypto", { value: real, configurable: true });
    vi.unstubAllGlobals();
  });

  it("supplies Web Crypto where the runtime has no global for it", () => {
    Object.defineProperty(globalThis, "crypto", { value: undefined, configurable: true });

    ensureRuntime();

    expect(typeof globalThis.crypto.subtle.digest).toBe("function");
  });

  it("leaves a runtime that already has one alone", () => {
    ensureRuntime();

    expect(globalThis.crypto).toBe(real);
  });

  /** No substitute exists, so it says which Node it is on instead. */
  it("names the running version when fetch is missing", () => {
    vi.stubGlobal("fetch", undefined);

    expect(() => ensureRuntime()).toThrow(/fetch API.*Node.*18/s);
  });
});
