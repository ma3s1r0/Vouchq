package com.vouchq.install;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the vouched install for a source group (MA3-137/138). A source (repo)
 * registers many Skills; this assembles the pinned, APPROVED-only install
 * manifest and serves individual file bytes by content hash.
 *
 * <p>The bytes come from {@code tool_version.definition} (the parser keeps each
 * skill file's content), so vouchq serves the exact governed version it pinned —
 * a consumer never re-clones the origin (the rug-pull vector). Every file the
 * client writes is checked against the hash in the manifest.
 */
@Service
public class InstallService {

    /** Manifest schema version (bumped if the lock shape changes). */
    static final String SCHEMA_VERSION = "0.1";

    private final SourceRepository sources;
    private final RegisteredServerRepository servers;
    private final ToolRepository tools;
    private final ApprovedVersionRepository approvedVersions;
    private final ToolVersionRepository toolVersions;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLog;

    public InstallService(SourceRepository sources,
                          RegisteredServerRepository servers,
                          ToolRepository tools,
                          ApprovedVersionRepository approvedVersions,
                          ToolVersionRepository toolVersions,
                          ObjectMapper objectMapper,
                          AuditLogService auditLog) {
        this.sources = sources;
        this.servers = servers;
        this.tools = tools;
        this.approvedVersions = approvedVersions;
        this.toolVersions = toolVersions;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
    }

    /**
     * The install manifest for a source: every APPROVED + pinned Skill in the
     * group with its per-file hashes, plus the counts of skills excluded because
     * they aren't approved. 404-style if the source is unknown to this org.
     */
    @Transactional(readOnly = true)
    public ApiDtos.InstallManifest buildManifest(UUID orgId, UUID sourceId) {
        Source source = sources.findByIdAndOrgId(sourceId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + sourceId));

        List<Tool> skills = skillTools(orgId, sourceId);
        List<ApiDtos.InstallSkill> installable = new ArrayList<>();
        int pending = 0, drifted = 0, blocked = 0;

        for (Tool t : skills) {
            switch (t.getStatus()) {
                case APPROVED -> approvedSkill(t).ifPresent(installable::add);
                case PENDING -> pending++;
                case DRIFTED -> drifted++;
                case BLOCKED -> blocked++;
            }
        }
        // An APPROVED tool whose pinned version can't be loaded is not installable;
        // count it as pending so the "excluded" total stays honest.
        int approvedButMissing = (int) skills.stream()
                .filter(t -> t.getStatus() == Tool.Status.APPROVED).count() - installable.size();
        pending += Math.max(0, approvedButMissing);

        installable.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new ApiDtos.InstallManifest(
                SCHEMA_VERSION,
                new ApiDtos.InstallSourceRef(source.getId(), source.getUri(), label(source.getUri())),
                OffsetDateTime.now(),
                installable,
                new ApiDtos.InstallExcluded(pending, drifted, blocked));
    }

    /**
     * Raw UTF-8 content of an approved file in the group, addressed by its
     * SHA-256. Returns empty if no approved skill in the source has a file with
     * that hash. The returned bytes hash to the requested value by construction;
     * the client re-verifies regardless.
     */
    @Transactional(readOnly = true)
    public Optional<String> fileContent(UUID orgId, UUID sourceId, String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return Optional.empty();
        }
        String want = sha256.trim().toLowerCase();
        for (Tool t : skillTools(orgId, sourceId)) {
            if (t.getStatus() != Tool.Status.APPROVED || t.getApprovedVersionId() == null) {
                continue;
            }
            JsonNode files = approvedDefinitionFiles(t);
            if (files == null) {
                continue;
            }
            for (JsonNode f : files) {
                if (want.equals(f.path("sha256").asText("").toLowerCase())) {
                    return Optional.of(f.path("content").asText(""));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Record that an install was served for this source on the WORM audit log
     * (MA3-140). Fired when the install script is issued — the actual "I'm
     * installing" moment — not on every manifest preview.
     */
    @Transactional
    public void recordInstallServed(UUID orgId, UUID sourceId, String actor,
                                    ApiDtos.InstallManifest manifest) {
        StringBuilder hashes = new StringBuilder("[");
        for (int i = 0; i < manifest.skills().size(); i++) {
            if (i > 0) {
                hashes.append(',');
            }
            hashes.append('"').append(manifest.skills().get(i).versionHash()).append('"');
        }
        hashes.append(']');
        String payload = "{\"source\":\"" + sourceId + "\",\"skills\":" + manifest.skills().size()
                + ",\"versionHashes\":" + hashes + "}";
        auditLog.append(orgId, actor, "SKILL_INSTALL_SERVED", sourceId, payload);
    }

    // ── helpers ──

    /** All SKILL tools belonging to a source (across its registered servers). */
    private List<Tool> skillTools(UUID orgId, UUID sourceId) {
        List<UUID> serverIds = servers.findByOrgIdAndSourceId(orgId, sourceId)
                .stream().map(RegisteredServer::getId).toList();
        if (serverIds.isEmpty()) {
            return List.of();
        }
        return tools.findByOrgIdAndServerIdIn(orgId, serverIds).stream()
                .filter(t -> t.getKind() == Tool.Kind.SKILL)
                .toList();
    }

    /** Build the {@link ApiDtos.InstallSkill} for an APPROVED tool, or empty. */
    private Optional<ApiDtos.InstallSkill> approvedSkill(Tool tool) {
        if (tool.getApprovedVersionId() == null) {
            return Optional.empty();
        }
        Optional<ApprovedVersion> av = approvedVersions.findById(tool.getApprovedVersionId());
        if (av.isEmpty()) {
            return Optional.empty();
        }
        JsonNode def = approvedDefinition(tool);
        if (def == null || !def.path("files").isArray()) {
            return Optional.empty();
        }
        List<ApiDtos.InstallFile> out = new ArrayList<>();
        for (JsonNode f : def.path("files")) {
            out.add(new ApiDtos.InstallFile(f.path("path").asText(""), f.path("sha256").asText("")));
        }
        return Optional.of(new ApiDtos.InstallSkill(
                tool.getName(), def.path("description").asText(""),
                av.get().getHash(), av.get().getApprovedAt(), out));
    }

    /** The {@code files} array from an approved tool's pinned definition, or null. */
    private JsonNode approvedDefinitionFiles(Tool tool) {
        JsonNode def = approvedDefinition(tool);
        if (def == null) {
            return null;
        }
        JsonNode files = def.path("files");
        return files.isArray() ? files : null;
    }

    /** The full parsed (ParsedSkill-shaped) definition of an approved tool, or null. */
    private JsonNode approvedDefinition(Tool tool) {
        ApprovedVersion av = approvedVersions.findById(tool.getApprovedVersionId()).orElse(null);
        if (av == null) {
            return null;
        }
        ToolVersion tv = toolVersions.findById(av.getToolVersionId()).orElse(null);
        if (tv == null) {
            return null;
        }
        try {
            return objectMapper.readTree(tv.getDefinition());
        } catch (Exception e) {
            return null;
        }
    }

    /** Readable source label: repo short-name for Git (drops .git), host for MCP. */
    static String label(String uri) {
        if (uri == null || uri.isBlank()) {
            return "—";
        }
        String stripped = uri.replaceAll("\\.git$", "").replaceAll("/+$", "");
        String last = null;
        for (String part : stripped.split("[/:]")) {
            if (!part.isBlank()) {
                last = part;
            }
        }
        return last != null ? last : uri;
    }
}
