/**
 * Settings data module.
 *
 * Swap points:
 *  - `getConnectedSources()`: calls `GET /api/sources`, maps SourceView → ConnectedSource.
 *  - `connectSource()` / `rescanSource()`: call `POST /api/sources` / `POST /api/sources/{id}/scan`.
 *  - `getNotificationChannels()`: calls `GET /api/settings/notification-channels` (MA3-92).
 *  - `getPolicyRules()`: calls `GET /api/settings/policy-rules` (MA3-92).
 *  - `getMembers()`: calls `GET /api/settings/members` (MA3-93).
 *
 * No fabricated fallbacks: on a trust product, errors propagate so the UI shows a
 * real error state rather than fake config (MA3-126).
 *
 * Self-hosted constraint (기획서 §7): notification channels are opt-in and OFF
 * by default. Nothing leaves the network until a destination is enabled.
 */

import {
  api,
  type ApiSourceView,
  type ApiNotificationChannel,
  type ApiPolicyRule,
  type ApiMember,
  type ApiSuppression,
} from "./api";
import type { AssetKind } from "./mock-inventory";

export interface ConnectedSource {
  id: string;
  /** Git URL or "mcp · <name>". */
  url: string;
  branch: string;
  /** ISO 8601 or human "last scanned" label (display only). */
  lastScan: string;
  assetCount: number;
}

/**
 * NotificationChannel matches the MA3-92 API shape.
 * `kind` maps to the API's `type` field for backwards compat with existing UI code.
 */
export type ChannelKind = "EMAIL" | "SLACK" | "WEBHOOK";

export interface NotificationChannel {
  id: string;
  kind: ChannelKind;
  /** Display name (e.g. "Security Alerts Slack"). */
  label: string;
  /** Destination (address / channel / url). */
  target: string;
  config?: Record<string, unknown>;
  /** OFF by default per self-hosted constraint. */
  enabled: boolean;
}

export type MemberRole = "ADMIN" | "MEMBER" | "VIEWER";

export interface Member {
  id: string;
  email: string;
  displayName: string;
  /** Two-letter avatar initials (derived from displayName or email). */
  initials: string;
  role: MemberRole;
  active: boolean;
  createdAt: string | null;
}

export type PolicyAction = "AUTO_BLOCK" | "HOLD";

export interface PolicyCondition {
  minRiskScore?: number;
  severity?: string;
  findingCategory?: string;
  nameRegex?: string;
}

/**
 * PolicyRule matches the MA3-92 API shape.
 */
export interface PolicyRule {
  id: string;
  name: string;
  priority: number;
  condition: PolicyCondition;
  action: PolicyAction;
  enabled: boolean;
}

function mapSource(s: ApiSourceView): ConnectedSource {
  return {
    id: s.id,
    url: s.uri,
    // API does not expose branch on SourceView yet. LOCAL FALLBACK = "—".
    branch: "—",
    // API does not expose lastScan on SourceView. LOCAL FALLBACK = createdAt.
    lastScan: s.createdAt,
    // API does not expose assetCount on SourceView yet. LOCAL FALLBACK = 0.
    assetCount: 0,
  };
}

function mapApiChannel(c: ApiNotificationChannel): NotificationChannel {
  return {
    id: c.id,
    kind: c.type as ChannelKind,
    label: c.name,
    target: c.target,
    config: c.config,
    enabled: c.enabled,
  };
}

function mapApiPolicyRule(r: ApiPolicyRule): PolicyRule {
  return {
    id: r.id,
    name: r.name,
    priority: r.priority,
    condition: r.condition,
    action: r.action,
    enabled: r.enabled,
  };
}

/** Kind label shown in the connect-source dialog (display only). */
export const SOURCE_KINDS: AssetKind[] = ["SKILL", "MCP_TOOL", "TOOL", "MCP_SERVER"];

export async function getConnectedSources(): Promise<ConnectedSource[]> {
  // assetCount needs a tool → server → source join (not on SourceView).
  const [sources, servers, tools] = await Promise.all([
    api.getSources(),
    api.getServers(),
    api.getTools(),
  ]);
  const serverToSource = new Map(servers.map((sv) => [sv.id, sv.sourceId]));
  const countBySource = new Map<string, number>();
  for (const t of tools) {
    const srcId = serverToSource.get(t.serverId);
    if (srcId) countBySource.set(srcId, (countBySource.get(srcId) ?? 0) + 1);
  }
  return sources.map((s) => ({
    ...mapSource(s),
    assetCount: countBySource.get(s.id) ?? 0,
  }));
}

/**
 * Connect a new Git source and trigger initial ingestion.
 * Returns the created source for optimistic UI update.
 */
export async function connectSource(
  repoUrl: string,
  token?: string,
): Promise<ConnectedSource> {
  const result = await api.createSource(repoUrl, token);
  return mapSource(result.source);
}

/**
 * Re-scan an existing source (re-ingest + drift scan).
 */
export async function rescanSource(id: string): Promise<void> {
  await api.scanSource(id);
}

/* ── Notification channels (MA3-92: API-backed) ─────────────────────── */

// No local fallback: on a trust product the settings screen must never show
// fabricated channels as if they were real config. Errors propagate so the
// caller surfaces a real error state (MA3-126).
export async function getNotificationChannels(): Promise<NotificationChannel[]> {
  const channels = await api.getNotificationChannels();
  return channels.map(mapApiChannel);
}

/* ── Policy rules (MA3-92: API-backed) ───────────────────────────────── */

// No local fallback — see getNotificationChannels (MA3-126). A fabricated
// policy rule shown as real is a security misrepresentation.
export async function getPolicyRules(): Promise<PolicyRule[]> {
  const rules = await api.getPolicyRules();
  return rules.map(mapApiPolicyRule);
}

/* ── Suppressions (MA3-94: API-backed) ───────────────────────────────── */

export interface Suppression {
  id: string;
  ruleId: string;
  /** null = org-wide; set = only this tool. */
  toolId: string | null;
  /** null = whole rule; set = one specific finding fingerprint. */
  fingerprint: string | null;
  reason: string | null;
  createdBy: string | null;
  createdAt: string;
}

function mapApiSuppression(s: ApiSuppression): Suppression {
  return {
    id: s.id,
    ruleId: s.ruleId,
    toolId: s.toolId,
    fingerprint: s.fingerprint,
    reason: s.reason,
    createdBy: s.createdBy,
    createdAt: s.createdAt,
  };
}

export async function getSuppressions(): Promise<Suppression[]> {
  const data = await api.getSuppressions();
  return data.map(mapApiSuppression);
}

/* ── Members & roles (MA3-93: API-backed) ────────────────────────────── */

function memberInitials(displayName: string, email: string): string {
  const name = (displayName || email).trim();
  const parts = name.split(/[\s@.]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

function mapApiMember(m: ApiMember): Member {
  return {
    id: m.id,
    email: m.email,
    displayName: m.displayName,
    initials: memberInitials(m.displayName, m.email),
    role: m.role as MemberRole,
    active: m.active,
    createdAt: m.createdAt,
  };
}

// No local fallback — MembersSection has its own fetchError state, so the error
// must propagate rather than render a fabricated admin row (MA3-126).
export async function getMembers(): Promise<Member[]> {
  const members = await api.getMembers();
  return members.map(mapApiMember);
}
