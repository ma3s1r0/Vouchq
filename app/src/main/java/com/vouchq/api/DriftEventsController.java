package com.vouchq.api;

import com.vouchq.registry.DriftDetectionService;
import com.vouchq.registry.DriftEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Drift event listing &amp; resolution (기획서 §8).
 *
 * <p>RBAC (MA3-71) is enforced centrally in {@code com.vouchq.security.SecurityConfig}:
 * GET is open to any authenticated role; resolve (POST) requires MEMBER or ADMIN
 * (VIEWER forbidden). Org context from the authenticated user's org ({@link com.vouchq.tenancy.CurrentOrg}); query-level
 * multitenancy is MA3-70.
 */
@RestController
@RequestMapping("/api/drift-events")
public class DriftEventsController {

    private final DriftDetectionService drift;
    private final DriftEventRepository driftEvents;
    private final com.vouchq.tenancy.CurrentOrg currentOrg;

    public DriftEventsController(DriftDetectionService drift,
                                DriftEventRepository driftEvents,
                                com.vouchq.tenancy.CurrentOrg currentOrg) {
        this.drift = drift;
        this.driftEvents = driftEvents;
        this.currentOrg = currentOrg;
    }

    /** List drift events, optionally filtered by {@code resolved} (true/false). */
    @GetMapping
    public List<ApiDtos.DriftEventView> list(@RequestParam(required = false) Boolean resolved) {
        UUID orgId = currentOrg.require();
        var events = (resolved == null)
                ? driftEvents.findByOrgIdOrderByDetectedAtDesc(orgId)
                : driftEvents.findByOrgIdAndResolvedOrderByDetectedAtDesc(orgId, resolved);
        return events.stream().map(ApiDtos.DriftEventView::from).toList();
    }

    /** Mark a drift event resolved (after re-approval or block). */
    @PostMapping("/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable UUID id) {
        // RBAC (MA3-71): MEMBER/ADMIN only — enforced in SecurityConfig (POST /api/**).
        UUID orgId = currentOrg.require();
        drift.resolve(orgId, id);
    }
}
