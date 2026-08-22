# Orbital elements fetcher

Fetches CelesTrak's `stations` and `visual` groups every two hours and
publishes `sats.json` to R2, where the CDN serves it to every install.

It exists because **Cloudflare's network cannot reach CelesTrak**. Three
attempts from Workers, preview and production alike, each returned `HTTP 522`
after a 24-second timeout, while the same URL answered an ordinary domestic
connection in under two seconds in the same minute. A Worker's egress address
cannot be chosen on any plan, so the fetch had to move to a host with an
address of its own. See ADR-0008 and issue 11 in `docs/architecture.md`.

Everything else is unchanged: R2 is still the fan-out, the app still reads
`sats.json` through the manifest, and this writes its own health record into the
same bucket so the Worker keeps composing it into `health.json` without knowing
where it came from.

**This holds no local state.** The circuit breaker, the health record and the
published document all live in R2, so the machine running it is disposable and
`/opt` can stay read-only.

## Build

```sh
cd fetcher
npm install
npm test && npm run typecheck
npm run build        # -> dist/gloaming-elements.mjs, about 24 KB
```

One self-contained ESM file. The deployed host needs Node and nothing else - no
package manager, no `node_modules`, no source tree, nothing to keep patched but
Node itself.

---

# Deploying to a server

Written for a Debian or Ubuntu host with root and systemd. Everything below is
run as root unless it says otherwise.

## 0. Check the host can actually reach CelesTrak

Do this first. It is the entire reason this component exists, and if it fails
here there is no point continuing.

```sh
curl -sS -o /dev/null -w '%{http_code} in %{time_total}s\n' \
  -A 'Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)' \
  'https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=CSV'
```

Expect `200` in about a second. A `522`, or a timeout of roughly 24 seconds,
means this host is no better placed than Cloudflare was.

Note that this counts as one of the two downloads CelesTrak's usage policy
allows every two hours, so do not run it repeatedly while setting things up.

## 1. Node

Version 20 or newer.

```sh
node --version || apt-get update && apt-get install -y nodejs
command -v node          # note the absolute path; the unit file needs it
```

If the distribution's Node is older than 20, install a current one from
NodeSource rather than reaching for `nvm` - the service account below has no
login shell, so a per-user version manager would not be on its path anyway.

## 2. A user that owns nothing

```sh
useradd --system --shell /usr/sbin/nologin --home-dir /opt/gloaming \
        --comment 'Gloaming elements fetcher' gloaming
```

`--system` keeps it out of the normal UID range and out of login screens;
`--shell /usr/sbin/nologin` means nobody can log in as it even if a password is
somehow set. It deliberately gets no `--create-home`: the directory is made
below and owned by **root**, not by this account, so the service cannot modify
the code it runs.

## 3. Directories

```sh
install -d -o root -g root -m 0755 /opt/gloaming
install -d -o root -g root -m 0750 /etc/gloaming
```

## 4. The programme

From your workstation, in the repository:

```sh
cd fetcher && npm run build
scp dist/gloaming-elements.mjs root@your-host:/opt/gloaming/
```

Then on the host:

```sh
chown root:root /opt/gloaming/gloaming-elements.mjs
chmod 0644      /opt/gloaming/gloaming-elements.mjs
```

Root-owned and world-readable: the service account reads it and cannot write
it, so a compromise of that account cannot rewrite what runs next time.

## 5. R2 credentials

In the Cloudflare dashboard: **R2** > **API** > **Manage API tokens** >
**Create API token**.

- Permissions: **Object Read & Write**
- Scope it to the `gloaming-data` bucket specifically, not the whole account
- TTL: whatever you are willing to rotate

You get an **Access Key ID**, a **Secret Access Key** (shown once), and your
account ID is in the R2 endpoint URL it displays. Take all three.

The token needs object read and write, not admin: this reads the breaker flag
and the health record and writes those plus `sats.json`. It cannot create or
delete buckets.

## 6. Configuration

```sh
cat > /etc/gloaming/elements.env <<'EOF'
R2_ACCOUNT_ID=your-account-id
R2_ACCESS_KEY_ID=your-access-key-id
R2_SECRET_ACCESS_KEY=your-secret-access-key
R2_BUCKET=gloaming-data

# Optional. Without a topic the fetcher stays silent and only the health
# record in R2 says anything, which nobody sees unless they look.
NTFY_BASE_URL=https://ntfy.sh
NTFY_TOPIC=
EOF

chown root:root /etc/gloaming/elements.env
chmod 0600      /etc/gloaming/elements.env
```

Root-owned and unreadable by anyone else, including the service account.
systemd reads it as root and passes the values in *before* dropping to the
`gloaming` user, so the file never has to be readable by the account that uses
it.

Use the same ntfy topic as the Worker if you want both to reach the same phone.
It is a secret in the sense that anyone who knows the topic name can read and
post to it.

The CelesTrak URLs and the User-Agent have sensible defaults compiled in and
only need setting here if they move. If you do set them, `celestrak.org` over
HTTPS exactly: a `.com` or `.net` answers with a redirect, which trips the
breaker by design.

## 7. The service and its timer

```sh
cat > /etc/systemd/system/gloaming-elements.service <<'EOF'
[Unit]
Description=Fetch CelesTrak orbital elements and publish sats.json to R2
Documentation=https://codeberg.org/arek/Gloaming
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=gloaming
Group=gloaming
EnvironmentFile=/etc/gloaming/elements.env
ExecStart=/usr/bin/node /opt/gloaming/gloaming-elements.mjs
TimeoutStartSec=180

# It writes nothing locally - all state is in R2 - so it can be locked down
# hard. Note MemoryDenyWriteExecute is deliberately absent: Node's JIT needs
# writable-executable pages and the service will not start without them.
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6
RestrictNamespaces=true
RestrictSUIDSGID=true
LockPersonality=true
CapabilityBoundingSet=
SystemCallFilter=@system-service
SystemCallErrorNumber=EPERM

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/gloaming-elements.timer <<'EOF'
[Unit]
Description=Fetch CelesTrak orbital elements every two hours

[Timer]
# CelesTrak publish every two hours and their policy forbids polling faster.
OnCalendar=*-*-* 00/2:00:00
# Politeness, and it keeps every deployment of this off the same second.
RandomizedDelaySec=300
# Catch up once after downtime rather than waiting for the next even hour.
Persistent=true
Unit=gloaming-elements.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now gloaming-elements.timer
```

Check the `ExecStart` path matches what `command -v node` printed in step 1.

## 8. Clear the breaker

**The breaker is currently tripped**, from the Cloudflare attempts that led to
all this. Until it is cleared the fetcher will refuse to query - correctly, and
by design - and the first run will report `did not publish: celestrak breaker
tripped at ...`.

Do this only once step 0 has succeeded on the host, since that is the
investigation the manual reset is supposed to follow. From a machine with
`wrangler` authenticated:

```sh
npx wrangler r2 object delete gloaming-data/internal/breaker/celestrak.json --remote
```

## 9. Verify

Run it once by hand rather than waiting for the timer:

```sh
systemctl start gloaming-elements.service
systemctl status gloaming-elements.service
journalctl -u gloaming-elements.service -n 30 --no-pager
```

A good run logs `published sats.json with N satellites` and exits 0. N should
be somewhere around 170 - roughly 20 in `stations` and 155 in `visual`, minus
the overlap.

A bad run logs `did not publish: ...` and exits 1, which `systemctl status`
shows in red. The message says which of the two things went wrong: a non-200
from CelesTrak (the breaker has tripped and needs a human), or an R2 error
(check the credentials and the bucket name).

Then confirm the object is really there, from your workstation:

```sh
npx wrangler r2 object get gloaming-data/v1/sats.json --pipe --remote | head -c 300
```

And that the Worker has picked the health record up - it recomposes
`health.json` every few minutes:

```sh
npx wrangler r2 object get gloaming-data/v1/health.json --pipe --remote |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["sources"]["celestrak"])'
```

When is the next run?

```sh
systemctl list-timers gloaming-elements.timer
```

## Operations

**Reading the logs.** `journalctl -u gloaming-elements.service --since today`.
Each run is two lines at most; there is nothing to rotate and no log file to
manage.

**The breaker has tripped.** Any non-200 from CelesTrak stops all further
querying until a human clears it. That is their policy's single most important
requirement, and it is deliberate rather than an inconvenience. Find out what
happened first - the alert and the health record both carry the code and the
URL - and only then, from a machine with `wrangler` authenticated:

```sh
npx wrangler r2 object delete gloaming-data/internal/breaker/celestrak.json --remote
```

The next timer run will query again. `sats.json` keeps serving its last good
copy throughout, and elements stay usable for days, so a tripped breaker
degrades pass prediction rather than breaking it.

**Updating.** Rebuild, copy, done - there is no migration and no local state:

```sh
cd fetcher && npm run build
scp dist/gloaming-elements.mjs root@your-host:/opt/gloaming/
```

**Pausing it.** `systemctl disable --now gloaming-elements.timer`. The app
carries on with the elements it holds and quietly stops predicting once they
are too old to trust.

**Removing it entirely.**

```sh
systemctl disable --now gloaming-elements.timer
rm /etc/systemd/system/gloaming-elements.{service,timer}
systemctl daemon-reload
rm -rf /opt/gloaming /etc/gloaming
userdel gloaming
```

Then revoke the R2 API token in the dashboard, which is the only part that
outlives the host.
