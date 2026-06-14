# How Vouchq compares

The MCP tooling landscape splits into a few jobs that are easy to conflate.
Vouchq does **one** of them: **post-approval integrity governance.**

- **Discovery** — *"what MCP servers exist, and where do I find them?"*
  (catalogs / registries)
- **Runtime mediation** — *"inspect/route/limit live tool traffic"*
  (gateways / proxies)
- **One-time scanning** — *"is this server vulnerable right now?"*
  (scanners)
- **Post-approval integrity governance** — *"this definition was reviewed and
  approved; pin it, and tell me the moment it changes — with evidence."*
  ← **Vouchq**

Vouchq is **complementary** to the first three, not a replacement. You can
discover a server in a catalog, run it behind a gateway, scan it once — and still
have no answer to *"did the approved definition silently change last Tuesday, and
who approved the original?"* That gap is what Vouchq fills.

## At a glance

> Comparison is based on each project's public positioning as of mid-2026 and is
> necessarily a simplification — verify against current docs. Categories evolve
> fast. The goal here is to locate Vouchq, not to rank tools.

| Capability | **Vouchq** | MCP registry / catalog (e.g. GitHub MCP Registry) | API/AI gateway (e.g. Kong) | MCP scanner (e.g. mcp-scan) | Agent governance toolkit (e.g. MS Agent Governance) |
|---|:---:|:---:|:---:|:---:|:---:|
| Discover / catalog MCP servers | inventory only | ✅ (primary) | ~ | — | ~ |
| Scan definitions for risky content | ✅ | — | ~ | ✅ (primary) | ✅ |
| **Cryptographic pin (박제) of the approved definition** | ✅ | — | — | — | ~ |
| **Post-approval drift / rug-pull detection** | ✅ (core) | — | ~ runtime | one-shot | ✅ (fingerprinting) |
| Approval workflow + RBAC | ✅ | — | ~ | — | ✅ |
| Tamper-evident (WORM, hash-chained) audit | ✅ | — | ~ logs | — | ~ |
| Policy: auto-block / hold | ✅ | — | ✅ runtime | — | ✅ |
| Distribute **only the pinned artifact** to agents | ✅ | serves live | proxies live | — | ~ |
| Inline in the request path (mediates live calls) | ❌ by design | ❌ | ✅ (primary) | ❌ | ✅ |
| Self-hosted, zero-outbound by default | ✅ | varies | varies | ✅ (CLI) | varies |

`✅` first-class · `~` partial/adjacent · `—` not its job · `❌` explicitly not

## Where each fits (and why Vouchq is complementary)

- **MCP registries / catalogs (GitHub MCP Registry, etc.)** solve *discovery* —
  finding and publishing MCP servers. They serve the **live** definition and
  don't pin, re-verify after you adopt a server, or own an approval/audit trail.
  Vouchq sits *downstream* of discovery: catalog → **approve & pin in Vouchq** →
  install the vouched artifact.

- **Gateways (Kong AI Gateway, generic API gateways)** mediate *live traffic* —
  routing, rate-limiting, auth, runtime policy. That's the **data path** Vouchq
  deliberately stays out of. A gateway can enforce calls in real time but doesn't
  give you a reviewed, hash-pinned baseline of each tool's *definition* or a
  drift history. Run both: gateway for runtime, Vouchq for definition integrity.

- **Scanners (mcp-scan and similar)** answer *"is this risky right now?"* — a
  valuable one-time/periodic check. Vouchq includes scanning, but its core is the
  thing a one-shot scan can't give you: **a pinned baseline + continuous drift
  detection + an approval/audit record over time.** A scan tells you it's clean
  today; Vouchq tells you the instant it stops being the thing you approved.

- **Agent governance toolkits (Microsoft Agent Governance, etc.)** overlap the
  most — governance, rug-pull fingerprinting, often with a gateway. They're
  typically tied to a specific platform/cloud. Vouchq's differences: it's
  **self-hosted, AGPL open source, zero-outbound by default**, and is a
  **control-plane issuer** (pins + distributes vouched artifacts) rather than an
  inline gateway — a fit for teams that can't put their MCP/Skill inventory in a
  vendor SaaS.

## The one-line positioning

> Discovery tells you a tool **exists**. A gateway controls a tool **in flight**.
> A scanner tells you a tool looks **clean today**. **Vouchq makes "approved"
> durable — and proves the moment it changes.**

See also the [threat model](threat-model.md) for exactly what that does and
doesn't cover.
