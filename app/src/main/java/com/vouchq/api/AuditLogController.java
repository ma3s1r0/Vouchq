package com.vouchq.api;

import com.vouchq.audit.AuditExportService;
import com.vouchq.audit.AuditLogRepository;
import com.vouchq.audit.AuditLogService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Audit log query endpoint (기획서 §8, §10 hash-chained log). The write side is
 * MA3-79; until it lands the table is empty and this endpoint returns {@code []}.
 *
 * <p>RBAC (MA3-71): read-only (GET) — open to any authenticated role via
 * {@code com.vouchq.security.SecurityConfig}. Org context from
 * the authenticated user's org ({@link com.vouchq.tenancy.CurrentOrg}); query-level multitenancy is MA3-70.
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 100;

    private final AuditLogRepository auditLogs;
    private final AuditLogService auditLogService;
    private final AuditExportService auditExportService;
    private final com.vouchq.tenancy.CurrentOrg currentOrg;

    public AuditLogController(AuditLogRepository auditLogs,
                              AuditLogService auditLogService,
                              AuditExportService auditExportService,
                              com.vouchq.tenancy.CurrentOrg currentOrg) {
        this.auditLogs = auditLogs;
        this.auditLogService = auditLogService;
        this.auditExportService = auditExportService;
        this.currentOrg = currentOrg;
    }

    /**
     * One page of the org's audit log, most recent first, with optional server-side
     * filters (action / actor / since) — so the unbounded WORM log is queried, not
     * fully rendered (MA3-118). The total matching count is returned in the
     * {@code X-Total-Count} header for pagination.
     */
    @GetMapping
    public ResponseEntity<List<ApiDtos.AuditLogView>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String since) {
        UUID orgId = currentOrg.require();
        int size = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int pageIdx = (page == null || page < 0) ? 0 : page;
        String actionF = blankToNull(action);
        String actorF = blankToNull(actor);
        OffsetDateTime sinceTs = parseSince(since);

        List<ApiDtos.AuditLogView> items =
                auditLogs.findPage(orgId, actionF, actorF, sinceTs, PageRequest.of(pageIdx, size))
                        .stream().map(ApiDtos.AuditLogView::from).toList();
        long total = auditLogs.countPage(orgId, actionF, actorF, sinceTs);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(total))
                .body(items);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** {@code since=yyyy-MM-dd} → start of that day in UTC; null/blank → no bound. */
    private static OffsetDateTime parseSince(String since) {
        if (since == null || since.isBlank()) return null;
        try {
            return LocalDate.parse(since.trim()).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /** Verify the org's hash chain end-to-end (기획서 §10 audit integrity). */
    @GetMapping("/verify")
    public AuditLogService.VerifyResult verify() {
        UUID orgId = currentOrg.require();
        return auditLogService.verifyChain(orgId);
    }

    /**
     * Tamper-evident integrity export of the org's <em>complete</em> chain in
     * ascending id order (not capped), each row with {@code prev_hash}/{@code
     * entry_hash}, plus an overall chain-verification result (MA3-91, 기획서 §9.6).
     *
     * <p>{@code format=json} (default) returns {@code {verification, entries}};
     * {@code format=csv} returns a header row with the verification result as
     * leading {@code #} comment lines. RBAC: VIEWER+ (a GET).
     */
    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(required = false) String format) {
        UUID orgId = currentOrg.require();
        AuditExportService.Format fmt = "csv".equalsIgnoreCase(format)
                ? AuditExportService.Format.CSV
                : AuditExportService.Format.JSON;
        AuditExportService.Export export = auditExportService.export(orgId, fmt);
        MediaType mediaType = fmt == AuditExportService.Format.CSV
                ? MediaType.parseMediaType("text/csv")
                : MediaType.APPLICATION_JSON;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.filename() + "\"")
                .body(export.body());
    }
}
