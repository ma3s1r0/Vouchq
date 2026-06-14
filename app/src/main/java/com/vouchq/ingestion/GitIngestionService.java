package com.vouchq.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.audit.AuditLogService;
import com.vouchq.credentials.CredentialCipher;
import com.vouchq.parser.ParsedSkill;
import com.vouchq.parser.SkillParser;
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
import com.vouchq.scanner.SkillScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * First ingestion path (MA3-75): connect a Git source, fetch it, parse Skills,
 * and persist them as inventory.
 *
 * <p>For each parsed skill it upserts a {@link RegisteredServer} (SKILL_BUNDLE),
 * a {@link Tool} (SKILL / PENDING), and — when the definition hash is new — a
 * {@link ToolVersion}, then points {@code tool.current_version_id} at it. On a
 * re-ingest where the hash is unchanged, nothing is written (idempotent). A
 * changed hash inserts a NEW version, which is what drift detection (MA3-78)
 * builds on.
 */
@Service
public class GitIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GitIngestionService.class);

    /** Ingestion is a system-initiated action; no human actor is attributed. */
    private static final String AUDIT_ACTOR = "system";

    private final GitFetcher gitFetcher;
    private final SkillParser skillParser;
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

    public GitIngestionService(GitFetcher gitFetcher,
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
        this.gitFetcher = gitFetcher;
        this.skillParser = new SkillParser();
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

    /** Summary of one ingestion run. */
    public record IngestionResult(
            UUID sourceId,
            int skillsParsed,
            int toolsCreated,
            int versionsCreated,
            int unchanged) {}

    /**
     * Ingest a remote Git repository: clone into a temp dir, parse, persist. The
     * token (if any) is used for the clone and persisted <em>encrypted</em> in
     * {@code source.auth_ref} (MA3-89) so scheduled re-scans can re-clone the same
     * private repo (MA3-90). The raw token is never logged.
     */
    @Transactional
    public IngestionResult ingestRepository(UUID orgId, String repoUrl, String token) {
        try (GitFetcher.Checkout checkout = gitFetcher.cloneRepository(repoUrl, token)) {
            return ingestPath(orgId, repoUrl, checkout.path(),
                    Source.Type.GIT_REPOSITORY, token);
        }
    }

    /**
     * Ingest from an already-available local path (e.g. an unpacked upload or a
     * pre-cloned tree). No credentials involved. Records the source as a
     * {@code GIT_REPOSITORY} (used by the git/rescan paths); the upload fallback
     * (MA3-76) uses {@link #ingestLocalPath(UUID, String, Path, Source.Type)}.
     */
    @Transactional
    public IngestionResult ingestLocalPath(UUID orgId, String sourceUri, Path repoRoot) {
        return ingestPath(orgId, sourceUri, repoRoot, Source.Type.GIT_REPOSITORY, null);
    }

    /**
     * Ingest from a local path while recording the source under an explicit
     * {@link Source.Type} (e.g. {@code FILE_UPLOAD} for the zip-upload fallback,
     * 기획서 §5.1). No credentials involved.
     */
    @Transactional
    public IngestionResult ingestLocalPath(UUID orgId, String sourceUri, Path repoRoot, Source.Type type) {
        return ingestPath(orgId, sourceUri, repoRoot, type, null);
    }

    private IngestionResult ingestPath(UUID orgId, String sourceUri, Path repoRoot,
                                       Source.Type type, String token) {
        Organization org = organizations.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown org: " + orgId));

        boolean authenticated = token != null && !token.isBlank();
        // Encrypt the token at rest; auth_ref holds ciphertext, never the raw token (기획서 §10).
        String authRef = credentialCipher.encrypt(token);
        Source existing = sources.findByOrgIdAndUri(org.getId(), sourceUri).orElse(null);
        boolean newSource = existing == null;
        Source source;
        if (newSource) {
            source = sources.save(new Source(UUID.randomUUID(), org.getId(),
                    type, sourceUri, authRef));
        } else {
            source = existing;
            // Re-ingest with a fresh token rotates the stored credential.
            if (authenticated) {
                source.setAuthRef(authRef);
                sources.save(source);
            }
        }

        // Audit: a brand-new source connection (기획서 §6 SOURCE_CONNECTED).
        if (newSource) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("uri", sourceUri);
            payload.put("type", type.name());
            payload.put("authenticated", authenticated);
            auditLog.append(org.getId(), AUDIT_ACTOR, "SOURCE_CONNECTED",
                    source.getId(), payload.toString());
        }

        List<ParsedSkill> parsed = skillParser.parseRepository(repoRoot);
        log.info("Ingest org={} source={} parsedSkills={}", org.getId(), source.getId(), parsed.size());

        int toolsCreated = 0;
        int versionsCreated = 0;
        int unchanged = 0;

        for (ParsedSkill skill : parsed) {
            // One SKILL_BUNDLE registered server per skill (named after the skill).
            RegisteredServer server = servers
                    .findByOrgIdAndSourceIdAndName(org.getId(), source.getId(), skill.name())
                    .orElseGet(() -> servers.save(new RegisteredServer(
                            UUID.randomUUID(), org.getId(), source.getId(),
                            RegisteredServer.Kind.SKILL_BUNDLE, skill.name())));

            Tool tool = tools
                    .findByOrgIdAndServerIdAndName(org.getId(), server.getId(), skill.name())
                    .orElse(null);
            boolean newTool = tool == null;
            if (newTool) {
                tool = tools.save(new Tool(UUID.randomUUID(), org.getId(), server.getId(),
                        Tool.Kind.SKILL, skill.name(), Tool.Status.PENDING));
                toolsCreated++;
            }

            // Idempotency: only write a new version when the definition hash changed.
            String currentHash = currentHashOf(tool);
            if (skill.definitionHash().equals(currentHash)) {
                unchanged++;
                continue;
            }

            ToolVersion version = toolVersions.save(new ToolVersion(
                    UUID.randomUUID(), org.getId(), tool.getId(),
                    serializeDefinition(skill), skill.definitionHash(), OffsetDateTime.now()));
            versionsCreated++;

            tool.setCurrentVersionId(version.getId());
            tool.touch();
            tools.save(tool);

            // Risk scan over the in-memory parsed files (content still present here),
            // persisted as a scan_result, then evaluated against the policy rules
            // (MA3-86). A matched AUTO_BLOCK rule flips the tool to BLOCKED below.
            com.vouchq.scanner.ScanResult scan = skillScanner.scan(skill);
            scanRecorder.record(org.getId(), version.getId(), scan);
            policyEngine.evaluate(org.getId(), tool, scan);
        }

        // Audit: every ingest run is a scan over the source (기획서 §6 SCAN_RUN).
        ObjectNode scanPayload = objectMapper.createObjectNode();
        scanPayload.put("uri", sourceUri);
        scanPayload.put("skillsParsed", parsed.size());
        scanPayload.put("toolsCreated", toolsCreated);
        scanPayload.put("versionsCreated", versionsCreated);
        scanPayload.put("unchanged", unchanged);
        auditLog.append(org.getId(), AUDIT_ACTOR, "SCAN_RUN", source.getId(), scanPayload.toString());

        return new IngestionResult(source.getId(), parsed.size(), toolsCreated, versionsCreated, unchanged);
    }

    private String currentHashOf(Tool tool) {
        if (tool.getCurrentVersionId() == null) {
            return null;
        }
        return toolVersions.findById(tool.getCurrentVersionId())
                .map(ToolVersion::getHash)
                .orElse(null);
    }

    private String serializeDefinition(ParsedSkill skill) {
        try {
            return objectMapper.writeValueAsString(skill);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ParsedSkill " + skill.name(), e);
        }
    }
}
