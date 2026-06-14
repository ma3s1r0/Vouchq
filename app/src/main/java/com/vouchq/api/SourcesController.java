package com.vouchq.api;

import com.vouchq.credentials.CredentialCipher;
import com.vouchq.ingestion.GitIngestionService;
import com.vouchq.ingestion.RescanService;
import com.vouchq.ingestion.ZipExtractor;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceDeletionService;
import com.vouchq.registry.SourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Source connection &amp; (re)scan endpoints (기획서 §8).
 *
 * <p>RBAC (MA3-71) is enforced centrally in {@code com.vouchq.security.SecurityConfig}:
 * GET is open to any authenticated role; connect/scan (POST) require MEMBER or ADMIN
 * (VIEWER forbidden). Org context comes from the authenticated user's org ({@link com.vouchq.tenancy.CurrentOrg}); query-level
 * multitenancy enforcement is MA3-70.
 */
@RestController
@RequestMapping("/api/sources")
public class SourcesController {

    private final GitIngestionService ingestion;
    private final RescanService rescan;
    private final SourceRepository sources;
    private final CredentialCipher credentialCipher;
    private final com.vouchq.tenancy.CurrentOrg currentOrg;
    private final SourceDeletionService sourceDeletion;

    public SourcesController(GitIngestionService ingestion,
                            RescanService rescan,
                            SourceRepository sources,
                            CredentialCipher credentialCipher,
                            com.vouchq.tenancy.CurrentOrg currentOrg,
                            SourceDeletionService sourceDeletion) {
        this.ingestion = ingestion;
        this.rescan = rescan;
        this.sources = sources;
        this.credentialCipher = credentialCipher;
        this.currentOrg = currentOrg;
        this.sourceDeletion = sourceDeletion;
    }

    /** Connect a Git source and ingest it. The token (if any) is used only for the clone. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.SourceIngestResult create(@RequestBody ApiDtos.CreateSourceRequest req) {
        // RBAC (MA3-71): MEMBER/ADMIN only — enforced in SecurityConfig (POST /api/**).
        UUID orgId = currentOrg.require();
        if (req == null || req.repoUrl() == null || req.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        GitIngestionService.IngestionResult result =
                ingestion.ingestRepository(orgId, req.repoUrl(), req.token());
        Source source = sources.findById(result.sourceId())
                .orElseThrow(() -> new IllegalStateException("Source vanished after ingest"));
        return new ApiDtos.SourceIngestResult(ApiDtos.SourceView.from(source), summary(result));
    }

    /**
     * Upload a zip bundle of Skill folders and ingest it (MA3-76, 기획서 §5.1
     * fallback). The zip is unpacked into a fresh temp dir — guarded against
     * zip-slip and size/entry bombs ({@link ZipExtractor}) — then ingested via
     * the same parse → scan → policy pipeline as the Git path, recording the
     * source as {@code FILE_UPLOAD}. The temp dir is always deleted afterwards.
     *
     * <p>Response shape mirrors {@code POST /api/sources} so callers handle git
     * and upload uniformly.
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.SourceUploadResult upload(@RequestParam("file") MultipartFile file) {
        // RBAC (MA3-71): MEMBER/ADMIN only — enforced in SecurityConfig (POST /api/**).
        UUID orgId = currentOrg.require();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "bundle.zip";
        }

        Path tempDir = null;
        try (InputStream in = file.getInputStream()) {
            tempDir = ZipExtractor.extractToTempDir(in);
            GitIngestionService.IngestionResult result = ingestion.ingestLocalPath(
                    orgId, "upload://" + filename, tempDir, Source.Type.FILE_UPLOAD);
            Source source = sources.findById(result.sourceId())
                    .orElseThrow(() -> new IllegalStateException("Source vanished after ingest"));
            return new ApiDtos.SourceUploadResult(ApiDtos.SourceView.from(source), summary(result));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read uploaded zip: " + e.getMessage());
        } finally {
            ZipExtractor.deleteRecursively(tempDir);
        }
    }

    @GetMapping
    public List<ApiDtos.SourceView> list() {
        UUID orgId = currentOrg.require();
        return sources.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(ApiDtos.SourceView::from)
                .toList();
    }

    /**
     * Re-ingest a source (picks up changes) then drift-scan every pinned tool it
     * owns, dispatching a notification for each new drift. Shares the
     * {@link RescanService} logic with the scheduled re-scan job (MA3-85).
     */
    @PostMapping("/{id}/scan")
    public ApiDtos.SourceScanResult scan(@PathVariable UUID id) {
        // RBAC (MA3-71): MEMBER/ADMIN only — enforced in SecurityConfig (POST /api/**).
        UUID orgId = currentOrg.require();
        Source source = sources.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + id));

        RescanService.RescanResult result = rescan.rescanSource(orgId, source);
        return new ApiDtos.SourceScanResult(
                source.getId(), summary(result.ingestion()), result.scanned(), result.detected());
    }

    /**
     * Rotate a source's stored credential (MA3-89) without recreating the source:
     * the supplied raw token is encrypted at rest and replaces the existing
     * {@code auth_ref}; a blank token clears it. The token is never logged or
     * echoed. Subsequent re-scans (MA3-90) use the new credential.
     */
    @PostMapping("/{id}/credentials")
    public ApiDtos.UpdateCredentialResult updateCredential(
            @PathVariable UUID id, @RequestBody ApiDtos.UpdateCredentialRequest req) {
        // RBAC (MA3-71): MEMBER/ADMIN only — enforced in SecurityConfig (POST /api/**).
        UUID orgId = currentOrg.require();
        Source source = sources.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + id));

        String token = req == null ? null : req.token();
        String ciphertext = credentialCipher.encrypt(token); // null when blank
        source.setAuthRef(ciphertext);
        sources.save(source);
        return new ApiDtos.UpdateCredentialResult(source.getId(), ciphertext != null);
    }

    /**
     * Remove a source and everything ingested from it (servers, tools, versions,
     * scans, approvals, drift, suppressions). RBAC: MEMBER/ADMIN (SecurityConfig
     * DELETE /api/**). Audited as {@code SOURCE_DELETED}.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        UUID orgId = currentOrg.require();
        String actor = auth == null ? "system" : auth.getName();
        sourceDeletion.delete(orgId, id, actor);
    }

    private static ApiDtos.IngestionSummary summary(GitIngestionService.IngestionResult r) {
        if (r == null) {
            // MCP rescans re-fetch via tools/list and carry no Git ingestion summary.
            return new ApiDtos.IngestionSummary(0, 0, 0, 0);
        }
        return new ApiDtos.IngestionSummary(r.skillsParsed(), r.toolsCreated(),
                r.versionsCreated(), r.unchanged());
    }
}
