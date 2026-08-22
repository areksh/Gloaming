import { run } from "./run";
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

function optional(name: string, fallback: string): string {
  const value = process.env[name];
  return value === undefined || value === "" ? fallback : value;
}

async function main(): Promise<number> {
  const store = new R2Store({
    accountId: required("R2_ACCOUNT_ID"),
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
    userAgent: optional("USER_AGENT", "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)"),
  };

  const result = await run(store, alerting, config);

  if (result.published) {
    console.log(`published sats.json with ${result.satellites} satellites`);
    return 0;
  }
  // stderr and a non-zero exit, so `systemctl status` and journalctl both show
  // it without anyone having to know where else to look.
  console.error(`did not publish: ${result.error}`);
  return 1;
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
