package com.vouchq.ingestion;

import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceRepository;
import com.vouchq.tenancy.CurrentOrgContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA3-85 scheduler: per source it re-ingests + drift-scans (via {@link RescanService}),
 * binds the right org via {@link CurrentOrgContext}, and skips non-Git sources.
 */
class RescanSchedulerTest {

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @Test
    void rescansGitAndMcpSourcesPerOrgWithOrgContextBoundSkippingUploads() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        when(orgs.findAll()).thenReturn(List.of(org(orgA), org(orgB)));

        Source aGit = gitSource(orgA);
        Source aMcp = mcpSource(orgA);     // MA3-90: MCP must be rescanned
        Source aUpload = uploadSource(orgA); // FILE_UPLOAD must be skipped
        Source bGit = gitSource(orgB);

        SourceRepository sources = mock(SourceRepository.class);
        when(sources.findByOrgIdOrderByCreatedAtDesc(orgA)).thenReturn(List.of(aGit, aMcp, aUpload));
        when(sources.findByOrgIdOrderByCreatedAtDesc(orgB)).thenReturn(List.of(bGit));

        // Capture the org bound on the thread when rescanSource is invoked.
        Map<UUID, UUID> boundOrgPerSource = new ConcurrentHashMap<>();
        RescanService rescan = mock(RescanService.class);
        when(rescan.rescanSource(any(), any())).thenAnswer(inv -> {
            Source src = inv.getArgument(1);
            boundOrgPerSource.put(src.getId(), CurrentOrgContext.require());
            return new RescanService.RescanResult(src.getId(), null, 0, 0);
        });

        RescanScheduler scheduler = new RescanScheduler(orgs, sources, rescan);
        scheduler.runScheduledRescan();

        // Git AND MCP sources scanned; the FILE_UPLOAD source skipped.
        verify(rescan).rescanSource(eq(orgA), eq(aGit));
        verify(rescan).rescanSource(eq(orgA), eq(aMcp));
        verify(rescan).rescanSource(eq(orgB), eq(bGit));
        verify(rescan, never()).rescanSource(any(), eq(aUpload));
        verify(rescan, times(3)).rescanSource(any(), any());

        // Each call ran with the correct tenant bound (orgFilter would be active).
        assertThat(boundOrgPerSource.get(aGit.getId())).isEqualTo(orgA);
        assertThat(boundOrgPerSource.get(aMcp.getId())).isEqualTo(orgA);
        assertThat(boundOrgPerSource.get(bGit.getId())).isEqualTo(orgB);

        // Context cleared after the run (no leak onto the pool thread).
        assertThat(CurrentOrgContext.current()).isEmpty();
    }

    @Test
    void oneFailingSourceDoesNotAbortTheRest() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        when(orgs.findAll()).thenReturn(List.of(org(orgA)));

        Source bad = gitSource(orgA);
        Source good = gitSource(orgA);
        SourceRepository sources = mock(SourceRepository.class);
        when(sources.findByOrgIdOrderByCreatedAtDesc(orgA)).thenReturn(List.of(bad, good));

        RescanService rescan = mock(RescanService.class);
        when(rescan.rescanSource(orgA, bad)).thenThrow(new RuntimeException("repo unreachable"));
        when(rescan.rescanSource(orgA, good))
                .thenReturn(new RescanService.RescanResult(good.getId(), null, 1, 0));

        RescanScheduler scheduler = new RescanScheduler(orgs, sources, rescan);
        scheduler.runScheduledRescan(); // must not throw

        verify(rescan).rescanSource(orgA, bad);
        verify(rescan).rescanSource(orgA, good);
    }

    // --- helpers --------------------------------------------------------------

    private Organization org(UUID id) {
        return new Organization(id, "Org " + id, id.toString());
    }

    private Source gitSource(UUID orgId) {
        return new Source(UUID.randomUUID(), orgId, Source.Type.GIT_REPOSITORY,
                "https://example.test/repo.git", null);
    }

    private Source mcpSource(UUID orgId) {
        return new Source(UUID.randomUUID(), orgId, Source.Type.MCP_SERVER,
                "https://mcp.example.test/rpc", null);
    }

    private Source uploadSource(UUID orgId) {
        return new Source(UUID.randomUUID(), orgId, Source.Type.FILE_UPLOAD, "upload://x", null);
    }
}
