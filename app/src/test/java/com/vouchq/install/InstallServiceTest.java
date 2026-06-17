package com.vouchq.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vouchq.api.ApiDtos;
import com.vouchq.audit.AuditLogService;
import com.vouchq.registry.ApprovedVersion;
import com.vouchq.registry.ApprovedVersionRepository;
import com.vouchq.registry.RegisteredServer;
import com.vouchq.registry.RegisteredServerRepository;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceRepository;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import com.vouchq.registry.ToolVersion;
import com.vouchq.registry.ToolVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link InstallService} (MA3-138): the install manifest includes
 * only APPROVED + pinned skills, reports the rest as excluded, and serves file
 * bytes addressed by their content hash.
 */
class InstallServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();

    private SourceRepository sources;
    private RegisteredServerRepository servers;
    private ToolRepository tools;
    private ApprovedVersionRepository approvedVersions;
    private ToolVersionRepository toolVersions;
    private AuditLogService auditLog;
    private InstallService service;

    @BeforeEach
    void setUp() {
        sources = mock(SourceRepository.class);
        servers = mock(RegisteredServerRepository.class);
        tools = mock(ToolRepository.class);
        approvedVersions = mock(ApprovedVersionRepository.class);
        toolVersions = mock(ToolVersionRepository.class);
        auditLog = mock(AuditLogService.class);
        service = new InstallService(sources, servers, tools, approvedVersions,
                toolVersions, new ObjectMapper(), auditLog);

        when(sources.findByIdAndOrgId(SOURCE, ORG)).thenReturn(Optional.of(
                new Source(SOURCE, ORG, Source.Type.GIT_REPOSITORY,
                        "https://github.com/acme/agent-tools.git", null)));
    }

    /** Two skills (one approved, one pending) → manifest has the approved one only. */
    @Test
    void manifestIncludesOnlyApprovedPinnedSkillsAndCountsExcluded() {
        UUID serverApproved = UUID.randomUUID();
        UUID serverPending = UUID.randomUUID();
        when(servers.findByOrgIdAndSourceId(ORG, SOURCE)).thenReturn(List.of(
                new RegisteredServer(serverApproved, ORG, SOURCE, RegisteredServer.Kind.SKILL_BUNDLE, "a"),
                new RegisteredServer(serverPending, ORG, SOURCE, RegisteredServer.Kind.SKILL_BUNDLE, "b")));

        UUID approvedToolId = UUID.randomUUID();
        Tool approved = new Tool(approvedToolId, ORG, serverApproved, Tool.Kind.SKILL, "alpha",
                Tool.Status.APPROVED);
        UUID avId = UUID.randomUUID();
        UUID tvId = UUID.randomUUID();
        approved.setApprovedVersionId(avId);

        Tool pending = new Tool(UUID.randomUUID(), ORG, serverPending, Tool.Kind.SKILL, "beta",
                Tool.Status.PENDING);

        when(tools.findByOrgIdAndServerIdIn(eq(ORG), any()))
                .thenReturn(List.of(approved, pending));

        String hash = "a".repeat(64);
        when(approvedVersions.findById(avId)).thenReturn(Optional.of(
                new ApprovedVersion(avId, ORG, approvedToolId, tvId, hash, "admin", OffsetDateTime.now())));
        when(toolVersions.findById(tvId)).thenReturn(Optional.of(
                new ToolVersion(tvId, ORG, approvedToolId, definitionJson(), hash, OffsetDateTime.now())));

        ApiDtos.InstallManifest m = service.buildManifest(ORG, SOURCE);

        assertThat(m.skills()).hasSize(1);
        assertThat(m.skills().get(0).name()).isEqualTo("alpha");
        assertThat(m.skills().get(0).description()).isEqualTo("d");
        assertThat(m.skills().get(0).versionHash()).isEqualTo(hash);
        assertThat(m.skills().get(0).files()).extracting(ApiDtos.InstallFile::path)
                .containsExactlyInAnyOrder("SKILL.md", "scripts/run.sh");
        assertThat(m.excluded().pending()).isEqualTo(1);
        assertThat(m.excluded().total()).isEqualTo(1);
        assertThat(m.source().label()).isEqualTo("agent-tools");
    }

    /** fileContent resolves an approved file by its sha256; unknown hash → empty. */
    @Test
    void fileContentResolvesByHash() {
        UUID server = UUID.randomUUID();
        when(servers.findByOrgIdAndSourceId(ORG, SOURCE)).thenReturn(List.of(
                new RegisteredServer(server, ORG, SOURCE, RegisteredServer.Kind.SKILL_BUNDLE, "a")));

        UUID toolId = UUID.randomUUID();
        Tool t = new Tool(toolId, ORG, server, Tool.Kind.SKILL, "alpha", Tool.Status.APPROVED);
        UUID avId = UUID.randomUUID();
        UUID tvId = UUID.randomUUID();
        t.setApprovedVersionId(avId);
        when(tools.findByOrgIdAndServerIdIn(eq(ORG), any())).thenReturn(List.of(t));
        String hash = "b".repeat(64);
        when(approvedVersions.findById(avId)).thenReturn(Optional.of(
                new ApprovedVersion(avId, ORG, toolId, tvId, hash, "admin", OffsetDateTime.now())));
        when(toolVersions.findById(tvId)).thenReturn(Optional.of(
                new ToolVersion(tvId, ORG, toolId, definitionJson(), hash, OffsetDateTime.now())));

        // SHA of "# alpha" (the SKILL.md content below).
        assertThat(service.fileContent(ORG, SOURCE, SKILL_MD_SHA)).contains("# alpha");
        assertThat(service.fileContent(ORG, SOURCE, "f".repeat(64))).isEmpty();
    }

    /** recordInstallServed appends a SKILL_INSTALL_SERVED audit entry. */
    @Test
    void recordInstallServedAppendsAudit() {
        ApiDtos.InstallManifest m = new ApiDtos.InstallManifest("0.1",
                new ApiDtos.InstallSourceRef(SOURCE, "uri", "label"), OffsetDateTime.now(),
                List.of(new ApiDtos.InstallSkill("alpha", "d", "a".repeat(64), OffsetDateTime.now(), List.of())),
                new ApiDtos.InstallExcluded(0, 0, 0));

        service.recordInstallServed(ORG, SOURCE, "admin", m);

        verify(auditLog).append(eq(ORG), eq("admin"), eq("SKILL_INSTALL_SERVED"), eq(SOURCE), any());
    }

    /** Unknown source → IllegalArgumentException, no audit. */
    @Test
    void unknownSourceRejected() {
        UUID other = UUID.randomUUID();
        when(sources.findByIdAndOrgId(other, ORG)).thenReturn(Optional.empty());
        try {
            service.buildManifest(ORG, other);
        } catch (IllegalArgumentException expected) {
            // ok
        }
        verify(auditLog, never()).append(any(), any(), any(), any(), any());
    }

    /** A clean MCP server with an approved tool → vouched connection view. */
    @Test
    void mcpInstallVouchedWhenApprovedAndClean() {
        UUID server = UUID.randomUUID();
        when(servers.findByOrgIdAndSourceId(ORG, SOURCE)).thenReturn(List.of(
                new RegisteredServer(server, ORG, SOURCE, RegisteredServer.Kind.MCP_SERVER, "acme-mcp")));
        when(tools.findByOrgIdAndServerIdIn(eq(ORG), any())).thenReturn(List.of(
                new Tool(UUID.randomUUID(), ORG, server, Tool.Kind.MCP_TOOL, "create_issue", Tool.Status.APPROVED),
                new Tool(UUID.randomUUID(), ORG, server, Tool.Kind.MCP_TOOL, "delete_issue", Tool.Status.PENDING)));

        ApiDtos.McpInstallView v = service.buildMcpInstall(ORG, SOURCE);
        assertThat(v.name()).isEqualTo("acme-mcp");
        assertThat(v.approvedTools()).isEqualTo(1);
        assertThat(v.totalTools()).isEqualTo(2);
    }

    /** A BLOCKED tool withholds the whole server config (governance signal). */
    @Test
    void mcpInstallWithheldWhenBlocked() {
        UUID server = UUID.randomUUID();
        when(servers.findByOrgIdAndSourceId(ORG, SOURCE)).thenReturn(List.of(
                new RegisteredServer(server, ORG, SOURCE, RegisteredServer.Kind.MCP_SERVER, "acme-mcp")));
        when(tools.findByOrgIdAndServerIdIn(eq(ORG), any())).thenReturn(List.of(
                new Tool(UUID.randomUUID(), ORG, server, Tool.Kind.MCP_TOOL, "a", Tool.Status.APPROVED),
                new Tool(UUID.randomUUID(), ORG, server, Tool.Kind.MCP_TOOL, "b", Tool.Status.BLOCKED)));

        assertThatThrownBy(() -> service.buildMcpInstall(ORG, SOURCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** No approved tools yet → withheld. */
    @Test
    void mcpInstallWithheldWhenNoApproved() {
        UUID server = UUID.randomUUID();
        when(servers.findByOrgIdAndSourceId(ORG, SOURCE)).thenReturn(List.of(
                new RegisteredServer(server, ORG, SOURCE, RegisteredServer.Kind.MCP_SERVER, "acme-mcp")));
        when(tools.findByOrgIdAndServerIdIn(eq(ORG), any())).thenReturn(List.of(
                new Tool(UUID.randomUUID(), ORG, server, Tool.Kind.MCP_TOOL, "a", Tool.Status.PENDING)));

        assertThatThrownBy(() -> service.buildMcpInstall(ORG, SOURCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** recordMcpInstallServed appends an MCP_INSTALL_SERVED audit entry. */
    @Test
    void recordMcpInstallServedAppendsAudit() {
        ApiDtos.McpInstallView v = new ApiDtos.McpInstallView(
                "acme-mcp", "https://mcp.example/v1", 2, 3, OffsetDateTime.now());
        service.recordMcpInstallServed(ORG, SOURCE, "admin", v);
        verify(auditLog).append(eq(ORG), eq("admin"), eq("MCP_INSTALL_SERVED"), eq(SOURCE), any());
    }

    // SHA-256 of "# alpha".
    private static final String SKILL_MD_SHA = sha("# alpha");

    private static String sha(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A ParsedSkill-shaped definition with two files (path, sha256, content). */
    private static String definitionJson() {
        String runSh = "echo hi";
        return "{\"name\":\"alpha\",\"description\":\"d\",\"definitionHash\":\"" + "a".repeat(64)
                + "\",\"files\":["
                + "{\"path\":\"SKILL.md\",\"sha256\":\"" + sha("# alpha") + "\",\"content\":\"# alpha\"},"
                + "{\"path\":\"scripts/run.sh\",\"sha256\":\"" + sha(runSh) + "\",\"content\":\"" + runSh + "\"}"
                + "]}";
    }
}
