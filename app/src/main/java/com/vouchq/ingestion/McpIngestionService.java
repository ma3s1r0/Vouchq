package com.vouchq.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.audit.AuditLogService;
import com.vouchq.credentials.CredentialCipher;
import com.vouchq.parser.SkillFile;
import com.vouchq.policy.PolicyEngine;
import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
import com.vouchq.registry.RegisteredServer;
import com.vouchq.registry.RegisteredServerRepository;
import com.vouchq.registry.ScanRecorder;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceRepository;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import com.vouchq.registry.ToolVersion;
import com.vouchq.registry.ToolVersionRepository;
import com.vouchq.scanner.ScanConfig;
import com.vouchq.scanner.SkillScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MCP ingestion path (MA3-84): connect an MCP server endpoint, call
 * {@code tools/list}, and persist its tools as inventory — the second축 of the
 * vision, joined by swapping only the collection layer.
 *
 * <p>It deliberately mirrors {@link GitIngestionService}: each MCP server becomes
 * one {@link RegisteredServer} (MCP_SERVER); each {@code tools/list} entry becomes
 * a {@link Tool} (MCP_TOOL / PENDING) and — when the definition hash is new — a
 * {@link ToolVersion}, then points {@code tool.current_version_id} at it. A
 * re-ingest with an unchanged hash writes nothing (idempotent); a changed hash
 * inserts a NEW version. Because the persisted shape is identical, the existing
 * drift / approve / audit pipeline works unchanged.
 */
@Service
public class McpIngestionService {

    private static final Logger log = LoggerFactory.getLogger(McpIngestionService.class);

    /** Ingestion is a system-initiated action; no human actor is attributed. */
    private static final String AUDIT_ACTOR = "system";

    private final McpToolFetcher fetcher;
    private final SkillScanner skillScanner;
    private final ObjectMapper objectMapper;
    private final CredentialCipher credentialCipher;

    private final OrganizationRepository organizations;
    private final SourceRepository sources;
    private final RegisteredServerRepository servers;
    private final ToolRepository tools;
    private final ToolVersionRepository toolVersions;
    private final ScanRecorder scanRecorder;
    private final PolicyEngine policyEngine;
    private final AuditLogService auditLog;

    public McpIngestionService(McpToolFetcher fetcher,
                               ObjectMapper objectMapper,
                               CredentialCipher credentialCipher,
                               OrganizationRepository organizations,
                               SourceRepository sources,
                               RegisteredServerRepository servers,
                               ToolRepository tools,
                               ToolVersionRepository toolVersions,
                               ScanRecorder scanRecorder,
                               PolicyEngine policyEngine,
                               AuditLogService auditLog) {
        this.fetcher = fetcher;
        this.skillScanner = new SkillScanner();
        this.objectMapper = objectMapper;
        this.credentialCipher = credentialCipher;
        this.organizations = organizations;
        this.sources = sources;
        this.servers = servers;
        this.tools = tools;
        this.toolVersions = toolVersions;
        this.scanRecorder = scanRecorder;
        this.policyEngine = policyEngine;
        this.auditLog = auditLog;
    }

    /** Summary of one MCP ingestion run. */
    public record IngestionResult(
            UUID sourceId,
            int toolsListed,
            int toolsCreated,
            int versionsCreated,
            int unchanged) {}

    /**
     * Ingest an MCP server: call {@code tools/list}, then upsert the server, its
     * tools, and changed versions. The bearer token (if any) is used for the call
     * and persisted <em>encrypted</em> in {@code source.auth_ref} (MA3-89) so
     * scheduled re-scans can re-fetch an authenticated server (MA3-90). The raw
     * token is never logged.
     */
    @Transactional
    public IngestionResult ingestServer(UUID orgId, String serverUrl, String bearerToken) {
        List<McpToolDefinition> definitions = fetcher.listTools(serverUrl, bearerToken);
        return persist(orgId, serverUrl, definitions,
                bearerToken != null && !bearerToken.isBlank(),
                credentialCipher.encrypt(bearerToken));
    }

    /**
     * Persist already-fetched MCP tool definitions without a stored credential.
     * Separated from the network call so the persistence + idempotency logic is
     * unit-testable without a live server.
     */
    @Transactional
    public IngestionResult persist(UUID orgId, String serverUrl,
                                   List<McpToolDefinition> definitions, boolean authenticated) {
        return persist(orgId, serverUrl, definitions, authenticated, null);
    }

    private IngestionResult persist(UUID orgId, String serverUrl,
                                    List<McpToolDefinition> definitions,
                                    boolean authenticated, String authRefCiphertext) {
        Organization org = organizations.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown org: " + orgId));

        // auth_ref holds ciphertext (or null), never the raw token.
        String authRef = authRefCiphertext;
        Source existing = sources.findByOrgIdAndUri(org.getId(), serverUrl).orElse(null);
        boolean newSource = existing == null;
        Source source;
        if (newSource) {
            source = sources.save(new Source(UUID.randomUUID(), org.getId(),
                    Source.Type.MCP_SERVER, serverUrl, authRef));
        } else {
            source = existing;
            // Re-ingest with a fresh token rotates the stored credential.
            if (authRef != null) {
                source.setAuthRef(authRef);
                sources.save(source);
            }
        }

        if (newSource) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("uri", serverUrl);
            payload.put("type", Source.Type.MCP_SERVER.name());
            payload.put("authenticated", authenticated);
            auditLog.append(org.getId(), AUDIT_ACTOR, "SOURCE_CONNECTED",
                    source.getId(), payload.toString());
        }

        // One MCP_SERVER registered server per source (named after the endpoint).
        RegisteredServer server = servers
                .findByOrgIdAndSourceIdAndName(org.getId(), source.getId(), serverUrl)
                .orElseGet(() -> servers.save(new RegisteredServer(
                        UUID.randomUUID(), org.getId(), source.getId(),
                        RegisteredServer.Kind.MCP_SERVER, serverUrl)));

        log.info("MCP ingest org={} source={} tools={}", org.getId(), source.getId(), definitions.size());

        int toolsCreated = 0;
        int versionsCreated = 0;
        int unchanged = 0;

        for (McpToolDefinition def : definitions) {
            Tool tool = tools
                    .findByOrgIdAndServerIdAndName(org.getId(), server.getId(), def.name())
                    .orElse(null);
            boolean newTool = tool == null;
            if (newTool) {
                tool = tools.save(new Tool(UUID.randomUUID(), org.getId(), server.getId(),
                        Tool.Kind.MCP_TOOL, def.name(), Tool.Status.PENDING));
                toolsCreated++;
            }

            // Idempotency: only write a new version when the definition hash changed.
            String hash = def.definitionHash();
            if (hash.equals(currentHashOf(tool))) {
                unchanged++;
                continue;
            }

            ToolVersion version = toolVersions.save(new ToolVersion(
                    UUID.randomUUID(), org.getId(), tool.getId(),
                    def.canonicalJson(), hash, OffsetDateTime.now()));
            versionsCreated++;

            tool.setCurrentVersionId(version.getId());
            tool.touch();
            tools.save(tool);

            // MCP tools have no file tree; scan the definition itself (a malicious
            // description/schema still trips injection/secret rules). Persisted as
            // a scan_result, then evaluated against the policy rules (MA3-86).
            com.vouchq.scanner.ScanResult scan = skillScanner.scan(
                    List.of(new SkillFile(def.name(), hash, def.canonicalJson())),
                    ScanConfig.permissive());
            scanRecorder.record(org.getId(), version.getId(), scan);
            policyEngine.evaluate(org.getId(), tool, scan);
        }

        ObjectNode scanPayload = objectMapper.createObjectNode();
        scanPayload.put("uri", serverUrl);
        scanPayload.put("toolsListed", definitions.size());
        scanPayload.put("toolsCreated", toolsCreated);
        scanPayload.put("versionsCreated", versionsCreated);
        scanPayload.put("unchanged", unchanged);
        auditLog.append(org.getId(), AUDIT_ACTOR, "SCAN_RUN", source.getId(), scanPayload.toString());

        return new IngestionResult(source.getId(), definitions.size(),
                toolsCreated, versionsCreated, unchanged);
    }

    private String currentHashOf(Tool tool) {
        if (tool.getCurrentVersionId() == null) {
            return null;
        }
        return toolVersions.findById(tool.getCurrentVersionId())
                .map(ToolVersion::getHash)
                .orElse(null);
    }
}
