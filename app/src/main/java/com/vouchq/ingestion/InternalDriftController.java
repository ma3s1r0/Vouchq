package com.vouchq.ingestion;

import com.vouchq.registry.DriftDetectionService;
import com.vouchq.registry.DriftEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Guarded dev-only trigger for MA3-78 verification. Active ONLY under the
 * {@code demo} Spring profile so it can never be reached in a normal deploy.
 * Manually re-scans a pinned tool and reports whether drift was detected.
 */
@RestController
@RequestMapping("/api/internal")
@Profile("demo")
public class InternalDriftController {

    private final DriftDetectionService driftDetectionService;
    private final DefaultOrganization defaultOrganization;

    public InternalDriftController(DriftDetectionService driftDetectionService,
                                   DefaultOrganization defaultOrganization) {
        this.driftDetectionService = driftDetectionService;
        this.defaultOrganization = defaultOrganization;
    }

    /** Request body: {@code {"toolId": "..."}}. */
    public record DriftScanRequest(UUID toolId) {}

    /** Response echoing the scan outcome so the e2e can assert on it. */
    public record DriftScanView(boolean driftDetected, DriftEventView event) {}

    public record DriftEventView(
            UUID id, UUID toolId, String approvedHash, String observedHash,
            String diff, String severity, OffsetDateTime detectedAt, boolean resolved) {
        static DriftEventView of(DriftEvent e) {
            return new DriftEventView(e.getId(), e.getToolId(), e.getApprovedHash(),
                    e.getObservedHash(), e.getDiff(), e.getSeverity().name(),
                    e.getDetectedAt(), e.isResolved());
        }
    }

    @PostMapping("/drift-scan")
    public DriftScanView scan(@RequestBody DriftScanRequest req) {
        UUID orgId = defaultOrganization.ensure();
        DriftDetectionService.ScanResult result = driftDetectionService.scan(orgId, req.toolId());
        DriftEventView event = result.event() == null ? null : DriftEventView.of(result.event());
        return new DriftScanView(result.driftDetected(), event);
    }
}
