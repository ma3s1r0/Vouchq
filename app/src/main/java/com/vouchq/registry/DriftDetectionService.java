package com.vouchq.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manual re-scan &amp; drift detection (MA3-78, 기획서 §5.1 "수동 재스캔 &amp; Drift 탐지").
 *
 * <p>A scan is only meaningful for a tool that has been pinned (박제) — i.e. has an
 * {@link ApprovedVersion}. It compares the tool's <strong>current</strong>
 * {@link ToolVersion} hash against the pinned {@link ApprovedVersion} hash:
 * <ul>
 *   <li>equal → no drift, no event, status untouched;</li>
 *   <li>different → a {@link DriftEvent} is created with a structured {@code diff}
 *       over the two definitions and the tool flips to {@code DRIFTED}.</li>
 * </ul>
 *
 * <p>Idempotent: re-scanning the same approved→observed hash pair while an
 * unresolved event already exists does not create a duplicate.
 */
@Service
public class DriftDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DriftDetectionService.class);

    private final ToolRepository tools;
    private final ToolVersionRepository toolVersions;
    private final ApprovedVersionRepository approvedVersions;
    private final DriftEventRepository driftEvents;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLog;

    public DriftDetectionService(ToolRepository tools,
                                 ToolVersionRepository toolVersions,
                                 ApprovedVersionRepository approvedVersions,
                                 DriftEventRepository driftEvents,
                                 ObjectMapper objectMapper,
                                 AuditLogService auditLog) {
        this.tools = tools;
        this.toolVersions = toolVersions;
        this.approvedVersions = approvedVersions;
        this.driftEvents = driftEvents;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
    }

    /**
     * Outcome of a single scan. {@code newlyDetected} is true only when this scan
     * created a brand-new {@link DriftEvent} (vs. re-observing an already-open one),
     * so callers can alert exactly once per drift (MA3-85 notifications).
     */
    public record ScanResult(boolean driftDetected, boolean newlyDetected, DriftEvent event) {
        static ScanResult clean() {
            return new ScanResult(false, false, null);
        }
        static ScanResult newDrift(DriftEvent event) {
            return new ScanResult(true, true, event);
        }
        static ScanResult existingDrift(DriftEvent event) {
            return new ScanResult(true, false, event);
        }
    }

    /**
     * Re-scan one pinned tool: compare current vs. approved hash and record drift.
     *
     * @throws IllegalArgumentException if the tool is unknown or not in this org
     * @throws IllegalStateException    if the tool is not pinned or its versions are missing
     */
    @Transactional
    public ScanResult scan(UUID orgId, UUID toolId) {
        Tool tool = requireTool(orgId, toolId);

        UUID approvedVersionId = tool.getApprovedVersionId();
        if (approvedVersionId == null) {
            throw new IllegalStateException("Tool is not pinned; nothing to compare against: " + toolId);
        }
        ApprovedVersion approved = approvedVersions.findById(approvedVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Approved version missing for tool " + toolId + ": " + approvedVersionId));

        UUID currentVersionId = tool.getCurrentVersionId();
        if (currentVersionId == null) {
            throw new IllegalStateException("Tool has no current version to scan: " + toolId);
        }
        ToolVersion current = toolVersions.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Current version missing for tool " + toolId + ": " + currentVersionId));

        String approvedHash = approved.getHash();
        String observedHash = current.getHash();

        if (approvedHash.equals(observedHash)) {
            log.info("Scan tool={} org={}: no drift (hash={})", toolId, orgId, observedHash);
            return ScanResult.clean();
        }

        // Idempotency: reuse the open event for this exact transition.
        Optional<DriftEvent> existing = driftEvents
                .findFirstByOrgIdAndToolIdAndApprovedHashAndObservedHashAndResolvedFalse(
                        orgId, toolId, approvedHash, observedHash);
        if (existing.isPresent()) {
            log.info("Scan tool={} org={}: drift already recorded (event={})",
                    toolId, orgId, existing.get().getId());
            return ScanResult.existingDrift(existing.get());
        }

        ToolVersion approvedTv = toolVersions.findById(approved.getToolVersionId()).orElse(null);
        String approvedDefinition = approvedTv != null ? approvedTv.getDefinition() : null;
        Diff diff = computeDiff(approvedDefinition, current.getDefinition());

        DriftEvent event = driftEvents.save(new DriftEvent(
                UUID.randomUUID(), orgId, toolId, approvedHash, observedHash,
                diff.json(), diff.severity(), OffsetDateTime.now()));

        tool.setStatus(Tool.Status.DRIFTED);
        tool.touch();
        tools.save(tool);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("toolName", tool.getName());
        payload.put("driftEventId", event.getId().toString());
        payload.put("approvedHash", approvedHash);
        payload.put("observedHash", observedHash);
        payload.put("severity", diff.severity().name());
        auditLog.append(orgId, "system", "DRIFT_DETECTED", toolId, payload.toString());

        log.warn("Drift detected tool={} org={} approved={} observed={} severity={} event={}",
                toolId, orgId, approvedHash, observedHash, diff.severity(), event.getId());
        return ScanResult.newDrift(event);
    }

    /** Mark a drift event resolved (foundation for re-approve / block flows). */
    @Transactional
    public void resolve(UUID orgId, UUID driftEventId) {
        DriftEvent event = driftEvents.findById(driftEventId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown drift event: " + driftEventId));
        if (!event.getOrgId().equals(orgId)) {
            throw new IllegalArgumentException("Drift event " + driftEventId + " not in org " + orgId);
        }
        event.resolve();
        driftEvents.save(event);
    }

    // --- diff computation -----------------------------------------------------

    /** Result of comparing two serialized definitions. */
    record Diff(String json, DriftEvent.Severity severity) {}

    /**
     * Compute a structured diff between two serialized {@code ParsedSkill}
     * definitions (each: name, description, files[{path, sha256}]). Reports a
     * description change (old/new) and files added / removed / changed by path
     * and sha256. Severity: any file-content change → CRITICAL; description-only
     * change → WARN; otherwise INFO (e.g. only metadata Jackson can't compare).
     */
    Diff computeDiff(String approvedDefinition, String observedDefinition) {
        ObjectNode diff = objectMapper.createObjectNode();
        JsonNode approved = readTree(approvedDefinition);
        JsonNode observed = readTree(observedDefinition);

        boolean descriptionChanged = diffDescription(approved, observed, diff);
        boolean filesChanged = diffFiles(approved, observed, diff);

        DriftEvent.Severity severity;
        if (filesChanged) {
            severity = DriftEvent.Severity.CRITICAL;
        } else if (descriptionChanged) {
            severity = DriftEvent.Severity.WARN;
        } else {
            severity = DriftEvent.Severity.INFO;
        }
        diff.put("severity", severity.name());

        return new Diff(serialize(diff), severity);
    }

    private boolean diffDescription(JsonNode approved, JsonNode observed, ObjectNode diff) {
        String oldDesc = textOrNull(approved, "description");
        String newDesc = textOrNull(observed, "description");
        if (java.util.Objects.equals(oldDesc, newDesc)) {
            return false;
        }
        ObjectNode node = diff.putObject("description");
        node.put("old", oldDesc);
        node.put("new", newDesc);
        return true;
    }

    private boolean diffFiles(JsonNode approved, JsonNode observed, ObjectNode diff) {
        Map<String, String> oldFiles = filesByPath(approved);
        Map<String, String> newFiles = filesByPath(observed);

        ArrayNode added = objectMapper.createArrayNode();
        ArrayNode removed = objectMapper.createArrayNode();
        ArrayNode changed = objectMapper.createArrayNode();

        for (Map.Entry<String, String> e : newFiles.entrySet()) {
            String path = e.getKey();
            if (!oldFiles.containsKey(path)) {
                added.add(objectMapper.createObjectNode().put("path", path).put("sha256", e.getValue()));
            } else if (!java.util.Objects.equals(oldFiles.get(path), e.getValue())) {
                changed.add(objectMapper.createObjectNode()
                        .put("path", path)
                        .put("oldSha256", oldFiles.get(path))
                        .put("newSha256", e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : oldFiles.entrySet()) {
            if (!newFiles.containsKey(e.getKey())) {
                removed.add(objectMapper.createObjectNode().put("path", e.getKey()).put("sha256", e.getValue()));
            }
        }

        boolean any = added.size() > 0 || removed.size() > 0 || changed.size() > 0;
        if (any) {
            ObjectNode files = diff.putObject("files");
            files.set("added", added);
            files.set("removed", removed);
            files.set("changed", changed);
        }
        return any;
    }

    /** Ordered path → sha256 map; missing/invalid files arrays yield an empty map. */
    private Map<String, String> filesByPath(JsonNode definition) {
        Map<String, String> out = new LinkedHashMap<>();
        if (definition == null) {
            return out;
        }
        JsonNode files = definition.get("files");
        if (files == null || !files.isArray()) {
            return out;
        }
        for (JsonNode file : files) {
            String path = textOrNull(file, "path");
            if (path != null) {
                out.put(path, textOrNull(file, "sha256"));
            }
        }
        return out;
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse stored definition for diff", e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize drift diff", e);
        }
    }

    private Tool requireTool(UUID orgId, UUID toolId) {
        Tool tool = tools.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolId));
        // Tenant isolation (기획서 §10): never act across orgs.
        if (!tool.getOrgId().equals(orgId)) {
            throw new IllegalArgumentException("Tool " + toolId + " not in org " + orgId);
        }
        return tool;
    }
}
