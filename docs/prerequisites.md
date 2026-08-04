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
| 11 | Worker deployed, manifest signing secret uploaded | Done, publishing live |
| 11a | **Cache Rule on `sky.arek.sh/v1/*`** | **Outstanding - see below** |
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

### Wiring it into the build, at release time

Do not put the passphrase in `app/build.gradle.kts`. Read it from a gitignored
`keystore.properties` or from the environment, and let the release build fail
loudly if it is absent rather than silently producing an unsigned or
debug-signed APK. This is not needed until there is something to release.

## Remaining Cloudflare setup: the Cache Rule

The read path works but the CDN is not yet honouring the caching contract, and
the deployment token cannot change zone settings. In the dashboard, under
**Caching -> Cache Rules**, add a rule matching hostname `sky.arek.sh` and URI
path starting `/v1/`, set to:

- **Eligible for cache** - without this `status.json` is never cached, because
  Cloudflare's default policy keys off file extension and `.json` is not in it.
  Every device poll currently reaches R2, which is exactly the fan-out the
  architecture exists to avoid.
- **Browser TTL: respect origin** - the zone's 4-hour default is currently
  rewriting `oval.bin` from `max-age=120` to `max-age=14400`, so a device would
  sit on a stale aurora grid for four hours.
- Edge TTL: respect origin.

Verify afterwards with `curl -sI https://sky.arek.sh/v1/status.json`: the
`cf-cache-status` should reach `HIT` on a second request rather than staying
`DYNAMIC`, and `oval.bin` should report `max-age=120`.

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
