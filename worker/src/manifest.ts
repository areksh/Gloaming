/**
 * The signed manifest.
 *
 * `index.json` is the only URL baked into the APK. APKs live forever - F-Droid
 * and Obtainium users do not reliably update - so any other URL hardcoded into
 * a release would leave those installs polling a dead endpoint indefinitely,
 * with no way to stop them. Everything else is reached through here.
 *
 * It is signed because `message` renders into a notification on every install.
 * If the domain lapsed or were hijacked, an unsigned manifest would be a
 * phishing primitive with no user recourse. The app verifies an Ed25519
 * detached signature against a public key compiled into the APK and falls back
 * to its last known-good copy on failure.
 */

export const MANIFEST_SCHEMA = 1;

export interface Manifest {
  schema: number;
  min_supported_version: number;
  /**
   * Unix seconds at which these contents were first published.
   *
   * The app refuses a manifest older than the one it already holds, so an
   * attacker who can answer for the domain cannot replay a correctly signed
   * older copy to move the endpoints back or restore a withdrawn `message`.
   * Signing alone does not stop that: an old signature stays valid forever.
   *
   * A wall clock rather than a counter, so that deleting the object - or
   * rebuilding the bucket - does not restart the sequence and lock every
   * existing install out of every future manifest.
   */
  issued_at: number;
  message: string | null;
  endpoints: Record<string, string>;
}

export interface ManifestInput {
  minSupportedVersion: number;
  issuedAt: number;
  message: string | null;
  baseUrl: string;
  weatherUrl: string;
}

/**
 * Only endpoints that exist are advertised. Since the manifest is re-fetched
 * daily, adding a key when its object starts being published costs nothing,
 * whereas advertising a URL that 404s today does.
 */
export function buildManifest(input: ManifestInput): Manifest {
  return {
    schema: MANIFEST_SCHEMA,
    min_supported_version: input.minSupportedVersion,
    issued_at: input.issuedAt,
    message: input.message,
    endpoints: {
      status: `${input.baseUrl}/status.json`,
      oval: `${input.baseUrl}/oval.bin`,
      sats: `${input.baseUrl}/sats.json`,
      comets: `${input.baseUrl}/comets.json`,
      weather: input.weatherUrl,
    },
  };
}

/**
 * The signature covers exact bytes, so the string produced here is what gets
 * both signed and published. Anything that re-serialises the document in
 * between would invalidate the signature on every install.
 */
export function serialiseManifest(manifest: Manifest): string {
  return JSON.stringify(manifest);
}

function base64Encode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

function base64Decode(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

/** Wraps DER bytes as a PKCS8 PEM, matching what `openssl genpkey` writes. */
export function toPem(pkcs8: Uint8Array): string {
  const body = base64Encode(pkcs8).replace(/(.{64})/g, "$1\n").trimEnd();
  return `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----`;
}

export async function importSigningKey(pem: string): Promise<CryptoKey> {
  const body = /-----BEGIN PRIVATE KEY-----([\s\S]*?)-----END PRIVATE KEY-----/.exec(pem)?.[1];
  if (body === undefined) {
    throw new Error("Signing key is not a PKCS8 PEM private key");
  }

  const der = base64Decode(body.replace(/\s+/g, ""));
  return crypto.subtle.importKey("pkcs8", der, { name: "Ed25519" }, false, ["sign"]);
}

export async function signManifest(key: CryptoKey, body: string): Promise<Uint8Array> {
  const signature = await crypto.subtle.sign(
    { name: "Ed25519" },
    key,
    new TextEncoder().encode(body),
  );
  return new Uint8Array(signature);
}
