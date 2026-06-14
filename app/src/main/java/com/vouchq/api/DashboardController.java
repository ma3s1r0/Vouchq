package com.vouchq.api;

import com.vouchq.registry.DriftEvent;
import com.vouchq.registry.DriftEventRepository;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard aggregate (기획서 §8/§9.1): total assets, counts by status, pending
 * approval queue size, unresolved drift by severity, and recent activity.
 *
 * <p>RBAC (MA3-71): read-only (GET) — open to any authenticated role via
 * {@code com.vouchq.security.SecurityConfig}. Org context from
 * the authenticated user's org ({@link com.vouchq.tenancy.CurrentOrg}); query-level multitenancy is MA3-70.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final int RECENT_DRIFT_LIMIT = 10;

    private final ToolRepository tools;
    private final DriftEventRepository driftEvents;
    private final com.vouchq.tenancy.CurrentOrg currentOrg;

    public DashboardController(ToolRepository tools,
                              DriftEventRepository driftEvents,
                              com.vouchq.tenancy.CurrentOrg currentOrg) {
        this.tools = tools;
        this.driftEvents = driftEvents;
        this.currentOrg = currentOrg;
    }

    @GetMapping("/summary")
    public ApiDtos.DashboardSummary summary() {
        UUID orgId = currentOrg.require();

        long total = tools.countByOrgId(orgId);

        List<ApiDtos.StatusCount> byStatus = Arrays.stream(Tool.Status.values())
                .map(s -> new ApiDtos.StatusCount(s.name(), tools.countByOrgIdAndStatus(orgId, s)))
                .toList();

        long pending = tools.countByOrgIdAndStatus(orgId, Tool.Status.PENDING);

        List<ApiDtos.SeverityCount> driftBySeverity = Arrays.stream(DriftEvent.Severity.values())
                .map(sev -> new ApiDtos.SeverityCount(sev.name(),
                        driftEvents.countByOrgIdAndResolvedFalseAndSeverity(orgId, sev)))
                .toList();
        long unresolvedDrift = driftBySeverity.stream().mapToLong(ApiDtos.SeverityCount::count).sum();

        List<ApiDtos.DriftEventView> recent =
                driftEvents.findByOrgIdOrderByDetectedAtDesc(orgId).stream()
                        .limit(RECENT_DRIFT_LIMIT)
                        .map(ApiDtos.DriftEventView::from)
                        .toList();

        return new ApiDtos.DashboardSummary(total, byStatus, pending,
                unresolvedDrift, driftBySeverity, recent);
    }
}
