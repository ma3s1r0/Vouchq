# Self-hosting Vouchq (production)

Vouchq is designed to run entirely inside your own infrastructure.
A fresh instance makes **zero outbound calls** — every external integration
(notifications, scheduled re-scan reaching out to sources) is opt-in. This guide
is the runbook for a real deployment: secrets, first boot, backups, and upgrades.

For local development use `compose.yaml` (`docker compose up --build`); this guide
uses the hardened **`compose.prod.yaml`**.

---

## 1. Prerequisites

- A container runtime: **Docker** (with the compose plugin) or **Podman**
  (rootless is fine). Examples use `docker compose`; substitute `podman compose`
  1:1 — it's the same `compose.prod.yaml`.
- ~1.5 GB RAM for Postgres + backend + console to start; a persistent disk for
  the DB volume.
- A TLS-terminating reverse proxy in front (nginx/Caddy/Traefik), pointed at the
  **console** on `127.0.0.1:3000` (the browser front door — it proxies `/api` to
  the backend internally). The backend binds `127.0.0.1:8080` for ops only;
  **don't expose it publicly**, and Postgres is never published.

## 2. Configure secrets

```bash
cp .env.prod.example .env
chmod 600 .env
```

Fill in the **required** values in `.env`:

| Variable | How to generate / choose |
|---|---|
| `VOUCHQ_DB_PASSWORD` | `openssl rand -base64 24` |
| `VOUCHQ_CREDENTIALS_KEY` | `openssl rand -base64 32` — AES key for source-credential encryption at rest (MA3-89). **Generate once and keep it stable.** |
| `VOUCHQ_ADMIN_EMAIL` / `VOUCHQ_ADMIN_PASSWORD` | First-boot ADMIN. Used only while the user table is empty. |

> **`VOUCHQ_CREDENTIALS_KEY` is load-bearing.** Source tokens are stored
> AES-GCM-encrypted under this key. If it is unset the app falls back to an
> insecure built-in dev key (logged as a warning on boot). If it is **changed**
> after credentials have been saved, those credentials can no longer be decrypted
> — treat it like a root key: back it up in your secret manager, rotate only with
> a planned re-entry of source tokens.

`.env` is git-ignored. For stronger isolation than an env file, inject these via
your platform's secret store (Podman secrets, Docker secrets, Vault sidecar, k8s
Secrets) — the app only reads ordinary environment variables, so any mechanism
that lands them in the process environment works.

## 3. First boot

This builds all three images (Postgres is pulled) and starts them in order —
db → app (waits for a healthy db) → console (waits for a healthy app):

```bash
docker compose -f compose.prod.yaml --env-file .env up -d --build
```

Watch it come up and confirm the migration + admin seed:

```bash
docker compose -f compose.prod.yaml logs -f app
# look for: "Flyway ... now at version v7", health UP,
#           and the AdminBootstrap line seeding your admin email
curl -fsS http://127.0.0.1:8080/actuator/health   # {"status":"UP"}  (backend, ops port)
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3000/    # console front door
```

Then **log in to the console and change the admin password immediately**
(Settings → Members), even if you set a strong `VOUCHQ_ADMIN_PASSWORD` — this also
verifies session auth end to end. Point your reverse proxy at the **console**
(`127.0.0.1:3000`) and serve it over HTTPS; the console proxies `/api` to the
backend internally, so you never expose `8080` publicly.

### Image hardening (already applied)

- Backend runtime image is `eclipse-temurin:21-jre`; the console is a Next.js
  **standalone** build on `node:20-alpine`. Both run as **non-root uid 1001**.
- The builds are multi-stage — no JDK/Gradle/npm dev deps or source in the
  runtime images; only the boot jar (+ `curl` for the healthcheck) and the
  standalone server + static assets.
- `compose.prod.yaml` runs both app and console **read-only**,
  `no-new-privileges`, with **all Linux capabilities dropped** (the app also gets
  a `/tmp` tmpfs).
- Postgres is **not published** to the host; only the app reaches it on the
  internal network, and only the console proxies to the app.
- No secrets are baked into the images — all config is injected at runtime.

## 4. Backups

The only durable state is the Postgres volume (`vouchq_pgdata`): the registry,
pinned versions, the hash-chained audit log, suppressions, and policy. Back it up
on a schedule.

**Logical dump (portable, recommended):**

```bash
# Dump (gzip). Run from the deployment directory.
docker compose -f compose.prod.yaml exec -T db \
  pg_dump -U "$VOUCHQ_DB_USER" -d "$VOUCHQ_DB_NAME" --clean --if-exists \
  | gzip > "vouchq-$(date +%F).sql.gz"
```

**Restore** into a running, empty DB:

```bash
gunzip -c vouchq-YYYY-MM-DD.sql.gz \
  | docker compose -f compose.prod.yaml exec -T db \
    psql -U "$VOUCHQ_DB_USER" -d "$VOUCHQ_DB_NAME"
```

Also store `VOUCHQ_CREDENTIALS_KEY` with (or alongside) the backup — a DB restore
without the matching key leaves stored source tokens undecryptable.

> The audit log is append-only and DB-enforced (WORM trigger, MA3-83): a normal
> logical dump captures it, but do **not** restore selectively into a live audit
> table — restore the whole database into a fresh instance.

## 5. Upgrades & migrations

Flyway runs automatically on app startup and is forward-only; the schema is
versioned (`V1…Vn`). To upgrade:

```bash
# 1. Back up first (section 4).
# 2. Pull/build the new image and pin the tag.
export VOUCHQ_VERSION=<new-tag>
# 3. Recreate the app; Flyway applies any new migrations on boot.
docker compose -f compose.prod.yaml --env-file .env up -d --build
docker compose -f compose.prod.yaml logs -f app   # confirm "now at version vN" + UP
```

Notes:
- Roll **one minor version at a time**; don't skip across major schema changes.
- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate never mutates the schema,
  so a version/schema mismatch fails fast at boot instead of corrupting data.
- To roll back, restore the pre-upgrade backup into the previous image version
  (Flyway has no auto-down migrations).

## 6. Telemetry / outbound posture (verify)

Self-hosted means no phone-home. Confirm:
- Only `/actuator/health` is exposed (`management.endpoints.web.exposure.include:
  health`, `show-details: never`).
- All notification channels default **off** (`VOUCHQ_NOTIFY_*_ENABLED=false`).
- `SPRING_PROFILES_ACTIVE` is empty in `compose.prod.yaml` → the demo/internal
  ingest endpoints are **not** registered.
- The only outbound traffic is to sources **you** register (Git clone / MCP
  `tools/list`) and to notification channels **you** enable.

## 7. systemd / Quadlet (optional)

For unattended start-on-boot under rootless Podman, `deploy/quadlet/` ships
example units (`vouchq.network`, `vouchq_pgdata.volume`, `vouchq-db.container`,
`vouchq-app.container`). Install them and provide the env files they reference:

```bash
mkdir -p ~/.config/containers/systemd ~/.config/vouchq
cp deploy/quadlet/* ~/.config/containers/systemd/
# Create ~/.config/vouchq/db.env and app.env with the same vars as .env
# (db.env: POSTGRES_DB/USER/PASSWORD; app.env: VOUCHQ_DB_PASSWORD,
#  VOUCHQ_CREDENTIALS_KEY, VOUCHQ_ADMIN_*). chmod 600 both.
chmod 600 ~/.config/vouchq/*.env
systemctl --user daemon-reload
systemctl --user start vouchq-app.service
loginctl enable-linger "$USER"   # keep services running after logout
```
