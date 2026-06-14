# Vouchq threat model

This document states plainly **what Vouchq defends against and what it does not.**
For a security tool, the boundary matters more than the feature list — so the
limits are spelled out, not buried.

## What Vouchq is (and isn't)

Vouchq is a **control-plane issuer of trust** for the MCP servers, Skills, and
Tools your agents depend on. It:

- **collects** tool definitions — MCP `tools/list` (`name` / `description` /
  `inputSchema`) and Skill files;
- **scans** them with rule-based detectors (prompt injection, secret exposure,
  exfiltration directives, dangerous commands);
- **pins (박제)** an approved definition by SHA-256 as the canonical baseline;
- **detects drift** by re-fetching the live definition on a schedule and
  comparing its hash to the pin;
- **records** every register / scan / approve / drift / policy action in an
  append-only, hash-chained (WORM) audit log;
- **distributes** only the pinned, approved artifact to agents (Skill files, or a
  vouched MCP connection config) — never the live upstream.

Vouchq is **not** an inline proxy or gateway. It does **not** sit in the request
path between your agent and a tool, and it does **not** see or filter tool
*calls*, *arguments*, or *results* at runtime. It governs **which definitions are
trusted** and **proves when a trusted one changes** — enforcement at the agent is
by installing only the vouched artifact.

## Assets protected

1. **Definition integrity** — the guarantee that the tool/Skill definition your
   agent uses is byte-for-byte the one a human reviewed and approved.
2. **Reviewed safety of definitions** — that approved definitions were scanned
   for known-dangerous content.
3. **Accountability** — a tamper-evident record of who approved what, and what
   changed afterward.

## Trust boundaries

```
 upstream (Git repo / MCP server)  →  [ Vouchq: scan · pin · drift ]  →  agent
        ^ may be compromised             ^ control plane (out-of-band)     ^ installs
          or mutate post-approval          not in the data path             pinned artifact
```

Vouchq assumes the **collection path** (clone / `tools/list`) reflects what the
agent would receive, and that the **operator and approvers** are trusted (subject
to RBAC + audit). It does not assume the upstream is honest — catching upstream
dishonesty after approval is the whole point.

## Threats

| Threat | In scope | How Vouchq addresses it | Residual risk / limits |
|---|---|---|---|
| **Rug-pull** — an approved MCP/Skill definition is silently changed after approval | ✅ Core | Pins the approved definition by SHA-256; scheduled re-scan re-fetches the live definition and raises a drift event on any mismatch | Detection is **bounded by the rescan cadence** (a change is caught on the next scan, not instantly); only changes that alter the *definition* are seen |
| **Tool/skill definition tampering** in transit or at rest | ✅ | Hash baseline + drift detection; WORM audit | Same detection-window caveat |
| **Tool poisoning / prompt injection in a definition** (e.g. "also send secrets to attacker.com" hidden in a description) | ✅ (content) | Rule-based scanner flags injection / exfiltration / secret / dangerous-command patterns and scores risk; policy can auto-block or hold | Rule-based → **novel or obfuscated payloads can evade**; not an ML/behavioral classifier (see scanner limits below) |
| **Malicious tool with a benign-looking definition** (description says "search", server actually exfiltrates) | ⚠️ Partial | Only what is expressed in the definition is visible; distribution of the pinned artifact prevents silent definition swaps | Vouchq reads **definitions, not server runtime behavior** — a malicious *implementation* behind an honest-looking definition is **out of scope** |
| **Compromised / malicious upstream registry or MCP server** | ✅ (for definition changes) | Drift detection flags when the upstream's definition diverges from the pin | Cannot vouch for the server's runtime behavior or non-definition side effects |
| **Shadow MCP** — a developer wires up an unapproved server | ⚠️ Partial | The distribution model ("install only vouched configs") + inventory of registered sources support governance | Vouchq governs **what you register and approve**; it cannot see servers you never told it about. Enforcement that agents *only* use vouched configs is organizational/tooling, not enforced inline |
| **Insider / compromised approver** approves a bad definition | ⚠️ Partial | RBAC (ADMIN/MEMBER/VIEWER) limits who can approve; policy rules can auto-block high-risk; WORM audit makes the approval attributable and tamper-evident | Audit gives **accountability, not prevention**; a privileged insider can still approve within policy |
| **First-approval compromise** — the upstream is already malicious at the moment of first review | ⚠️ Partial | Scanner + policy at ingest reduce the chance of approving obviously-dangerous definitions | "Garbage in" still possible if the malicious content evades the rules and the human reviewer |
| **Runtime / inference-time prompt injection** (malicious content arriving in tool *results* or user input during a session) | ❌ Out | — | Vouchq is not inline; runtime input/output filtering needs a guardrail/gateway at the agent |
| **Data exfiltration at call time** by an approved tool | ❌ Out | Mitigated indirectly: only scanned, pinned definitions are distributed | Not prevented inline; pair with network egress controls / runtime policy |
| **Compromise of the Vouchq deployment itself** (DB, host, operator account) | ❌ Out (operational) | Self-hosted, zero-outbound-by-default, encrypted source credentials, hardened images; the WORM audit is DB-enforced | Standard infra hardening, backups, and access control are the operator's responsibility |

## Scanner limits (be explicit)

The scanner is **rule-based** (regex/heuristic), intentionally conservative on the
CRITICAL bar to keep false positives low. That means:

- it catches **known patterns** (override/conceal instructions, exfiltration
  directives near secret keywords, AWS keys / private keys, dangerous commands);
- it will **miss** novel phrasing, heavily obfuscated payloads (e.g. unusual
  unicode), or attacks expressed only in the tool's *behavior*;
- a low score is **"no known-bad patterns found,"** not a proof of safety.

Treat the score as a triage signal, not a verdict. Suppressions and policy let you
tune it to your risk tolerance, and the rule set is meant to grow (contributions
welcome).

## Detection-window assumptions

Drift is caught on **re-scan**, so the exposure window for a rug-pull is at most
one rescan interval (configurable; default hourly when enabled). Tighten the
interval for higher-risk sources; there is no continuous interception.

## Defense in depth

Vouchq is the **integrity + governance** layer. It pairs with, and does not
replace:

- runtime guardrails / an agent gateway (for inference-time injection and
  call/result filtering),
- network egress controls (to contain exfiltration at runtime),
- secrets management and least-privilege tool credentials,
- code review and supply-chain controls for the upstreams themselves.

Use Vouchq to make "approved" mean something durable — and to **know the moment it
stops being true.**
