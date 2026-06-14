/**
 * Approval queue data — backed by the real REST API.
 *
 * `getApprovalQueue()` lists PENDING tools (`GET /api/tools?status=PENDING`) and,
 * for each, pulls its detail (`GET /api/tools/{id}`) to surface the scan's
 * effective (post-suppression) risk + findings on the card (MA3-86 / MA3-94).
 * Exported types + signature are unchanged so call-sites don't change.
 *
 * Note: `ref` (git ref) is not on the API yet → display fallback "—".
 */

import { api, type ApiToolView, type ApiFinding } from "./api";
import type { AssetKind } from "./mock-inventory";
import type { Severity } from "./mock-tool-detail";

const SEVERITY_RANK: Record<Severity, number> = { CRITICAL: 3, WARN: 2, INFO: 1 };

/** A condensed finding shown inline on a queue card. */
export interface TopFinding {
  severity: Severity;
  /** Short, human description. */
  text: string;
}

export interface ApprovalItem {
  id: string;
  name: string;
  kind: AssetKind;
  source: string;
  /** Git ref / context line (display only). */
  ref: string;
  shaShort: string;
  /** Risk score 0–100. */
  risk: number;
  findingCount: number;
  topFindings: TopFinding[];
}

function mapToolToApprovalItem(t: ApiToolView): ApprovalItem {
  const shaShort = t.currentVersionId
    ? t.currentVersionId.replace(/-/g, "").slice(0, 8)
    : "00000000";

  return {
    id: t.id,
    name: t.name,
    kind: t.kind.toUpperCase() as AssetKind,
    // LOCAL FALLBACK: source name requires a join not yet on ToolView.
    source: t.serverId ?? "—",
    // LOCAL FALLBACK: git ref not available on ToolView.
    ref: "—",
    shaShort,
    // Effective (post-suppression) risk, matching the inventory badge.
    risk: t.effectiveRiskScore ?? t.riskScore ?? 0,
    findingCount: 0,
    topFindings: [],
  };
}

/** Pull the tool's scan detail to populate risk findings on the card. */
async function withFindings(t: ApiToolView): Promise<ApprovalItem> {
  const base = mapToolToApprovalItem(t);
  try {
    const scan = (await api.getToolDetail(t.id)).currentScan;
    if (!scan?.findings) return base;
    // Only non-suppressed findings count toward the queue card (effective view).
    const findings = (JSON.parse(scan.findings) as ApiFinding[]).filter(
      (f) => !f.suppressed,
    );
    const top = [...findings]
      .sort((a, b) => SEVERITY_RANK[b.severity] - SEVERITY_RANK[a.severity])
      .slice(0, 3)
      .map((f) => ({ severity: f.severity, text: `${f.category} · ${f.path}:${f.line}` }));
    return { ...base, findingCount: findings.length, topFindings: top };
  } catch {
    // Detail fetch failed — show the row with risk only.
    return base;
  }
}

/**
 * Returns the PENDING approval queue from the live API, newest first.
 */
export async function getApprovalQueue(): Promise<ApprovalItem[]> {
  const tools = await api.getTools({ status: "PENDING" });
  return Promise.all(tools.map(withFindings));
}
