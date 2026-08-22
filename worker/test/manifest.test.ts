import { describe, expect, it } from "vitest";
import {
  buildManifest,
  importSigningKey,
  MANIFEST_SCHEMA,
  serialiseManifest,
  signManifest,
  toPem,
} from "../src/manifest";

const INPUT = {
  minSupportedVersion: 1,
  issuedAt: 1_755_853_200,
  message: null,
  baseUrl: "https://sky.arek.sh/v1",
  weatherUrl: "https://api.open-meteo.com/v1/forecast",
};

/** A throwaway Ed25519 keypair, so tests never touch the project's real key. */
async function generateKeyPem(): Promise<{ pem: string; publicKey: CryptoKey }> {
  const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ])) as CryptoKeyPair;
  const pkcs8 = (await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer;
  return { pem: toPem(new Uint8Array(pkcs8)), publicKey: pair.publicKey };
}

describe("buildManifest", () => {
  it("advertises the endpoints the app needs, from configuration", () => {
    const manifest = buildManifest(INPUT);

    expect(manifest.schema).toBe(MANIFEST_SCHEMA);
    expect(manifest.min_supported_version).toBe(1);
    expect(manifest.message).toBeNull();
    expect(manifest.endpoints).toEqual({
      status: "https://sky.arek.sh/v1/status.json",
      oval: "https://sky.arek.sh/v1/oval.bin",
      weather: "https://api.open-meteo.com/v1/forecast",
    });
  });

  // Hardcoding the weather URL into the APK reproduces exactly the trap the
  // manifest exists to avoid.
  it("carries the weather endpoint so it is not baked into the APK", () => {
    expect(buildManifest({ ...INPUT, weatherUrl: "https://example.test/f" }).endpoints.weather).toBe(
      "https://example.test/f",
    );
  });

  it("passes a broadcast message through when one is set", () => {
    expect(buildManifest({ ...INPUT, message: "Aurora alerts paused" }).message).toBe(
      "Aurora alerts paused",
    );
  });
});

describe("serialiseManifest", () => {
  // The signature covers exact bytes. If anything re-serialises the document
  // between signing and publishing, every install rejects it.
  it("is stable across calls", () => {
    expect(serialiseManifest(buildManifest(INPUT))).toBe(serialiseManifest(buildManifest(INPUT)));
  });

  it("produces parseable JSON", () => {
    expect(JSON.parse(serialiseManifest(buildManifest(INPUT))).schema).toBe(MANIFEST_SCHEMA);
  });
});

describe("signManifest", () => {
  it("produces a 64-byte detached Ed25519 signature", async () => {
    const { pem } = await generateKeyPem();
    const key = await importSigningKey(pem);

    const signature = await signManifest(key, "hello");

    expect(signature.byteLength).toBe(64);
  });

  it("produces a signature the matching public key verifies", async () => {
    const { pem, publicKey } = await generateKeyPem();
    const key = await importSigningKey(pem);
    const body = serialiseManifest(buildManifest(INPUT));

    const signature = await signManifest(key, body);

    const ok = await crypto.subtle.verify(
      { name: "Ed25519" },
      publicKey,
      signature,
      new TextEncoder().encode(body),
    );
    expect(ok).toBe(true);
  });

  // The whole point: a tampered manifest must not verify. `message` renders
  // into a notification on every install, so an attacker who could swap the
  // document without breaking the signature would have a phishing primitive.
  it("does not verify against altered content", async () => {
    const { pem, publicKey } = await generateKeyPem();
    const key = await importSigningKey(pem);
    const body = serialiseManifest(buildManifest(INPUT));

    const signature = await signManifest(key, body);

    const tampered = body.replace('"message":null', '"message":"Install the new version here"');
    expect(tampered).not.toBe(body);
    const ok = await crypto.subtle.verify(
      { name: "Ed25519" },
      publicKey,
      signature,
      new TextEncoder().encode(tampered),
    );
    expect(ok).toBe(false);
  });

  it("rejects a key from a different pair", async () => {
    const signer = await generateKeyPem();
    const other = await generateKeyPem();
    const key = await importSigningKey(signer.pem);
    const body = "payload";

    const signature = await signManifest(key, body);

    const ok = await crypto.subtle.verify(
      { name: "Ed25519" },
      other.publicKey,
      signature,
      new TextEncoder().encode(body),
    );
    expect(ok).toBe(false);
  });
});

describe("importSigningKey", () => {
  it("accepts the PEM openssl produces, whitespace and all", async () => {
    const { pem } = await generateKeyPem();

    await expect(importSigningKey(`\n${pem}\n\n`)).resolves.toBeDefined();
  });

  it("fails loudly on something that is not a private key", async () => {
    await expect(importSigningKey("not a pem")).rejects.toThrow(/PEM|key/i);
  });
});
