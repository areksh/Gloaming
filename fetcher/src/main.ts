import { ensureRuntime } from "./runtime";
import { run, runComets } from "./run";
import { R2Store } from "./store";

/**
 * The entry point the timer runs.
 *
 * Configuration comes from the environment so the credentials live in a file
 * only root can read, which systemd passes in before dropping privileges - see
 * `fetcher/README.md`. Nothing is read from a config file next to the code,
 * because the code is deliberately not writable by the account that runs it.
 */
function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === "") {
    throw new Error(`${name} is not set`);
  }
  return value;
}

/**
 * The account ID, checked to be an account ID.
 *
 * The dashboard shows it inside an endpoint URL, so the obvious thing to paste
 * is the whole URL - which would be assembled into a nonsense hostname and fail
 * somewhere much less obvious than here.
 */
function accountId(): string {
  const value = required("R2_ACCOUNT_ID");
  if (!/^[0-9a-f]{32}$/.test(value)) {
    throw new Error(
      `R2_ACCOUNT_ID should be the 32-character id on its own, not the whole ` +
        `endpoint URL. From "https://<id>.r2.cloudflarestorage.com" take only <id>. Got: "${value}"`,
    );
  }
  return value;
}

function optional(name: string, fallback: string): string {
  const value = process.env[name];
  return value === undefined || value === "" ? fallback : value;
}

async function main(): Promise<number> {
  ensureRuntime();

  const store = new R2Store({
    accountId: accountId(),
    accessKeyId: required("R2_ACCESS_KEY_ID"),
    secretAccessKey: required("R2_SECRET_ACCESS_KEY"),
    bucket: optional("R2_BUCKET", "gloaming-data"),
  });

  const alerting = {
    baseUrl: optional("NTFY_BASE_URL", "https://ntfy.sh"),
    topic: optional("NTFY_TOPIC", ""),
    fetch: globalThis.fetch.bind(globalThis),
  };

  const config = {
    stationsUrl: optional(
      "CELESTRAK_STATIONS_URL",
      "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=CSV",
    ),
    visualUrl: optional(
      "CELESTRAK_VISUAL_URL",
      "https://celestrak.org/NORAD/elements/gp.php?GROUP=visual&FORMAT=CSV",
    ),
    // The supplemental endpoint, not gp.php. Sec. 3 records that the Starlink
    // list is under enforcement for GP and SupGP alike, so the same two-hourly
    // cap and the same breaker cover this as cover the other two.
    starlinkUrl: optional(
      "CELESTRAK_STARLINK_URL",
      "https://celestrak.org/NORAD/elements/supplemental/sup-gp.php?FILE=starlink&FORMAT=csv",
    ),
    userAgent: optional("USER_AGENT", "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)"),
  };

  const comets = {
    /**
     * `data.minorplanetcenter.net`, not `www`.
     *
     * The two serve the same file and only one of them is reachable. `www`
     * times out from some networks entirely and answers 403 to others, and its
     * `/data` path redirects; the data host answers over HTTP/2 with an ETag
     * and a permissive CORS header. Checking this before writing the fetcher is
     * the lesson of ADR-0008, where Cloudflare's egress could not reach
     * CelesTrak and a whole phase had to move.
     */
    elementsUrl: optional(
      "MPC_COMETS_URL",
      "https://data.minorplanetcenter.net/iau/MPCORB/CometEls.txt",
    ),
    observationsUrl: optional("COBS_OBSERVATIONS_URL", "https://cobs.si/api/obs_list.api"),
    userAgent: optional("USER_AGENT", "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)"),
  };

  const result = await run(store, alerting, config);

  if (result.published) {
    console.log(
      `published sats.json with ${result.satellites} satellites ` +
        `and ${result.trains} Starlink train(s)`,
    );
  } else {
    // stderr and a non-zero exit, so `systemctl status` and journalctl both
    // show it without anyone having to know where else to look.
    console.error(`did not publish sats.json: ${result.error}`);
  }

  // Run regardless of how the satellites went, and reported separately. The
  // two sources share nothing but the bucket: neither should be able to cost
  // the other a refresh.
  const comet = await runComets(store, alerting, comets);

  if (comet.published) {
    console.log(
      `published comets.json with ${comet.comets} comet(s), ` +
        `${comet.measured} carrying a measured magnitude`,
    );
  } else {
    console.error(`did not publish comets.json: ${comet.error}`);
  }

  return result.published && comet.published ? 0 : 1;
}

main()
  .then((code) => {
    process.exitCode = code;
  })
  .catch((error: unknown) => {
    // Only misconfiguration reaches here; run() handles everything else.
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 2;
  });
