package com.vouchq.ingestion;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guarded dev-only trigger for MA3-84 verification. Active ONLY under the
 * {@code demo} Spring profile so it can never be reached in a normal deploy.
 * Lets you point at an MCP server URL, run {@code tools/list}, and observe the
 * persisted rows.
 */
@RestController
@RequestMapping("/api/internal/ingest-mcp")
@Profile("demo")
public class InternalMcpIngestController {

    private final McpIngestionService ingestionService;
    private final DefaultOrganization defaultOrganization;

    public InternalMcpIngestController(McpIngestionService ingestionService,
                                       DefaultOrganization defaultOrganization) {
        this.ingestionService = ingestionService;
        this.defaultOrganization = defaultOrganization;
    }

    /**
     * Request body: {@code {"url": "https://...", "token": "..."}}. The token is
     * optional, kept in memory only, and never persisted or logged (기획서 §10).
     */
    public record McpIngestRequest(String url, String token) {}

    @PostMapping
    public McpIngestionService.IngestionResult ingest(@RequestBody McpIngestRequest req) {
        var orgId = defaultOrganization.ensure();
        return ingestionService.ingestServer(orgId, req.url(), req.token());
    }
}
