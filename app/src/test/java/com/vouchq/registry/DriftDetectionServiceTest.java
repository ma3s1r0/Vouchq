package com.vouchq.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Pure-logic test of drift compare / diff / severity / status transitions
 * (MA3-78). Uses in-memory fakes for the repositories (no DB / Testcontainers)
 * so it runs in the build container, mirroring {@link ApprovalServiceTest}.
 */
class DriftDetectionServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID SERVER_ID = UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper();

    private DriftDetectionService service;

    private final Map<UUID, Tool> toolStore = new HashMap<>();
    private final Map<UUID, ToolVersion> versionStore = new HashMap<>();
    private final Map<UUID, ApprovedVersion> approvedStore = new HashMap<>();
    private final Map<UUID, DriftEvent> driftStore = new HashMap<>();

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
        when(approved.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(approvedStore.get(inv.getArgument(0))));

        DriftEventRepository drift = mock(DriftEventRepository.class);
        when(drift.save(any(DriftEvent.class))).thenAnswer(inv -> {
            DriftEvent e = inv.getArgument(0);
            driftStore.put(e.getId(), e);
            return e;
        });
        when(drift.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(driftStore.get(inv.getArgument(0))));
        when(drift.findFirstByOrgIdAndToolIdAndApprovedHashAndObservedHashAndResolvedFalse(
                any(), any(), any(), any())).thenAnswer(inv ->
                driftStore.values().stream()
                        .filter(e -> e.getOrgId().equals(inv.getArgument(0)))
                        .filter(e -> e.getToolId().equals(inv.getArgument(1)))
                        .filter(e -> e.getApprovedHash().equals(inv.getArgument(2)))
                        .filter(e -> e.getObservedHash().equals(inv.getArgument(3)))
                        .filter(e -> !e.isResolved())
                        .findFirst());

        service = new DriftDetectionService(tools, versions, approved, drift, mapper,
                mock(com.vouchq.audit.AuditLogService.class));
    }

    @Test
    void noDriftWhenCurrentHashEqualsApprovedHash() {
        Tool tool = pinnedTool("hash-v1", definition("greeter", "Greets people", "scripts/run.sh", "sha-a"));

        DriftDetectionService.ScanResult result = service.scan(ORG_ID, tool.getId());

        assertThat(result.driftDetected()).isFalse();
        assertThat(result.event()).isNull();
        assertThat(driftStore).isEmpty();
        assertThat(toolStore.get(tool.getId()).getStatus()).isEqualTo(Tool.Status.APPROVED);
    }

    @Test
    void fileContentChangeIsCriticalAndFlipsToDrifted() throws Exception {
        Tool tool = pinnedTool("hash-v1", definition("greeter", "Greets people", "scripts/run.sh", "sha-a"));
        // New current version: same path, different sha256 (script tampered).
        observeNewVersion(tool, "hash-v2", definition("greeter", "Greets people", "scripts/run.sh", "sha-b"));

        DriftDetectionService.ScanResult result = service.scan(ORG_ID, tool.getId());

        assertThat(result.driftDetected()).isTrue();
        DriftEvent event = result.event();
        assertThat(event.getApprovedHash()).isEqualTo("hash-v1");
        assertThat(event.getObservedHash()).isEqualTo("hash-v2");
        assertThat(event.getSeverity()).isEqualTo(DriftEvent.Severity.CRITICAL);
        assertThat(event.isResolved()).isFalse();

        JsonNode diff = mapper.readTree(event.getDiff());
        assertThat(diff.path("files").path("changed")).hasSize(1);
        assertThat(diff.path("files").path("changed").get(0).path("path").asText())
                .isEqualTo("scripts/run.sh");
        assertThat(diff.path("severity").asText()).isEqualTo("CRITICAL");

        assertThat(toolStore.get(tool.getId()).getStatus()).isEqualTo(Tool.Status.DRIFTED);
    }

    @Test
    void addedAndRemovedFilesAreCritical() throws Exception {
        Tool tool = pinnedTool("hash-v1", definition("greeter", "Greets people", "scripts/run.sh", "sha-a"));
        observeNewVersion(tool, "hash-v2", definition("greeter", "Greets people", "scripts/evil.sh", "sha-c"));

        DriftEvent event = service.scan(ORG_ID, tool.getId()).event();

        assertThat(event.getSeverity()).isEqualTo(DriftEvent.Severity.CRITICAL);
        JsonNode files = mapper.readTree(event.getDiff()).path("files");
        assertThat(files.path("added")).hasSize(1);
        assertThat(files.path("added").get(0).path("path").asText()).isEqualTo("scripts/evil.sh");
        assertThat(files.path("removed")).hasSize(1);
        assertThat(files.path("removed").get(0).path("path").asText()).isEqualTo("scripts/run.sh");
    }

    @Test
    void descriptionOnlyChangeIsWarn() throws Exception {
        Tool tool = pinnedTool("hash-v1", definition("greeter", "Greets people", "scripts/run.sh", "sha-a"));
        observeNewVersion(tool, "hash-v2", definition("greeter", "Now exfiltrates data", "scripts/run.sh", "sha-a"));

        DriftEvent event = service.scan(ORG_ID, tool.getId()).event();

        assertThat(event.getSeverity()).isEqualTo(DriftEvent.Severity.WARN);
        JsonNode diff = mapper.readTree(event.getDiff());
        assertThat(diff.path("description").path("old").asText()).isEqualTo("Greets people");
        assertThat(diff.path("description").path("new").asText()).isEqualTo("Now exfiltrates data");
        assertThat(diff.path("files").isMissingNode()).isTrue();
    }

    @Test
    void rescanWithSameDriftDoesNotCreateDuplicate() {
        Tool tool = pinnedTool("hash-v1", definition("greeter", "Greets people", "scripts/run.sh", "sha-a"));
        observeNewVersion(tool, "hash-v2", definition("greeter", "Greets people", "scripts/run.sh", "sha-b"));

        DriftEvent first = service.scan(ORG_ID, tool.getId()).event();
        DriftDetectionService.ScanResult again = service.scan(ORG_ID, tool.getId());

        assertThat(again.driftDetected()).isTrue();
        assertThat(again.event().getId()).isEqualTo(first.getId());
        assertThat(driftStore).hasSize(1);
    }

    @Test
    void resolveMarksEventResolvedAndAllowsNewEventForSameTransition() {
        Tool tool = pinnedTool("hash-v1", definition("greeter", "Greets people", "scripts/run.sh", "sha-a"));
        observeNewVersion(tool, "hash-v2", definition("greeter", "Greets people", "scripts/run.sh", "sha-b"));

        DriftEvent first = service.scan(ORG_ID, tool.getId()).event();
        service.resolve(ORG_ID, first.getId());
        assertThat(driftStore.get(first.getId()).isResolved()).isTrue();

        DriftEvent second = service.scan(ORG_ID, tool.getId()).event();
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(driftStore).hasSize(2);
    }

    @Test
    void scanUnpinnedToolFails() {
        Tool tool = new Tool(UUID.randomUUID(), ORG_ID, SERVER_ID, Tool.Kind.SKILL, "x", Tool.Status.PENDING);
        UUID v = addVersion(tool.getId(), "hash-v1", definition("x", "d", "f", "s"));
        tool.setCurrentVersionId(v);
        toolStore.put(tool.getId(), tool);

        assertThatThrownBy(() -> service.scan(ORG_ID, tool.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scanAcrossOrgIsRejected() {
        Tool tool = pinnedTool("hash-v1", definition("greeter", "Greets people", "scripts/run.sh", "sha-a"));
        assertThatThrownBy(() -> service.scan(OTHER_ORG, tool.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownToolIsRejected() {
        assertThatThrownBy(() -> service.scan(ORG_ID, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers --------------------------------------------------------------

    /** Serialized definition shaped like a ParsedSkill: name, description, files[]. */
    private String definition(String name, String description, String path, String sha256) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description
                + "\",\"files\":[{\"path\":\"" + path + "\",\"sha256\":\"" + sha256
                + "\",\"content\":\"...\"}],\"definitionHash\":\"ignored\"}";
    }

    /** A pinned (APPROVED) tool whose current and approved version share one hash. */
    private Tool pinnedTool(String hash, String definition) {
        Tool tool = new Tool(UUID.randomUUID(), ORG_ID, SERVER_ID, Tool.Kind.SKILL, "greeter", Tool.Status.PENDING);
        UUID versionId = addVersion(tool.getId(), hash, definition);
        tool.setCurrentVersionId(versionId);

        ApprovedVersion approved = new ApprovedVersion(UUID.randomUUID(), ORG_ID, tool.getId(),
                versionId, hash, "alice", OffsetDateTime.now());
        approvedStore.put(approved.getId(), approved);
        tool.setApprovedVersionId(approved.getId());
        tool.setStatus(Tool.Status.APPROVED);

        toolStore.put(tool.getId(), tool);
        return tool;
    }

    private void observeNewVersion(Tool tool, String hash, String definition) {
        UUID v = addVersion(tool.getId(), hash, definition);
        tool.setCurrentVersionId(v);
        toolStore.put(tool.getId(), tool);
    }

    private UUID addVersion(UUID toolId, String hash, String definition) {
        UUID id = UUID.randomUUID();
        versionStore.put(id, new ToolVersion(id, ORG_ID, toolId, definition, hash, OffsetDateTime.now()));
        return id;
    }
}
