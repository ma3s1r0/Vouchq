package com.vouchq.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The trust core (MA3-77 "승인 &amp; 해시 박제"). Approving a tool
 * freezes its current definition as the authoritative pinned version.
 *
 * <p>Approve takes the tool's {@code current_version_id} {@link ToolVersion},
 * snapshots that version's id + {@code hash} into a new (immutable)
 * {@link ApprovedVersion} row, points {@code tool.approved_version_id} at it,
 * and flips status to {@code APPROVED}. Re-approval after drift pins the new
 * current version as a fresh {@code ApprovedVersion} row — the prior row is
 * never mutated, preserving the audit trail.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ToolRepository tools;
    private final ToolVersionRepository toolVersions;
    private final ApprovedVersionRepository approvedVersions;
    private final AuditLogService auditLog;
    private final ObjectMapper objectMapper;

    public ApprovalService(ToolRepository tools,
                           ToolVersionRepository toolVersions,
                           ApprovedVersionRepository approvedVersions,
                           AuditLogService auditLog,
                           ObjectMapper objectMapper) {
        this.tools = tools;
        this.toolVersions = toolVersions;
        this.approvedVersions = approvedVersions;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
    }

    /**
     * Approve a tool and pin its current version. Captures the current
     * {@link ToolVersion}'s id + hash into a new {@link ApprovedVersion}, sets
     * {@code tool.approved_version_id}, and flips status to {@code APPROVED}.
     *
     * @return the newly created pinned version
     * @throws IllegalArgumentException if the tool is unknown or not in this org
     * @throws IllegalStateException    if the tool has no current version to pin
     */
    @Transactional
    public ApprovedVersion approve(UUID orgId, UUID toolId, String approvedBy) {
        Tool tool = requireTool(orgId, toolId);

        UUID currentVersionId = tool.getCurrentVersionId();
        if (currentVersionId == null) {
            throw new IllegalStateException("Tool has no current version to pin: " + toolId);
        }
        ToolVersion current = toolVersions.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Current version missing for tool " + toolId + ": " + currentVersionId));

        // New immutable pin row — never mutate a prior ApprovedVersion.
        ApprovedVersion pinned = approvedVersions.save(new ApprovedVersion(
                UUID.randomUUID(), orgId, tool.getId(), current.getId(), current.getHash(),
                approvedBy, OffsetDateTime.now()));

        tool.setApprovedVersionId(pinned.getId());
        tool.setStatus(Tool.Status.APPROVED);
        tool.touch();
        tools.save(tool);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("toolName", tool.getName());
        payload.put("approvedVersionId", pinned.getId().toString());
        payload.put("toolVersionId", current.getId().toString());
        payload.put("hash", current.getHash());
        auditLog.append(orgId, approvedBy, "TOOL_APPROVED", tool.getId(), payload.toString());

        log.info("Approved tool={} org={} pinned version={} hash={} by={}",
                tool.getId(), orgId, current.getId(), current.getHash(), approvedBy);
        return pinned;
    }

    /**
     * Block a tool. Flips status to {@code BLOCKED}; the existing pin (if any)
     * is left in place untouched.
     */
    @Transactional
    public void block(UUID orgId, UUID toolId, String blockedBy) {
        Tool tool = requireTool(orgId, toolId);
        tool.setStatus(Tool.Status.BLOCKED);
        tool.touch();
        tools.save(tool);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("toolName", tool.getName());
        auditLog.append(orgId, blockedBy, "TOOL_BLOCKED", tool.getId(), payload.toString());

        log.info("Blocked tool={} org={} by={}", tool.getId(), orgId, blockedBy);
    }

    private Tool requireTool(UUID orgId, UUID toolId) {
        Tool tool = tools.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolId));
        // Tenant isolation: never act across orgs.
        if (!tool.getOrgId().equals(orgId)) {
            throw new IllegalArgumentException("Tool " + toolId + " not in org " + orgId);
        }
        return tool;
    }
}
