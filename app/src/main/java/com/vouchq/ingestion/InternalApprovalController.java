package com.vouchq.ingestion;

import com.vouchq.registry.ApprovalService;
import com.vouchq.registry.ApprovedVersion;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Guarded dev-only triggers for MA3-77 verification. Active ONLY under the
 * {@code demo} Spring profile so they can never be reached in a normal deploy.
 * Lets you approve / block an ingested tool and observe the persisted rows.
 */
@RestController
@RequestMapping("/api/internal")
@Profile("demo")
public class InternalApprovalController {

    private final ApprovalService approvalService;
    private final DefaultOrganization defaultOrganization;

    public InternalApprovalController(ApprovalService approvalService,
                                      DefaultOrganization defaultOrganization) {
        this.approvalService = approvalService;
        this.defaultOrganization = defaultOrganization;
    }

    /** Request body: {@code {"toolId": "...", "actor": "..."}}. */
    public record ApprovalRequest(UUID toolId, String actor) {}

    /** Response echoing the pin so the e2e can assert the hash / version. */
    public record ApprovedVersionView(
            UUID id, UUID toolId, UUID toolVersionId, String hash,
            String approvedBy, OffsetDateTime approvedAt) {
        static ApprovedVersionView of(ApprovedVersion v) {
            return new ApprovedVersionView(v.getId(), v.getToolId(), v.getToolVersionId(),
                    v.getHash(), v.getApprovedBy(), v.getApprovedAt());
        }
    }

    @PostMapping("/approve")
    public ApprovedVersionView approve(@RequestBody ApprovalRequest req) {
        UUID orgId = defaultOrganization.ensure();
        String actor = (req.actor() == null || req.actor().isBlank()) ? "demo-admin" : req.actor();
        return ApprovedVersionView.of(approvalService.approve(orgId, req.toolId(), actor));
    }

    @PostMapping("/block")
    public void block(@RequestBody ApprovalRequest req) {
        UUID orgId = defaultOrganization.ensure();
        String actor = (req.actor() == null || req.actor().isBlank()) ? "demo-admin" : req.actor();
        approvalService.block(orgId, req.toolId(), actor);
    }
}
