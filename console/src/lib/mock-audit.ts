/**
 * Audit log — now backed by the real REST API (MA3-80).
 *
 * Swap point: `getAuditLog()` calls `GET /api/audit-logs` and maps API
 * AuditLogView DTOs → the UI's AuditEntry shape. Exported types and function
 * signature are unchanged so call-sites don't need to change.
 *
 * Notes on gaps:
 *  - `target`: the API carries `targetId` (UUID string), not a human name.
 *    We display the UUID short prefix until a name resolver is added.
 *  - `detail`: mapped from `payload` (stringified JSON or free text).
 *  - `prevHash`: not on AuditLogView (the chain is prev→entry, but prevHash
 *    is not exposed). LOCAL FALLBACK = "—".
 *  - `entryHash`: present on AuditLogView (기획서 §10 hash-chain display).
 *  - `action`: mapped 1-to-1 from the API's string. Unknown actions fall
 *    through as-is (the UI renders them as a plain label).
 */

import { api, type ApiAuditLogView } from "./api";

/** Action kinds drive the colored chip on each row. */
export type AuditAction =
  | "TOOL_APPROVED"
  | "TOOL_BLOCKED"
  | "DRIFT_DETECTED"
  | "SCAN_COMPLETED"
  | "SOURCE_CONNECTED"
  | "MEMBER_INVITED";

export interface AuditEntry {
  id: string;
  /** ISO 8601 timestamp. */
  at: string;
  action: AuditAction;
  /** Asset / source / member the action targeted. */
  target: string;
  /** Who performed it (email or "scheduler"). */
  actor: string;
  /** Short human detail line. */
  detail: string;
  /** Short hash prefix of the previous entry (LOCAL FALLBACK = "—"). */
  prevHash: string;
  entryHash: string;
}

function mapAuditEntry(a: ApiAuditLogView): AuditEntry {
  // targetId is a UUID; display the first 8 chars as a short identifier.
  const target = a.targetId ? a.targetId.replace(/-/g, "").slice(0, 8) : "—";

  // payload may be a JSON object or free text; show it as the detail line.
  const detail = a.payload ?? "";

  // action is stored as-is in the DB; cast to the UI union type.
  const action = (a.action as AuditAction) ?? "SCAN_COMPLETED";

  return {
    id: String(a.id),
    at: a.createdAt,
    action,
    target,
    actor: a.actor ?? "system",
    detail,
    // LOCAL FALLBACK: prevHash not exposed by the API yet.
    prevHash: "—",
    entryHash: a.entryHash ?? "—",
  };
}

/** All distinct actions present in the log, for the filter bar. */
export function auditActions(entries: AuditEntry[]): AuditAction[] {
  return [...new Set(entries.map((e) => e.action))];
}

/** All distinct actors present in the log, for the filter bar. */
export function auditActors(entries: AuditEntry[]): string[] {
  return [...new Set(entries.map((e) => e.actor))];
}

/**
 * Returns audit entries from the live API, newest first.
 */
export async function getAuditLog(): Promise<AuditEntry[]> {
  const entries = await api.getAuditLogs();
  return entries.map(mapAuditEntry);
}
