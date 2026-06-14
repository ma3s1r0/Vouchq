/**
 * Dashboard data — now backed by the real REST API (MA3-80).
 *
 * Swap point: `getDashboardSummary()` calls `GET /api/dashboard/summary` and
 * maps the API DTO → the UI's `DashboardSummary` shape. The exported types and
 * function signature are unchanged so call-sites don't need to change.
 */

import { api, type ApiDashboardSummary, type ApiToolView } from "./api";

export type RiskLevel = "CRITICAL" | "WARN" | "INFO" | "CLEAN";

export type ActivityKind = "APPROVE" | "BLOCK" | "DRIFT" | "SCAN" | "REGISTER";

export interface RecentActivity {
  id: string;
  kind: ActivityKind;
  asset: string;
  actor: string;
  /** ISO 8601 timestamp. */
  at: string;
}

/** Counts of assets by lifecycle state (see status-badge.html). */
export interface AssetLifecycle {
  approved: number;
  pending: number;
  blocked: number;
}

export interface DashboardSummary {
  /** 총 자산 수 — total registered assets (Skills / Tools / MCP servers). */
  totalAssets: number;
  /** Lifecycle breakdown of the registered assets. */
  lifecycle: AssetLifecycle;
  /** 위험 등급 분포 — counts of assets by their highest finding severity. */
  riskDistribution: Record<RiskLevel, number>;
  /** 미처리 Drift — unresolved drift events. */
  unresolvedDrift: number;
  /** Severity split of the unresolved drift events. */
  unresolvedDriftBySeverity: { critical: number; warn: number };
  /** 승인 대기 큐 — assets in PENDING state awaiting review. */
  pendingApprovals: number;
  /**
   * Net change in the approval queue vs. yesterday (can be negative).
   * LOCAL FALLBACK: the API does not expose a delta yet; hardcoded to 0.
   */
  pendingApprovalsDelta: number;
  /** 최근 활동 — most recent drift events, newest first. */
  recentActivity: RecentActivity[];
}

/** Map an API DashboardSummary DTO → the UI DashboardSummary shape. */
function mapDashboard(dto: ApiDashboardSummary, tools: ApiToolView[]): DashboardSummary {
  const countByStatus = (status: string) =>
    dto.byStatus.find((s) => s.status === status)?.count ?? 0;

  const countBySeverity = (severity: string) =>
    dto.unresolvedDriftBySeverity.find((s) => s.severity === severity)?.count ?? 0;

  // Resolve tool names for the activity feed (API gives only toolId on drift).
  const toolName = new Map(tools.map((t) => [t.id, t.name]));
  const recentActivity: RecentActivity[] = dto.recentDrift.map((d) => ({
    id: d.id,
    kind: "DRIFT" as ActivityKind,
    asset: toolName.get(d.toolId) ?? d.toolId,
    actor: "scanner",
    at: d.detectedAt,
  }));

  // Risk distribution: count assets by their effective (post-suppression)
  // highest finding severity; no severity = CLEAN.
  const riskDistribution: Record<RiskLevel, number> = {
    CRITICAL: 0,
    WARN: 0,
    INFO: 0,
    CLEAN: 0,
  };
  for (const t of tools) {
    const sev = (t.effectiveHighestSeverity ?? "").toUpperCase();
    if (sev === "CRITICAL") riskDistribution.CRITICAL++;
    else if (sev === "WARN") riskDistribution.WARN++;
    else if (sev === "INFO") riskDistribution.INFO++;
    else riskDistribution.CLEAN++;
  }

  return {
    totalAssets: dto.totalAssets,
    lifecycle: {
      approved: countByStatus("APPROVED"),
      pending: countByStatus("PENDING"),
      blocked: countByStatus("BLOCKED"),
    },
    riskDistribution,
    unresolvedDrift: dto.unresolvedDrift,
    unresolvedDriftBySeverity: {
      critical: countBySeverity("CRITICAL"),
      warn: countBySeverity("WARN"),
    },
    pendingApprovals: dto.pendingApproval,
    // LOCAL FALLBACK: delta not available from API yet.
    pendingApprovalsDelta: 0,
    recentActivity,
  };
}

/**
 * Returns the dashboard summary from the live API.
 * Falls back gracefully — callers catch or let Next.js surface the error.
 */
export async function getDashboardSummary(): Promise<DashboardSummary> {
  const [dto, tools] = await Promise.all([
    api.getDashboardSummary(),
    api.getTools(),
  ]);
  return mapDashboard(dto, tools);
}
