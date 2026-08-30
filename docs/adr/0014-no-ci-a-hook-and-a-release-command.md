# 14. No CI: a hook and a release command

Date: 2026-08-30

## Status

Accepted.

## Context

`docs/prerequisites.md` has carried "CI runner on Codeberg (Woodpecker or
Forgejo Actions)" as outstanding since before development started, and
`docs/architecture.md` has said "CI: none yet ... an alternative host has still
to be chosen". Preparing the first release made that a question to answer rather
than to keep deferring.

The hosting is not the obstacle. Codeberg CI is Woodpecker, granted per user on
request for public, freely licensed repositories - which this is. Codeberg's
hosted Forgejo Actions is deliberately limited, but self-hosted runners register
to a Codeberg repository and connect outbound, so a runner on any machine here
would work. A push mirror to GitLab or GitHub would open the rest of the market.

The obstacle is what CI could actually cover, and what it would cost to let it.

**It could only run `./go check`.** The app's unit tests, the instrumented
sources compiled but not run, and both Node projects. Under a minute. Everything
that has ever caught a real defect in the alerting path needs a real phone: the
609 instrumented tests, the catalogue of nights drawn on a real screen, and
above all the two harnesses in `tools/`. App standby deferred an alarm by 365
days on hardware; no emulator has that behaviour to find, and an emulator that
did would still not answer whether a notification posts from deep idle on this
phone. The load-bearing milestone is overnight delivery on a real device
(Sec. 14 step 6), and no cloud runner can attempt it.

**Letting it publish means letting it sign.** The release keystore is described
in `docs/prerequisites.md` as the single most valuable thing in the project:
there is no Play App Signing to fall back on, and losing or leaking it means
every installed copy has to be uninstalled and reinstalled by hand. Sec. 7 of
`docs/sourcing and constraints.md` already rejects JitPack for astronomy-engine
partly as "a third-party build service in the supply chain". Putting the signing
key into a hosted runner's secret store to save a minute of waiting contradicts
that on a far more valuable key, and points away from the reproducible builds
F-Droid's main repo will want (Sec. 12).

A self-hosted runner avoids the key leaving the property, but then buys very
little: it is a daemon to keep alive on the same machine, for asynchrony and a
clean checkout.

## Decision

No CI. The gate is two things, both on the machine that holds the key and has
the phone plugged in.

**`tools/hooks/pre-push` runs `./go check` before every push,** and refuses the
push if it fails. It lives in the tree rather than in `.git/hooks`, with
`core.hooksPath` pointed at it by `./go setup`, so it cannot quietly diverge on
a checkout somebody set up months ago. Deletions pass straight through.

**`./go release <version>` runs everything else,** in the order the phone
allows: `check`, the instrumented suite, that suite again against R8's output,
the debuggable build reinstalled, then `bfu-check.sh` and `doze-check.sh`. Then
the version bump, the changelog, the signed APK, the tag, and the release. It
asks once before anything leaves the machine.

Between the two, `./go push` publishes the tip of `main` as a rolling
development release, so there is somewhere to get a current build from without
cutting a version.

## Consequences

The signing key never leaves the machine it was generated on, and no third party
is in the supply chain between a commit and an APK. There is no runner to keep
alive, no secret to rotate out of a service, and no second definition of what
the build is - `./go` is the only one, and CI would have been a copy of it.

The gate only runs where the phone is. A push from a machine with no device gets
`check` and nothing more, which is honest but is less than it sounds: `check` is
the part that was never in doubt.

Nothing outside this repository attests that a published APK came from the
commit it claims. The build stamps the commit into the APK and the settings
screen shows it, and `./go release` refuses to build from a dirty tree, but that
is the project vouching for itself. Reproducible builds are the answer to that
and remain a separate, post-v1 piece of work (Sec. 12).

The hook is bypassable with `--no-verify`. That is a matter of discipline rather
than of mechanism, and the message it prints on failure says so.

Revisit if a second person works on this, if pushes routinely come from a
machine with no phone, or if F-Droid's main repo requires a build that runs
unattended on someone else's server - the last of which is a different problem
from CI and will need its own answer regardless.
