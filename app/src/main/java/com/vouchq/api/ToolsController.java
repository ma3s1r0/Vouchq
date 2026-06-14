package com.vouchq.api;

import com.vouchq.registry.ApprovalService;
import com.vouchq.registry.ApprovedVersion;
import com.vouchq.registry.ApprovedVersionRepository;
import com.vouchq.registry.DriftEventRepository;
import com.vouchq.registry.RegisteredServer;
import com.vouchq.registry.RegisteredServerRepository;
import com.vouchq.registry.ScanResult;
import com.vouchq.registry.ScanResultRepository;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceRepository;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import com.vouchq.registry.ToolVersion;
import com.vouchq.registry.ToolVersionRepository;
import com.vouchq.tenancy.CurrentOrg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tool/Skill inventory, detail, and approve/block lifecycle (기획서 §8).
 *
 * <p>RBAC (MA3-71) is enforced centrally in {@code com.vouchq.security.SecurityConfig}:
 * GET is open to any authenticated role; approve/block (POST) require MEMBER or ADMIN
 * (VIEWER forbidden). Org context is the authenticated user's org via
 * {@link CurrentOrg} (MA3-93); query-level multitenancy is MA3-70.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final ApprovalService approval;
    private final ToolRepository tools;
    private final ToolVersionRepository toolVersions;
    private final ApprovedVersionRepository approvedVersions;
    private final ScanResultRepository scanResults;
    private final DriftEventRepository driftEvents;
    private final CurrentOrg currentOrg;
    private final ScanViewAssembler scanViews;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final RegisteredServerRepository registeredServers;
    private final SourceRepository sources;

    public ToolsController(ApprovalService approval,
                          ToolRepository tools,
                          ToolVersionRepository toolVersions,
                          ApprovedVersionRepository approvedVersions,
                          ScanResultRepository scanResults,
                          DriftEventRepository driftEvents,
                          CurrentOrg currentOrg,
                          ScanViewAssembler scanViews,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                          RegisteredServerRepository registeredServers,
                          SourceRepository sources) {
        this.approval = approval;
        this.tools = tools;
        this.toolVersions = toolVersions;
        this.approvedVersions = approvedVersions;
        this.scanResults = scanResults;
        this.driftEvents = driftEvents;
        this.currentOrg = currentOrg;
        this.scanViews = scanViews;
        this.objectMapper = objectMapper;
        this.registeredServers = registeredServers;
        this.sources = sources;
    }

    /**
     * Inventory listing with optional {@code kind} / {@code status} / {@code q}
     * (name search) filters. When {@code limit} is given it returns that page
     * (newest first) with the total in {@code X-Total-Count} (MA3-118); without
     * {@code limit} it returns the full list (used by dashboard/drift aggregates).
     */
    @GetMapping
    public ResponseEntity<List<ApiDtos.ToolView>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer page) {
        UUID orgId = currentOrg.require();
        Tool.Kind kindFilter = parseEnum(Tool.Kind.class, kind, "kind");
        Tool.Status statusFilter = parseEnum(Tool.Status.class, status, "status");

        if (limit != null && limit > 0) {
            String qF = (q == null || q.isBlank()) ? null : q.trim();
            int size = Math.min(limit, 500);
            int pageIdx = (page == null || page < 0) ? 0 : page;
            List<ApiDtos.ToolView> items = tools
                    .searchPage(orgId, kindFilter, statusFilter, qF,
                            org.springframework.data.domain.PageRequest.of(pageIdx, size))
                    .stream().map(t -> toolView(orgId, t)).toList();
            long total = tools.countSearch(orgId, kindFilter, statusFilter, qF);
            return ResponseEntity.ok().header("X-Total-Count", Long.toString(total)).body(items);
        }

        List<ApiDtos.ToolView> all = tools.search(orgId, kindFilter, statusFilter).stream()
                .map(t -> toolView(orgId, t)).toList();
        return ResponseEntity.ok().header("X-Total-Count", Long.toString(all.size())).body(all);
    }

    /** Detail: current + pinned definition, full version history, open drift events. */
    @GetMapping("/{id}")
    public ApiDtos.ToolDetailView detail(@PathVariable UUID id) {
        UUID orgId = currentOrg.require();
        Tool tool = tools.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + id));

        ApiDtos.ToolVersionView current = tool.getCurrentVersionId() == null ? null
                : toolVersions.findById(tool.getCurrentVersionId())
                        .map(ApiDtos.ToolVersionView::from).orElse(null);

        ApiDtos.ApprovedVersionView approved = tool.getApprovedVersionId() == null ? null
                : approvedVersions.findById(tool.getApprovedVersionId())
                        .map(ApiDtos.ApprovedVersionView::from).orElse(null);

        List<ApiDtos.ToolVersionView> history =
                toolVersions.findByOrgIdAndToolIdOrderByObservedAtDesc(orgId, id).stream()
                        .map(ApiDtos.ToolVersionView::from)
                        .toList();

        List<ApiDtos.DriftEventView> openDrift =
                driftEvents.findByOrgIdAndToolIdAndResolvedFalse(orgId, id).stream()
                        .map(ApiDtos.DriftEventView::from)
                        .toList();

        ScanResult scan = latestScan(orgId, tool);
        ApiDtos.ScanResultView scanView = scan == null ? null
                : scanViews.toView(orgId, tool.getId(), scan);

        return new ApiDtos.ToolDetailView(toolView(orgId, tool, scan), current, approved,
                scanView, history, openDrift);
    }

    /**
     * The approved (pinned) artifact for a tool, reconstructed from its approved
     * {@link ToolVersion} so a consumer can install the exact governed version
     * (e.g. into {@code ~/.claude/skills/<name>/}). 400 if not yet approved.
     */
    @GetMapping("/{id}/approved/artifact")
    public ApiDtos.ApprovedArtifactView approvedArtifact(@PathVariable UUID id) {
        UUID orgId = currentOrg.require();
        Tool tool = tools.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + id));
        if (tool.getApprovedVersionId() == null) {
            throw new IllegalArgumentException("Tool has no approved version: " + id);
        }
        ApprovedVersion av = approvedVersions.findById(tool.getApprovedVersionId())
                .orElseThrow(() -> new IllegalStateException("Approved version missing for tool " + id));
        ToolVersion tv = toolVersions.findById(av.getToolVersionId())
                .orElseThrow(() -> new IllegalStateException("Tool version missing for tool " + id));
        return ApiDtos.ApprovedArtifactView.of(tool.getName(), av.getHash(),
                tv.getDefinition(), objectMapper);
    }

    /**
     * Issue a vouched install config for the MCP server this tool belongs to
     * (MA3-110, hub distribution). Only an MCP server in good standing — at least
     * one APPROVED tool and no BLOCKED or DRIFTED tools — is installable; the
     * refusal (400) is itself the signal. vouchq is only the issuer, never in the
     * agent↔server data path.
     */
    @GetMapping("/{id}/mcp-install")
    public ApiDtos.McpInstallView mcpInstall(@PathVariable UUID id) {
        UUID orgId = currentOrg.require();
        Tool tool = tools.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + id));
        if (tool.getKind() != Tool.Kind.MCP_TOOL) {
            throw new IllegalArgumentException("Not an MCP tool: " + id);
        }
        RegisteredServer server = registeredServers.findById(tool.getServerId())
                .filter(s -> orgId.equals(s.getOrgId()))
                .orElseThrow(() -> new IllegalStateException("MCP server missing for tool " + id));
        Source source = sources.findByIdAndOrgId(server.getSourceId(), orgId)
                .orElseThrow(() -> new IllegalStateException("MCP source missing for tool " + id));

        List<Tool> serverTools =
                tools.findByOrgIdAndServerIdIn(orgId, java.util.List.of(server.getId()));
        long approved = serverTools.stream().filter(t -> t.getStatus() == Tool.Status.APPROVED).count();
        boolean blocked = serverTools.stream().anyMatch(t -> t.getStatus() == Tool.Status.BLOCKED);
        boolean drifted = serverTools.stream().anyMatch(t -> t.getStatus() == Tool.Status.DRIFTED);

        if (blocked) {
            throw new IllegalArgumentException("MCP server has a BLOCKED tool — not installable");
        }
        if (drifted) {
            throw new IllegalArgumentException("MCP server has unresolved DRIFT — not installable");
        }
        if (approved == 0) {
            throw new IllegalArgumentException("MCP server has no approved tools yet");
        }

        return new ApiDtos.McpInstallView(server.getName(), source.getUri(),
                (int) approved, serverTools.size(), java.time.OffsetDateTime.now());
    }

    /** Tool inventory row with raw + effective (post-suppression, MA3-94) risk. */
    private ApiDtos.ToolView toolView(UUID orgId, Tool tool) {
        return toolView(orgId, tool, latestScan(orgId, tool));
    }

    private ApiDtos.ToolView toolView(UUID orgId, Tool tool, ScanResult scan) {
        if (scan == null) {
            return ApiDtos.ToolView.from(tool);
        }
        ScanViewAssembler.Effective eff = scanViews.compute(orgId, tool.getId(), scan);
        return ApiDtos.ToolView.from(tool, scan, eff.riskScore(), eff.highestSeverity());
    }

    /** Latest scan for the tool's current version (null if unscanned). */
    private ScanResult latestScan(UUID orgId, Tool tool) {
        if (tool.getCurrentVersionId() == null) {
            return null;
        }
        return scanResults
                .findFirstByOrgIdAndToolVersionIdOrderByScannedAtDesc(orgId, tool.getCurrentVersionId())
                .orElse(null);
    }

    /** Approve &amp; pin (박제) the tool's current version. */
    @PostMapping("/{id}/approve")
    public ApiDtos.ApprovedVersionView approve(@PathVariable UUID id,
                                               @RequestBody(required = false) ApiDtos.ApproveRequest req) {
        // RBAC (MA3-71): MEMBER/ADMIN only — enforced in SecurityConfig (POST /api/**).
        UUID orgId = currentOrg.require();
        String approvedBy = (req == null || req.approvedBy() == null || req.approvedBy().isBlank())
                ? "system" : req.approvedBy();
        ApprovedVersion pinned = approval.approve(orgId, id, approvedBy);
        return ApiDtos.ApprovedVersionView.from(pinned);
    }

    /** Block the tool. */
    @PostMapping("/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@PathVariable UUID id,
                     @RequestBody(required = false) ApiDtos.BlockRequest req) {
        // RBAC (MA3-71): MEMBER/ADMIN only — enforced in SecurityConfig (POST /api/**).
        UUID orgId = currentOrg.require();
        String blockedBy = (req == null || req.blockedBy() == null || req.blockedBy().isBlank())
                ? "system" : req.blockedBy();
        approval.block(orgId, id, blockedBy);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + field + ": " + raw);
        }
    }
}
