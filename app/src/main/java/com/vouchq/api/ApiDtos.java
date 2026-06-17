package com.vouchq.api;

import com.vouchq.audit.AuditLog;
import com.vouchq.notify.NotificationChannelEntity;
import com.vouchq.policy.PolicyRule;
import com.vouchq.registry.ApprovedVersion;
import com.vouchq.registry.DriftEvent;
import com.vouchq.registry.RegisteredServer;
import com.vouchq.registry.ScanResult;
import com.vouchq.registry.Source;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolVersion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Request/response DTOs for the public REST API (MA3-80).
 *
 * <p>JPA entities are never serialized directly — every endpoint maps to one of
 * these records so the wire shape is stable and lazy/internal fields never leak.
 * Each {@code from(...)} factory is the single mapping point entity → DTO.
 */
public final class ApiDtos {

    private ApiDtos() {}

    // --- sources -------------------------------------------------------------

    /** {@code POST /api/sources} body. Provide a Git repo URL; token is optional. */
    public record CreateSourceRequest(String repoUrl, String token) {}

    /**
     * {@code POST /api/sources/{id}/credentials} body (MA3-89 rotation): a new raw
     * token. It is encrypted at rest immediately and never echoed back. A blank/null
     * token clears the stored credential.
     */
    public record UpdateCredentialRequest(String token) {}

    /** {@code POST /api/sources/{id}/credentials} response — never includes the token. */
    public record UpdateCredentialResult(UUID sourceId, boolean credentialStored) {}

    public record SourceView(UUID id, String type, String uri, boolean authenticated,
                             OffsetDateTime createdAt) {
        public static SourceView from(Source s) {
            return new SourceView(s.getId(), s.getType().name(), s.getUri(),
                    s.getAuthRef() != null, s.getCreatedAt());
        }
    }

    /** {@code POST /api/sources} response: the source plus the ingestion summary. */
    public record IngestionSummary(int skillsParsed, int toolsCreated,
                                   int versionsCreated, int unchanged) {}

    public record SourceIngestResult(SourceView source, IngestionSummary ingestion) {}

    /**
     * {@code POST /api/sources/upload} response (MA3-76 zip fallback). Same shape
     * as the git ingest result so callers handle both paths uniformly.
     */
    public record SourceUploadResult(SourceView source, IngestionSummary ingestion) {}

    /** {@code POST /api/sources/{id}/scan} response. */
    public record SourceScanResult(UUID sourceId, IngestionSummary ingestion,
                                   int toolsScanned, int driftDetected) {}

    // --- servers -------------------------------------------------------------

    public record ServerView(UUID id, UUID sourceId, String kind, String name,
                             OffsetDateTime createdAt) {
        public static ServerView from(RegisteredServer s) {
            return new ServerView(s.getId(), s.getSourceId(), s.getKind().name(),
                    s.getName(), s.getCreatedAt());
        }
    }

    // --- tools ---------------------------------------------------------------

    /**
     * Inventory row. {@code riskScore} / {@code highestSeverity} come from the
     * latest {@link ScanResult} of the tool's current version (MA3-86); both are
     * null when the tool has no scan yet (e.g. ingested before scanner wiring).
     */
    public record ToolView(UUID id, UUID serverId, String kind, String name, String status,
                           UUID currentVersionId, UUID approvedVersionId,
                           Integer riskScore, String highestSeverity,
                           Integer effectiveRiskScore, String effectiveHighestSeverity,
                           OffsetDateTime updatedAt,
                           // The originating source (Git repo / MCP server). Lets the
                           // console group the inventory by source (MA3-135) — a repo
                           // can register many skills. Null until enriched by the caller.
                           UUID sourceId, String sourceUri) {
        public static ToolView from(Tool t) {
            return from(t, null, null, null);
        }

        /**
         * {@code effectiveRiskScore}/{@code effectiveHighestSeverity} reflect any
         * false-positive suppressions (MA3-94) over the raw scan; when there is no
         * suppression they equal the raw values. Both null when unscanned.
         */
        public static ToolView from(Tool t, ScanResult scan,
                                    Integer effectiveRiskScore, String effectiveHighestSeverity) {
            return new ToolView(t.getId(), t.getServerId(), t.getKind().name(), t.getName(),
                    t.getStatus().name(), t.getCurrentVersionId(), t.getApprovedVersionId(),
                    scan == null ? null : scan.getRiskScore(),
                    scan == null ? null : scan.getHighestSeverity(),
                    effectiveRiskScore, effectiveHighestSeverity,
                    t.getUpdatedAt(), null, null);
        }

        /** Copy with the resolved source (tool → server → source), batched by the caller. */
        public ToolView withSource(UUID sourceId, String sourceUri) {
            return new ToolView(id, serverId, kind, name, status, currentVersionId, approvedVersionId,
                    riskScore, highestSeverity, effectiveRiskScore, effectiveHighestSeverity,
                    updatedAt, sourceId, sourceUri);
        }
    }

    public record ToolVersionView(UUID id, String hash, String definition,
                                  OffsetDateTime observedAt) {
        public static ToolVersionView from(ToolVersion v) {
            return new ToolVersionView(v.getId(), v.getHash(), v.getDefinition(),
                    v.getObservedAt());
        }
    }

    public record ApprovedVersionView(UUID id, UUID toolVersionId, String hash,
                                      String approvedBy, OffsetDateTime approvedAt) {
        public static ApprovedVersionView from(ApprovedVersion a) {
            return new ApprovedVersionView(a.getId(), a.getToolVersionId(), a.getHash(),
                    a.getApprovedBy(), a.getApprovedAt());
        }
    }

    /**
     * Scan outcome for the current version (MA3-86 / MA3-94): the RAW aggregate
     * ({@code riskScore}/{@code highestSeverity}) is preserved for transparency,
     * alongside the EFFECTIVE values after false-positive suppression. {@code findings}
     * is a JSON array string of the raw findings, each annotated with a
     * {@code suppressed} boolean. When nothing is suppressed the effective values
     * equal the raw ones.
     */
    public record ScanResultView(int riskScore, String highestSeverity,
                                 int effectiveRiskScore, String effectiveHighestSeverity,
                                 String findings, OffsetDateTime scannedAt) {}

    /** {@code GET /api/tools/{id}}: current + pinned definition, history, open drift, scan. */
    public record ToolDetailView(ToolView tool,
                                 ToolVersionView currentDefinition,
                                 ApprovedVersionView approvedDefinition,
                                 ScanResultView currentScan,
                                 List<ToolVersionView> versionHistory,
                                 List<DriftEventView> openDriftEvents) {}

    /** One file inside an installable approved artifact. */
    public record ArtifactFile(String path, String content) {}

    /**
     * {@code GET /api/tools/{id}/approved/artifact}: the approved (pinned) version
     * reconstructed into installable files (path + raw content) so a consumer can
     * drop the exact governed version into e.g. {@code ~/.claude/skills/<name>/}.
     */
    public record ApprovedArtifactView(String name, String hash, List<ArtifactFile> files) {
        static ApprovedArtifactView of(String name, String hash, String definitionJson,
                                       ObjectMapper mapper) {
            List<ArtifactFile> files = new ArrayList<>();
            try {
                JsonNode arr = mapper.readTree(definitionJson).path("files");
                if (arr.isArray()) {
                    for (JsonNode f : arr) {
                        files.add(new ArtifactFile(
                                f.path("path").asText(), f.path("content").asText("")));
                    }
                }
            } catch (Exception ignored) {
                // Unparseable definition → empty file list; the caller renders nothing.
            }
            return new ApprovedArtifactView(name, hash, files);
        }
    }

    /**
     * {@code GET /api/tools/{id}/mcp-install}: a vouched connection config for the
     * MCP server this tool belongs to (MA3-110). Issued only for a server in good
     * standing; the console renders Claude/Codex install snippets from it.
     */
    public record McpInstallView(String name, String url, int approvedTools,
                                 int totalTools, OffsetDateTime vouchedAt) {}

    // ── Group one-click install (MA3-137/138, vouched-out distribution) ──
    // A source (repo) registers many Skills; these describe the pinned install
    // "lock" for that whole group. Only APPROVED + pinned skills are included;
    // PENDING / DRIFTED / BLOCKED are reported in {@code excluded} for honesty.

    /** Which source the manifest is for (id + raw uri + readable label). */
    public record InstallSourceRef(UUID id, String uri, String label) {}

    /** One file of a pinned skill — path + its content hash (content fetched separately). */
    public record InstallFile(String path, String sha256) {}

    /** One APPROVED + pinned skill in the group, with its files. */
    public record InstallSkill(String name, String description, String versionHash,
                               OffsetDateTime approvedAt, List<InstallFile> files) {}

    /** Counts of skills left out of the install because they aren't approved. */
    public record InstallExcluded(int pending, int drifted, int blocked) {
        public int total() {
            return pending + drifted + blocked;
        }
    }

    /**
     * {@code GET /api/sources/{id}/install/manifest}: the install "lock" for a
     * source group — the exact pinned skills + per-file hashes a client installs
     * (verified against vouchq-stored bytes, no origin re-clone). The companion
     * {@code install.sh} bakes this plan into a POSIX script.
     */
    public record InstallManifest(String vouchq, InstallSourceRef source,
                                  OffsetDateTime generatedAt, List<InstallSkill> skills,
                                  InstallExcluded excluded) {}

    // ── CI verify (MA3-98) ──
    // A read-only build gate: a consumer's CI uploads its checked-out repo and
    // vouchq reports, per Skill, whether the current definition is an APPROVED +
    // pinned version. vouchq stays a registry — this is a query, not a data path.

    /**
     * One Skill's verdict. {@code APPROVED} = its definition hash matches a pinned
     * approved version; {@code CHANGED} = a Skill of this name is approved but the
     * working-tree definition diverges (an unreviewed change / drift); {@code
     * BLOCKED} = a Skill of this name is blocked; {@code UNKNOWN} = not registered.
     */
    public record VerifyItem(String name, String definitionHash, String verdict) {}

    /**
     * {@code POST /api/verify} result: {@code pass} is true only when every Skill
     * is {@code APPROVED}. {@code approved} counts the passing ones out of {@code
     * total}; {@code items} carries each verdict for the CI log.
     */
    public record VerifyResult(boolean pass, int total, int approved, List<VerifyItem> items) {}

    // ── Ruleset self-test / Sentinel (vouchq governing its own scanner) ──

    /**
     * {@code GET /api/ruleset/health}: the live ruleset self-test verdict. {@code
     * state} is HEALTHY / DEGRADED; {@code rulesetHash} fingerprints the active
     * rules; {@code failedCanaries} names any known-malicious fixture the scanner
     * failed to flag (empty when healthy).
     */
    public record RulesetHealthView(String state, String rulesetHash,
                                    OffsetDateTime lastCheckedAt, int canaryTotal,
                                    int canaryFailed, List<String> failedCanaries) {}

    /** {@code POST /api/tools/{id}/approve} body. */
    public record ApproveRequest(String approvedBy) {}

    /** {@code POST /api/tools/{id}/block} body. */
    public record BlockRequest(String blockedBy) {}

    // --- drift events --------------------------------------------------------

    public record DriftEventView(UUID id, UUID toolId, String approvedHash, String observedHash,
                                 String diff, String severity, OffsetDateTime detectedAt,
                                 boolean resolved) {
        public static DriftEventView from(DriftEvent e) {
            return new DriftEventView(e.getId(), e.getToolId(), e.getApprovedHash(),
                    e.getObservedHash(), e.getDiff(), e.getSeverity().name(),
                    e.getDetectedAt(), e.isResolved());
        }
    }

    // --- audit log -----------------------------------------------------------

    public record AuditLogView(Long id, String actor, String action, UUID targetId,
                               String payload, String entryHash, OffsetDateTime createdAt) {
        public static AuditLogView from(AuditLog a) {
            return new AuditLogView(a.getId(), a.getActor(), a.getAction(), a.getTargetId(),
                    a.getPayload(), a.getEntryHash(), a.getCreatedAt());
        }
    }

    // --- dashboard -----------------------------------------------------------

    public record StatusCount(String status, long count) {}

    public record SeverityCount(String severity, long count) {}

    public record DashboardSummary(long totalAssets,
                                   List<StatusCount> byStatus,
                                   long pendingApproval,
                                   long unresolvedDrift,
                                   List<SeverityCount> unresolvedDriftBySeverity,
                                   List<DriftEventView> recentDrift) {}

    // --- settings: notification channels (MA3-92) ----------------------------

    /**
     * {@code GET /api/settings/notification-channels} item. {@code config} is the
     * channel's jsonb extras as a raw JSON string (e.g. {@code {"to":["ops@x"]}}).
     */
    public record NotificationChannelView(UUID id, String type, String name, String target,
                                          String config, boolean enabled, OffsetDateTime createdAt) {
        public static NotificationChannelView from(NotificationChannelEntity c) {
            return new NotificationChannelView(c.getId(), c.getType().name(), c.getName(),
                    c.getTarget(), c.getConfig(), c.isEnabled(), c.getCreatedAt());
        }
    }

    /**
     * Create/replace body for a notification channel. {@code config} is an optional
     * raw JSON object string (defaults to {@code {}}). On PATCH any null field is
     * left unchanged.
     */
    public record NotificationChannelRequest(String type, String name, String target,
                                             String config, Boolean enabled) {}

    /** {@code POST .../{id}/test} response — whether the test send succeeded. */
    public record ChannelTestResult(UUID channelId, boolean delivered, String detail) {}

    // --- settings: policy rules (MA3-92) -------------------------------------

    /** {@code GET /api/settings/policy-rules} item. {@code condition} is raw JSON. */
    public record PolicyRuleView(UUID id, String name, int priority, String condition,
                                 String action, boolean enabled, OffsetDateTime createdAt) {
        public static PolicyRuleView from(PolicyRule r) {
            return new PolicyRuleView(r.getId(), r.getName(), r.getPriority(), r.getCondition(),
                    r.getAction().name(), r.isEnabled(), r.getCreatedAt());
        }
    }

    /**
     * Create/replace body for a policy rule. {@code condition} is a raw JSON object
     * string ({@code {minRiskScore?,severity?,findingCategory?,nameRegex?}}). On
     * PATCH any null field is left unchanged.
     */
    public record PolicyRuleRequest(String name, Integer priority, String condition,
                                    String action, Boolean enabled) {}

    // --- settings: finding suppressions (MA3-94) -----------------------------

    /**
     * {@code GET /api/settings/suppressions} item. {@code toolId} null = org-wide
     * for the rule; {@code fingerprint} null = the whole rule (else one finding).
     */
    public record SuppressionView(UUID id, String ruleId, UUID toolId, String fingerprint,
                                  String reason, String createdBy, OffsetDateTime createdAt) {
        public static SuppressionView from(com.vouchq.policy.FindingSuppression s) {
            return new SuppressionView(s.getId(), s.getRuleId(), s.getToolId(),
                    s.getFingerprint(), s.getReason(), s.getCreatedBy(), s.getCreatedAt());
        }
    }

    /**
     * {@code POST /api/settings/suppressions} body. {@code ruleId} required;
     * {@code toolId} optional (null = org-wide); {@code fingerprint} optional
     * (null = suppress the whole rule, else a single finding); {@code reason}
     * optional but recommended (it is audit-logged).
     */
    public record SuppressionRequest(String ruleId, UUID toolId, String fingerprint, String reason) {}

    // --- errors --------------------------------------------------------------

    public record ApiError(String error, String message) {}
}
