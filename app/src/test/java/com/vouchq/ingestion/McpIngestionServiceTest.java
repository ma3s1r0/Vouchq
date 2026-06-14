package com.vouchq.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-logic test of MCP ingestion + idempotency. Uses in-memory fakes for the
 * repositories (no DB / Testcontainers) so it runs in the build container, and
 * exercises {@link McpIngestionService#persist} so no live MCP server is needed.
 */
class McpIngestionServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final String URL = "https://mcp.example.com/rpc";

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpToolFetcher fetcher = new McpToolFetcher(mapper);

    private McpIngestionService service;

    private final Map<UUID, Source> sourceStore = new HashMap<>();
    private final Map<UUID, RegisteredServer> serverStore = new HashMap<>();
    private final Map<UUID, Tool> toolStore = new HashMap<>();
    private final Map<UUID, ToolVersion> versionStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        when(orgs.findById(ORG_ID)).thenReturn(
                Optional.of(new Organization(ORG_ID, "Default", "default")));

        SourceRepository sources = mock(SourceRepository.class);
        when(sources.findByOrgIdAndUri(eq(ORG_ID), any())).thenAnswer(inv ->
                sourceStore.values().stream()
                        .filter(s -> s.getUri().equals(inv.getArgument(1)))
                        .findFirst());
        when(sources.save(any(Source.class))).thenAnswer(inv -> {
            Source s = inv.getArgument(0);
            sourceStore.put(s.getId(), s);
            return s;
        });

        RegisteredServerRepository servers = mock(RegisteredServerRepository.class);
        when(servers.findByOrgIdAndSourceIdAndName(eq(ORG_ID), any(), any())).thenAnswer(inv ->
                serverStore.values().stream()
                        .filter(s -> s.getSourceId().equals(inv.getArgument(1))
                                && s.getName().equals(inv.getArgument(2)))
                        .findFirst());
        when(servers.save(any(RegisteredServer.class))).thenAnswer(inv -> {
            RegisteredServer s = inv.getArgument(0);
            serverStore.put(s.getId(), s);
            return s;
        });

        ToolRepository tools = mock(ToolRepository.class);
        when(tools.findByOrgIdAndServerIdAndName(eq(ORG_ID), any(), any())).thenAnswer(inv ->
                toolStore.values().stream()
                        .filter(t -> t.getServerId().equals(inv.getArgument(1))
                                && t.getName().equals(inv.getArgument(2)))
                        .findFirst());
        when(tools.save(any(Tool.class))).thenAnswer(inv -> {
            Tool t = inv.getArgument(0);
            toolStore.put(t.getId(), t);
            return t;
        });

        ToolVersionRepository versions = mock(ToolVersionRepository.class);
        when(versions.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(versionStore.get(inv.getArgument(0))));
        when(versions.save(any(ToolVersion.class))).thenAnswer(inv -> {
            ToolVersion v = inv.getArgument(0);
            versionStore.put(v.getId(), v);
            return v;
        });

        service = new McpIngestionService(
                fetcher, mapper,
                new com.vouchq.credentials.AesGcmCredentialCipher(
                        new com.vouchq.credentials.CredentialProperties(null)),
                orgs, sources, servers, tools, versions,
                mock(com.vouchq.registry.ScanRecorder.class),
                mock(com.vouchq.policy.PolicyEngine.class),
                mock(com.vouchq.audit.AuditLogService.class));
    }

    private List<McpToolDefinition> defs(String description) {
        String body = """
                {"jsonrpc":"2.0","id":1,"result":{"tools":[
                  {"name":"get_weather","description":"%s",
                   "inputSchema":{"type":"object","properties":{"city":{"type":"string"}}}},
                  {"name":"ping"}]}}
                """.formatted(description);
        return fetcher.parseToolsList(body);
    }

    @Test
    void firstIngestCreatesMcpServerToolsAndVersions() {
        var result = service.persist(ORG_ID, URL, defs("Get weather"), false);

        assertThat(result.toolsListed()).isEqualTo(2);
        assertThat(result.toolsCreated()).isEqualTo(2);
        assertThat(result.versionsCreated()).isEqualTo(2);
        assertThat(result.unchanged()).isZero();

        assertThat(serverStore).hasSize(1);
        assertThat(serverStore.values().iterator().next().getKind())
                .isEqualTo(RegisteredServer.Kind.MCP_SERVER);
        assertThat(sourceStore.values().iterator().next().getType())
                .isEqualTo(Source.Type.MCP_SERVER);

        Tool weather = toolStore.values().stream()
                .filter(t -> t.getName().equals("get_weather")).findFirst().orElseThrow();
        assertThat(weather.getKind()).isEqualTo(Tool.Kind.MCP_TOOL);
        assertThat(weather.getStatus()).isEqualTo(Tool.Status.PENDING);
        assertThat(weather.getCurrentVersionId()).isNotNull();
        assertThat(versionStore).hasSize(2);
    }

    @Test
    void reingestUnchangedIsIdempotent() {
        service.persist(ORG_ID, URL, defs("Get weather"), false);
        var second = service.persist(ORG_ID, URL, defs("Get weather"), false);

        assertThat(second.toolsCreated()).isZero();
        assertThat(second.versionsCreated()).isZero();
        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(versionStore).hasSize(2); // no new rows
        assertThat(serverStore).hasSize(1);  // same registered server reused
        assertThat(sourceStore).hasSize(1);  // same source reused
    }

    @Test
    void changedDefinitionInsertsNewVersionAndRepointsCurrent() {
        service.persist(ORG_ID, URL, defs("Get weather"), false);
        UUID firstVersionId = toolStore.values().stream()
                .filter(t -> t.getName().equals("get_weather")).findFirst().orElseThrow()
                .getCurrentVersionId();

        // rug-pull: same tool name, mutated description -> new hash.
        var second = service.persist(ORG_ID, URL, defs("Get weather AND forecast"), false);

        assertThat(second.toolsCreated()).isZero();
        assertThat(second.versionsCreated()).isEqualTo(1); // only get_weather changed
        assertThat(second.unchanged()).isEqualTo(1);        // ping unchanged
        assertThat(versionStore).hasSize(3);

        UUID newVersionId = toolStore.values().stream()
                .filter(t -> t.getName().equals("get_weather")).findFirst().orElseThrow()
                .getCurrentVersionId();
        assertThat(newVersionId).isNotEqualTo(firstVersionId);
    }
}
