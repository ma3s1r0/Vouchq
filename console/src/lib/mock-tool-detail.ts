/**
 * Tool detail data — now backed by the real REST API (MA3-80).
 *
 * Swap point: `getToolDetail(id)` calls `GET /api/tools/{id}` and maps the API
 * ToolDetailView → the UI's ToolDetail shape. Exported types and function
 * signature are unchanged so call-sites don't need to change.
 *
 * MA3-94: scanner suppression fields now wired:
 *  - `findings`: parsed from `currentScan.findings` JSON; includes `suppressed` flag.
 *  - `effectiveRisk`: post-suppression risk score (headline risk).
 *  - `rawRisk`: pre-suppression risk score (shown struck-through when different).
 *  - `drift.lines`: the API's `diff` field is a raw unified-diff string, not the
 *    pre-parsed DiffLine[]. We parse it here; malformed lines become ctx entries.
 */

import { api, type ApiToolDetailView, type ApiDriftEventView, type ApiFinding } from "./api";
import type { AssetKind, AssetStatus } from "./mock-inventory";

export type Severity = "CRITICAL" | "WARN" | "INFO";

/** A single line of a unified diff. */
export interface DiffLine {
  kind: "ctx" | "add" | "del";
  /** Gutter label: line number for ctx, "+"/"-" for add/del. */
  gutter: string;
  text: string;
}

export interface DriftDiff {
  pinnedSha: string;
  observedSha: string;
  /** Short human note about what changed. */
  summary: string;
  lines: DiffLine[];
}

export interface Finding {
  id: string;
  severity: Severity;
  ruleId: string;
  category: string;
  /** `{path}:{line}` */
  location: string;
  /** Masked evidence string. */
  evidence: string;
  /** Unique fingerprint for this finding (used for suppression). */
  fingerprint: string;
  /** True when this finding has been suppressed by an org/tool/fingerprint rule. */
  suppressed: boolean;
}

export interface VersionEntry {
  id: string;
  /** ISO 8601 timestamp. */
  at: string;
  shaShort: string;
  marker?: "PINNED" | "DRIFTED";
  note: string;
}

export interface ToolDetail {
  id: string;
  name: string;
  kind: AssetKind;
  source: string;
  status: AssetStatus;
  shaShort: string;
  /** Post-suppression (effective) risk score 0-100. 0 when no scan available. */
  effectiveRisk: number;
  /**
   * Pre-suppression (raw) risk score. Equals effectiveRisk when nothing is
   * suppressed. Exposed so the UI can show "Risk 90 (raw 93)" when they differ.
   */
  rawRisk: number;
  /** Convenience alias (= effectiveRisk) for callers that just want a single risk value. */
  risk: number;
  scannedAt?: string;
  /** Present only when status is DRIFTED. */
  drift?: DriftDiff;
  findings: Finding[];
  history: VersionEntry[];
}

/**
 * Parse a raw unified-diff string into DiffLine[].
 * Lines starting with '+' are additions, '-' are deletions, everything else
 * (including '@@' headers) is context. We skip diff --git / index / --- / +++
 * header lines as they're not user-readable.
 */
function parseDiff(raw: string): DiffLine[] {
  if (!raw) return [];
  const skip = /^(diff --git|index |--- |\+\+\+ |@@)/;
  return raw.split("\n").flatMap((line): DiffLine[] => {
    if (skip.test(line)) return [];
    if (line.startsWith("+")) {
      return [{ kind: "add", gutter: "+", text: line.slice(1) }];
    }
    if (line.startsWith("-")) {
      return [{ kind: "del", gutter: "-", text: line.slice(1) }];
    }
    return [{ kind: "ctx", gutter: "·", text: line.startsWith(" ") ? line.slice(1) : line }];
  });
}

function mapDriftEvent(d: ApiDriftEventView): DriftDiff {
  return {
    pinnedSha: d.approvedHash.slice(0, 8),
    observedSha: d.observedHash.slice(0, 8),
    summary: `sha256 drift: ${d.approvedHash.slice(0, 8)} → ${d.observedHash.slice(0, 8)}`,
    lines: parseDiff(d.diff ?? ""),
  };
}

/**
 * Parse the JSON findings array string from the scan result into the UI Finding shape.
 * Stable `id` = fingerprint (guaranteed unique per finding by the scanner).
 */
function parseScanFindings(findingsJson: string): Finding[] {
  try {
    const raw: ApiFinding[] = JSON.parse(findingsJson);
    return raw.map((f) => ({
      id: f.fingerprint,
      severity: f.severity,
      ruleId: f.ruleId,
      category: f.category,
      location: `${f.path}:${f.line}`,
      evidence: f.evidence,
      fingerprint: f.fingerprint,
      suppressed: f.suppressed,
    }));
  } catch {
    return [];
  }
}

function mapDetail(dto: ApiToolDetailView): ToolDetail {
  const tool = dto.tool;
  const currentHash = dto.currentDefinition?.hash ?? null;
  const approvedHash = dto.approvedDefinition?.hash ?? null;
  const shaShort = currentHash ? currentHash.slice(0, 8) : "00000000";

  const status = tool.status.toUpperCase() as AssetStatus;
  const kind = tool.kind.toUpperCase() as AssetKind;

  // Drift: use the first open drift event if any.
  const drift: DriftDiff | undefined =
    dto.openDriftEvents.length > 0
      ? mapDriftEvent(dto.openDriftEvents[0])
      : undefined;

  // Version history from API
  const history: VersionEntry[] = dto.versionHistory.map((v) => {
    const isPinned = approvedHash && v.hash === approvedHash;
    const isDrifted = dto.openDriftEvents.some((d) => d.observedHash === v.hash);
    return {
      id: v.id,
      at: v.observedAt,
      shaShort: v.hash.slice(0, 8),
      marker: isPinned ? "PINNED" : isDrifted ? "DRIFTED" : undefined,
      note: isPinned
        ? `pinned · approved by ${dto.approvedDefinition?.approvedBy ?? "system"}`
        : isDrifted
          ? "observed drift · not pinned"
          : "observed version",
    };
  });

  // MA3-94: scanner + suppression integration.
  const scan = dto.currentScan ?? null;
  const effectiveRisk = scan?.effectiveRiskScore ?? 0;
  const rawRisk = scan?.riskScore ?? 0;
  const findings = scan ? parseScanFindings(scan.findings) : [];

  return {
    id: tool.id,
    name: tool.name,
    kind,
    // LOCAL FALLBACK: source name not on ToolView; use serverId until API enriches it.
    source: tool.serverId ?? "—",
    status,
    shaShort,
    effectiveRisk,
    rawRisk,
    risk: effectiveRisk,
    scannedAt: scan?.scannedAt,
    drift,
    findings,
    history,
  };
}

/**
 * Returns one asset's full detail from the live API, or `null` if unknown.
 */
export async function getToolDetail(id: string): Promise<ToolDetail | null> {
  try {
    const dto = await api.getToolDetail(id);
    return mapDetail(dto);
  } catch {
    // 404 or unknown id
    return null;
  }
}
