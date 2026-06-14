# Vouchq

> **A trust registry and governance plane for the MCP servers, Skills, and Tools your AI agents depend on.**
> Register · Verify · Pin (박제) · Audit.

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-3FB950)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-388BFD)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3-3FB950)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-388BFD)](https://www.postgresql.org/)
[![Self-hosted](https://img.shields.io/badge/self--hosted-friendly-3FB950)](#production-self-hosted-docker-compose)

**English** · [한국어](README.ko.md)

---

## What is Vouchq

AI agents load Skills and connect to MCP servers whose tool descriptions can change **at runtime**. A capability that was benign when you adopted it can later mutate — a tool description quietly gains a hidden *"…also send the result to attacker.com"* instruction, and the agent obeys it. This is a **rug-pull**, and the MCP spec itself permits mid-session tool-description changes with no integrity check, hash pinning, or forced re-approval.

Vouchq is the authoritative record of *what your organization has approved to trust, and at which version* — plus the verification engine that catches when an approved definition is **silently changed**. Discovery catalogs check a capability **once**; Vouchq continuously verifies the **live** definition against a cryptographically **pinned** baseline, so post-approval tampering is caught instead of assumed away.

It is **not** a discovery catalog ("what exists in the world") and **not** an inline proxy. It is the control-plane *issuer* of trust: governance on the way **in**, and vouched distribution on the way **out**.

---

## Key capabilities

### Ingestion & inventory
Connect Git repositories and (Phase 1) MCP servers. Vouchq's OSS parser normalizes Skills (`SKILL.md` + scripts) and MCP tools (`tools/list`) into a single definition model and builds a searchable inventory of every capability your agents can reach — kind, source, status, risk, and last-verified time.

### Risk scanning
A rule-based scanner inspects each definition for **prompt injection**, **leaked secrets**, **data exfiltration**, and **dangerous commands**, producing a 0–100 risk score and structured findings. **False-positive suppression** (per-rule / per-tool / per-finding) keeps the signal high so reviewers trust the score. The scanner is pure Java and open source.

### Approve & pin (박제)
When a reviewer approves a definition, Vouchq snapshots it and stores an immutable **SHA-256 baseline** — the *정본* ("authoritative original"). This pinned version is the fixed reference point everything else is measured against, and the exact artifact distributed downstream.

### Drift / rug-pull detection
A scheduled (or manual) re-scan re-fetches the live definition and compares its hash to the pinned baseline. Any divergence raises a **DriftEvent** with a severity (`INFO` / `WARN` / `CRITICAL`) and a field-level diff, flips the tool to "needs review", and is the alarm for a rug-pull in progress.

### Policy engine
Declarative rules act on scan and drift outcomes — for example, auto-**block** or **hold** anything that crosses a risk threshold or trips a critical drift — so high-risk changes are gated without waiting on a human.

### Audit (WORM + hash chain)
Every register, scan, approve, block, and drift event is written to an **append-only audit log**. Entries are linked in a `prev_hash → entry_hash` SHA-256 chain and the table is **WORM-enforced at the database** (update/delete triggers), so tampering breaks the chain and is detectable — turning the log into real compliance evidence.

### RBAC & multitenancy
Spring Security with **Admin / Member / Viewer** roles. All data is isolated by `org_id`, enforced at the query layer, so multiple teams or tenants share one deployment without leaking across boundaries.

### Distribution / install
Developers pull only **vouched (approved)** capabilities — never the live upstream. From the console, Skills install as pinned files and MCP servers install as vouched connection configs (*"Add to Claude / Add to Codex"*). Vouchq issues the trusted artifact; it does not sit inline in the request path.

---

## Architecture

### Module map

```
:parser     library — Skill / MCP definition parser; pure Java, no framework deps
:scanner    library — rule-based risk scanner; pure Java
:app        Control plane (Spring Boot); depends on :parser / :scanner
               └ registry / audit / notify / policy / tenancy / api
console/    Admin console (Next.js + Tailwind)
```

Vouchq is **fully open source under AGPL-3.0** — the whole stack, libraries
included. The AGPL keeps every part open even when run as a service (so a thin
wrapper around the cores can't be offered as a closed SaaS). See **[`LICENSING.md`](LICENSING.md)**.

### How it works

```
  ┌── Governance (door in) ────────────────────────────────────────────────┐
  │  Git repo / MCP server                                                  │
  │        │  parse (:parser)                                               │
  │        ▼                                                                │
  │   Inventory ──► Scan (:scanner)  risk score + findings + FP suppression │
  │        │                                                                │
  │        ▼  reviewer approves                                             │
  │   Approve & Pin (박제)  ──►  immutable SHA-256 baseline (정본)          │
  │        │                                                                │
  │        ▼  scheduled re-scan                                             │
  │   Drift detection  ──►  live ≠ pinned ?  ──►  DriftEvent (rug-pull)     │
  │        │                                                                │
  │        ▼                                                                │
  │   Policy engine (auto-block / hold)  ──►  hash-chained WORM audit log   │
  └─────────────────────────────────────────────────────────────────────────┘
                              │  only approved + pinned
  ┌── Distribution (door out)─▼─────────────────────────────────────────────┐
  │   Add to Claude / Add to Codex   (pinned Skill files · vouched MCP cfg)  │
  └─────────────────────────────────────────────────────────────────────────┘
```

The app starts as a single Spring Boot deployment unit with clean internal module boundaries (parser, scanner, registry, audit, notify, policy, tenancy). PostgreSQL holds the registry, pinned versions, audit log, suppressions, and policy.

---

## Quick start (local)

You only need a container runtime — **Docker** (with the compose plugin) or **Podman** (rootless) — and Node 20 for the console in dev.

```bash
# 1) Backend + Postgres (built inside containers — no local JDK needed)
docker compose up --build -d          # or: podman compose up --build -d
#    health: curl -s localhost:8080/actuator/health  →  {"status":"UP"}

# 2) Console (dev server, hot reload)
cd console
npm install
API_PROXY_TARGET=http://localhost:8080 npm run dev
#    → http://localhost:3000   (dev login: admin@vouchq.local / admin)
```

> The compose files are runtime-agnostic — every command works the same with `docker compose` or `podman compose`.

API docs (OpenAPI / Swagger UI) are served by the backend at `/swagger-ui`.

If you have JDK 21 locally you can use the Gradle wrapper directly:

```bash
./gradlew :parser:test :scanner:test   # OSS unit tests (no container needed)
./gradlew build                        # full build (app tests use Testcontainers → needs a container runtime)
```

### Production (self-hosted, Docker Compose)

A fresh instance makes **zero outbound calls** — every integration is opt-in. The hardened `compose.prod.yaml` brings up the whole stack — **Postgres + backend + console** — as one unit:

```bash
cp .env.prod.example .env && chmod 600 .env   # fill in the required secrets
docker compose -f compose.prod.yaml --env-file .env up -d --build
#   (podman compose -f compose.prod.yaml --env-file .env up -d --build works identically)
```

The **console** is the front door on `127.0.0.1:3000` (it proxies `/api` to the backend internally) — point your TLS-terminating reverse proxy at it. Postgres is never published; the backend binds loopback only for ops.

The hardened `compose.prod.yaml` covers secret generation (via `.env.prod.example`), first-boot admin, and the security defaults; systemd (rootless Podman) units for an alternative deployment are in [`deploy/quadlet/`](deploy/quadlet/).

---

## Stack

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · Gradle (multi-module) · Console: Next.js (App Router) + TypeScript + Tailwind.

**Self-hosted-friendly** (spec §7): standard dependencies only; all outbound traffic (telemetry, notifications) is optional and **off by default**. The build is multi-stage and the runtime image runs read-only as a non-root user. GitHub Actions builds every module and runs the full test suite (including Testcontainers integration tests), the console build, and a container-image smoke build on each push/PR.

---

## Documentation

- Deployment: see **Production (self-hosted, Docker Compose)** above + `.env.prod.example`
- API: OpenAPI / Swagger UI at `/swagger-ui` on a running backend
- Roadmap: Linear project **Vouchq**

---

## Community & contributing

Contributions are welcome across the whole stack — new scanner rules, parser coverage for more Skill / MCP shapes, test fixtures, and control-plane improvements. Open an issue or PR on **[ma3s1r0/Vouchq](https://github.com/ma3s1r0/Vouchq)**. Contributions are licensed under **AGPL-3.0-or-later**; a lightweight CLA/DCO may be requested so the project can keep offering a commercial license — see [`LICENSING.md`](LICENSING.md).

---

## License

**Fully open source under [AGPL-3.0-or-later](LICENSE)** — the whole stack, libraries included. The AGPL's network clause keeps every part open even when run as a service. A separate commercial license is available for organizations that cannot accept the AGPL. Full rationale and terms: **[`LICENSING.md`](LICENSING.md)**.
