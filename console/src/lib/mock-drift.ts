/**
 * Drift alerts — now backed by the real REST API (MA3-80).
 *
 * Swap point: `getDriftAlerts()` calls `GET /api/drift-events?resolved=false`
 * and maps API DriftEventView DTOs → the UI's DriftAlert shape. Exported types
 * and function signature are unchanged so call-sites don't need to change.
 *
 * Notes on gaps:
 *  - `name` / `kind` / `source`: the drift event only carries `toolId`.
 *    We resolve via `GET /api/tools` upfront and join in memory.
 *  - `risk`: not on DriftEventView (scanner not integrated). LOCAL FALLBACK = 0.
 *  - `detected`: derived from `detectedAt` ISO timestamp (rendered as ISO string;
 *    relative formatting is a UI concern).
 *  - `diff.lines`: raw unified-diff string is parsed; see parseDiff() in
 *    mock-tool-detail for the algorithm (duplicated here to avoid cross-imports).
 */

import { api, type ApiDriftEventView, type ApiToolView } from "./api";
import type { AssetKind } from "./mock-inventory";
import type { DriftDiff, Severity } from "./mock-tool-detail";

export interface DriftAlert {
  /** DriftEvent id (used for resolve). */
  id: string;
  /** The drifted tool's id (used to link to its detail page). */
  toolId: string;
  name: string;
  kind: AssetKind;
  source: string;
  /** Highest finding severity introduced by the drift. */
  severity: Severity;
  /** Risk score 0–100. LOCAL FALLBACK = 0 until scanner integration. */
  risk: number;
  /** ISO 8601 timestamp from API. */
  detected: string;
  /** One-line note about what changed. */
  summary: string;
  /** Current↔pinned unified diff, rendered by `<DiffView>`. */
  diff: DriftDiff;
}

function parseDiff(raw: string): import("./mock-tool-detail").DiffLine[] {
  if (!raw) return [];
  const skip = /^(diff --git|index |--- |\+\+\+ |@@)/;
  const result: import("./mock-tool-detail").DiffLine[] = [];
  for (const line of raw.split("\n")) {
    if (skip.test(line)) continue;
    if (line.startsWith("+")) {
      result.push({ kind: "add", gutter: "+", text: line.slice(1) });
    } else if (line.startsWith("-")) {
      result.push({ kind: "del", gutter: "-", text: line.slice(1) });
    } else {
      result.push({ kind: "ctx", gutter: "·", text: line.startsWith(" ") ? line.slice(1) : line });
    }
  }
  return result;
}

function mapDriftEvent(
  d: ApiDriftEventView,
  toolMap: Map<string, ApiToolView>,
): DriftAlert {
  const tool = toolMap.get(d.toolId);
  const severity = (d.severity || "WARN").toUpperCase() as Severity;

  return {
    id: d.id,
    toolId: d.toolId,
    name: tool?.name ?? d.toolId,
    kind: (tool?.kind?.toUpperCase() ?? "MCP_TOOL") as AssetKind,
    // LOCAL FALLBACK: source name requires join not yet on DriftEventView/ToolView.
    source: tool?.serverId ?? "—",
    severity,
    // Risk comes from the joined tool's effective (post-suppression) scan.
    risk: tool?.effectiveRiskScore ?? tool?.riskScore ?? 0,
    detected: d.detectedAt,
    summary: `sha256 drift: pinned ${d.approvedHash.slice(0, 8)} → observed ${d.observedHash.slice(0, 8)}`,
    diff: {
      pinnedSha: d.approvedHash.slice(0, 8),
      observedSha: d.observedHash.slice(0, 8),
      summary: `sha256 drift detected`,
      lines: parseDiff(d.diff ?? ""),
    },
  };
}

/**
 * Returns DRIFTED alerts (unresolved) from the live API.
 */
export async function getDriftAlerts(): Promise<DriftAlert[]> {
  // Fetch drift events and all tools in parallel; join in memory.
  const [events, tools] = await Promise.all([
    api.getDriftEvents(false),
    api.getTools(),
  ]);

  const toolMap = new Map<string, ApiToolView>(tools.map((t) => [t.id, t]));

  return events.map((d) => mapDriftEvent(d, toolMap));
}
