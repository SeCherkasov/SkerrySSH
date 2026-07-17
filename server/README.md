# Skerry Sync Server

**English** · [Русский](README.ru.md) · [中文](README.zh.md)

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
exact `<version>`, `<major.minor>`, `latest`. The server is released on its own cadence,
independently of the Skerry client versions.

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
builds with `-PserverOnly` — no Android SDK required.

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
| `SKERRY_ADMIN_TOKEN` | *(empty)* | Admin console token (`/console`, `/admin/*`). Empty ⇒ admin data endpoints are closed. |
| `SKERRY_ACCESS_TTL` | `900` (15 min) | Access-token lifetime, seconds. |
| `SKERRY_REFRESH_TTL` | `2592000` (30 days) | Refresh-token lifetime, seconds. |
| `SKERRY_PAIRING_TTL` | `300` (5 min) | Lifetime of a one-shot QR pairing session. |
| `SKERRY_TOMBSTONE_DAYS` | `90` | How long deletion tombstones are retained before physical cleanup. |
| `SKERRY_CORS_HOSTS` | *(empty)* | Comma-separated allowed CORS origins. Empty disables CORS (native clients aren't subject to it). |
| `SKERRY_MAX_BODY_BYTES` | `4194304` (4 MiB) | Request-body cap (OOM/abuse guard); larger requests get `413`. |
| `SKERRY_DEV` | *(unset)* | `1` unlocks the default JWT secret for local development only. |

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
| `GET` | `/healthz` | Liveness (open; used by the container healthcheck). |
| `POST` | `/auth/register` | Registration: SRP salt/verifier + wrapped dataKey → tokens. |
| `POST` | `/auth/srp/challenge` → `/auth/srp/verify` | SRP-6a login without transmitting the password. |
| `POST` | `/auth/refresh` | Access/refresh token rotation. |

### Vault & devices (JWT auth)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/vault/keys` | Wrapped `dataKey` for a new device. |
| `GET` | `/vault/records?since={cursor}` | Delta of encrypted records. |
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

## Admin console

A static page at `http://localhost:8080/console` (requires `SKERRY_ADMIN_TOKEN`) — a single
dashboard: **Overview** (accounts, devices, records, total ciphertext size), **Devices**
(platform, last sync, cursor version, status + revocation), **Privacy boundary** (what the
server can and cannot see), and **Recent activity** (audit log). Zero-knowledge holds: the
console sees only metadata — event, device, platform label, cursors, and size aggregates —
never record contents, the master password, or the `dataKey`.

> Fonts (Space Grotesk, JetBrains Mono) are bundled into the server
> (`resources/admin/fonts/*.woff2`), icons are inline SVG. The console works fully offline,
> with no external CDN requests.

> ⚠️ Metadata includes `accountId` (an e-mail) and is retained in the audit log (last 2000
> events). For a single-user self-host the operator *is* the data subject — acceptable. The
> admin token travels in the `X-Admin-Token` header in cleartext: put a TLS terminator in
> front (below), otherwise the token is visible on the wire.

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
