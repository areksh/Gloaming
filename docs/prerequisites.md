# Before development can commence

The provisioning and one-off setup needed before the first code task (recreating
the app scaffold). None of it is blocked on a third party: all three
data-source permissions (AuroraWatch UK, Open-Meteo, CelesTrak) were granted in
July 2026 (Sec. 1 of `docs/sourcing and constraints.md`). Keep the
correspondence.

## Checklist

1. **Buy the domain** (~GBP 10/year) and add it to Cloudflare DNS. The manifest
   and migration story depend on owning it before v1.0 (Sec. 10): it is the
   difference between "migration is a DNS change" and "migration is impossible".

2. **Cloudflare account** (free plan), with:
   - an **R2 bucket** and a **public custom domain** serving `sky.<domain>/v1/`;
   - **Cron Triggers** on the Worker (confirm the free plan allows the four
     schedules needed and 3-minute cron - issue #2 in `docs/architecture.md`);
   - a **KV namespace** for the CelesTrak circuit-breaker flag;
   - **Wrangler** installed and authenticated.

3. **Generate the Ed25519 manifest keypair offline.** The private key becomes a
   Wrangler secret; the public key is committed for compile-in to the APK. Back up
   the private key. (App-side verification needs a bundled Ed25519 verifier at
   `minSdk 24` - issue #3.)

4. **Generate the Android release keystore offline and back it up.** Losing it
   means every install must be manually reinstalled (Sec. 12). Needed at release,
   but generate and back it up early.

5. **Create the maintainer-only ntfy topic** for circuit-breaker and
   silent-failure alerts. First check whether Cloudflare's free-plan Workers
   alerting already covers cron failure (Sec. 3), to avoid building what exists.

6. **Codeberg** repository for code, issues and releases. Decide the CI runner
   (Woodpecker or Forgejo Actions). The F-Droid-repo-on-Pages setup and F-Droid
   main-repo submission are post-v1 (Sec. 12).

7. **Confirm the package id and display name** from the Gloaming decision
   (ADR-0001), for example `cc.<domain>.gloaming`.

8. **Recreate the Android project** as the Compose stack in `docs/architecture.md`
   Sec. 5 (`minSdk 24`, `compileSdk`/`targetSdk 37`, JDK 21, Kotlin 2.4.x, Compose
   BOM 2026.06.x), replacing the current Views/AppCompat scaffold. This is the
   first code task once the architecture is agreed.

## Not blockers

Left deliberately open, to be settled by reading a policy page or writing code,
not before starting (Sec. 15): default alert thresholds `T_on`/`T_off`,
adaptive-polling escalation values, storm-mode exit condition, OVATION
storm-time gzip size, and Shetland per-region alerting. Begin logging aurora
activity against a subjective "was it worth it?" judgement from Phase 1 so there
is data to calibrate the thresholds against by the time Phase 2 needs them.
