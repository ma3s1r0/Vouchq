/**
 * Inventory data — now backed by the real REST API (MA3-80).
 *
 * Swap point: `getInventory()` calls `GET /api/tools` and maps API ToolView
 * DTOs → the UI's InventoryItem shape. Exported types and function signatures
 * are unchanged so call-sites don't need to change.
 *
 * Notes on gaps:
 *  - `shaShort`: derived from `currentVersionId` short prefix (UUID first 8 chars
 *    are not a sha256 — this is a display stand-in until the API exposes the hash
 *    directly on ToolView, which would require a join).  We use the currentVersionId
 *    prefix for now and the full hash is available on ToolDetailView.
 *  - `risk`: not on ToolView (scanner not yet integrated); LOCAL FALLBACK = 0.
 *  - `lastVerified`: derived from `updatedAt` ISO timestamp, rendered as ISO string.
 */

import { api, type ApiToolView } from "./api";

/** Lifecycle state of an asset (see status-badge.html). */
export type AssetStatus = "APPROVED" | "PENDING" | "DRIFTED" | "BLOCKED";

/** Kind of registered asset. */
export type AssetKind = "SKILL" | "MCP_TOOL" | "TOOL" | "MCP_SERVER";

/** Highest-severity bucket used for the Risk filter. */
export type RiskBand = "CLEAN" | "INFO" | "WARN" | "CRITICAL";

export interface InventoryItem {
  id: string;
  name: string;
  /** Short sha256 prefix (display only). */
  shaShort: string;
  kind: AssetKind;
  source: string;
  status: AssetStatus;
  /**
   * Effective (post-suppression) risk score 0–100.
   * Falls back to rawRisk when effectiveRiskScore is null.
   * LOCAL FALLBACK = 0 until scanner integration.
   */
  risk: number;
  /** ISO 8601 or human "last verified" label. */
  lastVerified: string;
}

/** Map a numeric risk score to its band, for the Risk filter. */
export function riskBand(risk: number): RiskBand {
  if (risk >= 70) return "CRITICAL";
  if (risk >= 40) return "WARN";
  if (risk >= 15) return "INFO";
  return "CLEAN";
}

function mapToolView(t: ApiToolView): InventoryItem {
  // Use first 8 chars of UUID as a display stand-in for the sha hash.
  // The actual content hash is on ToolVersionView (available in detail endpoint).
  const shaShort = t.currentVersionId
    ? t.currentVersionId.replace(/-/g, "").slice(0, 8)
    : "00000000";

  // Map API status strings to UI AssetStatus.
  const status = (t.status as string).toUpperCase() as AssetStatus;

  // Map API kind strings to UI AssetKind.
  const kind = (t.kind as string).toUpperCase() as AssetKind;

  // MA3-94: prefer effectiveRiskScore (post-suppression); fall back to raw riskScore.
  const risk =
    t.effectiveRiskScore !== null && t.effectiveRiskScore !== undefined
      ? t.effectiveRiskScore
      : (t.riskScore ?? 0);

  return {
    id: t.id,
    name: t.name,
    shaShort,
    kind,
    // API does not expose source name on ToolView (requires a server/source join).
    // LOCAL FALLBACK: use serverId until the API enriches ToolView.
    source: t.serverId ?? "—",
    status,
    risk,
    lastVerified: t.updatedAt,
  };
}

/** Distinct source count, for the toolbar result line. */
export function sourceCount(items: InventoryItem[]): number {
  return new Set(items.map((i) => i.source)).size;
}

/**
 * Returns the full inventory from the live API, mapped to the UI shape.
 */
export async function getInventory(): Promise<InventoryItem[]> {
  const tools = await api.getTools();
  return tools.map(mapToolView);
}
