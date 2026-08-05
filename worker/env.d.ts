// Secrets are not part of the Wrangler configuration, so `wrangler types`
// cannot see them and the generated Env omits them. Declaring them here keeps
// them type-checked without putting a value anywhere near the repository.
//
// Both the global `Env` and `Cloudflare.Env` are augmented: generated code
// declares the two separately, and `env` imported from "cloudflare:workers" is
// typed as the latter.
//
// Set with:
//   npx wrangler secret put MANIFEST_SIGNING_KEY < ../secrets/manifest_ed25519_private.pem

interface GloamingSecrets {
  /** PKCS8 PEM Ed25519 private key used to sign `index.json`. */
  MANIFEST_SIGNING_KEY: string;
  /**
   * Maintainer-only ntfy topic for failure alerts. Secret because anyone who
   * knows a topic name can read and post to it. Empty disables alerting.
   */
  NTFY_TOPIC: string;
}

interface Env extends GloamingSecrets {}

declare namespace Cloudflare {
  interface Env extends GloamingSecrets {}
}
