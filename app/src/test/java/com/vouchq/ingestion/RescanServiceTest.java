package com.vouchq.ingestion;

import com.vouchq.credentials.AesGcmCredentialCipher;
import com.vouchq.credentials.CredentialCipher;
import com.vouchq.credentials.CredentialProperties;
import com.vouchq.notify.NotificationService;
import com.vouchq.registry.DriftDetectionService;
import com.vouchq.registry.RegisteredServerRepository;
import com.vouchq.registry.Source;
import com.vouchq.registry.ToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA3-90: rescan decrypts the source's stored credential and reuses it for the
 * right re-fetch path — Git re-clone for GIT_REPOSITORY, tools/list for MCP_SERVER.
 */
class RescanServiceTest {

    private final UUID orgId = UUID.randomUUID();
    private final CredentialCipher cipher =
            new AesGcmCredentialCipher(new CredentialProperties(null));

    private GitIngestionService git;
    private McpIngestionService mcp;
    private RegisteredServerRepository servers;
    private RescanService rescan;

    @BeforeEach
    void setUp() {
        git = mock(GitIngestionService.class);
        mcp = mock(McpIngestionService.class);
        servers = mock(RegisteredServerRepository.class);
        when(servers.findByOrgIdAndSourceId(eq(orgId), any())).thenReturn(List.of());
        rescan = new RescanService(git, mcp, cipher,
                mock(DriftDetectionService.class), servers,
                mock(ToolRepository.class), mock(NotificationService.class));
    }

    @Test
    void gitRescanReClonesWithDecryptedStoredToken() {
        String authRef = cipher.encrypt("ghp_storedtoken");
        Source gitSource = new Source(UUID.randomUUID(), orgId,
                Source.Type.GIT_REPOSITORY, "https://example.test/repo.git", authRef);
        when(git.ingestRepository(eq(orgId), eq("https://example.test/repo.git"), any()))
                .thenReturn(new GitIngestionService.IngestionResult(gitSource.getId(), 0, 0, 0, 0));

        rescan.rescanSource(orgId, gitSource);

        verify(git).ingestRepository(orgId, "https://example.test/repo.git", "ghp_storedtoken");
        verify(mcp, never()).ingestServer(any(), any(), any());
    }

    @Test
    void mcpRescanReCallsToolsListWithDecryptedStoredToken() {
        String authRef = cipher.encrypt("bearer_stored");
        Source mcpSource = new Source(UUID.randomUUID(), orgId,
                Source.Type.MCP_SERVER, "https://mcp.example.test/rpc", authRef);

        rescan.rescanSource(orgId, mcpSource);

        verify(mcp).ingestServer(orgId, "https://mcp.example.test/rpc", "bearer_stored");
        verify(git, never()).ingestRepository(any(), any(), any());
    }

    @Test
    void noStoredCredentialReFetchesWithNullToken() {
        Source pub = new Source(UUID.randomUUID(), orgId,
                Source.Type.GIT_REPOSITORY, "https://example.test/public.git", null);
        when(git.ingestRepository(eq(orgId), any(), any()))
                .thenReturn(new GitIngestionService.IngestionResult(pub.getId(), 0, 0, 0, 0));

        rescan.rescanSource(orgId, pub);

        verify(git).ingestRepository(orgId, "https://example.test/public.git", null);
    }
}
