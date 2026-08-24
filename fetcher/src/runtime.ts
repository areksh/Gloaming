import { webcrypto } from "node:crypto";

/**
 * Makes the Web APIs this depends on available, on whatever Node the host has.
 *
 * `crypto` only became a global in Node 19; before that Web Crypto exists but
 * has to be reached for in `node:crypto`. Request signing needs it, so on an
 * older runtime every call failed with a bare `crypto is not defined` - which
 * says nothing about what is actually wrong or how to fix it, and which cost an
 * evening the first time it happened on a real host.
 *
 * The alternative was to demand a particular Node version and let the operator
 * find out the hard way. A machine that is not yours to reinstall is exactly
 * the machine that will have the wrong version, so this adapts instead.
 */
export function ensureRuntime(): void {
  if (globalThis.crypto?.subtle === undefined) {
    Object.defineProperty(globalThis, "crypto", {
      value: webcrypto,
      configurable: true,
      writable: false,
      enumerable: false,
    });
  }

  // Nothing to substitute here: `fetch` arrived in Node 18 and this needs it.
  // Say which version is running, because that is the first question anyone
  // debugging this will ask.
  if (typeof globalThis.fetch !== "function") {
    throw new Error(
      `This needs the fetch API, which Node has had since 18. Running on ${process.version}.`,
    );
  }
}
