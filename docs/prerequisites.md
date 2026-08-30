# Provisioning and setup

One-off setup for the project's infrastructure and signing material. Nothing
here is blocked on a third party: all three data-source permissions (AuroraWatch
UK, Open-Meteo, CelesTrak) were granted in July 2026 (Sec. 1 of
`docs/sourcing and constraints.md`). Keep the correspondence.

## Status

| | Item | State |
|---|---|---|
| 1 | Domain `arek.sh` on Cloudflare DNS | Done |
| 2 | R2 bucket `gloaming-data` | Done |
| 3 | Public custom domain `sky.arek.sh` (TLS 1.2 minimum) | Done, serving |
| 4 | Ed25519 manifest keypair | Done, in `secrets/` |
| 5 | Maintainer ntfy topic | Done, in `secrets/` |
| 6 | Codeberg repository and `origin` remote | Done |
| 7 | Package id `sh.arek.gloaming` | Done |
| 8 | Compose app scaffold | Done |
| 9 | Wrangler installed and authenticated | Done |
| 10 | Android release keystore (`secrets/gloaming-release.p12`) | Done, fingerprint in README |
| 10a | Release signing wired into the build | Done; needs `keystore.properties` on the machine that cuts a release |
| 11 | Worker deployed, manifest signing secret uploaded | Done, publishing live |
| 11a | Cache Rule on `sky.arek.sh/v1/*` | Done, verified |
| 12 | KV namespace for the CelesTrak circuit breaker | Not needed until Phase 3 |
| 13 | CI runner on Codeberg (Woodpecker or Forgejo Actions) | Outstanding |

The F-Droid-repo-on-Codeberg-Pages setup and F-Droid main-repo submission are
post-v1 (Sec. 12).

## Generating the Android release keystore

Every future update must be signed with this exact key. There is no recovery:
lose it and every installed copy has to be uninstalled and reinstalled by hand,
because Android refuses an update signed by a different key. Off-Play there is
no Play App Signing to fall back on, so this file and its passphrase are the
single most valuable things in the project.

Generate it yourself rather than having it done for you, so the passphrase never
passes through a tool, a shell history or a log.

```sh
cd /home/arek/Code/Gloaming

keytool -genkeypair -v \
  -keystore secrets/gloaming-release.p12 \
  -storetype PKCS12 \
  -alias gloaming \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Gloaming, O=Gloaming, C=GB"
```

It will prompt for a passphrase. Notes on the choices:

- **PKCS12, not JKS.** JKS is the obsolete proprietary format; `keytool` warns
  about it and PKCS12 is the standard. One passphrase covers store and key.
- **RSA 4096.** Android supports it everywhere back to API 24, and this key has
  to stay credible for decades.
- **10,000 days** (about 27 years). The validity must outlast the app: an expired
  signing certificate means no further updates. F-Droid also expects a long
  validity.
- `secrets/` is gitignored, as are `*.keystore` and `*.jks`; `*.p12` sits inside
  the ignored directory.

Then record the fingerprint, which is what lets anyone verify an APK really came
from this project:

```sh
keytool -list -v -keystore secrets/gloaming-release.p12 -storetype PKCS12 \
  | grep -A1 "SHA256:"
```

Publish that fingerprint in the README once you have it, so users installing via
Obtainium can check what they are trusting.

### Back it up before using it

- Copy `secrets/gloaming-release.p12` somewhere durable and offline - an
  encrypted volume, or a password manager that takes file attachments. More than
  one location.
- Store the passphrase in the password manager, separately from the file.
- Do not put either in the repository, in cloud storage that syncs
  automatically, or in a note on the build machine only.

### Wiring it into the build

Done, and this is what it expects. `app/build.gradle.kts` reads the signing
material from a `keystore.properties` in the repository root, which is
gitignored, or from the environment where there is no file to read. The
passphrase is in neither the build file nor the repository.

```properties
# keystore.properties - never committed
storeFile=secrets/gloaming-release.p12
storePassword=<the passphrase>
# keyAlias defaults to gloaming, keyPassword to storePassword
```

The environment form is `GLOAMING_KEYSTORE`, `GLOAMING_KEYSTORE_PASSWORD`,
`GLOAMING_KEY_ALIAS` and `GLOAMING_KEY_PASSWORD`, for a build machine with
nowhere to put a file.

Without either, a release build fails at `validateSigningRelease` and says which
properties are missing. That is deliberate: an APK signed with the debug key, or
with none, installs and runs and looks like a build that worked, and only fails
much later when the real key tries to update it and Android refuses. Off-Play
there is no Play App Signing to notice on the project's behalf, so the failure
has to be at build time or it is not caught at all.

`./go release` then compares the certificate in the APK it has just built
against the fingerprint published in the README, and says which key signed it.
Debug builds need none of this and are unaffected.

## The Cache Rule

A Cache Rule on hostname `sky.arek.sh`, path starting `/v1/`, makes the objects
cache-eligible and sets browser and edge TTL to respect the origin. It is
load-bearing, not tidying:

- Without **eligible for cache**, `status.json` is never cached at all, because
  Cloudflare's default policy keys off file extension and `.json` is not in it.
  Every device poll would reach R2 - the fan-out the architecture exists to
  avoid.
- Without **browser TTL: respect origin**, the zone's 4-hour default rewrites
  `oval.bin` from `max-age=120` to `max-age=14400`, leaving a device on a stale
  aurora grid for four hours.

If the read path ever looks wrong, check this rule first. Verify with:

```sh
curl -sI https://sky.arek.sh/v1/status.json   # want cf-cache-status: HIT on a repeat
curl -sI https://sky.arek.sh/v1/oval.bin      # want max-age=120, not 14400
```

## Already done

The manifest signing key is uploaded as the Worker secret
`MANIFEST_SIGNING_KEY`; its public half is compiled into the APK so the app can
reject a manifest that was not signed by this project (Sec. 10). To rotate it:

```sh
cd worker
npx wrangler secret put MANIFEST_SIGNING_KEY < ../secrets/manifest_ed25519_private.pem
```

## Not blockers

Left deliberately open, to be settled by writing code or reading a policy page
(Sec. 15): default alert thresholds `T_on`/`T_off`, adaptive-polling escalation
values, storm-mode exit condition, and Shetland per-region alerting. Begin
logging aurora activity against a subjective "was it worth it?" judgement from
Phase 1, so there is something to calibrate against by the time Phase 2 needs
it.
