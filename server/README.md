# Skerry Sync Server

**English** · [Русский](README.ru.md) · [简体中文](README.zh.md)

Self-hosted, zero-knowledge E2E sync for [Skerry](../README.md) (Vaultwarden model). The
server stores **ciphertext only** — the wrapped `dataKey` and encrypted vault records — plus
sync metadata. The master password, `masterKey`, and `dataKey` never leave the device and are
unavailable to the server.

> License: **AGPL-3.0** (see `LICENSE`). The Skerry clients are GPL-3.0.

## What's inside

- **Stack**: Kotlin + Ktor (Netty), Exposed, HikariCP. Auth: SRP-6a (Nimbus) + JWT.
- **Storage**: SQLite by default (a single file, zero configuration); PostgreSQL by changing
  `SKERRY_DB_URL`.
- **No crypto on the server** by design: the server cannot decrypt user data. Registration
  uploads an SRP salt/verifier and a wrapped `dataKey`; login is an SRP-6a exchange in which
  the password itself is never transmitted.

## Quick start

### Docker (prebuilt image, recommended)

Multi-arch images (amd64 + arm64) are published to Docker Hub as
[`secherkasov/skerry-sync`](https://hub.docker.com/r/secherkasov/skerry-sync) — tags:
exact `<version>`, `<major.minor>`, `latest`. The server is released separately from the clients — its own
`server-v*` tag and its own workflow. Its version numbers have matched the client release so far.

```bash
docker run -d --name skerry-sync \
  -p 8080:8080 \
  -e SKERRY_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)" \
  -v skerry-data:/data \
  secherkasov/skerry-sync:latest
```

Keep `SKERRY_JWT_SECRET` stable across container re-creation (store it in an env file) —
changing it invalidates all issued tokens.

### Docker Compose (build from source)

```bash
# from the repository root
export SKERRY_JWT_SECRET="$(openssl rand -base64 48)"
export SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)"
docker compose up -d --build
```

Either way the server comes up on `http://localhost:8080`. Data lives in the `skerry-data`
volume (SQLite). To switch to PostgreSQL, uncomment the `db` service and the postgres
variables in `docker-compose.yml`.

The container runs as an unprivileged user, exposes a `/healthz` healthcheck, and the image
builds with `-PserverOnly` — no Android SDK required. The administration CLI ships alongside it:
`docker exec skerry-sync skerry-admin --help`.

### Local (Gradle)

```bash
SKERRY_JWT_SECRET=dev-secret SKERRY_ADMIN_TOKEN=admin ./gradlew :server:run -PserverOnly
```

## Configuration

Everything is configured through environment variables (single-`.env` model); a commented
template lives in [`.env.example`](.env.example). All values have sane defaults for local
runs — production only *requires* a stable `SKERRY_JWT_SECRET`.

| Variable | Default | Purpose |
|---|---|---|
| `SKERRY_HOST` | `0.0.0.0` | Bind interface. Set `127.0.0.1` behind a reverse proxy. |
| `SKERRY_PORT` | `8080` | Listen port. |
| `SKERRY_DB_URL` | `jdbc:sqlite:skerry-sync.db` | JDBC URL; `jdbc:postgresql://…` switches the driver to PostgreSQL. |
| `SKERRY_DB_USER` / `SKERRY_DB_PASSWORD` | *(empty)* | Database credentials (PostgreSQL). |
| `SKERRY_JWT_SECRET` | `dev-insecure-change-me` | JWT signing secret. **The server refuses to start with the default** unless `SKERRY_DEV=1`. Rotating it invalidates all issued tokens. |
| `SKERRY_JWT_ISSUER` | `skerry-sync` | JWT `iss` claim. |
| `SKERRY_ADMIN_TOKEN` | *(empty)* | Operator console token (`/console`, `/admin/*`). Empty ⇒ admin data endpoints are closed. |
| `SKERRY_ACCESS_TTL` | `900` (15 min) | Access-token lifetime, seconds. |
| `SKERRY_REFRESH_TTL` | `2592000` (30 days) | Refresh-token lifetime, seconds. |
| `SKERRY_PAIRING_TTL` | `300` (5 min) | Lifetime of a one-shot QR pairing session. |
| `SKERRY_TOMBSTONE_DAYS` | `90` | How long deletion tombstones are retained before physical cleanup. |
| `SKERRY_CORS_HOSTS` | *(empty)* | Comma-separated allowed CORS origins. Empty disables CORS (native clients aren't subject to it). |
| `SKERRY_MAX_BODY_BYTES` | `4194304` (4 MiB) | Request-body cap (OOM/abuse guard); larger requests get `413`. |
| `SKERRY_DEV` | *(unset)* | `1` unlocks the default JWT secret for local development only. |
| `SKERRY_METRICS` | `off` | Prometheus `/metrics`: `off` (404), `token` (bearer), `open` (no credential). |
| `SKERRY_METRICS_TOKEN` | *(empty)* | Bearer token for `SKERRY_METRICS=token`. Startup fails if the mode is `token` and this is empty. |
| `SKERRY_METRICS_INVENTORY_SECONDS` | `60` | Refresh interval of the inventory gauges (min 15, `0` disables them). |

## How sync works

1. **Register** — the client derives keys locally (Argon2id → `masterKey` →
   `authKey`/`dataKey`) and uploads an SRP salt/verifier plus the `dataKey` wrapped with the
   master key. Nothing uploaded is enough to decrypt anything.
2. **Log in** — SRP-6a challenge/verify; the server learns only that the client knows the
   password, never the password itself. On success it issues short-lived access + refresh
   JWTs.
3. **Push/pull** — clients `PUT` batches of encrypted records; conflicts resolve by
   last-writer-wins (record `version`, then `deviceId` as tiebreaker). Pulls are deltas by a
   monotonic cursor (`?since=`).
4. **Live updates** — the `/sync` WebSocket pushes a "changes available" signal carrying only
   the new cursor, never content; clients then pull the delta.
5. **Deletions** — propagate as tombstones and are physically aged out after
   `SKERRY_TOMBSTONE_DAYS`.
6. **New device** — either logs in and fetches the wrapped `dataKey` from `/vault/keys`, or
   uses quick QR pairing (`/pairing/*`, a one-shot session with a short TTL).

All cipher blobs (`blob`, `wrappedDataKey`, `encryptedDataKey`) travel as base64.

## API

### Health & auth

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/healthz` | Liveness (open; used by the container healthcheck). Never touches the database. |
| `GET` | `/readyz` | Readiness: `200` + `{"status":"ready","db":"up"}`, or `503` when the database probe has failed three times in a row. |
| `GET` | `/metrics` | Prometheus exposition. Disabled by default — see `SKERRY_METRICS`. |
| `POST` | `/auth/register` | Registration: SRP salt/verifier + wrapped dataKey → tokens. |
| `POST` | `/auth/srp/challenge` → `/auth/srp/verify` | SRP-6a login without transmitting the password. |
| `POST` | `/auth/refresh` | Access/refresh token rotation. |
| `POST` | `/auth/change-password` | Password rotation: SRP proof of the current one, new verifier and re-wrapped dataKey. |
| `GET` | `/auth/web-password` (JWT) | Whether the account has a web password — what the app's Web access card reads. |
| `POST` | `/auth/web-password` (JWT) | Set, rotate or clear the **web** password from the app. Clearing also revokes the open browser session. |
| `POST` | `/auth/web-login` | Web password → the standard token pair. Rate-limited; the session registers as a `platform = "web"` device. |

### Vault & devices (JWT auth)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/vault/keys` | Wrapped `dataKey` for a new device. |
| `GET` | `/vault/records?since={cursor}` | Delta of encrypted records. |
| `GET` | `/vault/envelopes` | Record metadata and a short ciphertext preview — sizes, never blobs (the account zone's Storage section). |
| `GET` | `/account/summary` | The caller's own totals: devices, records, tombstones, ciphertext size, last sync. |
| `GET` | `/account/activity` | The caller's own audit rows (team-scoped rows excluded). |
| `PUT` | `/vault/records` | Batch upsert with LWW (version, then deviceId). |
| `WS` | `/sync` | "Changes available" push (cursor only, no content). |
| `GET` / `DELETE` | `/devices`, `/devices/{id}` | Device list and revocation. |
| `POST` | `/pairing/start` (auth) → `/pairing/claim` | Quick local QR pairing. |

### Teams (JWT auth)

E2E-encrypted sharing: team records are ciphertext to the server, membership is granted via
sealed-envelope invitations against members' public keys.

| Method | Path | Purpose |
|---|---|---|
| `PUT` | `/account/key` | Publish the account's public key. |
| `GET` | `/account/keys/{accountId}` | Fetch a member's public key (for envelopes). |
| `POST` / `GET` / `DELETE` | `/teams`, `/teams/{id}` | Create, list, delete a team. |
| `GET` / `POST` | `/teams/{id}/members` | Member list; invite (sealed envelope). |
| `PUT` | `/teams/{id}/members/{accountId}/role` | Change role (owner/member). |
| `DELETE` | `/teams/{id}/members/{accountId}` | Remove a member / revoke access. |
| `POST` | `/teams/{id}/accept` | Accept an invitation. |
| `GET` / `PUT` | `/teams/{id}/records` | Pull/push encrypted shared records. |
| `GET` | `/teams/{id}/activity` | Team activity feed. |

### Admin (under `SKERRY_ADMIN_TOKEN`, header `X-Admin-Token`)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/admin/health` | Liveness (open). |
| `GET` | `/admin/stats` | Aggregates: accounts, devices, records, blob sizes. |
| `GET` | `/admin/devices` | All devices with platform, cursor, last sync. |
| `GET` | `/admin/activity` | Audit log (last 2000 events). |
| `GET` | `/admin/accounts`, `/admin/accounts/{id}/records` | Account list, per-account record metadata. |
| `DELETE` | `/admin/devices/{id}?accountId=` | Revoke a device from the console. |
| `DELETE` | `/admin/accounts/{id}/tombstones` | Purge an account's tombstones early. |
| `DELETE` | `/admin/accounts/{id}` | Delete an account with all its data. |

### Deleting an account

`DELETE /admin/accounts/{id}` removes everything the account owns in one transaction: records,
devices, pairing sessions, its published Teams keys, its memberships and scope grants — no row is
left naming an id that no longer exists (SQLite doesn't enforce foreign keys, PostgreSQL refuses the
delete outright while any remain). A team the account **owned** passes to its most senior active
member, who becomes the new owner; if no active member is left, the team is deleted with all of its
records, scopes and grants. The audit line names each affected team and its new owner, a
`team.owner_replaced` entry lands in that team's own feed, and every remaining member gets the same
live membership signal any other membership change sends. The remaining members should rotate the
team key afterwards — the deleted account knew it, and only a client can rotate it.

Nothing stops the same person from registering the same id again afterwards: ids are deterministic
and the local vault is still on their device, so a fresh registration re-uploads it. Close the
instance (`SKERRY_REGISTRATION=closed`) if that matters.

## Web frontend

One static bundle, three entrances, served by Ktor itself:

| URL | Who | Credential | Shows |
|---|---|---|---|
| `/` | anyone | none | Is the instance serving, what version, is registration open, and the URL to paste into a client. |
| `/account` | an account owner | account id + **web password** (set in the app) | Devices, teams, live sessions, stored record envelopes, the account's own log, security hand-offs. |
| `/console` | the operator | `SKERRY_ADMIN_TOKEN` | Instance totals, accounts (devices and record envelopes expand inside a row), observability, audit log. |

The web password is a separate credential, unrelated to any vault key: the page asking for it is
served by the same server it protects, so the master password never travels this way. A browser
signed in with it reads the metadata the server already holds in the clear and cannot decrypt a
record — `dataKey` is not part of the flow. The token it gets is restricted server-side to that:
read-only, without `/vault/keys` and `/vault/records`, plus revoking a device. Losing the password
costs a reset from the app; losing the master password is still unrecoverable by design.

The password is set in the app: **Settings → Sync → Web access**, on a connected device. The same
card rotates it and takes it away — removing it also signs out the browser session that is open at
that moment. It is 8–256 characters. The endpoint behind the card is `POST /auth/web-password`
(`{"password": null}` clears it), which is also how a script would do it.

The account's token pair lives in `sessionStorage` and dies with the tab; the admin token is kept in
memory only and is asked for again after a reload. Every destructive action (revoke, purge, delete)
states its exact blast radius before it runs.

Team membership and keys are **not** manageable from the browser: an invite or a key rotation seals
an envelope under the team key, and no browser session has one. The team panes are read-only.

Interface languages: English, Russian, Chinese (`?lang=` in the URL, then the stored preference,
then the browser). Zero-knowledge holds throughout — lists show ids, types, sizes and timestamps,
and the ciphertext preview is shown as what it is.

> Fonts (Space Grotesk, JetBrains Mono) are bundled into the server
> (`resources/web/assets/fonts/*.woff2`), icons are inline SVG, and the CSP is `default-src 'self'`.
> The pages work fully offline, with no external CDN requests. Chinese falls through to the
> system CJK stack — the bundled faces are latin + latin-ext.

> ⚠️ Metadata includes `accountId` (an e-mail) and is retained in the audit log (last 2000
> events). For a single-user self-host the operator *is* the data subject — acceptable. The
> admin token travels in the `X-Admin-Token` header in cleartext: put a TLS terminator in
> front (below), otherwise the token is visible on the wire.

## Admin CLI

`skerry-admin` ships in the same image as the server (`/app/bin/skerry-admin`) and drives the same
`/admin` endpoints as the operator console — one implementation and one authorization gate per operation.
It needs `SKERRY_ADMIN_TOKEN` and a reachable server; it never touches the database directly.

```bash
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin stats
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin devices list --account alice@example.com
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin devices revoke devA --account alice@example.com
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin accounts delete alice@example.com --yes
```

| Command | Purpose |
|---|---|
| `health` | Liveness and version (no token required). |
| `stats` | Instance totals: accounts, active devices, records, storage. |
| `accounts list` / `accounts records <id>` | Accounts with aggregates; per-account record metadata. |
| `accounts purge-tombstones <id>` | Drop deletion markers every device has synced past. |
| `accounts delete <id> --yes` | Delete an account with all of its data (irreversible, hence the flag). |
| `devices list [--account id]` | Active devices, most recently seen first. |
| `devices revoke <id> --account <id>` | Revoke a device (it may re-authenticate later). |
| `activity` | Recent audit-log events. |
| `metrics` | Raw Prometheus exposition (uses `SKERRY_METRICS_TOKEN`). |

Global options: `--url` (default `SKERRY_ADMIN_URL`, else `http://127.0.0.1:$SKERRY_PORT`), `--token`
/ `--token-file` (a flag is visible in `ps` — prefer the environment variable or a secret file),
`--limit`, `--json` (prints the server's JSON verbatim, for `jq`), `--help`.

Exit codes are meant for scripts: `0` ok, `1` error, `2` usage, `3` unauthorized, `4` not found,
`5` server unreachable. Run it against a remote instance with `--url https://sync.example.com`; the
URL must point at the server root (a reverse-proxy path prefix is not supported).

## Metrics and monitoring

`/metrics` serves a Prometheus exposition — off by default, because on a zero-knowledge server the
metadata *is* the attack surface. Enable it with a token:

```bash
-e SKERRY_METRICS=token -e SKERRY_METRICS_TOKEN="$(openssl rand -hex 24)"
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: skerry-sync
    static_configs: [{ targets: ["sync.example.com:8080"] }]
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/skerry-metrics-token
```

What you get, beyond the standard `jvm_*`, `process_*` and `hikaricp_*` families:

| Family | What it answers |
|---|---|
| `skerry_http_server_requests_seconds` | Latency and volume by `method`, `route` (**template**, never the id), `status`. |
| `skerry_http_rejected_requests_total`, `skerry_http_unhandled_exceptions_total` | Requests refused before routing (411/413); server-side faults. |
| `skerry_auth_attempts_total{kind,outcome}`, `skerry_auth_tokens_issued_total` | Login/registration/refresh outcomes — the brute-force signal. |
| `skerry_admin_auth_failures_total`, `skerry_metrics_auth_failures_total` | Wrong static tokens, i.e. someone probing the console or the scrape endpoint. |
| `skerry_auth_jwt_rejected_total{reason}`, `skerry_team_authz_denied_total{reason}` | Rejected device tokens; denied team/scope access. |
| `skerry_sync_records_received_total` / `_pulled_total` / `skerry_sync_push_bytes_total` | Sync volume by scope (`account`, `team`). |
| `skerry_sync_ws_sessions`, `…_opened_total`, `…_closed_total{reason}`, `…_session_duration_seconds` | Live-push sockets: how many are open, why they close, how long they last. |
| `skerry_accounts`, `skerry_devices{state}`, `skerry_records{state}`, `skerry_storage_bytes{scope}`, `skerry_db_file_bytes`, `skerry_teams`, `skerry_pairing_sessions{state}` | Inventory, refreshed in the background (see `SKERRY_METRICS_INVENTORY_SECONDS`). |
| `skerry_inventory_last_success_time_seconds`, `skerry_inventory_errors_total` | Whether the inventory above is still fresh. |
| `skerry_db_up`, `skerry_db_probe_duration_seconds` | The readiness probe behind `/readyz`. |
| `skerry_build_info{version}`, `skerry_server_start_time_seconds` | Running version; restart detection. |

**No label ever carries an accountId, deviceId, recordId, teamId, scopeId or IP address.** Those are
client-chosen values: as labels they would let a user grow the registry until the process dies, and
they would publish exactly the metadata the design keeps out of the server's reach. Per-account
figures live behind the admin token, in `/admin/accounts`.

A starting set of alerts:

```promql
skerry_db_up == 0                                                    # database unreachable
time() - skerry_inventory_last_success_time_seconds > 300            # inventory gauges went stale
changes(skerry_server_start_time_seconds[15m]) > 2                   # restart loop
rate(skerry_admin_auth_failures_total[10m]) > 0                      # someone guessing the admin token
sum(rate(skerry_auth_attempts_total{outcome="denied"}[10m])) > 0.2   # login brute force
hikaricp_connections_pending > 0                                     # contention for the single SQLite connection
predict_linear(skerry_db_file_bytes[6h], 7*24*3600) > 20e9           # volume will fill up this week
skerry_records{state="tombstone"} / skerry_records{state="live"} > 0.5  # tombstone cleanup is not running
```

## Production security

- Set a stable `SKERRY_JWT_SECRET` (otherwise a restart invalidates every token) and a
  non-empty `SKERRY_ADMIN_TOKEN`.
- Backup = the SQLite file (`/data`) or a PostgreSQL dump; the data is encrypted, but it is
  your only restore point.
- The server itself listens on cleartext HTTP — TLS is terminated by a reverse proxy (below).
  The payload is E2E-encrypted anyway (zero-knowledge) and SRP is safe over cleartext, but
  **the admin token and metadata (including `accountId` = e-mail) travel in the clear** —
  without TLS they are visible on the network. TLS is mandatory for a publicly reachable
  host.

### TLS termination

Point the client at `https://…` — the `/sync` WebSocket switches to `wss://` automatically
(same host).

**Caddy** (automatic Let's Encrypt, the simplest option):

```caddy
sync.example.com {
    reverse_proxy localhost:8080
}
```

**nginx** (your own cert or Certbot; the WebSocket upgrade for `/sync` must be forwarded):

```nginx
server {
    listen 443 ssl;
    server_name sync.example.com;
    ssl_certificate     /etc/letsencrypt/live/sync.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sync.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # WebSocket /sync (live pull): realtime notifications break without these two headers.
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h; # /sync is a long-lived connection; don't cut it on timeout
    }
}
```

Bind the server to loopback (`SKERRY_HOST=127.0.0.1`) so port 8080 isn't reachable around the
proxy.

> **Self-hosting on a trusted LAN without TLS** is an acceptable, deliberate choice: the
> traffic is E2E-encrypted and the metadata stays inside the LAN. The Android client allows
> cleartext (`network_security_config.xml`). The moment the host becomes reachable from
> outside — add TLS.

## Tests

```bash
./gradlew :server:test
```

They cover LWW conflicts, the SRP round-trip, JWT, team roles/ACL, and the full HTTP flow
(register → login → push/pull → devices → pairing → admin).
