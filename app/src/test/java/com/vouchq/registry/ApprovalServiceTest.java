package com.vouchq.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-logic test of the approve / block / re-approve transitions (MA3-77).
 * Uses in-memory fakes for the repositories (no DB / Testcontainers) so it runs
 * in the build container.
 */
class ApprovalServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID SERVER_ID = UUID.randomUUID();

    private ApprovalService service;

    private final Map<UUID, Tool> toolStore = new HashMap<>();
    private final Map<UUID, ToolVersion> versionStore = new HashMap<>();
    private final Map<UUID, ApprovedVersion> approvedStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        ToolRepository tools = mock(ToolRepository.class);
        when(tools.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(toolStore.get(inv.getArgument(0))));
        when(tools.save(any(Tool.class))).thenAnswer(inv -> {
            Tool t = inv.getArgument(0);
            toolStore.put(t.getId(), t);
            return t;
        });

        ToolVersionRepository versions = mock(ToolVersionRepository.class);
        when(versions.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(versionStore.get(inv.getArgument(0))));

        ApprovedVersionRepository approved = mock(ApprovedVersionRepository.class);
        when(approved.save(any(ApprovedVersion.class))).thenAnswer(inv -> {
            ApprovedVersion v = inv.getArgument(0);
            approvedStore.put(v.getId(), v);
            return v;
        });

        service = new ApprovalService(tools, versions, approved,
                mock(com.vouchq.audit.AuditLogService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void approvePinsCurrentVersionHashAndFlipsStatus() {
        Tool tool = pendingToolWithVersion("hash-v1");

        ApprovedVersion pinned = service.approve(ORG_ID, tool.getId(), "alice");

        assertThat(pinned.getHash()).isEqualTo("hash-v1");
        assertThat(pinned.getToolVersionId()).isEqualTo(tool.getCurrentVersionId());
        assertThat(pinned.getApprovedBy()).isEqualTo("alice");
        assertThat(pinned.getOrgId()).isEqualTo(ORG_ID);

        Tool saved = toolStore.get(tool.getId());
        assertThat(saved.getStatus()).isEqualTo(Tool.Status.APPROVED);
        assertThat(saved.getApprovedVersionId()).isEqualTo(pinned.getId());
        assertThat(approvedStore).hasSize(1);
    }

    @Test
    void reApprovalAfterDriftPinsNewVersionWithoutMutatingPrior() {
        Tool tool = pendingToolWithVersion("hash-v1");
        ApprovedVersion first = service.approve(ORG_ID, tool.getId(), "alice");

        // Drift: a new current version is observed; tool flagged DRIFTED.
        UUID v2 = addVersion(tool.getId(), "hash-v2");
        tool.setCurrentVersionId(v2);
        tool.setStatus(Tool.Status.DRIFTED);

        ApprovedVersion second = service.approve(ORG_ID, tool.getId(), "bob");

        // New row, prior pin untouched.
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getHash()).isEqualTo("hash-v2");
        assertThat(second.getToolVersionId()).isEqualTo(v2);
        assertThat(approvedStore).hasSize(2);

        ApprovedVersion priorReloaded = approvedStore.get(first.getId());
        assertThat(priorReloaded.getHash()).isEqualTo("hash-v1");
        assertThat(priorReloaded.getToolVersionId()).isEqualTo(first.getToolVersionId());

        Tool saved = toolStore.get(tool.getId());
        assertThat(saved.getStatus()).isEqualTo(Tool.Status.APPROVED);
        assertThat(saved.getApprovedVersionId()).isEqualTo(second.getId());
    }

    @Test
    void blockFlipsStatusAndLeavesPinInPlace() {
        Tool tool = pendingToolWithVersion("hash-v1");
        ApprovedVersion pinned = service.approve(ORG_ID, tool.getId(), "alice");

        service.block(ORG_ID, tool.getId(), "alice");

        Tool saved = toolStore.get(tool.getId());
        assertThat(saved.getStatus()).isEqualTo(Tool.Status.BLOCKED);
        // Pin row left intact.
        assertThat(saved.getApprovedVersionId()).isEqualTo(pinned.getId());
        assertThat(approvedStore).hasSize(1);
    }

    @Test
    void approveWithoutCurrentVersionFails() {
        Tool tool = new Tool(UUID.randomUUID(), ORG_ID, SERVER_ID,
                Tool.Kind.SKILL, "no-version", Tool.Status.PENDING);
        toolStore.put(tool.getId(), tool);

        assertThatThrownBy(() -> service.approve(ORG_ID, tool.getId(), "alice"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(approvedStore).isEmpty();
    }

    @Test
    void approveAcrossOrgIsRejected() {
        Tool tool = pendingToolWithVersion("hash-v1");

        assertThatThrownBy(() -> service.approve(OTHER_ORG, tool.getId(), "mallory"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(approvedStore).isEmpty();
    }

    @Test
    void unknownToolIsRejected() {
        assertThatThrownBy(() -> service.approve(ORG_ID, UUID.randomUUID(), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ----------------------------------------------------------

    private Tool pendingToolWithVersion(String hash) {
        Tool tool = new Tool(UUID.randomUUID(), ORG_ID, SERVER_ID,
                Tool.Kind.SKILL, "greeter", Tool.Status.PENDING);
        UUID versionId = addVersion(tool.getId(), hash);
        tool.setCurrentVersionId(versionId);
        toolStore.put(tool.getId(), tool);
        return tool;
    }

    private UUID addVersion(UUID toolId, String hash) {
        UUID id = UUID.randomUUID();
        versionStore.put(id, new ToolVersion(id, ORG_ID, toolId,
                "{\"def\":\"" + hash + "\"}", hash, OffsetDateTime.now()));
        return id;
    }
}
