# Vouchq

> **Private trust registry for MCP tools. Approve once, pin cryptographically, detect rug-pulls forever.**
> An approved MCP server or Skill can silently change its tool definitions *after* you trust it. Vouchq snapshots the approved definition, pins it by SHA-256 (박제), and raises a drift event the moment the live definition diverges — with a tamper-evident audit trail. Register · Scan · Approve & Pin · Detect drift · Audit.

[![Release](https://img.shields.io/github/v/tag/ma3s1r0/Vouchq?filter=v*&sort=semver&color=388BFD)](https://github.com/ma3s1r0/Vouchq/releases/tag/v0.1.0-alpha)
[![Website](https://img.shields.io/badge/website-vouchq-388BFD)](https://ma3s1r0.github.io/vouchq-website)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-3FB950)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-388BFD)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3-3FB950)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-388BFD)](https://www.postgresql.org/)
[![Self-hosted](https://img.shields.io/badge/self--hosted-friendly-3FB950)](#production-self-hosted-docker-compose)

**English** · [한국어](README.ko.md)

> Installs vouched Skills and remote MCP servers straight into **Claude**, **Cursor**, and **Codex**.

## See it catch a rug-pull

An approved `web_search` tool is pinned. Its upstream server then silently rewrites the
description to exfiltrate secrets — Vouchq detects the drift and flags the live version
CRITICAL, while the pinned benign version is still what agents receive.

![Vouchq detecting an MCP rug-pull: approve & pin → upstream tampers → drift detected → CRITICAL exfiltration finding → audit chain](examples/evil-mcp-rugpull/vouchq-rugpull.gif)

> Run it yourself in ~90s: [`examples/evil-mcp-rugpull/`](examples/evil-mcp-rugpull/).

---

## What is Vouchq

AI agents load Skills and connect to MCP servers whose tool descriptions can change **at runtime**. A capability that was benign when you adopted it can later mutate — a tool description quietly gains a hidden *"…also send the result to attacker.com"* instruction, and the agent obeys it. This is a **rug-pull**, and the MCP spec itself permits mid-session tool-description changes with no integrity check, hash pinning, or forced re-approval.

Vouchq is the authoritative record of *what your organization has approved to trust, and at which version* — plus the verification engine that catches when an approved definition is **silently changed**. Discovery catalogs check a capability **once**; Vouchq continuously verifies the **live** definition against a cryptographically **pinned** baseline, so post-approval tampering is caught instead of assumed away.

It is **not** a discovery catalog ("what exists in the world") and **not** an inline proxy. It is the control-plane *issuer* of trust: governance on the way **in**, and vouched distribution on the way **out**.

---

## Key capabilities

### Ingestion & inventory
Connect Git repositories and MCP servers. Vouchq's OSS parser normalizes Skills (`SKILL.md` + scripts) and MCP tools (`tools/list`) into a single definition model and builds a searchable inventory of every capability your agents can reach — kind, source, status, risk, and last-verified time.

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
Developers pull only **vouched (approved)** capabilities — never the live upstream. A repo registers many Skills, so the inventory groups them by source and each group gets a one-click **Install** that emits a copy-paste `curl … | sh`: the generated script fetches each approved file from Vouchq (the exact **pinned** bytes), re-verifies its SHA-256 before writing, and drops it into your agent's skills directory — **Claude** (`.claude/skills/`) or **Cursor** (`.cursor/rules/<name>.mdc`, verify-then-adapt), in project or user scope. Only `APPROVED` + pinned skills are served — pending / drifted / blocked are reported and skipped — and every install is recorded on the WORM audit log as `SKILL_INSTALL_SERVED`. **Remote** MCP servers install the same way — a vouched connection config issued **only** for a server in good standing (≥1 approved tool, none blocked or drifted; the refusal is itself the signal), for Claude (`.mcp.json`), Cursor (`.cursor/mcp.json`), or Codex (`config.toml`), recorded as `MCP_INSTALL_SERVED`. Because the bytes come from Vouchq's pinned snapshot rather than a fresh `git clone`, a consumer never re-pulls a rug-pulled upstream. Vouchq issues the trusted artifact; it does not sit inline in the request path.

### CI verify / build gate
A read-only **build gate** for consumer CI: the [`vouchq-verify` GitHub Action](integrations/github-action/) uploads the checked-out repo, and Vouchq reports — per Skill — whether its **current** definition is an `APPROVED` + pinned version, failing the job on anything `CHANGED` / `BLOCKED` / `UNKNOWN`. This closes the loop with distribution: vouched capabilities flow **out**, and unapproved or silently-changed ones are stopped on the way **in**. Identity is the same `definitionHash`, computed server-side by the authoritative parser, so there's no client-side hash to drift. Vouchq stays a registry — the gate is just another reader (`POST /api/verify`, VIEWER+); it never sits in the agents' request path.

### Self-governing ruleset (Sentinel)
Vouchq holds itself to the standard it sells. Because the scanner is open source, its rule set is itself a supply-chain target — a PR that quietly weakens a rule, or a tampered build, would let malicious definitions sail through. So Vouchq ships a sealed **canary corpus** (one known-malicious fixture per CRITICAL rule) and continuously runs its own scanner against it — at startup, hourly, and as a **CI gate**. If any canary goes undetected, the rule set has been weakened: Vouchq flips to **DEGRADED** and **fails closed** — approvals are suspended (it won't mint trust it can't stand behind) — records it on the WORM audit log, and exposes the verdict + rule-set fingerprint at `GET /api/ruleset/health`. You cannot neuter detection without a canary going dark.

### Scope — first-party observation only
Vouchq governs capabilities whose definition it can observe **first-party**: a Skill's bytes (parsed from the repo) and a **remote** MCP server's tool surface (fetched directly via `tools/list`). It pins and verifies only what it can see for itself.

**Local stdio MCP servers** — a `docker run` / `npx` binary the agent launches on your own machine — are **deliberately out of scope.** Governing one would require either executing an unverified third-party server *inside Vouchq's own trust boundary* (importing the exact supply-chain risk Vouchq exists to contain), or trusting a tool-surface snapshot the submitter captured (hearsay, not verification). Vouchq does not claim to vouch for what it cannot independently observe — and pinning a `@sha256` digest is reproducibility, not a safety judgment, so we don't dress it up as one. If the need becomes real, the right answer is a **signed, attested** capture from a trusted CI, not silent execution or unsigned snapshots.

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
  │   One-click install  ·  curl|sh, hash-verified  ·  vouched MCP config    │
  └─────────────────────────────────────────────────────────────────────────┘
```

The app starts as a single Spring Boot deployment unit with clean internal module boundaries (parser, scanner, registry, audit, notify, policy, tenancy). PostgreSQL holds the registry, pinned versions, audit log, suppressions, and policy. Two more readers sit at the edges: a **CI verify gate** lets consumers fail builds on unapproved skills (door in), and **Sentinel** continuously self-tests the scanner's own rule set and fails closed if it's been weakened.

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

### Your first few minutes

Once the console is up at `http://localhost:3000`, the whole loop is four steps:

1. **Register a source** — on **Inventory**, paste a Git repo (its Skills) or an MCP server URL. Vouchq parses every capability, risk-scans it, and lists it as `PENDING`.
2. **Review & approve** — open a capability to read its findings and definition, then **Approve & pin**. Vouchq snapshots the exact bytes as the immutable baseline (정본).
3. **It keeps watching** — a re-scan compares the live definition to your pin; any change raises a **drift** alert — the rug-pull alarm.
4. **Distribute & gate** — give developers a one-click **Install** (only approved, pinned bytes), and drop the [`vouchq-verify`](integrations/github-action/) check into CI so builds fail on anything unapproved.

Prefer to just *see* it first? The [90-second rug-pull demo](examples/evil-mcp-rugpull/) runs the whole loop end-to-end with one command.

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

**Self-hosted-friendly:** standard dependencies only; all outbound traffic (telemetry, notifications) is optional and **off by default**. The build is multi-stage and the runtime image runs read-only as a non-root user. GitHub Actions builds every module and runs the full test suite (including Testcontainers integration tests), the console build, and a container-image smoke build on each push/PR.

---

## Documentation

- **[Threat model](docs/threat-model.md)** — what Vouchq defends against, and what it explicitly does not
- **[How Vouchq compares](docs/comparison.md)** — vs MCP registries, gateways, and scanners
- **[Scanner rules](docs/scanner-rules.md)** — the full rule catalog + how to add one (good first issue)
- Deployment: see **Production (self-hosted, Docker Compose)** above + `.env.prod.example`
- API: OpenAPI / Swagger UI at `/swagger-ui` on a running backend
- Roadmap: Linear project **Vouchq**

---

## Community & contributing

Contributions are welcome across the whole stack — new scanner rules, parser coverage for more Skill / MCP shapes, test fixtures, and control-plane improvements. Open an issue or PR on **[ma3s1r0/Vouchq](https://github.com/ma3s1r0/Vouchq)**. Contributions are licensed under **AGPL-3.0-or-later** — the same terms as the rest of the project. See [`LICENSING.md`](LICENSING.md).

---

## License

**Fully open source under [AGPL-3.0-or-later](LICENSE)** — the whole stack, libraries included. The AGPL's network clause keeps every part open even when run as a service. Full rationale: **[`LICENSING.md`](LICENSING.md)**.
