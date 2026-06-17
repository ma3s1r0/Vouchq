package com.vouchq.ingestion;

import com.vouchq.credentials.CredentialCipher;
import com.vouchq.notify.DriftNotification;
import com.vouchq.notify.NotificationService;
import com.vouchq.registry.DriftDetectionService;
import com.vouchq.registry.DriftEvent;
import com.vouchq.registry.RegisteredServer;
import com.vouchq.registry.RegisteredServerRepository;
import com.vouchq.registry.Source;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Re-scan orchestration shared by the manual REST trigger
 * ({@code SourcesController}) and the scheduled job ({@code RescanScheduler},
 * MA3-85): re-ingest one source then drift-scan every pinned tool it owns, and
 * dispatch a {@link DriftNotification} for each <em>newly</em> detected drift.
 *
 * <p>Callers must already have bound the tenant via
 * {@link com.vouchq.tenancy.CurrentOrgContext} (the REST path binds it per
 * request; the scheduler binds it with {@code runAs}). This service does not
 * duplicate ingestion or drift logic — it delegates to the existing
 * {@link GitIngestionService} and {@link DriftDetectionService}.
 */
@Service
public class RescanService {

    private static final Logger log = LoggerFactory.getLogger(RescanService.class);

    private final GitIngestionService ingestion;
    private final McpIngestionService mcpIngestion;
    private final CredentialCipher credentialCipher;
    private final DriftDetectionService drift;
    private final RegisteredServerRepository servers;
    private final ToolRepository tools;
    private final NotificationService notifications;

    public RescanService(GitIngestionService ingestion,
                         McpIngestionService mcpIngestion,
                         CredentialCipher credentialCipher,
                         DriftDetectionService drift,
                         RegisteredServerRepository servers,
                         ToolRepository tools,
                         NotificationService notifications) {
        this.ingestion = ingestion;
        this.mcpIngestion = mcpIngestion;
        this.credentialCipher = credentialCipher;
        this.drift = drift;
        this.servers = servers;
        this.tools = tools;
        this.notifications = notifications;
    }

    /**
     * Outcome of re-scanning one source. {@code ingestion} is the Git ingestion
     * summary (null for MCP sources — see {@code mcpToolsListed}).
     */
    public record RescanResult(
            UUID sourceId,
            GitIngestionService.IngestionResult ingestion,
            int scanned,
            int detected) {}

    /**
     * Re-ingest {@code source} (re-fetch + upsert) then drift-scan each pinned tool,
     * notifying on new drift. Supports both {@code GIT_REPOSITORY} (re-clone) and
     * {@code MCP_SERVER} (re-call {@code tools/list}) — the path that actually
     * matters for rug-pull detection. The stored credential, if any, is
     * decrypted transiently and reused so private/authenticated sources keep
     * verifying (MA3-89/90). The org must be bound on the current thread.
     */
    public RescanResult rescanSource(UUID orgId, Source source) {
        GitIngestionService.IngestionResult ingestResult = null;
        String token = decryptCredential(source);
        if (source.getType() == Source.Type.MCP_SERVER) {
            // Re-call tools/list with the stored bearer token (decrypted in memory).
            mcpIngestion.ingestServer(orgId, source.getUri(), token);
        } else {
            // GIT_REPOSITORY / FILE_UPLOAD recorded as git: re-clone with stored token.
            ingestResult = ingestion.ingestRepository(orgId, source.getUri(), token);
        }

        List<UUID> serverIds = servers.findByOrgIdAndSourceId(orgId, source.getId()).stream()
                .map(RegisteredServer::getId)
                .toList();

        int scanned = 0;
        int detected = 0;
        if (!serverIds.isEmpty()) {
            for (Tool tool : tools.findByOrgIdAndServerIdIn(orgId, serverIds)) {
                if (tool.getApprovedVersionId() == null) {
                    continue; // unpinned tools have nothing to compare against
                }
                scanned++;
                DriftDetectionService.ScanResult result = drift.scan(orgId, tool.getId());
                if (result.driftDetected()) {
                    detected++;
                    notifyIfNew(orgId, tool, result.event(), result.newlyDetected());
                }
            }
        }
        return new RescanResult(source.getId(), ingestResult, scanned, detected);
    }

    /**
     * Decrypt the stored credential for re-fetch. Returns {@code null} when the
     * source has no credential. A credential that fails to decrypt (e.g. a legacy
     * {@code token:in-memory} placeholder from before MA3-89, or a key change) is
     * logged and treated as "no token" rather than aborting the rescan — for a
     * public repo/server the unauthenticated re-fetch still succeeds; for a private
     * one it will fail loudly on the fetch, which the caller already tolerates.
     */
    private String decryptCredential(Source source) {
        String authRef = source.getAuthRef();
        if (authRef == null || authRef.isBlank()) {
            return null;
        }
        try {
            return credentialCipher.decrypt(authRef);
        } catch (RuntimeException e) {
            log.warn("Could not decrypt stored credential for source={}; re-fetching without it",
                    source.getId());
            return null;
        }
    }

    private void notifyIfNew(UUID orgId, Tool tool, DriftEvent event, boolean newlyDetected) {
        if (!newlyDetected) {
            return; // already-open drift; do not re-alert on every scan
        }
        notifications.dispatch(new DriftNotification(
                orgId,
                tool.getId(),
                tool.getName(),
                event.getId(),
                event.getSeverity().name(),
                event.getApprovedHash(),
                event.getObservedHash()));
        log.info("Drift notification dispatched org={} tool={} event={}", orgId, tool.getId(), event.getId());
    }
}
