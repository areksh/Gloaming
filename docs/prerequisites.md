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
| 6 | GitHub repository and `origin` remote | Done; moved from Codeberg in November 2026 |
| 7 | Package id `sh.arek.gloaming` | Done |
| 8 | Compose app scaffold | Done |
| 9 | Wrangler installed and authenticated | Done |
| 10 | Android release keystore (`secrets/gloaming-release.p12`) | Done, fingerprint in README |
| 10a | Release signing wired into the build | Done; needs `keystore.properties` on the machine that cuts a release |
| 11 | Worker deployed, manifest signing secret uploaded | Done, publishing live |
| 11a | Cache Rule on `sky.arek.sh/v1/*` | Done, verified |
| 12 | KV namespace for the CelesTrak circuit breaker | Not needed until Phase 3 |
| 13 | CI runner | Decided against; ADR-0014. The gate is the pre-push hook and `./go release` |
| 14 | GitHub access token for publishing releases | Needed by `./go push` and `./go release` |

The F-Droid-repo-on-Codeberg-Pages setup and F-Droid main-repo submission are
post-v1 (Sec. 12).

## The build machine

`./go setup` installs only what the Node projects need. The Android build needs
two things it leaves to you: JDK 21 and the Android SDK.

### JDK 21

`app/build.gradle.kts` compiles to Java 21, so Gradle needs a JDK 21. A JRE
will not work, because the build calls `javac`. On Debian 13 (trixie) it is in
main:

```sh
sudo apt update
sudo apt install openjdk-21-jdk
java -version     # want: openjdk version "21.0.x"
```

Gradle finds it as `java` on `PATH`, or through `JAVA_HOME` if that is set, so
the apt install needs nothing more. If several JDKs are installed, choose 21
with `sudo update-alternatives --config java` (and again for `javac`), or point
`JAVA_HOME` at `/usr/lib/jvm/java-21-openjdk-amd64`. A newer JDK is not needed,
and the build is only checked against 21.

### Android SDK

Install the platform `android-37.0`. The build and `./go` find the SDK through
`ANDROID_HOME` (or `ANDROID_SDK_ROOT`), or through `sdk.dir=` in a
`local.properties` at the repository root. That file is gitignored, because the
path differs from machine to machine.

Debian's packaged SDK is too old for this, so use Google's command-line tools.
Put the SDK in your home directory, not in `/usr/lib/android-sdk`: that
directory is owned by root, so Gradle could not install build-tools into it.
None of this needs root.

```sh
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip commandlinetools-linux-13114758_latest.zip
mv cmdline-tools latest
rm commandlinetools-linux-13114758_latest.zip
```

If that link has gone, copy the current Linux one from "Command line tools only"
at developer.android.com/studio. Any build will do, because the install below
updates it. The `latest/` directory name is required: `sdkmanager` works out the
SDK root from its own location and refuses to run from anywhere else.

Add this to `~/.zshrc` (or `~/.bashrc`) and open a new shell:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Then accept the licences yourself, and install the packages:

```sh
sdkmanager --licenses
sdkmanager "cmdline-tools;latest" "platform-tools" "platforms;android-37.0"
```

Build-tools are not listed, because once the licences are accepted and the SDK
directory is writable, the Android Gradle plugin installs the version it wants
on the first build. Check with `./go check`.

If `/usr/bin/adb` is also installed from Debian's
`google-android-platform-tools-installer`, the `PATH` line above puts the SDK's
`adb` first. If both are used, they fight over the adb server ("adb server
version doesn't match this client"). Either run `adb kill-server` when that
happens, or `sudo apt remove google-android-platform-tools-installer`.

## GitHub access token

`./go push` and `./go release` publish through the GitHub REST API, which needs
a token that can write releases. Generate a fine-grained personal access token
at github.com under Settings -> Developer settings -> Personal access tokens ->
Fine-grained tokens, with access to this repository only and the single
repository permission Contents: Read and write - it is used for releases and
their assets, and for no other call. Give it an expiry and put a reminder
against it; an expired token fails with a 401 at the very end of a release.

Put it in `secrets/github-token.txt`, which is gitignored along with the rest
of that directory, or set `GITHUB_TOKEN` in the environment. The file wins if
both are present, which is the order the signing material uses. A file still
holding the template's placeholder counts as absent, so an unfilled template
fails with a message about the token rather than with a 401 that reads like a
revoked one.

## Signing material on the machine that releases

`keystore.properties` tells the build where the keystore is and what its
passphrase is. It is gitignored by name; `keystore.properties.example` is the
template beside it:

```sh
cp keystore.properties.example keystore.properties
```

Fill in `storePassword`. Only the machine that cuts releases needs it - every
other machine builds debug and `minified` without it, and a release build
refuses rather than emitting an APK signed with a key nobody can update from.

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
