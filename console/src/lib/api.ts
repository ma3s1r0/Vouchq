/**
 * Typed API client for the Vouchq backend.
 *
 * Auth model (MA3-93): session cookie (JSESSIONID) set by POST /api/auth/login.
 *
 * URL strategy:
 * - Browser (client components): relative /api/... paths → Next.js rewrite proxies
 *   to the backend, making cookies first-party. credentials:"include" sends the cookie.
 * - Server (server components / SSR): absolute API_PROXY_TARGET/api/... so Node.js
 *   can resolve the URL. Server-side calls that need cookie auth must forward the
 *   Cookie header manually (see server-fetch helpers), or use client-side fetch.
 *
 * No Basic auth headers — credentials are never embedded in the bundle.
 *
 * NOTE: The backend may not be running during `next build`. Every getter
 * should only be called at runtime, not at module-load time. The build
 * succeeds because these are ordinary async functions (no top-level await).
 */

/**
 * Carries the HTTP status + the backend's error message (ApiDtos.ApiError.message)
 * so callers can react to *why* a request failed — e.g. the MCP-install refusal,
 * which is a governance signal, not a generic failure (MA3-120).
 */
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function readApiError(res: Response, method: string, path: string): Promise<ApiError> {
  let message = `${method} ${path} → ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { message?: string };
    if (body?.message) message = body.message;
  } catch {
    /* non-JSON body — keep the status line */
  }
  return new ApiError(res.status, message);
}

// On the server Node.js cannot resolve relative URLs, so we use the proxy target.
// In the browser we use relative paths so the Next.js rewrite makes cookies first-party.
const BASE =
  typeof window === "undefined"
    ? `${process.env.API_PROXY_TARGET ?? "http://localhost:8080"}/api`
    : "/api";

/**
 * On the server, `credentials:"include"` is a no-op (there is no browser cookie
 * jar), so SSR fetches would hit the now-authenticated backend unauthenticated
 * and 401 (MA3-93). Forward the incoming request's cookies (incl. JSESSIONID)
 * manually. `next/headers` is dynamically imported only on the server so it is
 * never bundled for the client; outside a request scope (e.g. build) there are
 * no cookies and we return nothing.
 */
async function serverCookieHeader(): Promise<Record<string, string>> {
  if (typeof window !== "undefined") return {};
  try {
    const { cookies } = await import("next/headers");
    const store = await cookies();
    const all = store.getAll();
    if (all.length === 0) return {};
    return { Cookie: all.map((c) => `${c.name}=${c.value}`).join("; ") };
  } catch {
    return {};
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    // Disable Next.js caching so pages always reflect live data.
    cache: "no-store",
    credentials: "include",
    headers: { Accept: "application/json", ...(await serverCookieHeader()) },
  });
  if (!res.ok) {
    throw await readApiError(res, "GET", path);
  }
  return res.json() as Promise<T>;
}

/** A page of results plus the total count of matching rows (X-Total-Count). */
export interface Page<T> {
  items: T[];
  total: number;
}

/**
 * GET a list endpoint that paginates server-side (MA3-118): the JSON body is the
 * page array, the full matching count rides in the {@code X-Total-Count} header.
 * Falls back to the page length if the header is absent.
 */
async function getPaged<T>(path: string): Promise<Page<T>> {
  const res = await fetch(`${BASE}${path}`, {
    cache: "no-store",
    credentials: "include",
    headers: { Accept: "application/json", ...(await serverCookieHeader()) },
  });
  if (!res.ok) {
    throw await readApiError(res, "GET", path);
  }
  const items = (await res.json()) as T[];
  const header = res.headers.get("X-Total-Count");
  const total = header != null ? Number(header) : items.length;
  return { items, total: Number.isFinite(total) ? total : items.length };
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    cache: "no-store",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...(await serverCookieHeader()),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    throw await readApiError(res, "POST", path);
  }
  // 204 No Content
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

async function patch<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "PATCH",
    cache: "no-store",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...(await serverCookieHeader()),
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw await readApiError(res, "PATCH", path);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

async function put<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "PUT",
    cache: "no-store",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...(await serverCookieHeader()),
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw await readApiError(res, "PUT", path);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

async function del(path: string): Promise<void> {
  const res = await fetch(`${BASE}${path}`, {
    method: "DELETE",
    cache: "no-store",
    credentials: "include",
    headers: { Accept: "application/json", ...(await serverCookieHeader()) },
  });
  if (!res.ok) {
    throw await readApiError(res, "DELETE", path);
  }
}

/* ── Auth DTO shapes (MA3-93) ─────────────────────────────────────────── */

export type ApiRole = "ADMIN" | "MEMBER" | "VIEWER";

// Flat shape, matching AuthController.MeView (login + /me return the same).
export interface ApiMeResponse {
  userId: string;
  email: string;
  displayName: string;
  role: ApiRole;
  orgId: string;
}

export interface ApiLoginRequest {
  email: string;
  password: string;
}

/* ── Members DTO shapes (MA3-93) ──────────────────────────────────────── */

export interface ApiMember {
  id: string;
  email: string;
  displayName: string;
  role: ApiRole;
  active: boolean;
  /** May be null on the immediate POST response. */
  createdAt: string | null;
}

export type ApiMemberCreate = {
  email: string;
  displayName: string;
  role: ApiRole;
  password: string;
};

export type ApiMemberPatch = Partial<Pick<ApiMember, "role" | "active">>;

/* ── API DTO shapes (mirroring ApiDtos.java) ──────────────────────────── */

export interface ApiStatusCount {
  status: string;
  count: number;
}

export interface ApiSeverityCount {
  severity: string;
  count: number;
}

export interface ApiDriftEventView {
  id: string;
  toolId: string;
  approvedHash: string;
  observedHash: string;
  diff: string;
  severity: string;
  detectedAt: string;
  resolved: boolean;
}

export interface ApiDashboardSummary {
  totalAssets: number;
  byStatus: ApiStatusCount[];
  pendingApproval: number;
  unresolvedDrift: number;
  unresolvedDriftBySeverity: ApiSeverityCount[];
  recentDrift: ApiDriftEventView[];
}

export interface ApiSourceView {
  id: string;
  type: string;
  uri: string;
  authenticated: boolean;
  createdAt: string;
}

export interface ApiIngestionSummary {
  skillsParsed: number;
  toolsCreated: number;
  versionsCreated: number;
  unchanged: number;
}

export interface ApiSourceIngestResult {
  source: ApiSourceView;
  ingestion: ApiIngestionSummary;
}

export interface ApiSourceScanResult {
  sourceId: string;
  ingestion: ApiIngestionSummary;
  toolsScanned: number;
  driftDetected: number;
}

export interface ApiServerView {
  id: string;
  sourceId: string;
  kind: string;
  name: string;
  createdAt: string;
}

export interface ApiToolView {
  id: string;
  serverId: string;
  kind: string;
  name: string;
  status: string;
  currentVersionId: string | null;
  approvedVersionId: string | null;
  updatedAt: string;
  /** Effective (post-suppression) risk score. Null when scanner has never run. */
  effectiveRiskScore: number | null;
  /** Effective (post-suppression) highest severity. Null when scanner has never run. */
  effectiveHighestSeverity: string | null;
  /** Raw (pre-suppression) risk score. */
  riskScore: number | null;
  /** Raw (pre-suppression) highest severity. */
  highestSeverity: string | null;
}

export interface ApiToolVersionView {
  id: string;
  hash: string;
  definition: string;
  observedAt: string;
}

export interface ApiApprovedVersionView {
  id: string;
  toolVersionId: string;
  hash: string;
  approvedBy: string;
  approvedAt: string;
}

/** A single parsed finding from a scan result's `findings` JSON string. */
export interface ApiFinding {
  ruleId: string;
  category: string;
  severity: "INFO" | "WARN" | "CRITICAL";
  path: string;
  line: number;
  evidence: string;
  fingerprint: string;
  suppressed: boolean;
}

/** Scan result view attached to a tool detail (MA3-94). */
export interface ApiScanResultView {
  riskScore: number;
  highestSeverity: string | null;
  effectiveRiskScore: number;
  effectiveHighestSeverity: string | null;
  /** JSON-encoded array of ApiFinding */
  findings: string;
  scannedAt: string;
}

export interface ApiToolDetailView {
  tool: ApiToolView;
  currentDefinition: ApiToolVersionView | null;
  approvedDefinition: ApiApprovedVersionView | null;
  versionHistory: ApiToolVersionView[];
  openDriftEvents: ApiDriftEventView[];
  /** Present when a scan result exists for this tool's current version. */
  currentScan: ApiScanResultView | null;
}

/* ── Approved artifact / change password ─────────────────────────────── */

export interface ApiArtifactFile {
  path: string;
  content: string;
}

/** GET /api/tools/{id}/approved/artifact — the pinned, installable version. */
export interface ApiApprovedArtifact {
  name: string;
  hash: string;
  files: ApiArtifactFile[];
}

export interface ApiChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/** GET /api/tools/{id}/mcp-install — vouched MCP server connection config (MA3-110). */
export interface ApiMcpInstall {
  name: string;
  url: string;
  approvedTools: number;
  totalTools: number;
  vouchedAt: string;
}

/* ── Suppressions (MA3-94) ───────────────────────────────────────────── */

export interface ApiSuppression {
  id: string;
  ruleId: string;
  /** null = org-wide suppression; set = only this tool. */
  toolId: string | null;
  /** null = whole rule suppressed; set = one specific finding fingerprint. */
  fingerprint: string | null;
  reason: string | null;
  createdBy: string | null;
  createdAt: string;
}

export interface ApiSuppressionCreate {
  ruleId: string;
  toolId?: string | null;
  fingerprint?: string | null;
  reason?: string;
}

/** GET /api/audit-logs/verify — real hash-chain verification (MA3-91). */
export interface ApiAuditVerify {
  valid: boolean;
  brokenEntryId: number | null;
  reason: string | null;
}

export interface ApiAuditLogView {
  id: number;
  actor: string;
  action: string;
  targetId: string;
  payload: string;
  entryHash: string;
  createdAt: string;
}

/* ── Settings: notification channels (MA3-92) ────────────────────────── */

export type ApiChannelType = "WEBHOOK" | "SLACK" | "EMAIL";

export interface ApiNotificationChannel {
  id: string;
  type: ApiChannelType;
  name: string;
  target: string;
  config?: Record<string, unknown>;
  enabled: boolean;
}

export type ApiNotificationChannelCreate = Omit<ApiNotificationChannel, "id">;
export type ApiNotificationChannelPatch = Partial<Omit<ApiNotificationChannel, "id">>;

/* ── Settings: policy rules (MA3-92) ─────────────────────────────────── */

export type ApiPolicyAction = "AUTO_BLOCK" | "HOLD";

export interface ApiPolicyCondition {
  minRiskScore?: number;
  severity?: string;
  findingCategory?: string;
  nameRegex?: string;
}

export interface ApiPolicyRule {
  id: string;
  name: string;
  priority: number;
  condition: ApiPolicyCondition;
  action: ApiPolicyAction;
  enabled: boolean;
}

export type ApiPolicyRuleCreate = Omit<ApiPolicyRule, "id">;
export type ApiPolicyRulePatch = Partial<Omit<ApiPolicyRule, "id">>;

/* ── Endpoint functions ───────────────────────────────────────────────── */

export const api = {
  // Auth (MA3-93)
  login: (body: ApiLoginRequest) =>
    post<ApiMeResponse>("/auth/login", body),
  logout: () =>
    post<void>("/auth/logout"),
  getMe: () =>
    get<ApiMeResponse>("/auth/me"),
  // Self-service password change (MA3-102)
  changePassword: (body: ApiChangePasswordRequest) =>
    post<void>("/auth/change-password", body),

  // Approved artifact — installable pinned version (MA3-103)
  getApprovedArtifact: (id: string) =>
    get<ApiApprovedArtifact>(`/tools/${id}/approved/artifact`),
  // Vouched MCP server install config (MA3-110)
  getMcpInstall: (id: string) =>
    get<ApiMcpInstall>(`/tools/${id}/mcp-install`),

  // Members (MA3-93) — ADMIN-only
  getMembers: () =>
    get<ApiMember[]>("/settings/members"),
  createMember: (body: ApiMemberCreate) =>
    post<ApiMember>("/settings/members", body),
  patchMember: (id: string, body: ApiMemberPatch) =>
    patch<ApiMember>(`/settings/members/${id}`, body),
  deleteMember: (id: string) =>
    del(`/settings/members/${id}`),

  // Dashboard
  getDashboardSummary: () =>
    get<ApiDashboardSummary>("/dashboard/summary"),

  // Sources
  getSources: () =>
    get<ApiSourceView[]>("/sources"),
  createSource: (repoUrl: string, token?: string) =>
    post<ApiSourceIngestResult>("/sources", { repoUrl, token }),
  scanSource: (id: string) =>
    post<ApiSourceScanResult>(`/sources/${id}/scan`),
  deleteSource: (id: string) =>
    del(`/sources/${id}`),

  // Servers
  getServers: () =>
    get<ApiServerView[]>("/servers"),

  // Tools / inventory
  getTools: (params?: { kind?: string; status?: string }) => {
    const qs = new URLSearchParams();
    if (params?.kind) qs.set("kind", params.kind);
    if (params?.status) qs.set("status", params.status);
    const q = qs.toString();
    return get<ApiToolView[]>(`/tools${q ? `?${q}` : ""}`);
  },
  // One inventory page with server-side search/filters (MA3-118). `limit > 0`
  // engages pagination; `total` (X-Total-Count) is the full matching count.
  getToolsPage: (params: {
    kind?: string;
    status?: string;
    q?: string;
    page?: number;
    limit: number;
  }) => {
    const qs = new URLSearchParams();
    if (params.kind) qs.set("kind", params.kind);
    if (params.status) qs.set("status", params.status);
    if (params.q) qs.set("q", params.q);
    if (params.page) qs.set("page", String(params.page));
    qs.set("limit", String(params.limit));
    return getPaged<ApiToolView>(`/tools?${qs.toString()}`);
  },
  getToolDetail: (id: string) =>
    get<ApiToolDetailView>(`/tools/${id}`),
  approveTool: (id: string, approvedBy?: string) =>
    post<ApiApprovedVersionView>(`/tools/${id}/approve`, approvedBy ? { approvedBy } : {}),
  blockTool: (id: string) =>
    post<void>(`/tools/${id}/block`, {}),

  // Drift events
  getDriftEvents: (resolved?: boolean) => {
    const qs = resolved !== undefined ? `?resolved=${resolved}` : "";
    return get<ApiDriftEventView[]>(`/drift-events${qs}`);
  },
  resolveDriftEvent: (id: string) =>
    post<void>(`/drift-events/${id}/resolve`),

  // Audit log
  verifyAuditChain: () =>
    get<ApiAuditVerify>("/audit-logs/verify"),
  getAuditLogs: (limit?: number) => {
    const qs = limit !== undefined ? `?limit=${limit}` : "";
    return get<ApiAuditLogView[]>(`/audit-logs${qs}`);
  },
  // One audit-log page with server-side filters (MA3-118): action / actor /
  // since (yyyy-MM-dd). `total` (X-Total-Count) is the full matching count.
  getAuditLogsPage: (params: {
    action?: string;
    actor?: string;
    since?: string;
    page?: number;
    limit: number;
  }) => {
    const qs = new URLSearchParams();
    if (params.action) qs.set("action", params.action);
    if (params.actor) qs.set("actor", params.actor);
    if (params.since) qs.set("since", params.since);
    if (params.page) qs.set("page", String(params.page));
    qs.set("limit", String(params.limit));
    return getPaged<ApiAuditLogView>(`/audit-logs?${qs.toString()}`);
  },
  // Full-chain export download URL (MA3-118) — streams the *complete* WORM log,
  // not the current view. Hits the same-origin proxy so the cookie is sent.
  auditExportUrl: (format: "json" | "csv") => `/api/audit-logs/export?format=${format}`,

  // Settings: notification channels (MA3-92)
  getNotificationChannels: () =>
    get<ApiNotificationChannel[]>("/settings/notification-channels"),
  createNotificationChannel: (body: ApiNotificationChannelCreate) =>
    post<ApiNotificationChannel>("/settings/notification-channels", body),
  updateNotificationChannel: (id: string, body: ApiNotificationChannelCreate) =>
    put<ApiNotificationChannel>(`/settings/notification-channels/${id}`, body),
  patchNotificationChannel: (id: string, body: ApiNotificationChannelPatch) =>
    patch<ApiNotificationChannel>(`/settings/notification-channels/${id}`, body),
  deleteNotificationChannel: (id: string) =>
    del(`/settings/notification-channels/${id}`),
  testNotificationChannel: (id: string) =>
    post<void>(`/settings/notification-channels/${id}/test`),

  // Settings: policy rules (MA3-92)
  getPolicyRules: () =>
    get<ApiPolicyRule[]>("/settings/policy-rules"),
  createPolicyRule: (body: ApiPolicyRuleCreate) =>
    post<ApiPolicyRule>("/settings/policy-rules", body),
  updatePolicyRule: (id: string, body: ApiPolicyRuleCreate) =>
    put<ApiPolicyRule>(`/settings/policy-rules/${id}`, body),
  patchPolicyRule: (id: string, body: ApiPolicyRulePatch) =>
    patch<ApiPolicyRule>(`/settings/policy-rules/${id}`, body),
  deletePolicyRule: (id: string) =>
    del(`/settings/policy-rules/${id}`),

  // Suppressions (MA3-94)
  getSuppressions: () =>
    get<ApiSuppression[]>("/settings/suppressions"),
  createSuppression: (body: ApiSuppressionCreate) =>
    post<ApiSuppression>("/settings/suppressions", body),
  deleteSuppression: (id: string) =>
    del(`/settings/suppressions/${id}`),
};
