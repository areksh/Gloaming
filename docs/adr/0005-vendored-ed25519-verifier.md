# 5. A vendored Ed25519 verifier rather than a crypto library

Date: 2026-08-05

## Status

Accepted

## Context

The app verifies the signed manifest (ADR-0003, Sec. 10) before trusting it,
which needs Ed25519 signature verification on `minSdk 24`. Android's own
Ed25519 support arrives only around API 33, so the platform cannot supply it
across the supported range.

The alternatives were:

- **BouncyCastle.** The safe, conventional answer, and several megabytes of
  dependency for a single primitive. That is a poor trade for an APK aimed at
  F-Droid and at users who keep hardware alive for a decade.
- **Tink or another Google library.** Same objection, plus a larger transitive
  surface.
- **Implement RFC 8032 verification directly**, validated against the
  specification's own test vectors.

"Do not roll your own cryptography" is sound general advice, so departing from
it needs stating rather than assuming.

## Decision

Implement Ed25519 **verification only**, in
`sh.arek.gloaming.crypto.Ed25519Verifier`, following the reference algorithm in
RFC 8032 section 5.1.7.

## Consequences

The usual objection to hand-rolled cryptography is mostly about key material
leaking through implementation flaws, and that hazard is largely absent here:

- **No secret is ever handled.** Verification touches a public key compiled into
  the APK, a manifest served over HTTPS, and a signature. There is no private
  key on the device, so there is no secret for a side channel to leak and no
  need for constant-time discipline - which is what makes signing
  implementations genuinely difficult to get right.
- **The residual risk is correctness, and correctness is testable.** The
  implementation is checked against all three RFC 8032 section 7.1 vectors,
  including the empty-message case, against a real signature produced by the
  project's own Worker, and against tampered messages, tampered signatures,
  wrong keys, malformed lengths, and a public key that is not a curve point.
  Every vector was confirmed against OpenSSL and Node before being written down
  rather than copied from memory.
- **Signatures with an unreduced scalar are rejected.** Without that check, the
  group order can be added to a valid `S` to yield a second signature over the
  same document, so a signature would no longer identify one exact set of bytes.

Costs accepted:

- Arithmetic uses `BigInteger` rather than packed limbs, so a verification costs
  tens of milliseconds. It happens about once a day, so clarity is worth more
  than speed here.
- The code is the project's to maintain. It implements a frozen specification
  against fixed test vectors, so it should not need to change; if it ever does,
  the vectors are the safety net.

If the app one day needs to *sign* anything on-device, this decision does not
extend to that. Signing is where constant-time behaviour and nonce handling
matter, and the answer there would be a real library.
