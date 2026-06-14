package com.vouchq.ingestion;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * Guarded dev-only trigger for MA3-75 verification. Active ONLY under the
 * {@code demo} Spring profile so it can never be reached in a normal deploy.
 * Lets you ingest a Git URL (or a local path) and observe the persisted rows.
 */
@RestController
@RequestMapping("/api/internal/ingest")
@Profile("demo")
public class InternalIngestController {

    private final GitIngestionService ingestionService;
    private final DefaultOrganization defaultOrganization;

    public InternalIngestController(GitIngestionService ingestionService,
                                    DefaultOrganization defaultOrganization) {
        this.ingestionService = ingestionService;
        this.defaultOrganization = defaultOrganization;
    }

    /**
     * Request body: {@code {"repoUrl": "...", "token": "...", "localPath": "..."}}.
     * Provide either {@code repoUrl} (clone) or {@code localPath} (ingest in place).
     */
    public record IngestRequest(String repoUrl, String token, String localPath) {}

    @PostMapping
    public GitIngestionService.IngestionResult ingest(@RequestBody IngestRequest req) {
        var orgId = defaultOrganization.ensure();
        if (req.localPath() != null && !req.localPath().isBlank()) {
            return ingestionService.ingestLocalPath(orgId, "file://" + req.localPath(),
                    Path.of(req.localPath()));
        }
        return ingestionService.ingestRepository(orgId, req.repoUrl(), req.token());
    }
}
